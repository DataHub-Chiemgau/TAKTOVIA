package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AcknowledgeKnownDefect
import de.taktovia.fleetsafety.api.KnownDefectCatalog
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-21 / LH-6.21-CHK-012, LH-6.22-DMG-012 (AT-29) unter der Runtime-Rolle gegen die echte Datenbank. Originalabnahmen: ein bekannter Schaden wird nicht ungeprüft als neues
 * Schadensereignis angelegt (CHK-012); der Fahrer quittiert einen vorhandenen Schaden, ohne ihn als selbst verursacht zu markieren (DMG-012). Arbeitsannahme „nutzungsrelevant“: alle
 * offenen Akten des Fahrzeugs, die beim Start des Laufs bestanden (nicht freigegeben). Der Abschluss `submitInspection` (WP-M3-14) ist noch nicht gebaut; der Abschluss des Laufs
 * geschieht hier als Statuswechsel in der Datenbank, den der Wächter (Trigger) und der Port `requireAllAcknowledged` abweisen, solange Altschäden unquittiert sind.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnownDamageAcknowledgementTest {
    private lateinit var fx: FleetFixture
    private lateinit var h: EvidenceHarness

    @BeforeAll fun up() { fx = FleetFixture(); h = EvidenceHarness(fx) }
    @AfterAll fun down() = fx.close()

    private fun world() = KnownDamageWorld(fx, h.fleet)
    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun requireAll(w: KnownDamageWorld, run: UUID) = fx.ctxTx.run(w.iw.ctx(UUID.randomUUID(), "workshop")) { j -> h.fleet.knownDefects.requireAllAcknowledged(j, run) }

    @Test
    fun altschaeden_vor_abschluss_angezeigt_und_quittierbar() {
        val w = world(); val d1 = w.defect(); val d2 = w.defect(category = "GLASS", description = "Steinschlag")
        val driver = w.driver(); val run = w.startRun(driver)
        val before = w.defectCount()
        val revisions = fx.strings("SELECT revision::text FROM taktovia.defect_case WHERE vehicle_id=? ORDER BY case_number", w.vehicle)

        // AT-29: neuer Fahrer sieht die offenen Altschäden des gewählten Fahrzeugs vor der Prüfung
        val list = h.fleet.knownDefects.forInspection(driver.ctx, run)
        assertEquals(setOf(d1, d2), list.defects.map { it.defectId }.toSet()); assertTrue(list.acknowledgementRequired); assertEquals(2, list.unacknowledgedCount)
        assertTrue(list.defects.all { !it.acknowledged && it.stillOpen && it.caseNumber.startsWith("SCH-") })
        // Abschluss ohne Quittierung nicht möglich, auch nicht teilweise
        assertEquals(KnownDefectCatalog.UNACKNOWLEDGED, problem { requireAll(w, run) }.detail!!.substringBefore(":"))
        val first = w.ack(driver, run, d1)
        assertTrue(first.created); assertTrue(first.defect.acknowledged); assertEquals(1L, first.defect.acknowledgedDefectRevision)
        assertEquals(KnownDefectCatalog.UNACKNOWLEDGED, problem { requireAll(w, run) }.detail!!.substringBefore(":"))
        assertThrows(SQLException::class.java) { w.complete(run) }
        w.ack(driver, run, d2)
        requireAll(w, run); w.complete(run); assertEquals("COMPLETED", w.status(run))

        // keine neue Schadenakte, alte Akten unverändert und referenziert
        assertEquals(before, w.defectCount())
        assertEquals(revisions, fx.strings("SELECT revision::text FROM taktovia.defect_case WHERE vehicle_id=? ORDER BY case_number", w.vehicle))
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE inspection_id=? AND defect_id IN (?, ?)", run, d1, d2))
        // nach dem Abschluss bleiben die quittierten samt Stand sichtbar, ohne Pflicht
        val after = h.fleet.knownDefects.forInspection(driver.ctx, run)
        assertEquals(2, after.defects.count { it.acknowledged }); assertFalse(after.acknowledgementRequired)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.known_defect.acknowledged' AND object_ref=?", w.tenant.value, "inspection:$run"))
    }

    @Test
    fun abschluss_ohne_quittierung_wird_abgewiesen() {
        val w = world(); val d = w.defect(); val driver = w.driver(); val run = w.startRun(driver)
        val e = assertThrows(SQLException::class.java) { w.complete(run) }
        assertEquals("23514", e.sqlState); assertTrue(e.message!!.contains(KnownDefectCatalog.UNACKNOWLEDGED))
        val p = problem { requireAll(w, run) }
        assertEquals(ErrorCode.CONFLICT, p.code); assertEquals(409, p.code.status)
        assertEquals("IN_PROGRESS", w.status(run))
        // ein abgeschlossener (hier: verworfener) Fall verlangt keine Quittierung mehr
        w.dismiss(d); requireAll(w, run); w.complete(run); assertEquals("COMPLETED", w.status(run))
        // Folgeprüfung der Werkstatt nach Reparatur: keine Quittierungspflicht
        val w2 = world(); w2.defect(); val d2 = w2.driver(); val follow = w2.startRun(d2, kind = "FOLLOW_UP"); requireAll(w2, follow); w2.complete(follow)
    }

    @Test
    fun leerzustand_ohne_quittierungspflicht() {
        val w = world(); val driver = w.driver(); val run = w.startRun(driver)
        val list = h.fleet.knownDefects.forInspection(driver.ctx, run)
        assertTrue(list.defects.isEmpty()); assertFalse(list.acknowledgementRequired); assertEquals(0, list.unacknowledgedCount)
        requireAll(w, run); w.complete(run); assertEquals("COMPLETED", w.status(run))
        assertTrue(h.fleet.knownDefects.forVehicle(driver.ctx, w.vehicle).defects.isEmpty())
    }

    @Test
    fun fahrer_sieht_nur_altschaeden_des_zugewiesenen_fahrzeugs_sonst_404() {
        val w = world(); val own = w.defect(); val otherVehicle = w.iw.vehicle("PKW"); val foreign = w.defect(otherVehicle, description = "fremdes Fahrzeug")
        val driver = w.driver(); val run = w.startRun(driver); val outsider = w.driver(otherVehicle)
        val seen = h.fleet.knownDefects.forInspection(driver.ctx, run).defects
        assertEquals(listOf(own), seen.map { it.defectId })
        assertEquals(listOf(own), h.fleet.knownDefects.forVehicle(driver.ctx, w.vehicle).defects.map { it.defectId })
        // anderes Fahrzeug ohne Bezug: 404; fremder Prüflauf: 404; Akte eines anderen Fahrzeugs quittieren: 404
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.knownDefects.forVehicle(driver.ctx, otherVehicle) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.knownDefects.forInspection(outsider.ctx, run) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { w.ack(outsider, run, own) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { w.ack(driver, run, foreign) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { w.ack(driver, run, UUID.randomUUID()) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE inspection_id=?", run))
        // fremder Mandant: weder Lauf noch Fahrzeug sichtbar (RLS, 404)
        val foreignTenant = world(); val stranger = foreignTenant.ctx(UUID.randomUUID(), "driver", "workshop")
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.knownDefects.forInspection(stranger, run) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.knownDefects.forVehicle(stranger, w.vehicle) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { h.fleet.knownDefects.acknowledge(stranger, run, own, AcknowledgeKnownDefect.parse(emptyMap())) }.code)
        // Rolle ohne Zugriff
        assertEquals(ErrorCode.FORBIDDEN, problem { h.fleet.knownDefects.forInspection(w.ctx(UUID.randomUUID(), "passenger"), run) }.code)
        // Disposition liest alle Läufe des Mandanten, quittiert aber nicht
        assertEquals(1, h.fleet.knownDefects.forInspection(w.dispatcher, run).defects.size)
        assertEquals(ErrorCode.FORBIDDEN, problem { w.ack(KnownDamageWorld.Driver(UUID.randomUUID(), w.dispatcher, UUID.randomUUID()), run, own) }.code)
    }

    @Test
    fun projektion_ohne_vertrauliche_dokumente_und_ohne_freitext() {
        val w = world(); val d = w.defect(description = "Beschreibung mit Details")
        // ein Standardfoto und ein vertrauliches Dokument über den echten Upload-Pfad (Ticket, Quarantäne, Medienprüfung)
        fun photo(seed: Int, confidential: Boolean): UUID {
            val bytes = h.jpeg(seed)
            val t = h.fleet.defectEvidence.requestTicket(w.dispatcher, de.taktovia.fleetsafety.api.RequestEvidenceTicket.parse(h.ticketRaw(bytes) + if (confidential) mapOf("confidentiality" to "CONFIDENTIAL") else emptyMap()))
            h.store.put(de.taktovia.platform.storage.ObjectKey(w.tenant, t.uploadKey), bytes, t.mime)
            return h.complete(w.dispatcher, t).id
        }
        val ticketStandard = photo(11, false); val ticketSecret = photo(12, true)
        val amend = h.fleet.defects.amend(w.dispatcher, d, de.taktovia.fleetsafety.api.DefectAmendment.parse(linkedMapOf("change_reason" to "Fotos", "add_evidence_ids" to listOf(ticketStandard.toString(), ticketSecret.toString()))))
        assertEquals(2, amend.evidence.size)
        val driver = w.driver(); val run = w.startRun(driver)
        val view = h.fleet.knownDefects.forInspection(driver.ctx, run).defects.single()
        assertEquals(1, view.photos.size)   // nur das nicht vertrauliche, freigegebene Foto
        assertEquals(fx.strings("SELECT id::text FROM taktovia.defect_evidence WHERE defect_id=? AND confidentiality='STANDARD'", d).single(), view.photos.single().evidenceId.toString())
        assertEquals(setOf("defectId", "caseNumber", "categoryCode", "kind", "vehicleArea", "photos", "stillOpen", "acknowledged", "acknowledgedAt", "acknowledgedDefectRevision"),
            view.javaClass.declaredFields.map { it.name }.toSet())   // keine Beschreibung, kein Ort, kein Melder, keine Klasse
    }

    @Test
    fun quittierung_speichert_keine_verursacherangabe() {
        val w = world(); val d = w.defect(); val driver = w.driver(); val run = w.startRun(driver)
        // die Anfrage kennt kein Feld: Verursacher-, Schuld- und Freitextfelder werden mit 422 abgewiesen und nie übernommen
        for (field in listOf("caused_by_me", "cause", "fault", "reason", "note", "driver_ref")) {
            val p = problem { AcknowledgeKnownDefect.parse(mapOf(field to true)) }
            assertEquals(ErrorCode.VALIDATION_FAILED, p.code); assertEquals(ViolationCode.UNKNOWN_FIELD, p.violations.single().code); assertEquals("/$field", p.violations.single().pointer)
        }
        w.ack(driver, run, d)
        val cols = fx.strings("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='known_defect_acknowledgement'").toSet()
        assertEquals(setOf("tenant_id", "id", "inspection_id", "defect_id", "acknowledged_by", "revision", "created_at", "defect_revision", "defect_status"), cols)
        // die Akte selbst trägt nach der Quittierung weder neuen Melder noch geänderte Felder
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE vehicle_id=? AND revision=1 AND reported_by <> ?::uuid", w.vehicle, driver.subject))
    }

    @Test
    fun neuanlage_bei_offenen_faellen_zeigt_bestehenden_fall_vorher() {
        val w = world(); val existing = w.defect(); val driver = w.driver()
        fun raw(extra: Map<String, Any?> = emptyMap()) = linkedMapOf<String, Any?>("vehicle_id" to w.vehicle.toString(), "occurred_at" to Instant.now().minusSeconds(60).toString(), "category_code" to "BODY",
            "description" to "Kratzer an der Beifahrertür") + extra
        val before = w.defectCount()
        // dieselbe Schadenmeldung noch einmal, ohne vorherige Anzeige: nicht still durchgewunken, 409 mit Fallnummer des bestehenden Falls
        val p = problem { h.fleet.defects.report(driver.ctx, ReportDefect.parse(raw())) }
        assertEquals(ErrorCode.CONFLICT, p.code); assertTrue(p.detail!!.startsWith(KnownDefectCatalog.OPEN_DEFECTS_PENDING))
        val number = fx.strings("SELECT case_number FROM taktovia.defect_case WHERE id=?", existing).single()
        assertTrue(p.detail!!.contains(number)); assertEquals(before, w.defectCount())
        // der Hinweis nutzt dieselbe Projektion je Fahrzeug
        assertEquals(listOf(existing), h.fleet.knownDefects.forVehicle(driver.ctx, w.vehicle).defects.map { it.defectId })
        // mit ausdrücklicher Bestätigung bleibt die Neuanlage möglich
        val created = h.fleet.defects.report(driver.ctx, ReportDefect.parse(raw(mapOf("confirm_new_despite_known" to true))))
        assertTrue(created.created); assertEquals(before + 1, w.defectCount())
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { ReportDefect.parse(raw(mapOf("confirm_new_despite_known" to "ja"))) }.code)
        // Fahrzeug ohne offene Fälle: keine Bestätigung nötig
        val clean = w.iw.vehicle("PKW"); val d2 = w.driver(clean)
        assertTrue(h.fleet.defects.report(d2.ctx, ReportDefect.parse(raw(mapOf("vehicle_id" to clean.toString())))).created)
        // Wiederholung einer bereits angelegten Meldung (gleiche Geräteereignis-ID) liefert dieselbe Akte, auch ohne Bestätigung
        val ev = UUID.randomUUID().toString()
        val d3 = d2
        val a = h.fleet.defects.report(d3.ctx, ReportDefect.parse(raw(mapOf("vehicle_id" to clean.toString(), "device_event_id" to ev, "confirm_new_despite_known" to true))))
        val again = h.fleet.defects.report(d3.ctx, ReportDefect.parse(raw(mapOf("vehicle_id" to clean.toString(), "device_event_id" to ev))))
        assertFalse(again.created); assertEquals(a.defect.id, again.defect.id)
    }

    @Test
    fun doppelte_quittierung_zwei_geraete_genau_ein_eintrag() {
        val w = world(); val d = w.defect(); val driver = w.driver(); val run = w.startRun(driver)
        val n = 6; val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        try {
            val results = (1..n).map { pool.submit<Boolean> { start.await(); w.ack(driver, run, d).created } }
            start.countDown()
            val created = results.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, created.count { it }); assertEquals(n - 1, created.count { !it })
        } finally { pool.shutdownNow() }
        assertEquals(1, w.ackCount(run, d))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.known_defect.acknowledged' AND object_ref=?", w.tenant.value, "inspection:$run"))
        // spätere Wiederholung: derselbe Eintrag, kein zweiter
        assertFalse(w.ack(driver, run, d).created); assertEquals(1, w.ackCount(run, d))
    }

    @Test
    fun altschaden_parallel_abgeschlossen_pruefabschluss_referenziert_stand_der_quittierung() {
        // deterministisch: quittiert, danach abgeschlossen (verworfen), Abschluss des Laufs bleibt möglich und verweist auf den Stand NEW/Revision 1
        val w = world(); val d = w.defect(); val driver = w.driver(); val run = w.startRun(driver)
        w.ack(driver, run, d); w.dismiss(d); requireAll(w, run); w.complete(run)
        assertEquals(listOf("1|NEW"), fx.strings("SELECT defect_revision || '|' || defect_status FROM taktovia.known_defect_acknowledgement WHERE inspection_id=?", run))
        assertEquals("DISMISSED", fx.strings("SELECT status FROM taktovia.defect_case WHERE id=?", d).single())
        val after = h.fleet.knownDefects.forInspection(driver.ctx, run).defects.single()
        assertTrue(after.acknowledged); assertFalse(after.stillOpen); assertEquals(1L, after.acknowledgedDefectRevision)
        // echt gleichzeitig: Quittierung und Abschluss der Akte; jede Reihenfolge ist zulässig, der Stand ist konsistent, der Abschluss des Laufs bleibt möglich
        repeat(5) {
            val v = world(); val dd = v.defect(); val drv = v.driver(); val r = v.startRun(drv)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            try {
                val a = pool.submit<Any?> { go.await(); try { v.ack(drv, r, dd) } catch (e: ProblemException) { e.code } }
                val b = pool.submit { go.await(); v.dismiss(dd) }
                go.countDown(); val ackResult = a.get(60, TimeUnit.SECONDS); b.get(60, TimeUnit.SECONDS)
                if (ackResult is ErrorCode) assertEquals(ErrorCode.CONFLICT, ackResult)   // Akte schon abgeschlossen: Quittierung nicht mehr nötig
            } finally { pool.shutdownNow() }
            requireAll(v, r); v.complete(r); assertEquals("COMPLETED", v.status(r))
            val rows = fx.strings("SELECT defect_revision || '|' || defect_status FROM taktovia.known_defect_acknowledgement WHERE inspection_id=?", r)
            assertTrue(rows.isEmpty() || rows == listOf("1|NEW") || rows == listOf("2|DISMISSED"), rows.toString())   // Revision und Status gehören immer zusammen
        }
    }

    @Test
    fun akte_nach_start_des_laufs_ist_kein_altschaden() {
        val w = world(); val driver = w.driver(); val run = w.startRun(driver)
        val late = w.defect()
        assertTrue(h.fleet.knownDefects.forInspection(driver.ctx, run).defects.isEmpty())
        assertEquals(ErrorCode.NOT_FOUND, problem { w.ack(driver, run, late) }.code)
        requireAll(w, run); w.complete(run)
    }
}
