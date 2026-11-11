package de.taktovia.config

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.PublishCommand
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.RevisionValidator
import de.taktovia.config.api.RollbackCommand
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.UpdateDraft
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fachliche Prüfregel als Testdouble: lehnt `max_wait_min = 99` ab, solange der Mandant in [rejecting] steht (zeigt: Regeln laufen bei Validierung und noch einmal bei Publikation). */
class ToggleRevisionRule : RevisionValidator {
    override val name = "test-regel"
    val rejecting: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    override fun check(j: JdbcOperations, ctx: RequestContext, revision: ConfigRevisionView): List<Violation> =
        if (ctx.tenant.value in rejecting && revision.values["max_wait_min"]?.toString() == "99") listOf(Violation("/values/max_wait_min", ViolationCode.INVALID_VALUE, "Wert vom Modul abgelehnt")) else emptyList()
}

/**
 * WP-M1-22 / LH-I.7-CFG-006: Validierung, Vier-Augen-Freigabe, Publikation und Rollback gegen die echte Datenbank unter der Runtime-Rolle, mit echten
 * parallelen Transaktionen. Die Nachweise „Sonntagsfahrten unverändert“ und „Buchungszusage bleibt korrekt“ werden in M1 an aufgelösten Werten und einem
 * Testobjekt mit gebundener Revisionsreferenz geführt; an echten Angeboten und Buchungen folgen sie mit M2 (WP-M2-04, -05, -09, -21, -23).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigPublicationTest {
    private lateinit var fx: ConfigFixture
    private lateinit var api: ConfigApi
    private val rule = ToggleRevisionRule()

    @BeforeAll fun up() {
        fx = ConfigFixture(poolSize = 24)
        api = ConfigApi.create(fx.ctxTx, fx.audit, fx.outbox, revisionValidators = listOf(rule))
        // Testobjekt mit gebundener Revisionsreferenz (Ersatz für fare_quote/booking aus M2): zusammengesetzter Fremdschlüssel auf config_revision, RLS wie alle Mandantentabellen.
        fx.adminUpdate("CREATE TABLE IF NOT EXISTS public.config_bound_probe(tenant_id uuid NOT NULL, id uuid NOT NULL, config_revision_id uuid NOT NULL, PRIMARY KEY(tenant_id,id), " +
            "FOREIGN KEY(tenant_id, config_revision_id) REFERENCES taktovia.config_revision(tenant_id, id))")
        fx.adminUpdate("ALTER TABLE public.config_bound_probe ENABLE ROW LEVEL SECURITY")
        fx.adminUpdate("ALTER TABLE public.config_bound_probe FORCE ROW LEVEL SECURITY")
        fx.adminUpdate("DROP POLICY IF EXISTS tenant_isolation ON public.config_bound_probe")
        fx.adminUpdate("CREATE POLICY tenant_isolation ON public.config_bound_probe USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())")
        fx.adminUpdate("GRANT SELECT, INSERT ON public.config_bound_probe TO taktovia_runtime")
    }
    @AfterAll fun down() = fx.close()

    private class Team(val tenant: TenantId, val alice: RequestContext, val bob: RequestContext)
    private fun team(): Team { val t = fx.tenant(); return Team(t, fx.context(t, "alice"), fx.context(t, "bob")) }
    private fun now() = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private fun problem(block: () -> Any?): ProblemException = assertThrows(ProblemException::class.java) { block() }

    private fun draft(t: Team, values: Map<String, Any?>, from: Instant, to: Instant? = null, scopeId: UUID = t.tenant.value, ctx: RequestContext = t.alice, reason: String = "Anpassung") =
        api.revisions.createDraft(ctx, CreateDraft(ScopeKind.TENANT, scopeId, "1", values, from, to, reason))

    private fun rv(t: Team, id: UUID) = api.revisions.get(t.alice, id).rowVersion

    /** Entwurf → Wirkungsanalyse → validieren (Alice) → freigeben (Bob); liefert Bericht-ID und Zeilenversion der freigegebenen Revision. */
    private fun ready(t: Team, d: ConfigRevisionView): Pair<UUID, Long> {
        val report = api.impact.analyze(t.alice, d.id, rv(t, d.id))
        val validated = api.publication.validate(t.alice, d.id, rv(t, d.id))
        assertTrue(validated.valid, validated.violations.toString())
        val approved = api.publication.approve(t.bob, d.id, validated.revision.rowVersion)
        assertEquals(RevisionState.APPROVED, approved.state)
        return report.id to approved.rowVersion
    }

    private fun publish(t: Team, d: ConfigRevisionView, report: UUID?, rowVersion: Long, ctx: RequestContext = t.bob, from: Instant = d.effectiveFrom, reason: String? = "Veröffentlichen") =
        api.publication.publish(ctx, d.id, rowVersion, PublishCommand(from, reason, report))

    private fun draftToPublished(t: Team, values: Map<String, Any?>, from: Instant, to: Instant? = null): ConfigRevisionView {
        val d = draft(t, values, from, to); val (rep, v) = ready(t, d); return publish(t, d, rep, v).revision
    }

    private fun valueAt(t: Team, parameter: String, at: Instant): String? =
        api.effective.resolve(t.alice, EffectiveRequest(parameter = parameter, at = at)).items.single().value?.toString()

    private fun audits(t: Team, id: UUID): List<String> =
        fx.adminStrings("SELECT action FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? ORDER BY occurred_at, id", t.tenant.value, "config_revision:$id")

    // ---- Positiv ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aenderung_wirkt_erst_nach_validierung_freigabe_und_publikation() {   // AK 1, 3 (CFG-006)
        val t = team(); val t0 = now()
        val base = draftToPublished(t, mapOf("max_wait_min" to 15, "min_booking_lead_min" to 60), t0)
        assertEquals(RevisionState.PUBLISHED, base.state)
        val monday = t0.plus(3, ChronoUnit.DAYS)
        assertEquals("15", valueAt(t, "max_wait_min", monday))

        val d = draft(t, mapOf("max_wait_min" to 25, "min_booking_lead_min" to 120), t0.plus(2, ChronoUnit.DAYS))
        // vor Validierung: nicht publizierbar und nicht wirksam
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { publish(t, d, null, rv(t, d.id)) }.code)
        assertEquals("15", valueAt(t, "max_wait_min", monday), "Entwurf ist nicht wirksam")
        val report = api.impact.analyze(t.alice, d.id, rv(t, d.id))
        val validated = api.publication.validate(t.alice, d.id, rv(t, d.id))
        assertTrue(validated.valid); assertEquals(RevisionState.VALIDATED, validated.revision.state); assertEquals("user:alice", validated.revision.validatedBy)
        // validiert, aber noch nicht von einer zweiten Person freigegeben: weiter nicht publizierbar
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { publish(t, d, report.id, validated.revision.rowVersion) }.code)
        val approved = api.publication.approve(t.bob, d.id, validated.revision.rowVersion)
        assertEquals("user:bob", approved.approvedBy); assertNotNull(approved.approvedAt)
        assertEquals("15", valueAt(t, "max_wait_min", monday), "freigegeben, aber noch nicht publiziert: nicht wirksam")

        val result = publish(t, d, report.id, approved.rowVersion)
        assertEquals(RevisionState.PUBLISHED, result.revision.state); assertEquals(base.id, result.closedPredecessorId)
        assertEquals("user:bob", result.revision.publishedBy)
        // Sonntag (vor effective_from) liefert weiter die alten Werte, ab Montag die neuen
        val sunday = t0.plus(1, ChronoUnit.DAYS)
        assertEquals("60", valueAt(t, "min_booking_lead_min", sunday)); assertEquals("15", valueAt(t, "max_wait_min", sunday))
        assertEquals("120", valueAt(t, "min_booking_lead_min", monday)); assertEquals("25", valueAt(t, "max_wait_min", monday))
        // Vorgänger ist abgelöst: Ende = Beginn des Nachfolgers; seine Werte bleiben unverändert lesbar
        val old = api.revisions.get(t.alice, base.id)
        assertEquals(d.effectiveFrom, old.effectiveTo); assertEquals(mapOf("max_wait_min" to 15, "min_booking_lead_min" to 60), old.values)
        // Audit: je Schritt ein Eintrag mit Akteur; die Publikation nennt die audit_event_id
        assertEquals(listOf("config.revision.created", "config.revision.validated", "config.revision.approved", "config.revision.published"), audits(t, d.id).filter { !it.startsWith("config.impact") })
        assertEquals("config.revision.published", fx.adminStrings("SELECT action FROM taktovia.audit_event WHERE tenant_id=? AND id=?", t.tenant.value, result.auditEventId).single())
        assertEquals("user:bob", fx.adminStrings("SELECT actor_ref FROM taktovia.audit_event WHERE tenant_id=? AND id=?", t.tenant.value, result.auditEventId).single())
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='config.revision_published'", t.tenant.value, d.id))
    }

    @Test
    fun auflosung_genau_zum_zeitpunkt_effective_from_liefert_trotz_cache_nie_den_abgeloesten_wert() {
        val t = team(); val t0 = now()
        draftToPublished(t, mapOf("max_wait_min" to 15), t0)
        val boundary = t0.plus(2, ChronoUnit.DAYS)
        // Cache aufwärmen: Auflösung zu verschiedenen Zeitpunkten
        repeat(3) { assertEquals("15", valueAt(t, "max_wait_min", boundary)); assertEquals("15", valueAt(t, "max_wait_min", boundary.minusMillis(1))) }
        draftToPublished(t, mapOf("max_wait_min" to 30), boundary)
        assertEquals("15", valueAt(t, "max_wait_min", boundary.minusMillis(1)), "eine Millisekunde vorher gilt der alte Wert")
        assertEquals("30", valueAt(t, "max_wait_min", boundary), "genau zu effective_from gilt der neue Wert, nicht der abgelöste aus dem Cache")
        assertEquals("30", valueAt(t, "max_wait_min", boundary.plusMillis(1)))
    }

    // ---- Negativ ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun selbstfreigabe_ist_ausgeschlossen_auch_fuer_dienstkonten() {
        val t = team(); val d = draft(t, mapOf("max_wait_min" to 20), now().plus(1, ChronoUnit.DAYS))
        val v = api.publication.validate(t.alice, d.id, rv(t, d.id))
        assertEquals(ErrorCode.FORBIDDEN, problem { api.publication.approve(t.alice, d.id, v.revision.rowVersion) }.code, "Ersteller kann nicht selbst freigeben")
        val bot = RequestContext(Actor(ActorType.SERVICE, "https://idp.test", "bot", null, false), t.tenant, setOf("tenant_admin"), setOf("config:read", "config:write", "config:publish"), emptySet(), UUID.randomUUID())
        assertEquals(ErrorCode.FORBIDDEN, problem { api.publication.approve(bot, d.id, v.revision.rowVersion) }.code, "ein Dienstkonto ist nie die zweite Person")
        assertEquals(RevisionState.VALIDATED, api.revisions.get(t.alice, d.id).state)
        assertNull(api.revisions.get(t.alice, d.id).approvedBy)
        // die Datenbank erzwingt es zusätzlich: Freigeber = Ersteller ist an der Anwendung vorbei ausgeschlossen
        val e = assertThrows(Exception::class.java) { fx.tx.run(t.tenant) { j -> j.update("UPDATE taktovia.config_revision SET state='APPROVED', approved_by='user:alice' WHERE id=?", d.id) } }
        assertTrue(e.message.orEmpty().contains("four_eyes") || e.cause?.message.orEmpty().contains("four_eyes"), e.message)
    }

    @Test
    fun publikation_ohne_validierung_ohne_freigabe_ohne_bericht_ohne_recht() {
        val t = team(); val from = now().plus(1, ChronoUnit.DAYS)
        val d = draft(t, mapOf("max_wait_min" to 20), from)
        val report = api.impact.analyze(t.alice, d.id, rv(t, d.id))
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { publish(t, d, report.id, rv(t, d.id)) }.code, "ohne Validierung")
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.publication.approve(t.bob, d.id, rv(t, d.id)) }.code, "Freigabe ohne Validierung")
        val v = api.publication.validate(t.alice, d.id, rv(t, d.id))
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { publish(t, d, report.id, v.revision.rowVersion) }.code, "ohne Freigabe")
        val approved = api.publication.approve(t.bob, d.id, v.revision.rowVersion)
        // fehlender Wirkungsbericht
        val noReport = problem { publish(t, d, null, approved.rowVersion) }
        assertEquals(ErrorCode.VALIDATION_FAILED, noReport.code); assertEquals("/impact_report_id", noReport.violations.single().pointer)
        // fehlendes Recht config:publish
        val readerWriter = fx.context(t.tenant, "carol", scopes = setOf("config:read", "config:write"))
        assertEquals(ErrorCode.FORBIDDEN, problem { publish(t, d, report.id, approved.rowVersion, ctx = readerWriter) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { api.publication.approve(readerWriter, d.id, approved.rowVersion) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { api.publication.rollback(readerWriter, d.id, 1, RollbackCommand(from, "Grund")) }.code)
        val readOnly = fx.context(t.tenant, "dave", scopes = setOf("config:read"))
        assertEquals(ErrorCode.FORBIDDEN, problem { api.publication.validate(readOnly, d.id, approved.rowVersion) }.code, "validieren verlangt config:write")
        assertEquals(RevisionState.APPROVED, api.revisions.get(t.alice, d.id).state, "nichts davon hat publiziert")
        // fehlender Grund
        assertEquals("/reason", problem { publish(t, d, report.id, approved.rowVersion, reason = " ") }.violations.single().pointer)
        // Gegenprobe
        assertEquals(RevisionState.PUBLISHED, publish(t, d, report.id, approved.rowVersion).revision.state)
    }

    @Test
    fun ungueltiger_wert_und_modellregel_blockieren_validierung_und_publikation() {
        val t = team(); rule.rejecting += t.tenant.value
        val from = now().plus(1, ChronoUnit.DAYS)
        val bad = draft(t, mapOf("max_wait_min" to 99), from)
        val v = api.publication.validate(t.alice, bad.id, rv(t, bad.id))
        assertEquals(false, v.valid); assertEquals("/values/max_wait_min", v.violations.single().pointer)
        assertEquals(RevisionState.DRAFT, api.revisions.get(t.alice, bad.id).state, "bleibt Entwurf")
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.publication.approve(t.bob, bad.id, rv(t, bad.id)) }.code)
        assertEquals(1, audits(t, bad.id).count { it == "config.revision.validated" }, "auch die fehlgeschlagene Prüfung ist im Audit")
        // Die Regel kann sich zwischen Validierung und Publikation ändern: auch die Publikation prüft noch einmal
        rule.rejecting -= t.tenant.value
        val ok = draft(t, mapOf("max_wait_min" to 99), from.plusSeconds(60))
        val (rep, rvn) = ready(t, ok)
        rule.rejecting += t.tenant.value
        val e = problem { publish(t, ok, rep, rvn) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals("/values/max_wait_min", e.violations.single().pointer)
        assertEquals(RevisionState.APPROVED, api.revisions.get(t.alice, ok.id).state)
        // unzulässiger Sicherheitswert: schon der Entwurf wird abgewiesen (Systemgrenze/Sicherheitsinvariante), es entsteht keine Revision
        val unsafe = problem { draft(t, mapOf("vehicle_readiness_require_checks" to false), from) }
        assertEquals(ErrorCode.VALIDATION_FAILED, unsafe.code)
        assertEquals("SAFETY_INVARIANT_VIOLATED", unsafe.violations.single().code.name)
        rule.rejecting -= t.tenant.value
    }

    @Test
    fun wirksamkeitsbeginn_nicht_rueckwirkend_und_wie_freigegeben() {
        val t = team()
        val past = draft(t, mapOf("max_wait_min" to 20), now().minus(1, ChronoUnit.HOURS))
        val (rep, v) = ready(t, past)
        val e = problem { publish(t, past, rep, v) }
        assertEquals("/effective_from", e.violations.single().pointer); assertEquals(RevisionState.APPROVED, api.revisions.get(t.alice, past.id).state)
        val future = draft(t, mapOf("max_wait_min" to 21), now().plus(1, ChronoUnit.DAYS))
        val (rep2, v2) = ready(t, future)
        assertEquals("/effective_from", problem { publish(t, future, rep2, v2, from = future.effectiveFrom.plusSeconds(1)) }.violations.single().pointer, "Datum weicht von der Freigabe ab")
        publish(t, future, rep2, v2)
    }

    @Test
    fun veraltete_zeilenversion_fremder_mandant_und_unbekannte_revision() {
        val t = team(); val d = draft(t, mapOf("max_wait_min" to 20), now().plus(1, ChronoUnit.DAYS))
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { api.publication.validate(t.alice, d.id, 99) }.code)
        val v = api.publication.validate(t.alice, d.id, 1)
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { api.publication.validate(t.alice, d.id, 1) }.code, "zweite Validierung mit altem Stand")
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { api.publication.approve(t.bob, d.id, 1) }.code)
        val other = team()
        assertEquals(ErrorCode.NOT_FOUND, problem { api.publication.validate(other.alice, d.id, v.revision.rowVersion) }.code, "Revision eines anderen Mandanten wie nicht vorhanden")
        assertEquals(ErrorCode.NOT_FOUND, problem { api.publication.approve(other.bob, d.id, v.revision.rowVersion) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { publish(other, d, UUID.randomUUID(), 1) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { api.publication.rollback(other.alice, d.id, 1, RollbackCommand(now(), "Grund")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { api.publication.validate(t.alice, UUID.randomUUID(), 1) }.code)
    }

    @Test
    fun publizierte_revision_bleibt_unveraenderlich_und_erneute_publikation_wird_abgewiesen() {
        val t = team(); val p = draftToPublished(t, mapOf("max_wait_min" to 20), now())
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { publish(t, p, UUID.randomUUID(), api.revisions.get(t.alice, p.id).rowVersion) }.code)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { api.publication.validate(t.alice, p.id, api.revisions.get(t.alice, p.id).rowVersion) }.code)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { api.revisions.updateDraft(t.alice, p.id, api.revisions.get(t.alice, p.id).rowVersion, UpdateDraft(reason = "Heimlich")) }.code)
    }

    // ---- Rollback ---------------------------------------------------------------------------------------------------------------------------

    @Test
    fun rollback_erzeugt_neue_revision_mit_frueheren_werten_und_aendert_nichts_an_gebundenen_zusagen() {   // AK 5
        val t = team(); val t0 = now()
        val good = draftToPublished(t, mapOf("max_wait_min" to 20), t0)
        val faulty = draftToPublished(t, mapOf("max_wait_min" to 200), t0.plus(1, ChronoUnit.HOURS))
        // Testobjekt mit gebundener Revisionsreferenz (Zusage auf die fehlerhafte Revision)
        val bound = UUID.randomUUID()
        fx.tx.run(t.tenant) { j -> j.update("INSERT INTO public.config_bound_probe(tenant_id,id,config_revision_id) VALUES (taktovia.current_tenant(),?,?)", bound, faulty.id) }
        fun boundValues() = fx.tx.run(t.tenant, readOnly = true) { j ->
            val id = j.queryForObject("SELECT config_revision_id FROM public.config_bound_probe WHERE id=?", UUID::class.java, bound)!!
            api.revisions.get(t.alice, id)
        }
        assertEquals("200", boundValues().values["max_wait_min"].toString())

        val noTarget = problem { api.publication.rollback(t.alice, good.id, api.revisions.get(t.alice, good.id).rowVersion, RollbackCommand(t0.plus(2, ChronoUnit.DAYS), "Rollback")) }
        assertEquals(ErrorCode.CONFLICT, noTarget.code, "die erste Revision hat keine frühere")
        val d = api.revisions.get(t.alice, faulty.id)
        val from = t0.plus(2, ChronoUnit.HOURS)
        val rolled = api.publication.rollback(t.bob, faulty.id, d.rowVersion, RollbackCommand(from, "Fehlerhafter Wert 200 zurückgenommen"))
        assertEquals(RevisionState.DRAFT, rolled.state); assertEquals(faulty.id, rolled.rolledBackFrom)
        assertEquals(mapOf("max_wait_min" to 20), rolled.values); assertEquals(faulty.revision + 1, rolled.revision)
        assertEquals("user:bob", rolled.createdBy)
        // die Rücknahme geht denselben Weg: nichts wirkt vor Publikation, die zweite Person ist Alice (Ersteller der Rücknahme ist Bob)
        assertEquals("200", valueAt(t, "max_wait_min", from.plusSeconds(1)))
        val report = api.impact.analyze(t.bob, rolled.id, rolled.rowVersion)
        val v = api.publication.validate(t.bob, rolled.id, rolled.rowVersion)
        val approved = api.publication.approve(t.alice, rolled.id, v.revision.rowVersion)
        val result = api.publication.publish(t.alice, rolled.id, approved.rowVersion, PublishCommand(from, "Rücknahme veröffentlichen", report.id))
        assertEquals(faulty.id, result.closedPredecessorId)
        assertEquals("20", valueAt(t, "max_wait_min", from.plusSeconds(1)), "wieder die früheren Werte")
        assertEquals("200", valueAt(t, "max_wait_min", from.minusSeconds(1)), "davor galt die fehlerhafte Revision")
        // die fehlerhafte Revision bleibt lesbar und unverändert; das gebundene Testobjekt zeigt weiter seine ursprüngliche Version
        assertEquals("200", boundValues().values["max_wait_min"].toString()); assertEquals(RevisionState.PUBLISHED, boundValues().state)
        // Audit: Rücknahme und Publikation; Ereignisse
        assertEquals(listOf("config.revision.reverted", "config.revision.validated", "config.revision.approved", "config.revision.published"), audits(t, rolled.id).filter { !it.startsWith("config.impact") })
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='config.revision_rolled_back'", t.tenant.value, rolled.id))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='config.revision_published'", t.tenant.value, rolled.id))
        // Rollback einer nicht publizierten Revision und mit fremder Zielrevision
        val draftOnly = draft(t, mapOf("max_wait_min" to 33), t0.plus(5, ChronoUnit.DAYS))
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { api.publication.rollback(t.bob, draftOnly.id, draftOnly.rowVersion, RollbackCommand(from, "Grund")) }.code)
        val foreign = problem { api.publication.rollback(t.bob, faulty.id, api.revisions.get(t.alice, faulty.id).rowVersion, RollbackCommand(from, "Grund", UUID.randomUUID())) }
        assertEquals("/target_revision_id", foreign.violations.single().pointer)
    }

    // ---- Konkurrierend ----------------------------------------------------------------------------------------------------------------------

    private fun <T> parallel(vararg jobs: () -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(jobs.size); val start = CountDownLatch(1)
        val futures = jobs.map { job -> pool.submit<Result<T>> { start.await(); runCatching(job) } }
        start.countDown()
        val out = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown(); return out
    }

    private fun published(t: Team) = fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_id=? AND state='PUBLISHED'", t.tenant.value, t.tenant.value)

    @Test
    fun zwei_publikationen_desselben_scopes_mit_ueberlappendem_intervall_genau_eine_erfolgreich() {   // Pflicht-Negativtest zu §4.5
        val t = team(); val t0 = now()
        // feste Enden, die sich überlappen
        val r1 = draft(t, mapOf("max_wait_min" to 10), t0.plus(1, ChronoUnit.DAYS), t0.plus(10, ChronoUnit.DAYS))
        val r2 = draft(t, mapOf("max_wait_min" to 12), t0.plus(5, ChronoUnit.DAYS), t0.plus(8, ChronoUnit.DAYS))
        val (rep1, v1) = ready(t, r1); val (rep2, v2) = ready(t, r2)
        val results = parallel({ publish(t, r1, rep1, v1) }, { publish(t, r2, rep2, v2) })
        assertEquals(1, results.count { it.isSuccess }, results.toString())
        val failure = results.single { it.isFailure }.exceptionOrNull() as ProblemException
        assertEquals(ErrorCode.CONFLICT, failure.code)
        assertEquals(1, published(t), "genau eine publizierte Revision")
        // identischer offener Beginn
        val t2 = team()
        val a = draft(t2, mapOf("max_wait_min" to 10), t0.plus(1, ChronoUnit.DAYS)); val b = draft(t2, mapOf("max_wait_min" to 12), t0.plus(1, ChronoUnit.DAYS))
        val (ra, va) = ready(t2, a); val (rb, vb) = ready(t2, b)
        val res2 = parallel({ publish(t2, a, ra, va) }, { publish(t2, b, rb, vb) })
        assertEquals(1, res2.count { it.isSuccess }, res2.toString()); assertEquals(ErrorCode.CONFLICT, (res2.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        assertEquals(1, published(t2))
        // sequentiell hintereinander (offener Vorgänger wird geschlossen) ist dagegen zulässig
        val t3 = team()
        draftToPublished(t3, mapOf("max_wait_min" to 10), t0.plus(1, ChronoUnit.DAYS)); draftToPublished(t3, mapOf("max_wait_min" to 12), t0.plus(2, ChronoUnit.DAYS))
        assertEquals(2, published(t3))
    }

    @Test
    fun paralleler_publish_und_rollback_bleiben_konsistent() {
        val t = team(); val t0 = now()
        draftToPublished(t, mapOf("max_wait_min" to 20), t0)
        val faulty = draftToPublished(t, mapOf("max_wait_min" to 200), t0.plus(1, ChronoUnit.HOURS))
        val from = t0.plus(3, ChronoUnit.HOURS)
        val rolled = api.publication.rollback(t.bob, faulty.id, api.revisions.get(t.alice, faulty.id).rowVersion, RollbackCommand(from, "Rücknahme"))
        val rep = api.impact.analyze(t.bob, rolled.id, rolled.rowVersion); val v = api.publication.validate(t.bob, rolled.id, rolled.rowVersion)
        val approved = api.publication.approve(t.alice, rolled.id, v.revision.rowVersion)
        val other = draft(t, mapOf("max_wait_min" to 40), from); val (repO, vO) = ready(t, other)
        val results = parallel(
            { api.publication.publish(t.alice, rolled.id, approved.rowVersion, PublishCommand(from, "Rücknahme", rep.id)) },     // Publikation der Rücknahme
            { publish(t, other, repO, vO) },                                                                                      // gleichzeitig eine andere Revision mit gleichem Beginn
            { api.publication.rollback(t.bob, faulty.id, api.revisions.get(t.alice, faulty.id).rowVersion, RollbackCommand(from, "Zweite Rücknahme")) },
        )
        val publications = results.take(2)
        assertEquals(1, publications.count { it.isSuccess }, results.toString())
        assertEquals(ErrorCode.CONFLICT, (publications.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        // die zweite Rücknahme: entweder angelegt, oder 412, wenn die fehlerhafte Revision zwischenzeitlich geschlossen wurde (Zeilenversion geändert); nie ein Fehler anderer Art
        results[2].exceptionOrNull()?.let { assertEquals(ErrorCode.REVISION_MISMATCH, (it as ProblemException).code) }
        // Konsistenz: Revisionsnummern eindeutig, keine überlappenden publizierten Intervalle, höchstens eine wirksame Revision zu jedem Zeitpunkt
        assertEquals(0, fx.adminCount("SELECT count(*) FROM (SELECT revision FROM taktovia.config_revision WHERE tenant_id=? AND scope_id=? GROUP BY revision HAVING count(*)>1) x", t.tenant.value, t.tenant.value))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_id=? AND state='PUBLISHED' AND effective_to IS NULL", t.tenant.value, t.tenant.value))
    }

    @Test
    fun gleichzeitige_publikation_in_a_und_b_mit_identischer_scope_id_ohne_gegenseitige_wirkung() {
        val a = team(); val b = team(); val t0 = now()
        // gleiche scope_id in beiden Mandanten (REGION ohne Fremdschlüssel): die Scope-Sperre enthält den Mandanten, die Sperren stören sich nicht
        val shared = UUID.randomUUID()
        fun regionDraft(t: Team) = api.revisions.createDraft(t.alice, CreateDraft(ScopeKind.REGION, shared, "1", mapOf("max_wait_min" to 20), t0.plus(1, ChronoUnit.DAYS), null, "Region"))
        val da = regionDraft(a); val db = regionDraft(b)
        val (ra, va) = ready(a, da); val (rb, vb) = ready(b, db)
        val res = parallel({ publish(a, da, ra, va) }, { publish(b, db, rb, vb) })
        assertTrue(res.all { it.isSuccess }, res.toString())
        // Mandant B bleibt von A unberührt: B sieht nur die eigene Revision
        assertEquals(listOf(db.id), api.revisions.list(b.alice, de.taktovia.config.api.RevisionFilter(scopeId = shared), 10, null).map { it.id })
    }

    @Test
    fun parallele_validierung_und_freigabe_derselben_revision_laufen_nacheinander() {
        val t = team(); val d = draft(t, mapOf("max_wait_min" to 20), now().plus(1, ChronoUnit.DAYS))
        val res = parallel({ api.publication.validate(t.alice, d.id, 1) }, { api.publication.validate(t.alice, d.id, 1) }, { api.publication.validate(t.alice, d.id, 1) })
        assertEquals(1, res.count { it.isSuccess }, res.toString())
        res.filter { it.isFailure }.forEach { assertTrue((it.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.REVISION_MISMATCH, ErrorCode.INVALID_STATE_TRANSITION)) }
        assertEquals(1, audits(t, d.id).count { it == "config.revision.validated" })
        assertNotEquals(0, rv(t, d.id))
    }
}
