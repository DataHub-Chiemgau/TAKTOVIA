package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.ConflictDecision
import de.taktovia.documentsfinance.api.OdometerConflictKind
import de.taktovia.documentsfinance.api.OdometerConflictStatus
import de.taktovia.documentsfinance.api.OdometerRegister
import de.taktovia.documentsfinance.api.OdometerServices
import de.taktovia.documentsfinance.api.OdometerSource
import de.taktovia.documentsfinance.api.DocumentKind
import de.taktovia.documentsfinance.api.RecordOdometer
import de.taktovia.documentsfinance.api.ResolveConflict
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.SnapshotFetcher
import de.taktovia.platform.outbox.SnapshotNotYetAvailableException
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataAccessException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-41 (FCO-003): Kilometerstandsregister gegen die echte Datenbank unter der Runtime-Rolle. Stände aus allen vier Quellen mit Herkunft, Konflikte (rückläufig, widersprüchliche Quellen) ohne
 * Überschreiben, Einmaligkeit bei wiederholter Zustellung (auch über die Inbox), serialisierte Konflikterkennung bei gleichzeitigen Meldungen, protokollierte Auflösung ohne Automatik, genau eine
 * Auflösung bei Parallelität und Mandantentrennung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OdometerRegisterTest {
    private lateinit var bf: BelegFixture
    private lateinit var reg: OdometerRegister
    private lateinit var events: de.taktovia.documentsfinance.api.OdometerEventConsumer
    private val json = JsonMapper.builder().build()
    private val t0: Instant = Instant.parse("2026-06-01T08:00:00Z")

    @BeforeAll fun start() {
        bf = BelegFixture(InMemoryObjectStore())
        val processor = InboxProcessor(bf.fx.tx, bf.fx.security, SnapshotFetcher { _, _, _ -> throw SnapshotNotYetAvailableException("im Test nicht vorgesehen") })
        val s = OdometerServices.create(bf.fx.ctxTx, JdbcAuditPort(), Outbox(), Clock.systemUTC(), processor)
        reg = s.register; events = s.events
    }
    @AfterAll fun stop() { bf.close() }

    private inner class W(val tenant: TenantId = bf.fx.world().tenant) {
        val vehicle = vehicle(tenant)
        val finance = ctx(tenant, setOf("finance"), setOf("odometer:read", "odometer:record", "odometer:resolve"))
        fun record(source: OdometerSource, ref: String, km: Long, at: Instant, v: UUID = vehicle, by: RequestContext = finance) = reg.record(by, v, RecordOdometer(source, ref, km, at))
        fun conflicts(v: UUID = vehicle, status: OdometerConflictStatus? = null) = reg.conflicts(finance, v, status, 100, null)
        fun rows(v: UUID = vehicle) = bf.fx.count("SELECT count(*) FROM taktovia.odometer_reading WHERE vehicle_id = ?", v)
        fun audit(action: String) = bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = ?", tenant.value, action)
    }

    private fun ctx(t: TenantId, roles: Set<String>, scopes: Set<String>, type: ActorType = ActorType.HUMAN) =
        RequestContext(Actor(type, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun vehicle(t: TenantId): UUID {
        val id = UUID.randomUUID()
        bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id)
        return id
    }

    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }

    private fun inspectionEvent(tenant: TenantId, vehicle: UUID, inspection: UUID, km: Long?, at: Instant, eventId: UUID = UUID.randomUUID()): String {
        val p = json.createObjectNode().also {
            it.put("inspection_id", inspection.toString()); it.put("vehicle_id", vehicle.toString()); it.put("shift_id", UUID.randomUUID().toString()); it.put("kind", "PRE_SHIFT"); it.put("outcome", "PASSED")
            it.put("defect_step_count", 0); it.put("template_revision_id", UUID.randomUUID().toString()); if (km != null) { it.put("odometer_km", km); it.put("odometer_measured_at", at.toString()) }
        }
        return EventEnvelope(eventId, "inspection.completed", "1.0", inspection, 2, at, at, tenant.value, tenant.value, UUID.randomUUID(), p).toJson()
    }

    private fun fuelEvent(tenant: TenantId, doc: UUID, vehicle: UUID, km: Long, at: Instant, revision: Long = 2, complete: Boolean = true): String {
        val p = json.createObjectNode().also {
            it.put("document_id", doc.toString()); it.put("revision", revision); it.put("vehicle_id", vehicle.toString()); it.put("fuel_type", "DIESEL"); it.put("unit", "LITER"); it.put("quantity", "40"); it.put("unit_price", "1.7")
            it.put("odometer_km", km); it.put("fueled_at", at.toString()); it.put("complete", complete); it.put("recorded_at", at.toString())
        }
        return EventEnvelope(UUID.randomUUID(), "fuel.transaction_recorded", "1.0", doc, revision, at, at, tenant.value, tenant.value, UUID.randomUUID(), p).toJson()
    }

    private fun approvedEvent(tenant: TenantId, doc: UUID, at: Instant): String {
        val p = json.createObjectNode().also { it.put("document_id", doc.toString()); it.put("revision", 9); it.put("state", "POSTED"); it.put("occurred_at", at.toString()) }
        return EventEnvelope(UUID.randomUUID(), "expense.approved", "1.0", doc, 9, at, at, tenant.value, tenant.value, UUID.randomUUID(), p).toJson()
    }

    @Test
    fun stand_aus_vier_quellen_wird_mit_herkunft_gespeichert() {
        val w = W()
        // aufsteigende Stände aus zwei Quellen, dann die beiden anderen: kein Konflikt
        w.record(OdometerSource.DRIVER_CHECK, "inspection-1", 10_000, t0)
        w.record(OdometerSource.FUEL_RECEIPT, "doc-1:10120", 10_120, t0.plusSeconds(86_400))
        w.record(OdometerSource.WORKSHOP, "wst-77", 10_300, t0.plusSeconds(2 * 86_400))
        // Telematik bis D-09 nur aus dem Simulator
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.record(OdometerSource.TELEMATICS, "gps-4711", 10_400, t0.plusSeconds(3 * 86_400)) }.code)
        assertEquals(3, w.rows(), "der abgewiesene Telematikstand wurde nicht gespeichert")
        w.record(OdometerSource.TELEMATICS, "sim:run-1", 10_400, t0.plusSeconds(3 * 86_400))
        val rows = reg.readings(w.finance, w.vehicle, 50, null)
        assertEquals(listOf(OdometerSource.DRIVER_CHECK, OdometerSource.FUEL_RECEIPT, OdometerSource.WORKSHOP, OdometerSource.TELEMATICS), rows.map { it.source })
        assertEquals(listOf(10_000L, 10_120L, 10_300L, 10_400L), rows.map { it.readingKm })
        assertEquals("inspection-1", rows[0].sourceRef); assertEquals(t0, rows[0].measuredAt)
        assertTrue(rows.all { it.recordedAt.isAfter(it.measuredAt) && it.recordedBy.startsWith("user:") }, "Messzeit und Erfassungszeit getrennt, Erfasser protokolliert")
        assertTrue(w.conflicts().isEmpty(), "aufsteigende Stände sind kein Konflikt")
        assertEquals(4, w.audit("finance.odometer.recorded"))
        // Seitenweise Abruf per Cursor
        val p1 = reg.readings(w.finance, w.vehicle, 2, null); val p2 = reg.readings(w.finance, w.vehicle, 2, p1.last().measuredAt to p1.last().id)
        assertEquals(rows.map { it.id }, (p1 + p2).map { it.id })
        // Fahrercheck und Tankbeleg kommen auch über Ereignisse (Inbox)
        val w2 = W(); val insp = UUID.randomUUID()
        assertEquals(InboxOutcome.PROCESSED, events.consume(inspectionEvent(w2.tenant, w2.vehicle, insp, 5_000, t0)))
        assertEquals(InboxOutcome.PROCESSED, events.consume(inspectionEvent(w2.tenant, w2.vehicle, UUID.randomUUID(), null, t0)))   // ohne Kilometerstand: keine Wirkung
        val r = reg.readings(w2.finance, w2.vehicle, 10, null)
        assertEquals(listOf(OdometerSource.DRIVER_CHECK), r.map { it.source }); assertEquals(insp.toString(), r[0].sourceRef); assertEquals("service:odometer-register", r[0].recordedBy)
    }

    /** Einen Rechtsträger im Mandanten des Szenarios (für Belege). */
    private fun docWorld(w: W): UploadFixture.World {
        val le = UUID.randomUUID()
        bf.fx.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", w.tenant.value, le) }
        return UploadFixture.World(w.tenant, le)
    }

    @Test
    fun widerspruechlicher_stand_wird_konflikt_und_kein_wert_ueberschrieben() {
        val w = W()
        w.record(OdometerSource.DRIVER_CHECK, "i-1", 5_000, t0)
        // rückläufig: später gemessen (zwei Stunden, außerhalb des Zeitfensters) und niedriger
        val dec = w.record(OdometerSource.FUEL_RECEIPT, "d-1:4000", 4_000, t0.plusSeconds(7_200))
        assertEquals(1, dec.conflicts.size); assertEquals(OdometerConflictKind.DECREASING, dec.conflicts[0].kind)
        assertEquals(5_000, dec.conflicts[0].earlier.readingKm); assertEquals(4_000, dec.conflicts[0].later.readingKm)
        // widersprüchliche Quellen im selben Zeitfenster (10 Minuten Abstand) mit Abweichung über der Toleranz
        val mis = w.record(OdometerSource.WORKSHOP, "w-1", 5_200, t0.plusSeconds(600))
        // der Werkstattstand 5.200 weicht vom Fahrercheck ab (Quellen) und liegt zugleich über dem später gemessenen Tankstand 4.000 (rückläufig): beides wird erfasst
        assertEquals(setOf(OdometerConflictKind.SOURCE_MISMATCH, OdometerConflictKind.DECREASING), mis.conflicts.map { it.kind }.toSet()); assertEquals(2, mis.conflicts.size)
        // beide Rohstände unverändert vorhanden, nichts überschrieben, nichts gelöscht
        val rows = reg.readings(w.finance, w.vehicle, 50, null)
        assertEquals(listOf(5_000L, 5_200L, 4_000L), rows.map { it.readingKm })
        assertEquals(3, w.conflicts().size); assertTrue(w.conflicts().all { it.status == OdometerConflictStatus.OPEN && it.resolution == null })
        assertEquals(3, w.audit("finance.odometer.conflict_detected"))
        assertEquals(3, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'odometer.conflict_detected'", w.tenant.value))
        // die Runtime-Rolle kann einen Stand weder ändern noch löschen
        val id = rows[0].id
        assertTrue(runCatching { bf.fx.ctxTx.run(w.finance) { j -> j.update("UPDATE taktovia.odometer_reading SET reading_km = 1 WHERE id = ?", id) } }.exceptionOrNull() is DataAccessException)
        assertTrue(runCatching { bf.fx.ctxTx.run(w.finance) { j -> j.update("DELETE FROM taktovia.odometer_reading WHERE id = ?", id) } }.exceptionOrNull() is DataAccessException)
        assertEquals(3, w.rows())
        // Fahrzeug eines fremden Mandanten ergibt 404 (Lesen, Schreiben, Konflikte)
        val other = W()
        assertEquals(ErrorCode.NOT_FOUND, problem { reg.readings(other.finance, w.vehicle, 10, null) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { other.record(OdometerSource.WORKSHOP, "x", 1, t0, v = w.vehicle) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { reg.conflicts(other.finance, w.vehicle, null, 10, null) }.code)
        // ohne Recht 403
        assertEquals(ErrorCode.FORBIDDEN, problem { reg.readings(ctx(w.tenant, setOf("finance"), emptySet()), w.vehicle, 10, null) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { w.record(OdometerSource.WORKSHOP, "y", 1, t0, by = ctx(w.tenant, setOf("finance"), setOf("odometer:read"))) }.code)
        // formale Verstöße
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.record(OdometerSource.WORKSHOP, "z", -1, t0) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.record(OdometerSource.WORKSHOP, " ", 1, t0) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { w.record(OdometerSource.WORKSHOP, "z", 1, Instant.now().plusSeconds(10 * 86_400)) }.code)
    }

    @Test
    fun wiederholte_zustellung_desselben_quellereignisses_ein_eintrag() {
        val w = W()
        val first = w.record(OdometerSource.WORKSHOP, "wst-1", 7_000, t0)
        val again = w.record(OdometerSource.WORKSHOP, "wst-1", 7_000, t0)
        assertTrue(first.created); assertFalse(again.created); assertEquals(first.reading.id, again.reading.id); assertEquals(1, w.rows())
        // über die Inbox: dasselbe Ereignis (gleiche event_id) zweimal zugestellt
        val insp = UUID.randomUUID(); val eventId = UUID.randomUUID()
        val json = inspectionEvent(w.tenant, w.vehicle, insp, 7_050, t0.plusSeconds(86_400), eventId)
        assertEquals(InboxOutcome.PROCESSED, events.consume(json)); assertEquals(InboxOutcome.DUPLICATE, events.consume(json))
        assertEquals(2, w.rows())
        // ein neu erzeugtes Ereignis (andere event_id) zum selben Prüflauf ergibt ebenfalls keinen zweiten Eintrag
        assertEquals(InboxOutcome.PROCESSED, events.consume(inspectionEvent(w.tenant, w.vehicle, insp, 7_050, t0.plusSeconds(86_400))))
        assertEquals(2, w.rows()); assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.odometer_reading WHERE vehicle_id = ? AND source = 'DRIVER_CHECK'", w.vehicle))
        // Tankbeleg: erst mit Freigabe; die Freigabe holt die Aufnahme nach, weitere Revisionen und Wiederholungen ergeben nichts Neues
        val fw = docWorld(w); val doc = bf.upload(fw, bf.driverCtx(fw), bf.uniqueJpeg(), kind = DocumentKind.FUEL_RECEIPT).documentId
        bf.fx.exec("INSERT INTO taktovia.fuel_transaction(tenant_id,id,document_id,vehicle_id,fuel_type,quantity,unit_price,odometer_km,fueled_at,document_revision,recorded_by) VALUES (?,?,?,?,'DIESEL',40,1.7,7100,TIMESTAMPTZ '2026-06-03T08:00:00Z',2,'u')", w.tenant.value, UUID.randomUUID(), doc, w.vehicle)
        assertEquals(InboxOutcome.PROCESSED, events.consume(fuelEvent(w.tenant, doc, w.vehicle, 7_100, t0.plusSeconds(2 * 86_400))))
        assertEquals(2, w.rows(), "nicht freigegeben: noch kein Stand")
        bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:freigeber' WHERE id=?", doc)
        assertEquals(InboxOutcome.PROCESSED, events.consume(approvedEvent(w.tenant, doc, t0.plusSeconds(3 * 86_400))))
        assertEquals(3, w.rows())
        assertEquals(InboxOutcome.PROCESSED, events.consume(fuelEvent(w.tenant, doc, w.vehicle, 7_100, t0.plusSeconds(2 * 86_400), revision = 5)))
        assertEquals(InboxOutcome.PROCESSED, events.consume(approvedEvent(w.tenant, doc, t0.plusSeconds(3 * 86_400))))
        assertEquals(3, w.rows())
        val fuel = reg.readings(w.finance, w.vehicle, 10, null).single { it.source == OdometerSource.FUEL_RECEIPT }
        assertEquals("$doc:7100", fuel.sourceRef); assertEquals(t0.plusSeconds(2 * 86_400), fuel.measuredAt)
        // unvollständige Tankwerte zählen nicht
        assertEquals(InboxOutcome.PROCESSED, events.consume(fuelEvent(w.tenant, doc, w.vehicle, 9_999, t0.plusSeconds(4 * 86_400), complete = false)))
        assertEquals(3, w.rows())
        // Fahrzeug im Empfängermandanten unbekannt (z. B. Kennung eines anderen Mandanten): verworfen, nichts geschrieben
        val foreign = W()
        assertEquals(InboxOutcome.PROCESSED, events.consume(inspectionEvent(foreign.tenant, w.vehicle, UUID.randomUUID(), 1, t0)))   // Fahrzeug im Mandanten unbekannt: verworfen
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.odometer_reading WHERE tenant_id = ?", foreign.tenant.value)); assertEquals(3, w.rows())
    }

    @Test
    fun gleichzeitige_meldung_aus_zwei_quellen_beide_erhalten_konflikt_sichtbar() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(8) { round ->
                val w = W()
                val barrier = CountDownLatch(1)
                val jobs = listOf(
                    pool.submit<Any> { barrier.await(); w.record(OdometerSource.DRIVER_CHECK, "i-$round", 10_000, t0) },
                    pool.submit<Any> { barrier.await(); w.record(OdometerSource.FUEL_RECEIPT, "d-$round:12000", 12_000, t0.plusSeconds(600)) },
                )
                barrier.countDown(); jobs.forEach { it.get(30, TimeUnit.SECONDS) }
                assertEquals(2, w.rows(), "kein Stand geht verloren (Runde $round)")
                val c = w.conflicts()
                assertEquals(1, c.size, "der Konflikt wird erkannt, egal in welcher Reihenfolge die Meldungen eintreffen (Runde $round)")
                assertEquals(OdometerConflictKind.SOURCE_MISMATCH, c[0].kind); assertEquals(setOf(10_000L, 12_000L), setOf(c[0].earlier.readingKm, c[0].later.readingKm))
                assertEquals(1, w.audit("finance.odometer.conflict_detected"))
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun toleranz_und_rueckgaengiger_stand_erzeugen_konflikt_innerhalb_toleranz_keiner() {
        val w = W()
        val h = 3_600L
        w.record(OdometerSource.DRIVER_CHECK, "a", 1_000, t0)
        // rückläufig innerhalb der Toleranz (5 km): kein Konflikt, auch genau auf der Toleranz nicht; darüber schon (verglichen wird mit dem unmittelbaren Vorgänger nach Messzeit)
        assertTrue(w.record(OdometerSource.FUEL_RECEIPT, "b", 996, t0.plusSeconds(2 * h)).conflicts.isEmpty())
        assertTrue(w.record(OdometerSource.FUEL_RECEIPT, "c", 995, t0.plusSeconds(4 * h)).conflicts.isEmpty())
        assertTrue(w.record(OdometerSource.FUEL_RECEIPT, "d", 990, t0.plusSeconds(6 * h)).conflicts.isEmpty(), "genau auf der Toleranz")
        val over = w.record(OdometerSource.FUEL_RECEIPT, "e", 984, t0.plusSeconds(8 * h))
        assertEquals(listOf(OdometerConflictKind.DECREASING), over.conflicts.map { it.kind }); assertEquals(990, over.conflicts[0].earlier.readingKm)
        // widersprüchliche Quellen im Zeitfenster: Abweichung bis zur Toleranz ist kein Konflikt, darüber schon (ein Konflikt je abweichendem Gegenstand)
        val m = W()
        m.record(OdometerSource.DRIVER_CHECK, "a", 1_000, t0)
        assertTrue(m.record(OdometerSource.WORKSHOP, "w", 1_005, t0.plusSeconds(600)).conflicts.isEmpty())
        val mis = m.record(OdometerSource.TELEMATICS, "sim:1", 1_006, t0.plusSeconds(1_200))
        assertEquals(listOf(OdometerConflictKind.SOURCE_MISMATCH), mis.conflicts.map { it.kind }); assertEquals(1_000, mis.conflicts[0].earlier.readingKm)
        // dieselbe Abweichung außerhalb des Zeitfensters (2 Stunden) ist keine Quellenabweichung, solange sie aufsteigend ist
        assertTrue(m.record(OdometerSource.FUEL_RECEIPT, "f", 1_100, t0.plusSeconds(2 * h + 1_200)).conflicts.isEmpty())
        // Nachzügler: ein früher gemessener, höherer Stand gegenüber einem schon vorhandenen späteren ist rückläufig (Nachfolgerprüfung)
        val n = W()
        n.record(OdometerSource.DRIVER_CHECK, "a", 2_000, t0.plusSeconds(24 * h))
        val late = n.record(OdometerSource.WORKSHOP, "w", 2_100, t0)
        assertEquals(listOf(OdometerConflictKind.DECREASING), late.conflicts.map { it.kind }); assertEquals(2_100, late.conflicts[0].earlier.readingKm); assertEquals(2_000, late.conflicts[0].later.readingKm)
        // Parameter je Mandant: Toleranz 50 km
        val p = W(); reg.updatePolicy(p.finance, 50, 60)
        p.record(OdometerSource.DRIVER_CHECK, "a", 1_000, t0)
        assertTrue(p.record(OdometerSource.FUEL_RECEIPT, "b", 960, t0.plusSeconds(2 * h)).conflicts.isEmpty())
        assertEquals(1, p.record(OdometerSource.FUEL_RECEIPT, "c", 905, t0.plusSeconds(4 * h)).conflicts.size)
    }

    @Test
    fun aufloesung_ohne_grund_422_mit_akteur_grund_protokolliert_keine_automatische_auswahl() {
        val w = W()
        w.record(OdometerSource.DRIVER_CHECK, "a", 5_000, t0)
        val c = w.record(OdometerSource.FUEL_RECEIPT, "b", 4_000, t0.plusSeconds(7_200)).conflicts.single()
        // keine automatische Auswahl: der Konflikt bleibt offen, solange kein Mensch entscheidet
        assertEquals(OdometerConflictStatus.OPEN, w.conflicts().single().status); assertNull(w.conflicts().single().resolution)
        // ohne Grund (leer, nur Leerzeichen, zu kurz) wird abgewiesen
        for (reason in listOf("", "   ", "ab")) assertEquals(ErrorCode.VALIDATION_FAILED, problem { reg.resolve(w.finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.CHOSEN, c.earlier.id, reason)) }.code, "Grund '$reason'")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { reg.resolve(w.finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.CHOSEN, null, "Grund genannt")) }.code, "CHOSEN ohne Stand")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { reg.resolve(w.finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.CHOSEN, UUID.randomUUID(), "Fremder Stand")) }.code)
        assertEquals(OdometerConflictStatus.OPEN, w.conflicts().single().status, "abgewiesene Auflösungen ändern nichts")
        // Rechte: Scope, Finanzrolle und Mensch
        assertEquals(ErrorCode.FORBIDDEN, problem { reg.resolve(ctx(w.tenant, setOf("finance"), setOf("odometer:read")), w.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "Grund genannt")) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { reg.resolve(ctx(w.tenant, setOf("dispatcher"), setOf("odometer:resolve")), w.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "Grund genannt")) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { reg.resolve(ctx(w.tenant, setOf("finance"), setOf("odometer:resolve"), ActorType.SERVICE), w.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "Grund genannt")) }.code)
        // protokollierte Auflösung: Akteur, Grund, Zeit; die Rohstände bleiben sichtbar
        val before = reg.readings(w.finance, w.vehicle, 10, null)
        val done = reg.resolve(w.finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.CHOSEN, c.earlier.id, "  Fahrercheck ist durch Foto belegt  "))
        assertEquals(OdometerConflictStatus.RESOLVED, done.status)
        assertEquals("user:" + w.finance.actor.subject, done.resolution!!.resolvedBy); assertEquals("Fahrercheck ist durch Foto belegt", done.resolution!!.reason)
        assertEquals(c.earlier.id, done.resolution!!.chosenReadingId); assertTrue(done.resolution!!.resolvedAt.isAfter(done.detectedAt))
        assertEquals(before, reg.readings(w.finance, w.vehicle, 10, null), "die Rohstände bleiben unverändert erhalten")
        assertEquals(1, w.audit("finance.odometer.conflict_resolved"))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'finance.odometer.conflict_resolved' AND actor_ref = ?", w.tenant.value, "user:" + w.finance.actor.subject))
        assertEquals(listOf(c.id), w.conflicts(status = OdometerConflictStatus.RESOLVED).map { it.id }); assertTrue(w.conflicts(status = OdometerConflictStatus.OPEN).isEmpty())
        // erneute Auflösung: 409; Konflikt eines anderen Fahrzeugs bzw. Mandanten: 404
        assertEquals(ErrorCode.CONFLICT, problem { reg.resolve(w.finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "zweiter Versuch")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { reg.resolve(w.finance, vehicle(w.tenant), c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "falsches Fahrzeug")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { reg.resolve(W().finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "fremder Mandant")) }.code)
        // ein verworfener Stand zählt für spätere Konflikte nicht mehr: 4.000 war verworfen, 5.010 gegen 5.000 ist in Ordnung
        assertTrue(w.record(OdometerSource.WORKSHOP, "w", 5_010, t0.plusSeconds(86_400)).conflicts.isEmpty())
    }

    @Test
    fun parallele_aufloesung_genau_ein_eintrag_und_mandanten_getrennt() {
        val w = W()
        w.record(OdometerSource.DRIVER_CHECK, "a", 5_000, t0)
        val c = w.record(OdometerSource.FUEL_RECEIPT, "b", 4_000, t0.plusSeconds(7_200)).conflicts.single()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val go = CountDownLatch(1)
            val jobs = (1..6).map { n -> pool.submit<Result<Unit>> { go.await(); runCatching { reg.resolve(w.finance, w.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "Auflösung Nr. $n")); Unit } } }
            go.countDown(); val results = jobs.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess }, results.toString())
            assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as ProblemException).code == ErrorCode.CONFLICT })
        } finally { pool.shutdownNow() }
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.odometer_conflict_resolution WHERE conflict_id = ?", c.id)); assertEquals(1, w.audit("finance.odometer.conflict_resolved"))
        // zwei Mandanten mit gleicher Quellkennung und gleichem Muster: getrennt, Konflikte nie mandantenübergreifend
        val b = W()
        b.record(OdometerSource.DRIVER_CHECK, "a", 9_000, t0)   // gleicher Bezug "a" wie bei Mandant A
        assertEquals(1, b.rows()); assertTrue(b.conflicts().isEmpty(), "Mandant B sieht keine Konflikte von A und löst keine aus")
        val bc = b.record(OdometerSource.FUEL_RECEIPT, "b", 8_000, t0.plusSeconds(7_200)).conflicts.single()
        assertNotEquals(c.id, bc.id); assertEquals(b.vehicle, bc.vehicleId)
        assertEquals(1, w.conflicts().size); assertEquals(w.vehicle, w.conflicts()[0].vehicleId)
        assertEquals(ErrorCode.NOT_FOUND, problem { reg.resolve(b.finance, b.vehicle, c.id, ResolveConflict(ConflictDecision.DISCARDED, null, "fremder Konflikt")) }.code)
        // Konfliktparameter je Mandant
        val p = reg.updatePolicy(w.finance, 50, 30); assertEquals(50, p.toleranceKm); assertEquals(30, p.windowMinutes); assertEquals(1, p.revision)
        assertEquals(5, reg.policy(b.finance).toleranceKm); assertEquals(ErrorCode.FORBIDDEN, problem { reg.updatePolicy(ctx(w.tenant, setOf("dispatcher"), setOf("odometer:resolve")), 1, 1) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { reg.updatePolicy(w.finance, 1001, 30) }.code)
    }
}
