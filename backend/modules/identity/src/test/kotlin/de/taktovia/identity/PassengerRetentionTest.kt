package de.taktovia.identity

import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.PassengerRetention
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.retention.ErasureEngine
import de.taktovia.platform.retention.JobStatus
import de.taktovia.platform.retention.RetentionAction
import de.taktovia.platform.retention.RetentionRules
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * WP-M1-33 / LH-6.18-SEC-007: Löschlauf und Archiv am echten Fahrgastkonto des Moduls (Kontakt- und Unterstützungsdaten verschlüsselt, Kanäle, Präferenzen,
 * Einwilligungsnachweis). Das Löschziel [PassengerRetention.target] läuft in der Engine der Plattform; die Plattformtests (`ErasureJobTest`) prüfen Engine und
 * Parallelität mit einem Test-Ziel.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassengerRetentionTest {
    private lateinit var f: PassengerTestFixture
    private lateinit var rules: RetentionRules
    private lateinit var engine: ErasureEngine
    @BeforeAll fun setup() {
        f = PassengerTestFixture()
        rules = RetentionRules(ContextTransactions(f.base.tx, f.base.identity.revalidator), f.base.audit)
        engine = ErasureEngine(f.base.tx, listOf(PassengerRetention.target()), f.base.audit)
    }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }

    private fun admin(t: TenantId, subject: String): RequestContext = f.base.context(t, subject, setOf(f.base.seedMembership(t, subject, "tenant_admin")), setOf("tenant_admin"))
    private fun operative(t: TenantId, subject: String): RequestContext = f.base.context(t, subject, setOf(f.base.seedMembership(t, subject, "callcenter")), setOf("callcenter"))

    private fun approved(t: TenantId, action: RetentionAction, archiveDays: Int? = null): UUID {
        val r = rules.create(admin(t, "editor-" + UUID.randomUUID()), "LK_PII", 30, action, archiveDays)
        return rules.approve(admin(t, "approver-" + UUID.randomUUID()), r.id, r.revision).id
    }

    /** Fahrgastkonto mit Kontakt (verifiziert), Unterstützungsbedarf, Benachrichtigungspräferenz und Einwilligungsnachweis; Aufbewahrungsende vor 400 Tagen. */
    private fun account(t: TenantId): Triple<UUID, String, RequestContext> {
        val (subject, ctx) = f.verifiedPassenger(t)
        val profile = f.api.profiles.get(ctx)
        f.api.profiles.update(ctx, profile.revision, ProfilePatch(notificationChannels = listOf("PUSH", "SMS")))
        f.api.profiles.replaceSupportNeeds(ctx, listOf("WHEELCHAIR_SPACE"))
        val id = profile.id
        f.base.adminExec("INSERT INTO taktovia.consent_revision(tenant_id,id,passenger_id,purpose,sequence,granted,text_version) VALUES (?,?,?,'MARKETING',1,true,'v1')", t.value, UUID.randomUUID(), id)
        f.base.adminExec("UPDATE taktovia.passenger_private SET retention_until = now() - interval '400 days' WHERE id = ?", id)
        return Triple(id, subject, ctx)
    }

    private fun run(t: TenantId, rule: UUID) = engine.run(t, rules.requestRun(admin(t, "starter-" + UUID.randomUUID()), rule).id)

    @Test fun loeschlauf_entfernt_personendaten_des_fahrgastkontos_vollstaendig() {
        val t = f.tenant(); val (id, subject, ctx) = account(t)
        val other = account(t).first.also { f.base.adminExec("UPDATE taktovia.passenger_private SET retention_until = now() + interval '100 days' WHERE id = ?", it) }   // Frist läuft noch
        assertEquals(listOf("SMS", "PUSH").sorted(), f.api.profiles.get(ctx).notificationChannels.sorted())
        val job = run(t, approved(t, RetentionAction.ANONYMIZE))
        assertEquals(JobStatus.COMPLETED, job.status); assertEquals(1, job.processedCount)
        // keine SQL-Abfrage liefert Personendaten: Ciphertexts, IdP-Bezug, Pseudonym, Kanäle, Aufgaben, Präferenzen
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NOT NULL AND contact_ciphertext IS NULL AND support_ciphertext IS NULL AND idp_subject IS NULL AND idp_issuer IS NULL AND subject_token = ?", id, "erased:$id"))
        for (table in listOf("passenger_contact", "contact_verification", "passenger_preference")) assertEquals(0, f.count("SELECT count(*) FROM taktovia.$table WHERE passenger_id = ?", id), table)
        // aufbewahrungspflichtiger Bezug (Einwilligungsnachweis) bleibt, ohne Personenbezug
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.consent_revision WHERE passenger_id = ?", id))
        // keine API liefert die Person: Profil, Unterstützungsbedarf und Registrierung sind weg
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { f.api.profiles.get(f.ctx(t, subject)) }.code)
        // Datensatz mit laufender Frist unberührt
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NULL AND contact_ciphertext IS NOT NULL", other))
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id = ?", other))
        // Löschprotokoll ohne Personendaten
        assertEquals(listOf("passenger:$id"), f.base.adminStrings("SELECT object_ref FROM taktovia.erasure_journal WHERE tenant_id = ?", t.value))
        assertEquals(0, f.count("SELECT count(*) FROM taktovia.erasure_journal WHERE tenant_id = ? AND (object_ref LIKE '%@%' OR object_ref LIKE '%+49%')", t.value))
    }

    @Test fun archiv_ist_fuer_operative_rollen_gesperrt_und_fuer_die_berechtigte_rolle_bis_fristende_abrufbar() {
        val t = f.tenant(); val (id, subject, _) = account(t)
        val archive = PassengerRetention.archive(ContextTransactions(f.base.tx, f.base.identity.revalidator), f.base.audit)
        val adminCtx = admin(t, "archivist"); val callcenter = operative(t, "agent")
        // vor der Archivierung ist das Konto kein Archivdatensatz
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { archive.read(adminCtx, id) }.code)
        run(t, approved(t, RetentionAction.ARCHIVE, archiveDays = 20))
        // operative Rolle (ohne Scope retention:read): verweigert; operative Abfragen finden das Konto nicht mehr
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { archive.read(callcenter, id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { f.api.profiles.get(f.ctx(t, subject)) }.code)
        // berechtigte Rolle: abrufbar, Kontaktkanäle ohne Werte, Zugriff auditiert
        val rec = archive.read(adminCtx, id)
        assertEquals(setOf(ContactChannel.EMAIL), rec.contactChannels)
        assertTrue(rec.archiveUntil.isAfter(java.time.Instant.now().plusSeconds(19L * 86400)))
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'retention.archive.read' AND object_ref = ?", t.value, "passenger:$id"))
        // anderer Mandant: nicht auffindbar
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { archive.read(admin(f.tenant(), "archivist-b"), id) }.code)
        // nach Fristende nicht mehr abrufbar, der nächste Lauf anonymisiert
        f.base.adminExec("UPDATE taktovia.passenger_private SET archive_until = now() - interval '1 minute' WHERE id = ?", id)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { archive.read(adminCtx, id) }.code)
        val second = engine.run(t, rules.requestRun(adminCtx, f.base.adminStrings("SELECT id::text FROM taktovia.retention_rule WHERE tenant_id = ?", t.value).single().let(UUID::fromString)).id)
        assertEquals(1, second.processedCount)
        assertEquals(1, f.count("SELECT count(*) FROM taktovia.passenger_private WHERE id = ? AND erased_at IS NOT NULL AND contact_ciphertext IS NULL", id))
        assertEquals(0, f.count("SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id = ?", id))
    }
}
