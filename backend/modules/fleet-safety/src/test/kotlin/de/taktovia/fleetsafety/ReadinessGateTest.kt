package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DispatchRefusal
import de.taktovia.fleetsafety.api.DispatchVerdict
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseBlocker
import de.taktovia.fleetsafety.api.ReleaseGate
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.ReleaseRevokeReason
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M3-09 / LH-6.21-CHK-001 (Readiness-Gate: die eine Regel „fehlende Voraussetzungen“) unter der Runtime-Rolle gegen die echte Datenbank, ohne Mocks. Positiv: nach bestätigter Prüfung dieser
 * Kombination ist die Schicht freigegeben, ohne fehlende Voraussetzung. Negativ: keine Prüfung, offener Lauf (mit offenen Pflichtschritten), Prüfung einer anderen Schicht, eines anderen Fahrzeugs oder
 * eines anderen Fahrers, widerrufene und abgelaufene Freigabe, Sperre; fremder Mandant 404. Konkurrierend: Dispositionsprüfung unter der Fahrzeugsperre parallel zum Widerruf, zwei gleichzeitige Erteilungen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadinessGateTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: ReadinessWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = ReadinessWorld(fx) }
    @AfterAll fun down() = fx.close()

    private val readiness get() = fx.fleet.readiness
    /** Die Prüfung erfüllt die Bedingung nicht: abgewiesen (fehlende Voraussetzung) oder, weil sie schon zu einer anderen Kombination gehört, 409; in keinem Fall entsteht eine Freigabe. */
    private fun notSatisfied(f: () -> ReleaseOutcome): Boolean = try { f() is ReleaseOutcome.Refused } catch (e: ProblemException) { true }

    private fun codes(v: UUID, s: UUID) = readiness.requirements(w.ctx("dispatcher"), v, s).missing.map { it.code }

    @Test
    fun bereitmeldung_ohne_pruefung_wird_serverseitig_abgewiesen() {
        val d = w.driver(); val v = w.vehicle(); val s = w.shift(d, v)
        // ohne Prüfung: nicht freigegeben, fehlende Voraussetzung NO_INSPECTION, die Disposition lehnt ab (kein Kandidat neuer Fahrten)
        val req = readiness.requirements(w.ctx("dispatcher"), v, s)
        assertEquals(ReleaseGate.NOT_RELEASED, req.gate); assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), req.missing.map { it.code }); assertNull(req.releaseId); assertNull(req.validUntil)
        assertTrue((readiness.assertDispatchable(w.ctx("dispatcher"), v, s, null, Instant.now()) as DispatchVerdict.Refused).reasons.contains(DispatchRefusal.NO_RELEASE))
        // ein Client „meldet bereit“, bringt aber nur einen Prüflauf in Bearbeitung: keine Freigabe, Liste der offenen Pflichtschritte
        val open = w.inspection(s, v, d, status = "IN_PROGRESS", outcome = null)
        val refused = readiness.issueRelease(w.safetyService(), IssueRelease(v, s, open)) as ReleaseOutcome.Refused
        assertTrue(ReleaseBlocker.INSPECTION_OPEN in refused.missing)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE shift_id = ?", s))
        // nach bestätigter, abgeschlossener Prüfung dieser Kombination: freigegeben, nichts fehlt (AT-27 gemeinsam mit WP-M3-14)
        val r = w.ready()
        assertTrue(readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection)) is ReleaseOutcome.Issued)
        val ok = readiness.requirements(w.ctx("dispatcher"), r.vehicle, r.shift)
        assertEquals(ReleaseGate.RELEASED, ok.gate); assertTrue(ok.missing.isEmpty()); assertTrue(ok.validUntil!!.isAfter(Instant.now())); assertTrue(ok.unavailableDimensions.isEmpty())
        // fremder Mandant: Fahrzeug und Schicht sind nicht vorhanden (404)
        val foreign = ReadinessWorld(fx)
        assertEquals(404, assertThrows(ProblemException::class.java) { readiness.requirements(foreign.ctx("dispatcher"), r.vehicle, r.shift) }.code.status)
    }

    @Test
    fun pruefung_anderer_kombination_oder_offener_lauf_erfuellt_nicht() {
        val a = w.ready(); readiness.issueRelease(w.safetyService(), IssueRelease(a.vehicle, a.shift, a.inspection)) as ReleaseOutcome.Issued
        // dieselbe Prüfung für ein anderes Fahrzeug oder eine andere Schicht erfüllt die Bedingung nicht
        val otherVehicle = w.vehicle(); val otherShift = w.shift(w.driver(), otherVehicle)
        assertTrue(notSatisfied { readiness.issueRelease(w.safetyService(), IssueRelease(otherVehicle, a.shift, a.inspection)) })
        assertTrue(notSatisfied { readiness.issueRelease(w.safetyService(), IssueRelease(a.vehicle, otherShift, a.inspection)) })
        assertEquals(listOf(ReleaseBlocker.NO_INSPECTION), codes(otherVehicle, otherShift), "die Freigabe der Schicht A gilt nicht für Fahrzeug und Schicht B")
        // ein anderer Fahrer für dasselbe Fahrzeug: Prüfung eines anderen Fahrers gilt nicht (eigene Schicht, eigene Prüfung)
        val laterDriver = w.driver(); val laterShift = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() + interval '10 hours',now() + interval '18 hours','Europe/Berlin',0,'PUBLISHED')", w.tenant.value, laterShift, laterDriver, a.vehicle)
        assertTrue(notSatisfied { readiness.issueRelease(w.safetyService(), IssueRelease(a.vehicle, laterShift, a.inspection)) }, "Prüfung eines anderen Fahrers erfüllt nicht")
        // offener Lauf: nicht serverbestätigt, die noch unbeantworteten Pflichtschritte stehen in der Antwort
        val d = w.driver(); val v = w.vehicle(); val s = w.shift(d, v); val open = w.inspection(s, v, d, status = "IN_PROGRESS", outcome = null)
        val m = readiness.requirements(w.ctx("dispatcher"), v, s).missing.single()
        assertEquals(ReleaseBlocker.INSPECTION_OPEN, m.code); assertEquals(open, m.inspectionId); assertTrue(m.stepCodes.isEmpty() || m.stepCodes.all { it.isNotBlank() })
        // widerrufene Freigabe: nicht freigegeben, Grund RELEASE_REVOKED
        val r = w.ready(); val rel = (readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection)) as ReleaseOutcome.Issued).release
        readiness.revokeRelease(w.safetyService(), rel.id, ReleaseRevokeReason.MANUAL)
        assertEquals(listOf(ReleaseBlocker.RELEASE_REVOKED), codes(r.vehicle, r.shift))
        // abgelaufene Freigabe (valid_until in der Vergangenheit): INSPECTION_EXPIRED
        val e = w.ready(); fx.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until,created_at) VALUES (?,?,?,?,(SELECT safety_revision FROM taktovia.vehicle WHERE id = ?),?,1,?,?)",
            w.tenant.value, UUID.randomUUID(), e.vehicle, e.shift, e.vehicle, e.inspection, Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(3600)))
        assertEquals(listOf(ReleaseBlocker.INSPECTION_EXPIRED), codes(e.vehicle, e.shift))
        // Sicherheitssperre nach der Freigabe: gesperrt (kein Freigabestand, Sperre mit Grund), die Disposition lehnt ab
        val b = w.ready(); readiness.issueRelease(w.safetyService(), IssueRelease(b.vehicle, b.shift, b.inspection))
        readiness.setBlock(w.safetyService(), SetBlock(b.vehicle, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defekt-1"))
        val req = readiness.requirements(w.ctx("dispatcher"), b.vehicle, b.shift)
        assertEquals(ReleaseGate.BLOCKED, req.gate); assertTrue(req.missing.any { it.code == ReleaseBlocker.DEFECT_BLOCK }); assertFalse(req.missing.isEmpty())
    }

    @Test
    fun bereitmeldung_parallel_zu_freigabewiderruf() {
        repeat(12) { round ->
            val r = w.ready(); val rel = (readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection)) as ReleaseOutcome.Issued).release
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val verdict = AtomicReference<DispatchVerdict>(); val checkedAt = AtomicReference<Instant>()
            // die Dispositionsprüfung läuft in einer Mandantentransaktion unter der gemeinsamen Fahrzeugsperre; der Widerruf nimmt die exklusive Sperre und wartet bis zu ihrem Ende
            val check = pool.submit {
                go.await()
                fx.ctxTx.run(w.ctx("dispatcher")) { j -> verdict.set(readiness.assertDispatchable(j, r.vehicle, r.shift, null, Instant.now())); Thread.sleep(25); checkedAt.set(Instant.now()) }
            }
            val revoke = pool.submit { go.await(); readiness.revokeRelease(w.safetyService(), rel.id, ReleaseRevokeReason.MANUAL) }
            go.countDown(); check.get(30, TimeUnit.SECONDS); revoke.get(30, TimeUnit.SECONDS); pool.shutdown()
            // in keiner Serialisierung entsteht eine Zusage auf ein nicht freigegebenes Fahrzeug: war die Prüfung „disponierbar“, lag der Widerruf danach; sonst vorher
            val revokedAt = fx.strings("SELECT revoked_at::text FROM taktovia.resource_release WHERE id = ?", rel.id).single().let { java.time.OffsetDateTime.parse(it.replace(' ', 'T').let { t -> if (t.endsWith("+00")) t + ":00" else t }).toInstant() }
            if (verdict.get() is DispatchVerdict.Dispatchable) assertTrue(!revokedAt.isBefore(checkedAt.get().minusMillis(5)), "Runde $round: Zusagefähigkeit nur vor dem Widerruf")
            assertTrue(readiness.assertDispatchable(w.ctx("dispatcher"), r.vehicle, r.shift, null, Instant.now()) is DispatchVerdict.Refused, "Runde $round: nach dem Widerruf nicht mehr disponierbar")
            assertEquals(listOf(ReleaseBlocker.RELEASE_REVOKED), codes(r.vehicle, r.shift))
        }
        // zwei gleichzeitige Erteilungen für denselben Prüflauf: genau eine Freigabe
        val r = w.ready(); val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val a = pool.submit<ReleaseOutcome> { go.await(); readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection)) }
        val b = pool.submit<ReleaseOutcome> { go.await(); readiness.issueRelease(w.safetyService(), IssueRelease(r.vehicle, r.shift, r.inspection)) }
        go.countDown(); val ra = a.get(30, TimeUnit.SECONDS); val rb = b.get(30, TimeUnit.SECONDS); pool.shutdown()
        assertEquals(1, listOf(ra, rb).count { it is ReleaseOutcome.Issued && !it.replayed }, "höchstens eine Einsatzfreigabe")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref = ?", r.inspection))
    }
}
