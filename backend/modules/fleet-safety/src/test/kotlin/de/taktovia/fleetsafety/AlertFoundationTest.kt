package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AckCause
import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.fleetsafety.api.AlertRule
import de.taktovia.fleetsafety.api.AlertSettings
import de.taktovia.fleetsafety.api.AlertState
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Veränderbare Serveruhr der Alarmtests (eigene Klasse, keine Duplikate fremder Fixtures). */
class AlertTestClock(@Volatile var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * WP-M3-52: Alarm-Grundlage gegen die echte Datenbank unter der Runtime-Rolle. Alarm erscheint und wird quittiert; fremder Dienst und Mandant bleiben unsichtbar; Quittierung ohne Rolle;
 * hundert identische Ereignisse ergeben einen Alarm, zwei gleichzeitige Quittierungen eine; kritische Alarme umgehen die Eskalationsverzögerung; Eskalation genau einmal je Stufe (auch bei parallelen
 * Läufen); revisionierte Alarmregeln; keine Personendaten; bedingungsbasierte (technische) Alarme mit Schließen nach Behebung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertFoundationTest {
    private lateinit var fx: FleetFixture
    private val clock = AlertTestClock(Instant.parse("2026-12-01T08:00:00Z"))
    private lateinit var fleet: FleetsafetyApi
    private lateinit var twoStage: FleetsafetyApi

    @BeforeAll fun up() {
        fx = FleetFixture()
        fleet = FleetsafetyApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), clock = clock, alertSettings = AlertSettings(escalationDelay = Duration.ofMinutes(10)))
        twoStage = FleetsafetyApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), clock = clock, alertSettings = AlertSettings(escalationDelay = Duration.ofMinutes(10), maxEscalationLevel = 2))
    }
    @AfterAll fun down() = fx.close()

    private fun tenant(): TenantId = TenantId(UUID.randomUUID()).also { fx.exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", it.value, "t-${it.value}") }

    private fun ctx(t: TenantId, roles: Set<String> = setOf("dispatcher"), scopes: Set<String> = setOf("operator:read", "operator:write"), services: Set<UUID> = emptySet(), subject: String = "disp-" + UUID.randomUUID()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", subject, "sid-$subject", true), t, roles, scopes, services, UUID.randomUUID())

    private fun raise(t: TenantId, kind: String = AlertKinds.VEHICLE_BLOCK_CRITICAL, source: UUID = UUID.randomUUID(), service: UUID? = null, api: FleetsafetyApi = fleet, vehicle: UUID? = null) =
        fx.tx.run(t) { j -> api.alerts.port.raise(j, BusinessAlertRequest(kind, sourceEventId = source, serviceId = service, vehicleId = vehicle)) }

    private fun problem(block: () -> Unit) = assertThrows(ProblemException::class.java, block)

    @Test
    fun alarm_erscheint_in_der_arbeitsliste_und_wird_quittiert() {
        val t = tenant(); val c = ctx(t)
        val vehicle = fx.vehicle(t)
        val r = raise(t, AlertKinds.TECHNICAL_EVENT, vehicle = vehicle)
        assertTrue(r.created); assertEquals(AlertPriority.HIGH, r.priority); assertEquals(1, r.policyRevisionNo)
        val list = fleet.alerts.list(c, null, 50, null)
        assertEquals(1, list.size); val a = list.single()
        assertEquals(r.alertId, a.id); assertEquals(AlertState.OPEN, a.state); assertEquals(vehicle, a.vehicleId); assertEquals(1, a.policyRevisionNo); assertEquals(1L, a.revision); assertNull(a.acknowledgement)
        clock.now = Instant.parse("2026-12-01T08:03:00Z")
        val done = fleet.alerts.acknowledge(c, a.id, a.revision, AckCause.HANDLED, "Werkstatt informiert")
        assertEquals(AlertState.ACKNOWLEDGED, done.state); assertEquals(2L, done.revision)
        val ack = done.acknowledgement!!
        assertEquals("user:${c.actor.subject}", ack.acknowledgedBy); assertEquals(Instant.parse("2026-12-01T08:03:00Z"), ack.acknowledgedAt); assertEquals(AckCause.HANDLED, ack.cause); assertEquals("Werkstatt informiert", ack.note)
        assertTrue(fleet.alerts.list(c, null, 50, null).isEmpty(), "quittiert: nicht mehr offen")
        assertEquals(listOf(a.id), fleet.alerts.list(c, AlertState.ACKNOWLEDGED, 50, null).map { it.id }, "ein quittierter Alarm bleibt nachvollziehbar")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.alert.acknowledged' AND object_ref = ?", t.value, "business_alert:${a.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'alert.raised' AND aggregate_id = ?", t.value, a.id))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'alert.acknowledged' AND aggregate_id = ?", t.value, a.id))
        // doppelte Quittierung: nicht mehr offen
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fleet.alerts.acknowledge(c, a.id, 2, AckCause.HANDLED, null) }.code)
        // leerer Zustand der Arbeitsliste
        assertTrue(fleet.alerts.list(ctx(tenant()), null, 50, null).isEmpty())
    }

    @Test
    fun alarm_fuer_fremden_dienst_und_fremden_mandanten_nicht_sichtbar_quittierung_ohne_rolle_abgewiesen() {
        val t = tenant(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID()
        val mine = raise(t, service = s1).alertId; val other = raise(t, service = s2).alertId; val wide = raise(t, AlertKinds.OVERDUE).alertId
        val c = ctx(t, services = setOf(s1))
        assertEquals(setOf(mine, wide), fleet.alerts.list(c, null, 50, null).map { it.id }.toSet(), "nur berechtigte Dienste und Alarme ohne Dienstbezug")
        assertEquals(ErrorCode.NOT_FOUND, problem { fleet.alerts.get(c, other) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fleet.alerts.acknowledge(c, other, 1, AckCause.HANDLED, null) }.code, "fremder Dienst: wie nicht vorhanden")
        // ohne Dienstscope: nur Alarme ohne Dienstbezug (fail-closed)
        assertEquals(listOf(wide), fleet.alerts.list(ctx(t), null, 50, null).map { it.id })
        // fremder Mandant
        val foreign = ctx(tenant(), services = setOf(s1))
        assertTrue(fleet.alerts.list(foreign, null, 50, null).isEmpty()); assertEquals(ErrorCode.NOT_FOUND, problem { fleet.alerts.get(foreign, mine) }.code)
        // Quittierung ohne Rolle (nur lesen) und mit Dienstkonto
        assertEquals(ErrorCode.FORBIDDEN, problem { fleet.alerts.acknowledge(ctx(t, roles = setOf("callcenter"), scopes = setOf("operator:read"), services = setOf(s1)), mine, 1, AckCause.HANDLED, null) }.code)
        val service = RequestContext(Actor(ActorType.SERVICE, "https://idp.test", "svc", null, false), t, emptySet(), setOf("operator:write"), setOf(s1), UUID.randomUUID())
        assertEquals(ErrorCode.FORBIDDEN, problem { fleet.alerts.acknowledge(service, mine, 1, AckCause.HANDLED, null) }.code)
        assertEquals(AlertState.OPEN, fleet.alerts.get(c, mine).state, "nichts wurde quittiert")
        // nach Entzug des Dienstes (Kontext ohne s1) ist der Alarm weg
        assertEquals(ErrorCode.NOT_FOUND, problem { fleet.alerts.get(ctx(t, services = setOf(s2)), mine) }.code)
    }

    @Test
    fun hundert_identische_ereignisse_ergeben_einen_alarm() {
        val t = tenant(); val source = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(20); val go = CountDownLatch(1)
        val results = (1..100).map { pool.submit<Any> { go.await(); try { raise(t, source = source) } catch (e: Throwable) { e } } }
        go.countDown(); val out = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<Any>(), out.filterIsInstance<Throwable>().map { it.toString() })
        val raised = out.filterIsInstance<de.taktovia.platform.alert.BusinessAlertResult>()
        assertEquals(1, raised.count { it.created }, "genau eine Anlage"); assertEquals(1, raised.map { it.alertId }.toSet().size, "alle Zustellungen nennen denselben Alarm")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ? AND source_event_id = ?", t.value, source))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'alert.raised' AND aggregate_id = ?", t.value, raised.first().alertId), "ein Ereignis je Alarm")
        // Wiederzustellung nach Quittierung erzeugt keinen zweiten Alarm
        val c = ctx(t); val a = fleet.alerts.list(c, null, 10, null).single()
        fleet.alerts.acknowledge(c, a.id, a.revision, AckCause.FALSE_ALARM, null)
        assertFalse(raise(t, source = source).created); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ? AND source_event_id = ?", t.value, source))
        // anderes Ereignis oder andere Regel: eigener Alarm
        assertTrue(raise(t).created); assertTrue(raise(t, AlertKinds.TECHNICAL_EVENT, source = source).created)
    }

    @Test
    fun zwei_gleichzeitige_quittierungen_ergeben_eine() {
        val t = tenant(); val a = raise(t).alertId
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(2); val start = CountDownLatch(1)
        val tasks = (1..2).map { i -> pool.submit<Any> { go.countDown(); start.await(); try { fleet.alerts.acknowledge(ctx(t, subject = "op-$i"), a, 1, AckCause.HANDLED, null) } catch (e: Throwable) { e } } }
        go.await(); start.countDown(); val out = tasks.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it !is Throwable }, "genau eine Quittierung wirkt: $out")
        val loser = out.filterIsInstance<ProblemException>().single(); assertEquals(ErrorCode.REVISION_MISMATCH, loser.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.alert_acknowledgement WHERE tenant_id = ? AND alert_id = ?", t.value, a))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.alert.acknowledged' AND object_ref = ?", t.value, "business_alert:$a"))
        // veraltete Revision: 412 mit aktuellem ETag
        val b = raise(t).alertId
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fleet.alerts.acknowledge(ctx(t), b, 7, AckCause.HANDLED, null) }.code)
    }

    @Test
    fun kritische_alarme_umgehen_die_eskalationsverzoegerung_und_andere_eskalieren_nach_der_verzoegerung() {
        val t = tenant(); val c = ctx(t); clock.now = Instant.parse("2026-12-01T09:00:00Z")
        val critical = raise(t, AlertKinds.VEHICLE_BLOCK_CRITICAL); val normal = raise(t, AlertKinds.OVERDUE)
        val list = fleet.alerts.list(c, null, 50, null).associateBy { it.id }
        assertEquals(1, list.getValue(critical.alertId).escalationLevel); assertEquals(clock.now, list.getValue(critical.alertId).escalatedAt, "kritisch: sofort eskaliert, ohne Verzögerung")
        assertEquals(0, list.getValue(normal.alertId).escalationLevel); assertNull(list.getValue(normal.alertId).escalatedAt); assertEquals(clock.now.plus(Duration.ofMinutes(10)), list.getValue(normal.alertId).escalateAt)
        // der Job fasst den kritischen Alarm nicht noch einmal an
        clock.now = Instant.parse("2026-12-01T09:09:59Z"); assertEquals(emptyList<UUID>(), fleet.alerts.escalateDue(t))
        clock.now = Instant.parse("2026-12-01T09:10:00Z"); assertEquals(listOf(normal.alertId), fleet.alerts.escalateDue(t))
        val after = fleet.alerts.get(c, normal.alertId)
        assertEquals(1, after.escalationLevel); assertEquals(clock.now, after.escalatedAt, "die Eskalation ist in der Arbeitsliste sichtbar"); assertEquals(2L, after.revision)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'alert.escalated' AND aggregate_id = ?", t.value, normal.alertId))
        // nicht noch einmal (höchste Stufe erreicht), auch nicht später
        clock.now = Instant.parse("2026-12-01T12:00:00Z"); assertEquals(emptyList<UUID>(), fleet.alerts.escalateDue(t))
    }

    @Test
    fun eskalation_genau_einmal_je_stufe_auch_bei_parallelen_laeufen_und_quittierte_eskalieren_nicht() {
        val t = tenant(); clock.now = Instant.parse("2026-12-02T09:00:00Z")
        val a = raise(t, AlertKinds.OVERDUE, api = twoStage).alertId; val acked = raise(t, AlertKinds.OVERDUE, api = twoStage).alertId
        val c = ctx(t); twoStage.alerts.acknowledge(c, acked, 1, AckCause.NOT_RELEVANT, null)
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val runs = (1..8).map { pool.submit<List<UUID>> { go.await(); twoStage.alerts.escalateDue(t) } }
        go.countDown(); val ids = runs.flatMap { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(listOf(a), ids, "Stufe 1 genau einmal, nur für den nicht quittierten Alarm")
        assertEquals(1, twoStage.alerts.get(c, a).escalationLevel)
        assertEquals(emptyList<UUID>(), twoStage.alerts.escalateDue(t), "vor Ablauf der nächsten Verzögerung nichts")
        clock.now = clock.now.plus(Duration.ofMinutes(10)); assertEquals(listOf(a), twoStage.alerts.escalateDue(t)); assertEquals(2, twoStage.alerts.get(c, a).escalationLevel)
        clock.now = clock.now.plus(Duration.ofHours(5)); assertEquals(emptyList<UUID>(), twoStage.alerts.escalateDue(t), "höchste Stufe: nie wieder")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'alert.escalated' AND aggregate_id = ?", t.value, a))
        assertEquals(0, twoStage.alerts.get(c, acked).escalationLevel)
    }

    @Test
    fun alarmregeln_sind_revisioniert_und_wirken_auf_neue_ereignisse() {
        val t = tenant(); val c = ctx(t)
        val old = raise(t, AlertKinds.OVERDUE)                                  // Richtlinie Revision 1 (Standard): OVERDUE = HIGH
        assertEquals(1, old.policyRevisionNo); assertEquals(AlertPriority.HIGH, old.priority)
        val rev2 = fleet.alerts.publishPolicy(c, listOf(AlertRule(AlertKinds.OVERDUE, AlertPriority.CRITICAL, "dispatcher"), AlertRule("*", AlertPriority.NORMAL, "support")))
        assertEquals(2, rev2.revisionNo); assertEquals(2, fleet.alerts.currentPolicy(c)!!.revisionNo)
        val fresh = raise(t, AlertKinds.OVERDUE)
        assertEquals(2, fresh.policyRevisionNo); assertEquals(AlertPriority.CRITICAL, fresh.priority); assertEquals(1, fresh.escalationLevel, "neue Regel kritisch: sofort eskaliert")
        val other = raise(t, "UNBEKANNT_X"); assertEquals(AlertPriority.NORMAL, other.priority)
        assertEquals("support", fleet.alerts.get(c, other.alertId).recipientRole)
        // bestehende Alarme behalten ihre Revision und Priorität
        val unchanged = fleet.alerts.get(c, old.alertId); assertEquals(1, unchanged.policyRevisionNo); assertEquals(AlertPriority.HIGH, unchanged.priority)
        assertEquals(2, fleet.alerts.get(c, fresh.alertId).policyRevisionNo)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.alert.policy_published'", t.value))
        // Rechte und Eingaben
        assertEquals(ErrorCode.FORBIDDEN, problem { fleet.alerts.publishPolicy(ctx(t, roles = setOf("support")), listOf(AlertRule("OVERDUE", AlertPriority.NORMAL, "dispatcher"))) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fleet.alerts.publishPolicy(c, emptyList()) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fleet.alerts.publishPolicy(c, listOf(AlertRule("OVERDUE", AlertPriority.NORMAL, "chef"))) }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fleet.alerts.publishPolicy(c, listOf(AlertRule("OVERDUE", AlertPriority.NORMAL, "dispatcher"), AlertRule("OVERDUE", AlertPriority.HIGH, "dispatcher"))) }.code)
        // parallele Veröffentlichung: lückenlose, eindeutige Revisionsnummern
        val pool = Executors.newFixedThreadPool(5); val go = CountDownLatch(1)
        val nums = (1..5).map { pool.submit<Int> { go.await(); fleet.alerts.publishPolicy(c, listOf(AlertRule("OVERDUE", AlertPriority.NORMAL, "dispatcher"))).revisionNo } }
        go.countDown(); val numbers = nums.map { it.get(30, TimeUnit.SECONDS) }.sorted(); pool.shutdown()
        assertEquals(listOf(3, 4, 5, 6, 7), numbers)
    }

    @Test
    fun alarm_enthaelt_keine_personendaten_nur_codes_und_kennungen() {
        val t = tenant()
        // Freitext als Grund oder Bezug wird schon bei der Auslösung abgewiesen
        assertThrows(IllegalArgumentException::class.java) { BusinessAlertRequest(AlertKinds.TECHNICAL_EVENT, sourceEventId = UUID.randomUUID(), reasonCode = "Fahrgast Erika Muster hat Schmerzen") }
        assertThrows(IllegalArgumentException::class.java) { BusinessAlertRequest("Fahrzeug von Erika", sourceEventId = UUID.randomUUID()) }
        assertThrows(IllegalArgumentException::class.java) { BusinessAlertRequest(AlertKinds.OVERDUE) }
        val r = fx.tx.run(t) { j -> fleet.alerts.port.raise(j, BusinessAlertRequest(AlertKinds.OVERDUE, sourceEventId = UUID.randomUUID(), reasonCode = "PICKUP_LATE", refType = "booking", refId = UUID.randomUUID())) }
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'alert.raised'", t.value, r.alertId).single()
        val row = fx.strings("SELECT row_to_json(a)::text FROM taktovia.business_alert a WHERE tenant_id = ? AND id = ?", t.value, r.alertId).single()
        for (text in listOf(payload, row)) for (forbidden in listOf("name", "phone", "email", "address", "diagnos", "passenger", "contact")) assertFalse(text.contains("\"$forbidden", ignoreCase = true), "Alarm enthält '$forbidden': $text")
        assertEquals(setOf("alert_id", "alert_kind", "priority", "vehicle_id", "dedupe_key", "service_id", "recipient_role", "escalation_level", "policy_revision_no"), tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload).propertyNames().toSet())
        // die Sicht (AlertView) hat dieselbe Feldliste: Codes, Kennungen, Zeiten
        val v = fleet.alerts.get(ctx(t), r.alertId)
        assertEquals("PICKUP_LATE", v.reasonCode); assertEquals("booking", v.refType)
    }

    @Test
    fun bedingungsalarm_anhaltender_fehler_genau_ein_offener_alarm_und_schliessen_nach_behebung() {
        val t = tenant(); val c = ctx(t); val key = "TECH_OUTBOX_BACKLOG"
        fun raiseTech() = fx.tx.run(t) { j -> fleet.alerts.port.raise(j, BusinessAlertRequest("TECH_OUTBOX_BACKLOG", dedupeKey = key)) }
        val first = raiseTech(); assertTrue(first.created); assertEquals(AlertPriority.HIGH, first.priority)
        repeat(20) { assertFalse(raiseTech().created, "anhaltender Fehler: kein neuer Alarm") }
        assertEquals(1, fleet.alerts.list(c, null, 50, null).count { it.kind == "TECH_OUTBOX_BACKLOG" })
        assertEquals(1, fx.tx.run(t) { j -> fleet.alerts.port.resolve(j, key) }, "Behebung schließt den Alarm")
        assertEquals(AlertState.RESOLVED, fleet.alerts.get(c, first.alertId).state); assertNotNull(fleet.alerts.get(c, first.alertId).resolvedAt)
        assertTrue(fleet.alerts.list(c, null, 50, null).none { it.kind == "TECH_OUTBOX_BACKLOG" })
        assertEquals(0, fx.tx.run(t) { j -> fleet.alerts.port.resolve(j, key) })
        val again = raiseTech(); assertTrue(again.created, "erneutes Auftreten: neuer Alarm"); assertTrue(again.alertId != first.alertId)
        // ein gelöster Alarm lässt sich nicht mehr quittieren
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fleet.alerts.acknowledge(c, first.alertId, fleet.alerts.get(c, first.alertId).revision, AckCause.HANDLED, null) }.code)
    }

    @Test
    fun arbeitsliste_seitenweise_nach_alter() {
        val t = tenant(); val c = ctx(t)
        val ids = (1..5).map { clock.now = clock.now.plusSeconds(60); raise(t, AlertKinds.OVERDUE).alertId }
        val p1 = fleet.alerts.list(c, null, 2, null); assertEquals(3, p1.size, "limit + 1 Zeilen: es gibt eine Folgeseite")
        val p2 = fleet.alerts.list(c, null, 2, p1[1].createdAt to p1[1].id); assertEquals(3, p2.size)
        val p3 = fleet.alerts.list(c, null, 2, p2[1].createdAt to p2[1].id); assertEquals(1, p3.size, "letzte Seite")
        assertEquals(ids, p1.take(2).map { it.id } + p2.take(2).map { it.id } + p3.map { it.id }, "älteste zuerst, ohne Lücke und Doppelte")
    }
}
