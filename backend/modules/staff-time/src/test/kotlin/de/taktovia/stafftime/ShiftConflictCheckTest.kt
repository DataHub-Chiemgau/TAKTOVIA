package de.taktovia.stafftime

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.AbsenceKind
import de.taktovia.stafftime.api.CreateAbsence
import de.taktovia.stafftime.api.RosterConflictKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M4-07 / LH-6.23-SCH-006: Konfliktprüfung (Doppelbelegung, Qualifikation, Abwesenheit) gegen die echte Datenbank (Runtime-Rolle). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftConflictCheckTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private val always = "2020-01-01T00:00:00Z" to null as String?

    @Test
    fun konfliktfreier_plan_hat_leere_konfliktliste_und_ist_veroeffentlichbar() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val p = w.person(mapOf("PERSONENBEFOERDERUNG" to always))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = p.driverId, qualification = "PERSONENBEFOERDERUNG"))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02", driver = p.driverId, qualification = "PERSONENBEFOERDERUNG"))
        assertEquals(emptyList<Any>(), fx.staff.rosters.get(ctx, r.rosterId).conflicts)
        fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId) }
    }

    @Test
    fun doppelbelegung_meldung_nennt_beide_schichten() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val person = UUID.randomUUID()
        val a = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "06:00", end = "14:00", driver = person))
        val b = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "13:00", end = "20:00", driver = person))
        // Hinweis beim Speichern (der Entwurf bleibt speicherbar): die zweite Schicht trägt die Meldung mit beiden Schichten
        assertEquals(1, b.conflicts!!.size); assertEquals(RosterConflictKind.DOUBLE_BOOKING, b.conflicts!!.single().kind)
        assertEquals(setOf(a.id, b.id), b.conflicts!!.single().shiftIds.toSet()); assertEquals(person, b.conflicts!!.single().personRef)
        // Konfliktliste im Dienstplan-Lesemodell (Originalabnahme: konfliktbezogene Meldung einschließlich betroffener Schichten)
        val list = fx.staff.rosters.get(ctx, r.rosterId).conflicts!!
        assertEquals(1, list.size); assertEquals(setOf(a.id, b.id), list.single().shiftIds.toSet())
        // eine dritte Person und eine dritte, nicht überlappende Schicht erzeugen nichts
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "20:00", end = "23:00", driver = person))
        assertEquals(1, fx.staff.rosters.get(ctx, r.rosterId).conflicts!!.size)
        // nicht veröffentlichbar: 409 mit stabilem Code, Ursache und beiden Schichten
        val e = assertThrows<ProblemException> { fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId) } }
        assertEquals(ErrorCode.CONFLICT, e.code); assertEquals(ViolationCode.ROSTER_DOUBLE_BOOKING, e.violations.single().code)
        assertTrue(e.violations.single().message.contains(a.id.toString()) && e.violations.single().message.contains(b.id.toString()))
    }

    @Test
    fun angrenzende_schichten_kein_konflikt() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val person = UUID.randomUUID()
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "06:00", end = "14:00", driver = person))
        val next = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "14:00", end = "22:00", driver = person))   // Ende = Beginn: links geschlossen, rechts offen
        assertTrue(next.conflicts!!.isEmpty()); assertEquals(emptyList<Any>(), fx.staff.rosters.get(ctx, r.rosterId).conflicts)
        // andere Person, gleiche Zeit: kein Konflikt
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "06:00", end = "14:00", driver = UUID.randomUUID()))
        assertEquals(emptyList<Any>(), fx.staff.rosters.get(ctx, r.rosterId).conflicts)
    }

    @Test
    fun parallele_zuweisung_wird_spaetestens_bei_publikation_erkannt() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val person = UUID.randomUUID()
        val a = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "06:00", end = "14:00"))
        val b = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(start = "10:00", end = "18:00"))
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        try {
            // zwei Planer weisen gleichzeitig dieselbe Person an zwei überlappende Schichten zu: beide Speicherungen gelingen (Entwurf)
            val f1 = pool.submit { go.await(); fx.staff.rosterShifts.update(w.planner(), r.rosterId, a.id, a.revision, w.input(start = "06:00", end = "14:00", driver = person)) }
            val f2 = pool.submit { go.await(); fx.staff.rosterShifts.update(w.planner(), r.rosterId, b.id, b.revision, w.input(start = "10:00", end = "18:00", driver = person)) }
            go.countDown(); f1.get(60, TimeUnit.SECONDS); f2.get(60, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        val conflicts = fx.staff.rosters.get(ctx, r.rosterId).conflicts!!
        assertEquals(setOf(a.id, b.id), conflicts.single().shiftIds.toSet())
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId) } }.code)
        // die verbindliche Zuordnung wird zusätzlich von der Datenbank verweigert: beide Schichten verbindlich zu setzen scheitert beim zweiten
        fx.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE id = ?", a.id)
        val ex = assertThrows<Exception> { fx.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE id = ?", b.id) }
        assertTrue(generateSequence<Throwable>(ex) { it.cause }.any { it.message?.contains("overlaps another shift") == true })
    }

    @Test
    fun fehlende_oder_abgelaufene_qualifikation_meldung_nennt_qualifikation() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx)
        val without = w.person()
        val expired = w.person(mapOf("PERSONENBEFOERDERUNG" to ("2020-01-01T00:00:00Z" to "2026-12-01T12:00:00Z")))   // läuft mitten in der Schicht ab
        val future = w.person(mapOf("PERSONENBEFOERDERUNG" to ("2026-12-03T00:00:00Z" to null)))
        val ok = w.person(mapOf("PERSONENBEFOERDERUNG" to always))
        val s1 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = without.driverId, qualification = "PERSONENBEFOERDERUNG"))
        val s2 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = expired.driverId, qualification = "PERSONENBEFOERDERUNG"))
        val s3 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = future.driverId, qualification = "PERSONENBEFOERDERUNG"))
        val s4 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = ok.driverId, qualification = "PERSONENBEFOERDERUNG"))
        val s5 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = UUID.randomUUID(), qualification = null))   // ohne Anforderung kein Konflikt
        assertEquals(1, s1.conflicts!!.size); assertEquals("PERSONENBEFOERDERUNG", s1.conflicts!!.single().missingQualification); assertEquals(RosterConflictKind.QUALIFICATION_MISSING, s1.conflicts!!.single().kind)
        assertEquals(listOf(s2.id), s2.conflicts!!.single().shiftIds); assertEquals(listOf(s3.id), s3.conflicts!!.single().shiftIds)
        assertTrue(s4.conflicts!!.isEmpty() && s5.conflicts!!.isEmpty())
        val all = fx.staff.rosters.get(ctx, r.rosterId).conflicts!!
        assertEquals(setOf(s1.id, s2.id, s3.id), all.flatMap { it.shiftIds }.toSet())
        assertTrue(all.all { it.kind == RosterConflictKind.QUALIFICATION_MISSING })
        val e = assertThrows<ProblemException> { fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId) } }
        assertEquals(3, e.violations.size); assertTrue(e.violations.all { it.code == ViolationCode.ROSTER_QUALIFICATION_MISSING && it.message.contains("PERSONENBEFOERDERUNG") })
    }

    @Test
    fun abwesenheit_meldung_ohne_abwesenheitsart() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx); val hr = w.hr()
        val p = w.person(); val legacy = UUID.randomUUID()   // Person mit Mitgliedschaft (Absenz über Subject) und Altbestand (driver_ref = Subject)
        fx.staff.absences.create(hr, CreateAbsence(p.subject, Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-02T00:00:00Z"), AbsenceKind.TRAINING))
        fx.staff.absences.create(hr, CreateAbsence(legacy.toString(), Instant.parse("2026-12-02T00:00:00Z"), Instant.parse("2026-12-03T00:00:00Z"), AbsenceKind.LEAVE))
        val withdrawn = fx.staff.absences.create(hr, CreateAbsence(UUID.randomUUID().toString(), Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-04T00:00:00Z"), AbsenceKind.LEAVE))
        val s1 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = p.driverId))
        val s2 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02", driver = legacy))
        val s3 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-04", driver = p.driverId))   // nach der Abwesenheit
        assertEquals(RosterConflictKind.ABSENCE_OVERLAP, s1.conflicts!!.single().kind); assertEquals(listOf(s1.id), s1.conflicts!!.single().shiftIds)
        assertEquals(RosterConflictKind.ABSENCE_OVERLAP, s2.conflicts!!.single().kind); assertTrue(s3.conflicts!!.isEmpty())
        // die Meldung nennt weder Art noch Kommentar der Abwesenheit, auch nicht in der Problem-Antwort
        val e = assertThrows<ProblemException> { fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId) } }
        val texts = (listOf(e.detail ?: "") + e.violations.map { it.message } + fx.staff.rosters.get(ctx, r.rosterId).conflicts!!.map { it.cause }).joinToString(" ").lowercase()
        for (word in listOf("training", "leave", "urlaub", "schulung", "krank", "sonstige")) assertFalse(texts.contains(word), "Abwesenheitsart '$word' in der Meldung")
        assertEquals(setOf(ViolationCode.ROSTER_ABSENCE_OVERLAP), e.violations.map { it.code }.toSet())
        // zurückgenommene Abwesenheit erzeugt keinen Konflikt
        fx.staff.absences.withdraw(hr, withdrawn.id, withdrawn.revision)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-03", driver = UUID.fromString(withdrawn.personRef)))
        assertEquals(2, fx.staff.rosters.get(ctx, r.rosterId).conflicts!!.size)
    }

    @Test
    fun abwesenheitsgenehmigung_parallel_zur_publikation_wertet_den_committeten_stand() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val r = w.roster(ctx); val hr = w.hr()
        val person = UUID.randomUUID()
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(driver = person))
        // Publikation (Prüfung unter Sperre) läuft; die Abwesenheit wird währenddessen genehmigt, ihr Commit wartet auf das Ende der Publikationstransaktion
        val checking = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val publish = pool.submit<Boolean> { fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId); checking.countDown(); release.await(30, TimeUnit.SECONDS); true } }
            assertTrue(checking.await(30, TimeUnit.SECONDS))
            val absence = pool.submit<Instant> { fx.staff.absences.create(hr, CreateAbsence(person.toString(), Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-02T00:00:00Z"), AbsenceKind.LEAVE)); Instant.now() }
            Thread.sleep(500)
            assertFalse(absence.isDone, "die Genehmigung wartet, solange die Publikation den Stand prüft")
            release.countDown()
            assertTrue(publish.get(30, TimeUnit.SECONDS)); absence.get(30, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        // nach dem Commit der Genehmigung wertet die nächste Prüfung den neuen Stand
        assertEquals(RosterConflictKind.ABSENCE_OVERLAP, fx.staff.rosters.get(ctx, r.rosterId).conflicts!!.single().kind)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.ctxTx.run(ctx) { j -> fx.staff.rosters.requirePublishable(j, ctx, r.rosterId) } }.code)
    }

    @Test
    fun konfliktliste_enthaelt_nur_schichten_des_eigenen_mandanten() {
        val a = RosterTestWorld(fx); val b = RosterTestWorld(fx)
        val person = UUID.randomUUID()   // dieselbe Personenkennung in zwei Mandanten
        val ra = a.roster(a.planner()); val rb = b.roster(b.planner())
        val sb = fx.staff.rosterShifts.create(b.planner(), rb.rosterId, b.input(driver = person))
        val sa = fx.staff.rosterShifts.create(a.planner(), ra.rosterId, a.input(driver = person))
        assertTrue(sa.conflicts!!.isEmpty(), "Schicht des anderen Mandanten kollidiert nie")
        assertEquals(emptyList<Any>(), fx.staff.rosters.get(a.planner(), ra.rosterId).conflicts)
        assertFalse(fx.staff.rosters.get(a.planner(), ra.rosterId).toString().contains(sb.id.toString()))
    }
}
