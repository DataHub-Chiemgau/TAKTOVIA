package de.taktovia.app.policy

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.policy.ConfirmingCommand
import de.taktovia.platform.policy.PolicyCheck
import de.taktovia.platform.policy.PolicyCommand
import de.taktovia.platform.policy.PolicyDecision
import de.taktovia.platform.policy.PolicyDefinition
import de.taktovia.platform.policy.PolicyResult
import de.taktovia.platform.policy.PolicyRules
import de.taktovia.platform.policy.PolicyScope
import de.taktovia.platform.policy.ServerPolicyCheck
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Testbefehl des Ports (WP-M1-26, LH-6.1-SYS-011). Der Server bildet die **Fakten** aus seinen eigenen Daten (gespeichertes Angebot, Serverzeit); aus dem Request-Body liest er
 * nur das fachliche Eingabefeld `pickup_at`. Alles andere (eigene Wartezeit, Preis, Regelwert, Revisions-ID) bleibt wirkungslos.
 */
class TestConfirmHandler(private val policy: PolicyCheck, private val clock: Clock = Clock.systemUTC()) {
    /** Das serverseitig gespeicherte Angebot: angebotene Wartezeit und die Regelrevisionen, auf denen es beruht. */
    class Quote(val offeredWaitMin: Int, val ruleRevisions: Set<UUID>?)

    @ConfirmingCommand
    fun confirm(ctx: RequestContext, jdbc: JdbcOperations, serviceId: UUID, payload: Map<String, Any?>, quote: Quote): PolicyResult {
        val pickupAt = Instant.parse(payload["pickup_at"] as String)
        val facts = mapOf("offered_wait_min" to quote.offeredWaitMin, "lead_min" to Duration.between(clock.instant(), pickupAt).toMinutes())
        return policy.check(ctx, jdbc, PolicyCommand("confirm_ride", PolicyScope(serviceId = serviceId), facts, quote.ruleRevisions))
    }
}

class PolicyCheckTest {
    private val env = IdentityEnv
    private val beans get() = env.server.ctx
    private val config: ConfigApi get() = beans.getBean(ConfigApi::class.java)
    private val ctxTx: ContextTransactions get() = beans.getBean(ContextTransactions::class.java)
    private val definition = PolicyDefinition("confirm_ride", setOf("max_wait_min", "min_booking_lead_min"),
        listOf(PolicyRules.atMost("max_wait_min", "offered_wait_min"), PolicyRules.atLeast("min_booking_lead_min", "lead_min")))
    private val policy: PolicyCheck get() = ServerPolicyCheck(ConfigPolicyRuleSource(config), listOf(definition), beans.getBean(AuditPort::class.java))
    private val handler get() = TestConfirmHandler(policy)
    private fun idem() = mapOf("Idempotency-Key" to "pol-" + UUID.randomUUID())

    private inner class World {
        val tenant: TenantId = env.tenant(); val admin = UUID.randomUUID(); val token: String
        val ctx = RequestContext(Actor(ActorType.SERVICE, env.idp.issuer, "adapter-test", null, true), tenant, setOf("tenant_admin"), setOf("config:read", "config:write"), emptySet(), UUID.randomUUID())
        init { env.grant(tenant, admin, "tenant_admin"); token = env.token(admin) }
        fun id(path: String, body: String): String = env.post(path, body, token, extra = idem()).also { check(it.status == 201) { "$path: ${it.text}" } }.json["id"].asString()
        val service: UUID = run {
            val le = id("/v1/legal-entities", """{"name":"LE","country":"DE"}""")
            val op = id("/v1/organizations", """{"legal_entity_id":"$le","name":"B","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""")
            UUID.fromString(id("/v1/services", """{"operator_id":"$op","name":"Rufbus","timezone":"Europe/Berlin"}"""))
        }
        private val serviceConfig = mapOf("service_config" to mapOf("service_area_id" to UUID.randomUUID().toString(), "traffic_type" to "AREA_ON_DEMAND", "operator_id" to UUID.randomUUID().toString(),
            "operating_hours" to listOf(mapOf("weekdays" to listOf("MON"), "from" to "06:00", "to" to "22:00")), "tariff_id" to UUID.randomUUID().toString()))
        fun publish(kind: ScopeKind, scope: UUID, values: Map<String, Any?>, from: String): UUID {
            val all = if (kind == ScopeKind.SERVICE) values + serviceConfig else values
            val d = config.revisions.createDraft(ctx, CreateDraft(kind, scope, "1", all, Instant.parse(from), null, "Testaufbau"))
            for ((state, extra) in listOf("VALIDATED" to "", "APPROVED" to ", approved_by = 'user:zweite-person'", "PUBLISHED" to ", published_at = clock_timestamp()"))
                env.tx.run(tenant) { j ->
                    if (state == "PUBLISHED") j.update("UPDATE taktovia.config_revision p SET effective_to = n.effective_from FROM taktovia.config_revision n WHERE n.id = ? AND p.tenant_id = n.tenant_id AND p.scope_kind = n.scope_kind AND p.scope_id = n.scope_id AND p.state = 'PUBLISHED' AND p.effective_to IS NULL AND p.effective_from < n.effective_from", d.id)
                    j.update("UPDATE taktovia.config_revision SET state = ?$extra WHERE id = ?", state, d.id)
                }
            return d.id
        }
        fun confirm(payload: Map<String, Any?>, quote: TestConfirmHandler.Quote): PolicyResult = ctxTx.run(ctx) { j -> handler.confirm(ctx, j, service, payload, quote) }
        fun pickup(minutes: Long) = mapOf<String, Any?>("pickup_at" to Instant.now().plus(Duration.ofMinutes(minutes)).toString())
    }

