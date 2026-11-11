package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.AbsenceKind
import de.taktovia.stafftime.api.AbsenceState
import de.taktovia.stafftime.api.AvailabilityRestriction
import de.taktovia.stafftime.api.CreateAbsence
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-06 (Abwesenheit als Verfügbarkeitsrestriktion) gegen die echte Datenbank (Runtime-Rolle): Positiv (anlegen, als Restriktion lesen, Audit),
 * Negativ (Planer sieht die Art nie, Anlegen ohne Personalrolle 403, Fremdmandant 404, ungültiger Zeitraum 422, veraltete Revision 412),
 * Konkurrierend (parallele Rücknahme: genau eine Wirkung).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbsenceTest {
    private lateinit var fx: StaffFixture
    private val from = Instant.parse("2027-03-01T00:00:00Z")
    private val to = Instant.parse("2027-03-06T00:00:00Z")

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    private fun tenant() = fx.world().tenant
    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }

    @Test
    fun abwesenheit_anlegen_und_als_restriktion_lesen_mit_audit() {
        val t = tenant(); val hr = StaffM4Support.hr(t); val person = UUID.randomUUID().toString()
        val a = fx.staff.absences.create(hr, CreateAbsence(person, from, to, AbsenceKind.LEAVE))
        assertEquals(AbsenceState.APPROVED, a.state); assertEquals(1, a.revision); assertEquals(hr.actor.subject, a.createdBy)
        val planner = StaffM4Support.planner(t)
        val rs = fx.staff.absences.restrictions(planner, null, null, null, 50, null)
        assertEquals(listOf(a.id), rs.map { it.id }); assertEquals(person, rs.single().personRef)
        assertEquals(from, rs.single().from); assertEquals(to, rs.single().to)
        // Zeitfenster: überlappend gefunden, davor/danach nicht (halboffen)
        assertEquals(1, fx.staff.absences.restrictions(planner, person, Instant.parse("2027-03-05T00:00:00Z"), Instant.parse("2027-03-09T00:00:00Z"), 50, null).size)
        assertEquals(0, fx.staff.absences.restrictions(planner, person, to, to.plusSeconds(3600), 50, null).size, "Ende ist exklusiv")
        assertEquals(0, fx.staff.absences.restrictions(planner, UUID.randomUUID().toString(), null, null, 50, null).size)
        // Konfliktprüfung (WP-M4-07) liest in ihrer Transaktion denselben Stand
        val intervals = fx.ctxTx.run(planner, readOnly = true) { j -> fx.staff.absences.restrictionsIn(j, person, from.minusSeconds(60), to.plusSeconds(60)) }
        assertEquals(listOf(from to to), intervals.map { it.from to it.to })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.absence.created' AND object_ref=?", t.value, "absence:${a.id}"), "Anlage steht im audit_event")
        assertEquals(a.id, fx.staff.absences.get(hr, a.id).id)
    }

    @Test
    fun planer_erhaelt_die_art_nicht_und_darf_nicht_anlegen() {
        val t = tenant(); val hr = StaffM4Support.hr(t); val planner = StaffM4Support.planner(t)
        val a = fx.staff.absences.create(hr, CreateAbsence("p-1", from, to, AbsenceKind.TRAINING))
        assertEquals(403, problem { fx.staff.absences.get(planner, a.id) }.code.status, "Detail mit Art nur für die Personalrolle")
        val fields = AvailabilityRestriction::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(emptySet<String>(), fields.intersect(setOf("kind", "state", "createdBy", "comment", "reason")), "Restriktion trägt keine Art und keinen Grund: $fields")
        assertEquals(403, problem { fx.staff.absences.create(planner, CreateAbsence("p-2", from, to, AbsenceKind.LEAVE)) }.code.status)
        assertEquals(403, problem { fx.staff.absences.withdraw(planner, a.id, 1) }.code.status)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.absence_request WHERE tenant_id=?", t.value), "nur die berechtigte Anlage ist gespeichert")
        assertEquals(403, problem { fx.staff.absences.restrictions(StaffM4Support.employee(t), null, null, null, 10, null) }.code.status, "Mitarbeiter ohne Scope: keine Restriktionen")
    }

    @Test
    fun fremdmandant_liest_weder_detail_noch_restriktion_noch_nimmt_er_zurueck() {
        val a = tenant(); val b = tenant()
        val created = fx.staff.absences.create(StaffM4Support.hr(a), CreateAbsence("p-1", from, to, AbsenceKind.OTHER))
        val hrB = StaffM4Support.hr(b)
        assertEquals(404, problem { fx.staff.absences.get(hrB, created.id) }.code.status)
        assertEquals(404, problem { fx.staff.absences.withdraw(hrB, created.id, 1) }.code.status)
        assertEquals(emptyList<Any>(), fx.staff.absences.restrictions(StaffM4Support.planner(b), null, null, null, 50, null))
        assertEquals("APPROVED", fx.strings("SELECT state FROM taktovia.absence_request WHERE id=?", created.id).single(), "unverändert")
    }

    @Test
    fun ungueltige_eingaben_ergeben_422() {
        val hr = StaffM4Support.hr(tenant())
        assertEquals(422, problem { fx.staff.absences.create(hr, CreateAbsence("p", from, from, AbsenceKind.LEAVE)) }.code.status)
        assertEquals(422, problem { fx.staff.absences.create(hr, CreateAbsence("p", to, from, AbsenceKind.LEAVE)) }.code.status)
        assertEquals(422, problem { fx.staff.absences.create(hr, CreateAbsence(" ", from, to, AbsenceKind.LEAVE)) }.code.status)
    }

    @Test
    fun ruecknahme_mit_revision_wiederholbar_und_wirkt_auf_die_restriktion() {
        val t = tenant(); val hr = StaffM4Support.hr(t); val planner = StaffM4Support.planner(t)
        val a = fx.staff.absences.create(hr, CreateAbsence("p-1", from, to, AbsenceKind.LEAVE))
        assertEquals(412, problem { fx.staff.absences.withdraw(hr, a.id, 7) }.code.status, "veraltete Revision")
        val w = fx.staff.absences.withdraw(hr, a.id, 1)
        assertEquals(AbsenceState.WITHDRAWN, w.state); assertEquals(2, w.revision)
        assertEquals(w.revision, fx.staff.absences.withdraw(hr, a.id, 2).revision, "Wiederholung mit gültiger Revision liefert denselben Stand")
        assertEquals(emptyList<Any>(), fx.staff.absences.restrictions(planner, null, null, null, 50, null), "zurückgenommen = keine Restriktion mehr")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.absence.withdrawn' AND object_ref=?", t.value, "absence:${a.id}"))
    }

    @Test
    fun parallele_ruecknahme_hat_genau_eine_wirkung() {
        val t = tenant(); val hr = StaffM4Support.hr(t)
        val a = fx.staff.absences.create(hr, CreateAbsence("p-1", from, to, AbsenceKind.LEAVE))
        val pool = Executors.newFixedThreadPool(10); val start = CountDownLatch(1)
        val futures = (1..10).map { pool.submit<Any> { start.await(); try { fx.staff.absences.withdraw(hr, a.id, 1) } catch (e: ProblemException) { e.code } } }
        start.countDown(); val results = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, results.count { it !is ErrorCode }, results.toString()); assertTrue(results.filterIsInstance<ErrorCode>().all { it == ErrorCode.REVISION_MISMATCH })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.absence.withdrawn'", t.value))
        assertEquals(2, fx.count("SELECT revision FROM taktovia.absence_request WHERE id=?", a.id))
    }
}
