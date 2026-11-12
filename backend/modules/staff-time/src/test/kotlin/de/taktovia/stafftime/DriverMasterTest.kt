package de.taktovia.stafftime

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.DriverCreate
import de.taktovia.stafftime.api.DriverStatus
import de.taktovia.stafftime.api.DriverUpdate
import de.taktovia.stafftime.api.ContactChannel
import de.taktovia.stafftime.api.QualificationInput
import de.taktovia.stafftime.api.QualificationStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
 * WP-M3-05: Fahrerstamm und Qualifikationsrevisionen unter der Runtime-Rolle. Positiv: gültige Qualifikation wird zum Zeitpunkt t gefunden, jede Änderung ist eine neue Revision, die alte
 * bleibt lesbar. Negativ: abgelaufene, nicht freigegebene, entzogene Qualifikation und fremder Mandant gelten als nicht vorhanden bzw. 404; HR-nahe Felder nur für die Personalplanung.
 * Konkurrierend: gleichzeitige Änderungen vergeben die Revisions- und Folgenummern lückenlos.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriverMasterTest {
    private lateinit var fx: StaffFixture

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val now = Instant.parse("2026-11-12T08:00:00Z")

    private class Tenant(val id: TenantId)

    private fun tenant(): TenantId = TenantId(UUID.randomUUID()).also { fx.exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", it.value, "t-${it.value}") }

    private fun membership(t: TenantId, role: String = "driver"): UUID = UUID.randomUUID().also {
        fx.exec("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (?,?,'https://idp.test/realms/x',?,?)", t.value, it, "sub-$it", role)
    }

    private fun ctx(t: TenantId, role: String = "staff_planning") = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/hr", "u-" + UUID.randomUUID(), "sid-x", true), t, setOf(role), setOf("drivers:read", "drivers:write"), emptySet(), UUID.randomUUID())

    private fun driver(t: TenantId, name: String = "Erika Muster") =
        fx.staff.drivers.create(ctx(t), DriverCreate(membership(t), name, ContactChannel.PHONE, "+491701234567"))

    private fun qual(code: String = "PBEF", from: Instant = t0, to: Instant? = null, status: QualificationStatus = QualificationStatus.APPROVED, evidence: String? = "akte-1") = QualificationInput(code, from, to, evidence, status)

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    @Test
    fun gueltige_qualifikation_wird_gefunden_und_jede_aenderung_ist_eine_neue_revision() {
        val t = tenant(); val c = ctx(t); val d = driver(t)
        assertEquals(0L, d.qualificationSeq)
        val (q1, d1) = fx.staff.drivers.addQualification(c, d.id, d.revision, qual())
        assertEquals(1L, q1.revision); assertEquals(1L, q1.driverSeq); assertEquals(QualificationStatus.APPROVED, q1.status); assertTrue(q1.validAt); assertEquals(d.revision + 1, d1.revision); assertEquals(1L, d1.qualificationSeq)
        val valid = fx.ctxTx.run(c, readOnly = true) { j -> fx.staff.drivers.validQualifications(j, d.id, now) }
        assertEquals(listOf("PBEF"), valid.map { it.code }); assertEquals(1L, valid.single().revision)
        // Änderung (Gültigkeit verkürzen) = neue Revision, die alte bleibt lesbar, die Folgenummer des Fahrers steigt lückenlos
        val (q2, d2) = fx.staff.drivers.addQualification(c, d.id, d1.revision, qual(to = Instant.parse("2026-12-31T00:00:00Z")))
        assertEquals(2L, q2.revision); assertEquals(2L, q2.driverSeq); assertEquals(2L, fx.ctxTx.run(c, readOnly = true) { j -> fx.staff.drivers.qualificationRevision(j, d.id) })
        val all = fx.staff.drivers.qualifications(c, d.id, now, false)
        assertEquals(listOf(2L, 1L), all.map { it.revision }, "beide Revisionen lesbar, neueste zuerst")
        assertEquals(listOf(true, false), all.map { it.effective }); assertTrue(all[0].validAt); assertFalse(all[1].validAt)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.qualification_revision WHERE tenant_id=? AND driver_id=? AND revision=1 AND valid_to IS NULL", t.value, d.id), "Revision 1 unverändert")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.qualification.changed' AND object_ref=?", t.value, "driver:${d.id}"), "jede Änderung auditiert")
        assertEquals(d2.revision, fx.staff.drivers.get(c, d.id).revision)
    }

    @Test
    fun abgelaufene_nicht_freigegebene_und_entzogene_qualifikation_gilt_als_nicht_vorhanden() {
        val t = tenant(); val c = ctx(t); var d = driver(t)
        fun add(q: QualificationInput) = fx.staff.drivers.addQualification(c, d.id, d.revision, q).also { d = it.second }.first
        add(qual("ABGELAUFEN", from = t0, to = Instant.parse("2026-06-01T00:00:00Z")))
        add(qual("ZUKUENFTIG", from = Instant.parse("2027-01-01T00:00:00Z")))
        add(qual("WARTEND", status = QualificationStatus.PENDING))
        add(qual("ENTZOGEN")); add(qual("ENTZOGEN", status = QualificationStatus.REVOKED))
        add(qual("OK_CODE"))
        fun valid(at: Instant) = fx.ctxTx.run(c, readOnly = true) { j -> fx.staff.drivers.validQualifications(j, d.id, at) }.map { it.code }
        assertEquals(listOf("OK_CODE"), valid(now))
        assertEquals(listOf("OK_CODE", "ZUKUENFTIG"), valid(Instant.parse("2027-02-01T00:00:00Z")).sorted(), "Intervall [von, bis): ab 2027 gilt ZUKUENFTIG")
        assertEquals(setOf("ABGELAUFEN", "OK_CODE", "ENTZOGEN").minus("ENTZOGEN"), valid(Instant.parse("2026-05-31T23:59:59Z")).toSet(), "vor dem Ablauf galt ABGELAUFEN, der entzogene nie")
        assertFalse(valid(Instant.parse("2026-06-01T00:00:00Z")).contains("ABGELAUFEN"), "Ende ist exklusiv")
        // wartend: nicht wirksam, bis eine freigegebene Revision folgt; eine wartende Revision verdrängt eine freigegebene nicht
        assertFalse(fx.staff.drivers.qualifications(c, d.id, now, false).single { it.code == "WARTEND" }.effective)
        add(qual("OK_CODE", status = QualificationStatus.PENDING))
        assertTrue(valid(now).contains("OK_CODE"), "wartende Neufassung ändert die geltende Freigabe nicht")
        add(qual("WARTEND", status = QualificationStatus.APPROVED)); assertTrue(valid(now).contains("WARTEND"))
        // nur wirksame Änderungen zählen in der fortlaufenden Nummer des Fahrers: 8 Revisionen, 2 davon wartend
        assertEquals(6L, fx.staff.drivers.get(c, d.id).qualificationSeq)
    }

    @Test
    fun fremder_mandant_ergibt_404_und_nichts_wird_geschrieben() {
        val a = tenant(); val b = tenant(); val d = driver(a)
        val foreign = ctx(b)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.drivers.get(foreign, d.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.drivers.qualifications(foreign, d.id, null, false) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.drivers.addQualification(foreign, d.id, d.revision, qual()) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.drivers.update(foreign, d.id, d.revision, DriverUpdate("X", ContactChannel.APP, "x", DriverStatus.INACTIVE)) }.code)
        assertTrue(fx.ctxTx.run(foreign, readOnly = true) { j -> fx.staff.drivers.validQualifications(j, d.id, now) }.isEmpty())
        assertTrue(fx.staff.drivers.list(foreign, null, 50, null).none { it.id == d.id })
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.qualification_revision WHERE driver_id=?", d.id))
        // Mitgliedschaft aus dem fremden Mandanten ist nicht zuordenbar
        val foreignMembership = membership(b)
        val e = problem { fx.staff.drivers.create(ctx(a), DriverCreate(foreignMembership, "X", ContactChannel.APP, "x")) }
        assertTrue(e.violations.any { it.pointer == "/membership_id" })
    }

    @Test
    fun fahrer_gehoert_genau_einer_mitgliedschaft_des_mandanten() {
        val t = tenant(); val c = ctx(t)
        val m = membership(t)
        fx.staff.drivers.create(c, DriverCreate(m, "A", ContactChannel.PHONE, "1"))
        val dup = problem { fx.staff.drivers.create(c, DriverCreate(m, "B", ContactChannel.PHONE, "2")) }
        assertEquals(ErrorCode.CONFLICT, dup.code); assertTrue(dup.violations.any { it.pointer == "/membership_id" })
        assertTrue(problem { fx.staff.drivers.create(c, DriverCreate(membership(t, "dispatcher"), "C", ContactChannel.APP, "3", DriverStatus.ACTIVE)) }.violations.any { it.pointer == "/membership_id" }, "nur Mitgliedschaften mit der Rolle driver")
        assertTrue(problem { fx.staff.drivers.create(c, DriverCreate(UUID.randomUUID(), "D", ContactChannel.APP, "4", DriverStatus.ACTIVE)) }.violations.any { it.pointer == "/membership_id" })
        // endgültig: die Zuordnung lässt sich auch direkt in der Datenbank nicht ändern
        val d = fx.staff.drivers.list(c, null, 10, null).single()
        assertThrows(Exception::class.java) { fx.exec("UPDATE taktovia.driver SET membership_id=? WHERE id=?", membership(t), d.id) }
    }

    @Test
    fun hr_nahe_felder_nur_fuer_berechtigte_rollen_und_audit_ohne_klarnamen() {
        val t = tenant(); val hr = ctx(t, "staff_planning"); val dispo = ctx(t, "dispatcher")
        val d = driver(t, "Erika Geheimname"); fx.staff.drivers.addQualification(hr, d.id, d.revision, qual(evidence = "akte-4711"))
        val forHr = fx.staff.drivers.get(hr, d.id); val forDispo = fx.staff.drivers.get(dispo, d.id)
        assertEquals("Erika Geheimname", forHr.hr!!.displayName); assertEquals("+491701234567", forHr.hr!!.contactValue)
        assertNull(forDispo.hr, "Dispositionssicht ohne Klarname und Kontakt")
        assertEquals("akte-4711", fx.staff.drivers.qualifications(hr, d.id, now, false).single().evidenceRef)
        assertNull(fx.staff.drivers.qualifications(dispo, d.id, now, false).single().evidenceRef, "Nachweisverweis nur für HR")
        assertTrue(fx.staff.drivers.list(dispo, null, 10, null).all { it.hr == null })
        val audit = fx.strings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id=?", t.value).joinToString(" ")
        assertFalse(audit.contains("Geheimname") || audit.contains("+49170") || audit.contains("akte-4711"), "Audit nennt keine HR-Klartexte: $audit")
    }

    @Test
    fun aendern_mit_revision_412_und_audit() {
        val t = tenant(); val c = ctx(t); val d = driver(t)
        val u = fx.staff.drivers.update(c, d.id, d.revision, DriverUpdate("Neu", ContactChannel.EMAIL, "e@example.org", DriverStatus.INACTIVE))
        assertEquals(d.revision + 1, u.revision); assertEquals(DriverStatus.INACTIVE, u.status)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.staff.drivers.update(c, d.id, d.revision, DriverUpdate("Alt", ContactChannel.APP, "x", DriverStatus.ACTIVE)) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.staff.drivers.addQualification(c, d.id, d.revision, qual()) }.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.driver.changed' AND object_ref=?", t.id(), "driver:${d.id}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.driver.created' AND object_ref=?", t.id(), "driver:${d.id}"))
    }

    private fun TenantId.id() = this.value

    @Test
    fun gleichzeitige_aenderungen_vergeben_revisionen_lueckenlos() {
        val t = tenant(); val c = ctx(t); val d = driver(t)
        // 4 parallele Versuche mit derselben Revision: genau einer gewinnt
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        try {
            val f = (0 until 4).map { pool.submit<Result<Long>> { start.await(); runCatching { fx.staff.drivers.addQualification(c, d.id, d.revision, qual("PBEF")).first.revision } } }
            start.countDown()
            val r = f.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, r.count { it.isSuccess }, r.toString())
            r.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
        } finally { pool.shutdownNow() }
        // mehrere Qualifikationen nacheinander lesen, dann parallel mit frisch gelesener Revision und Wiederholung: Nummern ohne Lücke und Duplikat
        val pool2 = Executors.newFixedThreadPool(5)
        try {
            val ok = java.util.concurrent.atomic.AtomicInteger()
            val tasks = (0 until 5).map { i -> pool2.submit { var tries = 0; while (tries++ < 40) { val cur = fx.staff.drivers.get(c, d.id); val r = runCatching { fx.staff.drivers.addQualification(c, d.id, cur.revision, qual("Q$i")) }; if (r.isSuccess) { ok.incrementAndGet(); break } } } }
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
            assertEquals(5, ok.get())
        } finally { pool2.shutdownNow() }
        val seqs = fx.strings("SELECT driver_seq::text FROM taktovia.qualification_revision WHERE tenant_id=? AND driver_id=? AND driver_seq IS NOT NULL ORDER BY driver_seq", t.value, d.id)
        assertEquals((1..6).map { it.toString() }, seqs, "Folgenummern 1..6 ohne Lücke und Duplikat")
        assertEquals(6L, fx.staff.drivers.get(c, d.id).qualificationSeq)
    }

    @Test
    fun feldfehler_mit_pointer() {
        val e = problem { DriverCreate.parse(mapOf("display_name" to "", "contact_channel" to "FAX", "contact_value" to "x", "tenant_id" to UUID.randomUUID().toString())) }
        val pointers = e.violations.map { it.pointer }
        assertTrue(pointers.containsAll(listOf("/membership_id", "/display_name", "/contact_channel", "/tenant_id")), pointers.toString())
        val q = problem { QualificationInput.parse(mapOf("qualification_code" to "kleinbuchstaben", "valid_from" to "2026-02-01T00:00:00Z", "valid_to" to "2026-01-01T00:00:00Z", "decision" to "VIELLEICHT")) }
        assertTrue(q.violations.map { it.pointer }.containsAll(listOf("/qualification_code", "/valid_to", "/decision")), q.violations.toString())
    }
}