    private fun setup(w: World): Pair<UUID, UUID> {
        val tenantRev = w.publish(ScopeKind.TENANT, w.tenant.value, mapOf("max_wait_min" to 30, "min_booking_lead_min" to 10), "2026-01-01T00:00:00Z")
        val serviceRev = w.publish(ScopeKind.SERVICE, w.service, mapOf("max_wait_min" to 15), "2026-01-01T00:00:00Z")
        return tenantRev to serviceRev
    }

    @Test
    fun regelkonformer_befehl_nennt_serverseitige_regelrevision_in_ergebnis_und_audit() {
        val w = World(); val (tenantRev, serviceRev) = setup(w)
        val result = w.confirm(w.pickup(30), TestConfirmHandler.Quote(12, null))
        assertEquals("confirm_ride", result.command); assertEquals(setOf(tenantRev, serviceRev), result.ruleRevisions.toSet())
        assertEquals("SERVICE", result.rules.getValue("max_wait_min").level); assertEquals(serviceRev, result.rules.getValue("max_wait_min").revisionId); assertEquals(15, result.rules.getValue("max_wait_min").value)
        assertEquals("TENANT", result.rules.getValue("min_booking_lead_min").level); assertEquals(tenantRev, result.rules.getValue("min_booking_lead_min").revisionId)
        // das Audit der Transaktion nennt dieselben Regelrevisionen (und nur diese)
        val audited = env.string("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'policy.command.checked'", w.tenant.value)
        assertEquals(1, audited.size)
        assertTrue(audited.single().contains(serviceRev.toString()) && audited.single().contains(tenantRev.toString()), audited.single())
        assertTrue(audited.single().contains("confirm_ride"))
    }

    @Test
    fun manipulierte_client_parameter_bleiben_wirkungslos() {
        val w = World(); val (tenantRev, serviceRev) = setup(w)
        val forged = mapOf("max_wait_min" to 999, "offered_wait_min" to 1, "lead_min" to 99999, "price_minor" to 1, "config_revision_id" to UUID.randomUUID().toString(),
            "rule_revisions" to listOf(UUID.randomUUID().toString()), "policy" to mapOf("max_wait_min" to 999))
        // Zusatzfelder ändern die Entscheidung nicht: das serverseitige Angebot (20 Minuten) verletzt die Regel (15), egal was der Client behauptet
        val rejected = assertThrows<ProblemException> { w.confirm(w.pickup(30) + forged, TestConfirmHandler.Quote(20, null)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, rejected.code)
        assertTrue(rejected.violations.any { it.pointer == "/facts/offered_wait_min" && it.code == ViolationCode.ABOVE_MAXIMUM }, rejected.violations.toString())
        assertEquals(rejected.violations, assertThrows<ProblemException> { w.confirm(w.pickup(30), TestConfirmHandler.Quote(20, null)) }.violations)
        // das Zeitfenster wird aus dem Eingabefeld und der Serverzeit gebildet: zu kurzer Vorlauf verletzt die Regel (min. 10 Minuten), ein behaupteter lead_min nicht
        val tooSoon = assertThrows<ProblemException> { w.confirm(w.pickup(2) + forged, TestConfirmHandler.Quote(12, null)) }
        assertTrue(tooSoon.violations.any { it.pointer == "/facts/lead_min" && it.code == ViolationCode.BELOW_MINIMUM }, tooSoon.violations.toString())
        // regelkonform mit Zusatzfeldern: die Revisionen stammen aus der serverseitigen Auflösung, nie aus der überschriebenen Revisions-ID
        val ok = w.confirm(w.pickup(30) + forged, TestConfirmHandler.Quote(12, null))
        assertEquals(setOf(tenantRev, serviceRev), ok.ruleRevisions.toSet())
        assertTrue(ok.ruleRevisions.none { it.toString() == forged["config_revision_id"] })
        // unbekannte Felder an einer echten Operation ergeben 422 (Eingabeprüfung der Anfrage, nicht stillschweigend übernommen)
        val r = env.post("/v1/regions", """{"name":"Nord","price_minor":1,"max_wait_min":999}""", w.token, extra = idem())
        assertEquals(422, r.status, r.text); assertTrue(r.json["violations"].values().any { it["code"].asString() == "UNKNOWN_FIELD" }, r.text)
    }

    @Test
    fun regelwechsel_zwischen_angebot_und_bestaetigung_wird_erkannt() {
        val w = World(); val (tenantRev, serviceRev) = setup(w)
        val quoted = setOf(tenantRev, serviceRev)
        assertEquals(quoted, w.confirm(w.pickup(30), TestConfirmHandler.Quote(12, quoted)).ruleRevisions.toSet())   // unverändert: bestätigt
        // eine strengere Regel wird zwischen Angebot und Bestätigung veröffentlicht: nie stillschweigend mit der alten Revision bestätigt (409 REQUOTE_REQUIRED)
        val stricter = w.publish(ScopeKind.SERVICE, w.service, mapOf("max_wait_min" to 5), "2026-02-01T00:00:00Z")
        val e = assertThrows<ProblemException> { w.confirm(w.pickup(30), TestConfirmHandler.Quote(12, quoted)) }
        assertEquals(ErrorCode.REQUOTE_REQUIRED, e.code)
        val decision = ctxTx.run(w.ctx) { j -> policy.evaluate(w.ctx, j, PolicyCommand("confirm_ride", PolicyScope(serviceId = w.service), mapOf("offered_wait_min" to 12, "lead_min" to 30L), quoted)) }
        assertTrue(decision is PolicyDecision.RequoteRequired && stricter in decision.ruleRevisions, decision.toString())
        // veraltete oder erfundene Revisionsreferenz
        assertEquals(ErrorCode.REQUOTE_REQUIRED, assertThrows<ProblemException> { w.confirm(w.pickup(30), TestConfirmHandler.Quote(3, setOf(UUID.randomUUID()))) }.code)
        // mit der neuen Revision im Angebot gilt die neue Regel (5 Minuten): 12 Minuten werden abgewiesen, 3 bestätigt
        val now = setOf(tenantRev, stricter)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { w.confirm(w.pickup(30), TestConfirmHandler.Quote(12, now)) }.code)
        assertEquals(now, w.confirm(w.pickup(30), TestConfirmHandler.Quote(3, now)).ruleRevisions.toSet())
        // ein Befehl ohne Regelsatz wird nie durchgewinkt
        assertThrows<IllegalStateException> { ctxTx.run(w.ctx) { j -> policy.check(w.ctx, j, PolicyCommand("unbekannter_befehl", PolicyScope(), emptyMap())) } }
    }

