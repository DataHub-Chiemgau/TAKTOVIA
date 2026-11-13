package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.fleetsafety.api.AlertSettings
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M3-46 / LH-6.22-DMG-006: sicherheitskritische Meldung, Sofortsperre und priorisierter Alarm gegen die echte Datenbank unter der Runtime-Rolle. Originalabnahme: nach der Meldung werden keine
 * neuen Buchungen an das betroffene Fahrzeug vergeben. Positiv: AT-28 (Bremsmangel melden, Fahrzeug sofort gesperrt, Alarm mit Fahrzeug, Fallnummer und Zahl der betroffenen Fahrten, Audit mit
 * Fallnummer), bestehende Zuweisungen im Störungsablauf, offline erfasste Meldung sperrt ab Serverempfang. Negativ: Meldung zu fremdem Fahrzeug, Doppelmeldung ohne zweiten Alarm, ohne zweiten
 * Revisionssprung, älteres Offline-Ereignis hebt die Sperre nicht auf, Alarm ohne Fahrgastdaten und trotz hoher Eskalationsverzögerung ohne Verzögerung. Konkurrierend: PH-AT-020 in beiden Reihenfolgen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CriticalDefectBlockTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: TriageWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = TriageWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun brake(world: TriageWorld, ctx: de.taktovia.platform.context.RequestContext, vehicle: UUID, extra: Map<String, Any?> = emptyMap()): DefectView =
        world.report(ctx, vehicle, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY", extra)

    /** Zwei offene Umläufe (Schichten in der Zukunft, disjunkt) und ein abgeschlossener des Fahrzeugs. */
    private fun runs(world: TriageWorld, r: ReadinessWorld.Ready): List<UUID> {
        val states = listOf("ACTIVE" to 20L, "PLANNED" to 40L, "COMPLETED" to 60L)
        return states.map { (state, from) ->
            val shift = world.rw.iw.shift(r.driver, r.vehicle, started = false, state = "PUBLISHED", fromHours = from, toHours = from + 6)
            UUID.randomUUID().also { id -> fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,?)", world.tenant.value, id, r.vehicle, shift, state) }
        }
    }

    @Test
    fun kritische_meldung_sperrt_und_alarmiert() {
        val r = w.released(); val open = runs(w, r).take(2)
        val before = w.safetyRevision(r.vehicle)
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, before, Instant.now()) is DispatchVerdict.Dispatchable)

        // der Fahrer meldet den Bremsmangel (Bezug über die laufende Schicht)
        val d = brake(w, w.ctx("driver", subject = r.driver), r.vehicle)
        assertEquals("SAFETY_CRITICAL", d.severity); assertTrue(d.blocksVehicle)
        // Sofortsperre: Sperrzeile mit Quelle Schaden und Fallnummer, Revisionssprung genau einmal, Freigabe widerrufen, Fahrzeug nicht mehr disponierbar (keine neue Buchung)
        assertEquals(listOf("SAFETY|DEFECT_CRITICAL|${d.caseNumber}"), w.activeBlocks(r.vehicle)); assertTrue(w.activeBlockFlag(r.vehicle)); assertEquals(before + 1, w.safetyRevision(r.vehicle))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id = ? AND revoked_at IS NOT NULL AND revoke_reason = 'BLOCK_SET'", r.vehicle))
        val v = fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, before, Instant.now()) as DispatchVerdict.Refused
        assertTrue(DispatchRefusal.VEHICLE_BLOCKED in v.reasons && (DispatchRefusal.RELEASE_REVOKED in v.reasons || DispatchRefusal.STALE_SAFETY_REVISION in v.reasons), v.reasons.toString())
        // Alarm höchster Priorität, ohne Verzögerung, mit Fahrzeug, Fallnummer und Zahl der betroffenen Fahrten (nur die zwei offenen Umläufe)
        val alert = fx.fleet.alerts.list(w.ctx("dispatcher"), null, 50, null).single { it.refId == d.id }
        assertEquals(AlertKinds.VEHICLE_BLOCK_CRITICAL, alert.kind); assertEquals(AlertPriority.CRITICAL, alert.priority); assertEquals(r.vehicle, alert.vehicleId)
        assertEquals(d.caseNumber, alert.caseNumber); assertEquals(2, alert.affectedRunCount); assertEquals(1, alert.escalationLevel); assertNotNull(alert.escalatedAt)
        // Audit: Sperre mit Fallnummer als Grund; Widerruf der Freigabe; Ereignis „Fahrzeug gesperrt“ mit Quelle Schaden
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.blocked' AND object_ref=? AND redacted_changes->>'reason_ref'=? AND redacted_changes->>'reason_code'='DEFECT_CRITICAL'",
            w.tenant.value, "vehicle:${r.vehicle}", d.caseNumber))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release.revoked' AND redacted_changes->>'reason_code'='BLOCK_SET' AND redacted_changes->>'vehicle_id'=?", w.tenant.value, r.vehicle.toString()))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.vehicle_blocked' AND payload->>'vehicle_id'=? AND payload->>'reason_ref'=? AND payload->>'source'='SAFETY'", w.tenant.value, r.vehicle.toString(), d.caseNumber))
        // alles in einem Commit: Akte, Sperre, Fahrzeug, Alarm und Störungsablauf tragen denselben Transaktionsstempel
        val stamps = fx.strings("SELECT (d.xmin::text = b.xmin::text AND d.xmin::text = v.xmin::text AND d.xmin::text = a.xmin::text AND d.xmin::text = i.xmin::text)::text FROM taktovia.defect_case d, taktovia.vehicle_block b, " +
            "taktovia.vehicle v, taktovia.business_alert a, taktovia.incident i WHERE d.id = ? AND d.vehicle_id = v.id AND b.vehicle_id = v.id AND b.reason_ref = d.case_number AND a.ref_id = d.id AND i.vehicle_id = v.id", d.id)
        assertEquals(listOf("true"), stamps)
        assertEquals(open.size, fx.count("SELECT count(*) FROM taktovia.incident_link l JOIN taktovia.incident i ON i.id = l.incident_id WHERE i.vehicle_id = ? AND l.link_kind = 'VEHICLE_RUN'", r.vehicle))
    }

    @Test
    fun schaden_und_zuweisung_parallel_keine_zusage() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            // beide Reihenfolgen gezielt (a: Zuweisung hält die gemeinsame Sperre, Meldung kommt hinzu; b: Meldung zuerst), dazu Verschränkungen mit wachsendem Versatz
            for (order in listOf("dispatch_first", "defect_first") + (1..12).map { "race$it" }) {
                val r = w.released(); val rev = w.safetyRevision(r.vehicle); val start = CountDownLatch(1)
                val commit = AtomicReference<Instant>(); val blockDone = AtomicReference<Instant>(); val seen = AtomicReference<DispatchVerdict>()
                val delayDispo = if (order == "defect_first") 120L else if (order.startsWith("race")) 0L else 0L
                val delayDefect = when { order == "dispatch_first" -> 60L; order.startsWith("race") -> (order.removePrefix("race").toLong() % 6) * 11L; else -> 0L }
                val dispo = pool.submit {
                    start.await(); Thread.sleep(delayDispo)
                    // wie der Hold-Commit der Disposition: Prüfung unter der gemeinsamen Zeilensperre, danach die „Zusage“-Arbeit, dann Commit
                    val v = fx.ctxTx.run(w.ctx("dispatcher")) { j -> fx.fleet.readiness.assertDispatchable(j, r.vehicle, r.shift, rev, Instant.now()).also { Thread.sleep(150) } }
                    commit.set(Instant.now()); seen.set(v)
                }
                val defect = pool.submit {
                    start.await(); Thread.sleep(delayDefect)
                    brake(w, w.ctx("dispatcher"), r.vehicle); blockDone.set(Instant.now())
                }
                start.countDown(); dispo.get(30, TimeUnit.SECONDS); defect.get(30, TimeUnit.SECONDS)
                when (val v = seen.get()) {
                    is DispatchVerdict.Dispatchable -> assertFalse(blockDone.get().isBefore(commit.get()), "$order: Zusage nur, wenn die Sperre erst nach dem Commit der Zusage wirksam wurde")
                    is DispatchVerdict.Refused -> assertTrue(v.reasons.isNotEmpty(), order)
                    else -> throw AssertionError("kein Ergebnis")
                }
                if (order == "defect_first") assertTrue(seen.get() is DispatchVerdict.Refused, "Meldung zuerst: die Zusage wird verweigert")
                // nach beiden Transaktionen gilt in jeder Serialisierung: gesperrt und nicht disponierbar
                assertEquals(1, w.activeBlocks(r.vehicle).size, order)
                assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now()) is DispatchVerdict.Refused, order)
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun doppelmeldung_erzeugt_einen_alarm() {
        val r = w.released(); val before = w.safetyRevision(r.vehicle); val ev = UUID.randomUUID(); val driver = w.ctx("driver", subject = r.driver)
        val raw = w.raw(r.vehicle, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY", mapOf("device_event_id" to ev.toString()))
        val first = fx.fleet.defects.report(driver, ReportDefect.parse(raw)); val second = fx.fleet.defects.report(driver, ReportDefect.parse(raw))
        assertTrue(first.created); assertFalse(second.created); assertEquals(first.defect.id, second.defect.id)
        // gleichzeitige Wiederholungen derselben Meldung (Timeout und Wiederholung des Geräts)
        val pool = Executors.newFixedThreadPool(6)
        val ev2 = UUID.randomUUID(); val raw2 = w.raw(r.vehicle, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY", mapOf("device_event_id" to ev2.toString(), "confirm_new_despite_known" to true))
        try {
            val start = CountDownLatch(1)
            val results = (1..6).map { pool.submit<Result<Boolean>> { start.await(); runCatching { fx.fleet.defects.report(driver, ReportDefect.parse(raw2)).created } } }
            start.countDown(); val outs = results.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(outs.all { it.isSuccess }, outs.toString()); assertEquals(1, outs.count { it.getOrThrow() }, "genau eine Anlage")
        } finally { pool.shutdownNow() }
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE vehicle_id = ?", r.vehicle), "zwei verschiedene Geräteereignisse, zwei Akten")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id=? AND ref_type='defect_case' AND vehicle_id = ?", w.tenant.value, r.vehicle), "je Akte genau ein Alarm")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.business_alert a JOIN taktovia.defect_case d ON d.id = a.ref_id WHERE d.device_event_id = ?", ev), "die Wiederholung erzeugt keinen zweiten Alarm")
        assertEquals(before + 2, w.safetyRevision(r.vehicle), "ein Revisionssprung je Akte, keiner für die Wiederholung")
        assertEquals(2, w.activeBlocks(r.vehicle).size)
        // dieselbe Sperrquelle desselben Falls ist idempotent: noch einmal einstufen erzeugt nichts Neues
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.incident WHERE vehicle_id = ? AND status = 'OPEN'", r.vehicle), "genau ein Störungsablauf je Fahrzeug und Ursache")
        // Meldung zu fremdem Fahrzeug: 404, nichts angelegt
        val foreign = TriageWorld(fx); val fv = foreign.rw.vehicle()
        assertEquals(404, problem { brake(w, w.ctx("dispatcher"), fv) }.code.status)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id = ?", fv)); assertEquals(0, fx.count("SELECT count(*) FROM taktovia.defect_case WHERE vehicle_id = ?", fv))
        // ein Fahrer ohne Bezug zum Fahrzeug erfährt nichts und sperrt nichts
        val other = w.released()
        assertEquals(404, problem { brake(w, w.ctx("driver", subject = r.driver), other.vehicle) }.code.status)
        assertEquals(0, w.activeBlocks(other.vehicle).size)
    }

    @Test
    fun bestehende_zuweisungen_gehen_in_stoerungsablauf() {
        val r = w.released(); val open = runs(w, r)
        val d1 = brake(w, w.ctx("dispatcher"), r.vehicle)
        val incident = fx.strings("SELECT id::text || ';' || kind || ';' || status || ';' || dedupe_key FROM taktovia.incident WHERE vehicle_id = ?", r.vehicle).single().split(';')
        assertEquals("VEHICLE_OUTAGE", incident[1]); assertEquals("OPEN", incident[2]); assertEquals("VEHICLE_OUTAGE|${r.vehicle}|SAFETY", incident[3])
        fun links(kind: String) = fx.strings("SELECT object_id::text FROM taktovia.incident_link WHERE incident_id = ?::uuid AND link_kind = ? ORDER BY object_id::text", incident[0], kind)
        assertEquals(open.take(2).map { it.toString() }.sorted(), links("VEHICLE_RUN"), "jeder offene Umlauf des Fahrzeugs steht im Störungsablauf, der abgeschlossene nicht")
        assertEquals(listOf(d1.id.toString()), links("DEFECT")); assertEquals(1, links("ALERT").size)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='incident.opened' AND aggregate_id = ?::uuid", w.tenant.value, incident[0]))
        // eine zweite kritische Akte desselben Fahrzeugs wird demselben Störungsablauf zugeordnet (kein zweiter Incident, kein zweites Ereignis)
        val d2 = brake(w, w.ctx("dispatcher"), r.vehicle)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.incident WHERE vehicle_id = ?", r.vehicle))
        assertEquals(setOf(d1.id.toString(), d2.id.toString()), links("DEFECT").toSet()); assertEquals(2, links("ALERT").size)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='incident.opened' AND aggregate_id = ?::uuid", w.tenant.value, incident[0]))
    }

    @Test
    fun aelteres_offline_ereignis_hebt_sperre_nicht_auf() {
        val r = w.released(); val driver = w.ctx("driver", subject = r.driver)
        // offline erfasste Meldung (Ereigniszeit drei Tage alt, Geräteereignis-ID) trifft erst jetzt ein: die Sperre wirkt ab Serverempfang
        val sent = Instant.now().minusSeconds(3 * 86400)
        val received = Instant.now()
        val d = fx.fleet.defects.report(driver, ReportDefect.parse(w.raw(r.vehicle, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY", mapOf("device_event_id" to UUID.randomUUID().toString(), "occurred_at" to sent.toString())))).defect
        val setAt = Instant.ofEpochMilli((fx.strings("SELECT extract(epoch FROM set_at)::text FROM taktovia.vehicle_block WHERE reason_ref = ? AND vehicle_id = ?", d.caseNumber, r.vehicle).single().toDouble() * 1000).toLong())
        assertFalse(setAt.isBefore(received.minusSeconds(1)), "die Sperre gilt ab Serverempfang, nicht ab der Ereigniszeit"); assertEquals(sent.epochSecond, d.occurredAt.epochSecond)
        // ein älteres Fahrerereignis (vor der Meldung abgeschlossene Prüfung, erst jetzt synchronisiert) hebt die Sperre nicht auf: keine neue Freigabe
        val older = w.rw.inspection(r.shift, r.vehicle, r.driver)
        val outcome = fx.fleet.readiness.issueRelease(w.rw.safetyService(), IssueRelease(r.vehicle, r.shift, older))
        assertTrue(outcome is ReleaseOutcome.Refused && ReleaseBlocker.DEFECT_BLOCK in outcome.missing, outcome.toString())
        assertEquals(1, w.activeBlocks(r.vehicle).size); assertTrue(w.activeBlockFlag(r.vehicle))
        assertTrue(fx.fleet.readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now()) is DispatchVerdict.Refused)
        // auch die Disposition hebt eine Sicherheitssperre nicht auf (nur die Wiederfreigabe, WP-M3-51)
        val blockId = fx.fleet.readiness.activeBlocks(w.ctx("dispatcher"), r.vehicle).single().id
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fx.fleet.readiness.liftOperationalBlock(w.ctx("dispatcher"), r.vehicle, blockId) }.code)
        assertEquals(1, w.activeBlocks(r.vehicle).size)
    }

    @Test
    fun alarm_ohne_fahrgastdaten_und_ohne_eskalationsverzoegerung() {
        val api = de.taktovia.fleetsafety.api.FleetsafetyApi.create(fx.ctxTx, de.taktovia.platform.audit.JdbcAuditPort(), de.taktovia.platform.outbox.Outbox(), alertSettings = AlertSettings(escalationDelay = Duration.ofHours(24), maxEscalationLevel = 2))
        val t = TriageWorld(fx, api); val v = t.rw.vehicle(); val ctx = t.ctx("dispatcher")
        val d = brake(t, ctx, v)
        val critical = api.alerts.list(ctx, null, 50, null).single { it.refId == d.id }
        assertEquals(AlertPriority.CRITICAL, critical.priority); assertEquals(1, critical.escalationLevel, "kritisch: bereits eskaliert, trotz Verzögerung von 24 h")
        assertNotNull(critical.escalatedAt)
        // ein nicht kritischer Alarm derselben Konfiguration wartet dagegen auf die Verzögerung
        t.rw.vehicle().let { other -> fx.ctxTx.run(ctx) { j -> api.alerts.port.raise(j, BusinessAlertRequest(AlertKinds.OVERDUE, sourceEventId = UUID.randomUUID(), vehicleId = other)) } }
        val normal = api.alerts.list(ctx, null, 50, null).single { it.kind == AlertKinds.OVERDUE }
        assertEquals(0, normal.escalationLevel); assertNull(normal.escalatedAt); assertNotNull(normal.escalateAt)
        assertTrue(Duration.between(Instant.now(), normal.escalateAt).toHours() >= 23)
        // der Betriebsauftrag eskaliert den kritischen Alarm nicht ein zweites Mal vor Ablauf der Stufenverzögerung und den normalen noch gar nicht
        assertEquals(emptyList<UUID>(), api.alerts.escalateDue(t.tenant))
        // keine Fahrgast- und Diagnosedaten: Alarmzeile, Ereignis und Antwort tragen nur Codes, Kennungen, Fallnummer und Zahl
        val text = fx.strings("SELECT row_to_json(a)::text FROM taktovia.business_alert a WHERE a.id = ?", critical.id).single() +
            fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='alert.raised' AND aggregate_id = ?", t.tenant.value, critical.id).single()
        for (forbidden in listOf("Testmeldung", "passenger", "Fahrgast", "phone", "email", "Beschreibung", "diagnos")) assertFalse(text.contains(forbidden, ignoreCase = true), forbidden)
        assertTrue(text.contains(d.caseNumber)); assertEquals(0, critical.affectedRunCount)
    }
}
