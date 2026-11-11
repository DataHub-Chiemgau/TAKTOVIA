package de.taktovia.servicegeo

import de.taktovia.config.api.ConfigRevisionView
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.PublishCommand
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.UpdateDraft
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.CreateService
import de.taktovia.servicegeo.api.OfferModel
import de.taktovia.servicegeo.api.OfferModelRules
import de.taktovia.servicegeo.api.ServiceView
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publizierte Fixture-Konfiguration je Angebotsmodell (WP-M1-25): drei Dienste, je ein Modell, über den echten Veröffentlichungsweg (WP-M1-22). Sie dient der Testfahrt je Modell mit M2
 * (WP-M2-04, -05, -07, -21, -23). Die Modellregeln sind Planungsannahme PA-01 (OP-28).
 */
object ServiceOfferFixtures {
    fun serviceConfig(operator: UUID, model: OfferModel) = mapOf("service_config" to mapOf(
        "service_area_id" to UUID.randomUUID().toString(), "traffic_type" to model.name, "operator_id" to operator.toString(),
        "operating_hours" to listOf(mapOf("weekdays" to listOf("MON", "TUE", "WED", "THU", "FRI"), "from" to "06:00", "to" to "22:00")), "tariff_id" to UUID.randomUUID().toString()))

    /** Gültige Werte je Modell: alle Pflichtparameter, keine unzulässigen. */
    fun validValues(operator: UUID, model: OfferModel): Map<String, Any?> = serviceConfig(operator, model) + when (model) {
        OfferModel.AREA_ON_DEMAND -> mapOf("max_detour_pct" to 30, "pooling_allowed" to true)
        OfferModel.LINE_ON_DEMAND -> mapOf("stop_walk_max_m" to 400)
        OfferModel.STATION_FEEDER -> mapOf("advance_arrival_buffer_min" to 15, "max_detour_pct" to 20)
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceConfigOfferModelTest {
    private lateinit var fx: StructureFixture
    @BeforeAll fun up() { fx = StructureFixture() }
    @AfterAll fun down() = fx.close()

    private fun now() = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private fun problem(block: () -> Any?) = assertThrows(ProblemException::class.java) { block() }

    private class Team(val world: StructureFixture.World, val alice: RequestContext, val bob: RequestContext)
    private fun team(): Team { val w = fx.world(); return Team(w, fx.context(w.tenant, "alice"), fx.context(w.tenant, "bob")) }
    private fun service(t: Team, name: String = "Rufbus") = fx.geo.structure.createService(t.alice, CreateService(t.world.operator, name, "Europe/Berlin"))

    private fun head(t: Team, s: ServiceView) = fx.config.revisions.get(t.alice, s.configRevisionId)
    private fun setValues(t: Team, s: ServiceView, values: Map<String, Any?>, from: Instant = now()): ConfigRevisionView {
        val h = head(t, s)
        return fx.config.revisions.updateDraft(t.alice, h.id, h.rowVersion, UpdateDraft(values = values, effectiveFrom = from))
    }

    /** Wirkungsbericht → validieren (Alice) → freigeben (Bob); liefert Bericht und Zeilenversion. */
    private fun ready(t: Team, id: UUID): Pair<UUID, Long> {
        val rep = fx.config.impact.analyze(t.alice, id, fx.config.revisions.get(t.alice, id).rowVersion)
        val v = fx.config.publication.validate(t.alice, id, fx.config.revisions.get(t.alice, id).rowVersion)
        assertTrue(v.valid, v.violations.toString())
        return rep.id to fx.config.publication.approve(t.bob, id, v.revision.rowVersion).rowVersion
    }

    private fun publish(t: Team, r: ConfigRevisionView, rep: UUID, rv: Long) = fx.config.publication.publish(t.bob, r.id, rv, PublishCommand(r.effectiveFrom, "Veröffentlichen", rep))

    private fun violations(t: Team, id: UUID): List<Violation> = fx.config.publication.validate(t.alice, id, fx.config.revisions.get(t.alice, id).rowVersion).violations

    @Test
    fun drei_angebotsmodelle_konfigurieren_validieren_publizieren() {
        val t = team()
        val published = OfferModel.entries.associateWith { model ->
            val s = service(t, model.label)
            val rev = setValues(t, s, ServiceOfferFixtures.validValues(t.world.operator, model))
            val (rep, rv) = ready(t, rev.id)
            assertEquals(RevisionState.PUBLISHED, publish(t, rev, rep, rv).revision.state)
            s to rev
        }
        for ((model, pair) in published) {
            val (s, rev) = pair
            val cfg = fx.geo.configurations.effective(t.alice, s.id, now().plusSeconds(60))
            assertEquals(model, cfg.model, "wirksames Modell"); assertEquals(OfferModelRules.of(model), cfg.rules, "wirksame Modellregeln")
            assertEquals(rev.id, cfg.revisionId)
            // die wirksamen Parameter entsprechen den Pflicht- und optionalen Werten der Revision, unzulässige fehlen
            assertTrue(cfg.parameters.keys.containsAll(OfferModelRules.of(model).required))
            assertTrue(cfg.parameters.keys.none { it in OfferModelRules.of(model).forbidden })
        }
        assertEquals(setOf("AREA_ON_DEMAND", "LINE_ON_DEMAND", "STATION_FEEDER"), published.keys.map { it.name }.toSet())
        assertEquals(3, fx.geo.configurations.modelRules().size)
        // ein Dienst ohne publizierte Konfiguration hat kein Modell
        val bare = service(t, "Neu")
        assertNull(fx.geo.configurations.effective(t.alice, bare.id).model)
    }

    @Test
    fun modellwidrige_parameterkombination_wird_abgewiesen() {
        val t = team(); val op = t.world.operator
        fun check(model: OfferModel, extra: Map<String, Any?>, remove: Set<String> = emptySet()): List<Violation> {
            val s = service(t, "x-${UUID.randomUUID()}")
            val values = (ServiceOfferFixtures.validValues(op, model) - remove) + extra
            val rev = setValues(t, s, values)
            val v = violations(t, rev.id)
            assertEquals(RevisionState.DRAFT, fx.config.revisions.get(t.alice, rev.id).state, "bei Verstoß bleibt es ein Entwurf")
            return v
        }
        // fehlender Pflichtparameter je Modell
        check(OfferModel.AREA_ON_DEMAND, emptyMap(), setOf("max_detour_pct")).single().let { assertEquals("/values/max_detour_pct", it.pointer); assertEquals(ViolationCode.REQUIRED, it.code) }
        check(OfferModel.LINE_ON_DEMAND, emptyMap(), setOf("stop_walk_max_m")).single().let { assertEquals("/values/stop_walk_max_m", it.pointer); assertEquals(ViolationCode.REQUIRED, it.code) }
        check(OfferModel.STATION_FEEDER, emptyMap(), setOf("advance_arrival_buffer_min")).single().let { assertEquals(ViolationCode.REQUIRED, it.code) }
        // modellfremder Parameter
        check(OfferModel.AREA_ON_DEMAND, mapOf("advance_arrival_buffer_min" to 10)).single().let { assertEquals("/values/advance_arrival_buffer_min", it.pointer); assertEquals(ViolationCode.UNKNOWN_FIELD, it.code) }
        check(OfferModel.LINE_ON_DEMAND, mapOf("max_detour_pct" to 10)).single().let { assertEquals("/values/max_detour_pct", it.pointer) }
        assertEquals(2, check(OfferModel.LINE_ON_DEMAND, mapOf("max_detour_pct" to 10, "advance_arrival_buffer_min" to 5)).size, "alle Verstöße werden gesammelt")
        // unbekanntes Modell: schon der Entwurf wird vom Katalog abgewiesen (422, keine Änderung)
        val s = service(t, "unbekannt")
        val bad = ServiceOfferFixtures.validValues(op, OfferModel.AREA_ON_DEMAND).toMutableMap()
        bad["service_config"] = (bad["service_config"] as Map<*, *>) + mapOf("traffic_type" to "TAXI")
        val h = head(t, s)
        val e = problem { fx.config.revisions.updateDraft(t.alice, h.id, h.rowVersion, UpdateDraft(values = bad)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertTrue(e.violations.any { it.pointer.endsWith("/traffic_type") && it.code == ViolationCode.NOT_IN_ENUM })
        assertEquals(emptyMap<String, Any?>(), head(t, s).values, "abgewiesene Änderung hinterlässt nichts")
        // eine ungültige Revision lässt sich nicht freigeben und nicht publizieren; nach Korrektur geht es
        val s2 = service(t, "korrigiert"); val rev = setValues(t, s2, ServiceOfferFixtures.validValues(op, OfferModel.LINE_ON_DEMAND) + mapOf("max_detour_pct" to 10))
        assertTrue(violations(t, rev.id).isNotEmpty())
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fx.config.publication.approve(t.bob, rev.id, fx.config.revisions.get(t.alice, rev.id).rowVersion) }.code)
        val fixed = fx.config.revisions.get(t.alice, rev.id).let { fx.config.revisions.updateDraft(t.alice, it.id, it.rowVersion, UpdateDraft(values = ServiceOfferFixtures.validValues(op, OfferModel.LINE_ON_DEMAND))) }
        val (rep, rv) = ready(t, fixed.id); assertEquals(RevisionState.PUBLISHED, publish(t, fixed, rep, rv).revision.state)
    }

    @Test
    fun dienst_mit_betreiber_fremder_mandant_wie_nicht_vorhanden() {
        val a = team(); val b = team()
        // Dienst mit Betreiber von B in Mandant A: wie nicht vorhanden (404), keine Zeile
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.structure.createService(a.alice, CreateService(b.world.operator, "Fremd", "Europe/Berlin")) }.code)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.service WHERE tenant_id = ? AND name = 'Fremd'", a.world.tenant.value))
        // Revision, die in der Dienstkonfiguration einen fremden Betreiber nennt: Verstoß bei der Validierung
        val s = service(a); val rev = setValues(a, s, ServiceOfferFixtures.validValues(b.world.operator, OfferModel.AREA_ON_DEMAND))
        val v = violations(a, rev.id).single()
        assertEquals("/values/service_config/operator_id", v.pointer); assertEquals(ViolationCode.INVALID_VALUE, v.code)
        // auch der eigene, aber vom Dienst abweichende Betreiber wird abgewiesen
        val other = fx.adminUpdate("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", a.world.tenant.value, UUID.randomUUID(), a.world.legalEntity, "Zweiter", "f-" + UUID.randomUUID(), "{TRANSPORT_COMPANY}")
        assertEquals(1, other)
        val otherOp = fx.adminCount("SELECT count(*) FROM taktovia.organization WHERE tenant_id = ?", a.world.tenant.value)
        assertEquals(2, otherOp)
        // fremder Dienst für die wirksame Konfiguration: 404 für den anderen Mandanten
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.geo.configurations.effective(b.alice, s.id) }.code)
    }

    @Test
    fun parallele_publikation_genau_eine_wirksam() {
        val t = team(); val s = service(t); val op = t.world.operator; val from = now().plus(1, ChronoUnit.DAYS)
        val r1 = setValues(t, s, ServiceOfferFixtures.validValues(op, OfferModel.AREA_ON_DEMAND), from)
        val r2 = fx.config.revisions.createDraft(t.alice, CreateDraft(ScopeKind.SERVICE, s.id, "1", ServiceOfferFixtures.validValues(op, OfferModel.LINE_ON_DEMAND), from, null, "Zweiter Stand"))
        val (rep1, v1) = ready(t, r1.id); val (rep2, v2) = ready(t, r2.id)
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val results = listOf(r1 to (rep1 to v1), r2 to (rep2 to v2)).map { (r, p) -> pool.submit<Result<Any?>> { start.await(); runCatching { publish(t, r, p.first, p.second) } } }
        start.countDown(); val done = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, done.count { it.isSuccess }, done.toString())
        assertEquals(ErrorCode.CONFLICT, (done.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        val winner = if (done[0].isSuccess) OfferModel.AREA_ON_DEMAND else OfferModel.LINE_ON_DEMAND
        assertEquals(winner, fx.geo.configurations.effective(t.alice, s.id, from.plusSeconds(60)).model, "genau eine Konfiguration ist wirksam")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ? AND scope_id = ? AND state = 'PUBLISHED'", t.world.tenant.value, s.id))
    }

    @Test
    fun gleichzeitige_dienstanlage_mandant_a_und_b_ohne_wirkung() {
        val a = team(); val b = team(); val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val futures = (1..20).map { i -> val t = if (i % 2 == 0) a else b; pool.submit<Pair<UUID, ServiceView>> { start.await(); t.world.tenant.value to service(t, "S-$i") } }
        start.countDown(); val created = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        for (t in listOf(a, b)) {
            val own = created.filter { it.first == t.world.tenant.value }.map { it.second.id }.toSet()
            val listed = fx.geo.structure.listServices(t.alice, 100, null).map { it.id }.toSet()
            assertEquals(own, listed, "jeder Mandant sieht genau die eigenen Dienste")
            assertEquals(own.size, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ? AND scope_kind = 'SERVICE'", t.world.tenant.value), "je Dienst genau die erste Revision")
        }
        assertNotNull(created.first())
    }
}