    @Test
    fun parallele_publikation_entscheidung_konsistent_zu_einer_revision() {
        val w = World(); val tenantRev = w.publish(ScopeKind.TENANT, w.tenant.value, mapOf("max_wait_min" to 30, "min_booking_lead_min" to 10), "2026-01-01T00:00:00Z")
        val loose = w.publish(ScopeKind.SERVICE, w.service, mapOf("max_wait_min" to 20), "2026-01-01T00:00:00Z")
        val pool = Executors.newFixedThreadPool(7); val stop = AtomicBoolean(false)
        // Befehle mit angebotener Wartezeit 10: bei Regel 20 bestätigt, bei der strengeren Regel 5 abgewiesen; jede Entscheidung nennt die Revision, gegen die entschieden wurde
        val readers = (1..6).map {
            pool.submit<List<PolicyDecision>> {
                val seen = mutableListOf<PolicyDecision>()
                while (!stop.get()) seen += ctxTx.run(w.ctx) { j -> policy.evaluate(w.ctx, j, PolicyCommand("confirm_ride", PolicyScope(serviceId = w.service), mapOf("offered_wait_min" to 10, "lead_min" to 30L))) }
                seen
            }
        }
        Thread.sleep(400)
        val strict = w.publish(ScopeKind.SERVICE, w.service, mapOf("max_wait_min" to 5), "2026-03-01T00:00:00Z")
        Thread.sleep(400); stop.set(true)
        val all = readers.flatMap { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        val allowed = all.filterIsInstance<PolicyDecision.Allowed>(); val rejected = all.filterIsInstance<PolicyDecision.Rejected>()
        assertTrue(allowed.isNotEmpty() && rejected.isNotEmpty(), "beide Zustände wurden beobachtet: ${allowed.size} bestätigt, ${rejected.size} abgewiesen")
        assertTrue(allowed.all { it.result.ruleRevisions.toSet() == setOf(tenantRev, loose) }, "jede Bestätigung beruht ganz auf der alten Revision")
        assertTrue(rejected.all { it.ruleRevisions.toSet() == setOf(tenantRev, strict) }, "jede Abweisung beruht ganz auf der neuen Revision")
        // keine Vermischung: nach der ersten Abweisung eines Lesers gibt es dort keine Bestätigung mehr
        readers.forEach { f -> val d = f.get(); val firstRejected = d.indexOfFirst { it is PolicyDecision.Rejected }; if (firstRejected >= 0) assertTrue(d.drop(firstRejected).none { it is PolicyDecision.Allowed }) }
        assertNotEquals(loose, strict)
    }
}
