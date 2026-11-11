package de.taktovia.config

import de.taktovia.config.api.ConfigLevel
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.config.api.LevelOutcome
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.internal.JdbcEffectiveConfig
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LH-I.7-CFG-005 / WP-M1-21 gegen die echte Datenbank unter der Runtime-Rolle: Präzedenz global, Mandant, Region, Dienst, Fahrzeug, Wirksamkeit zum Zeitpunkt T,
 * Sicherheitsgrenze, Freigabe der Fahrzeug-Overrides, Mandantentrennung, Dienstscope, Konsistenz bei paralleler Publikation.
 */
class EffectiveConfigResolverTest {
    private companion object {
        val fx = ConfigFixture(poolSize = 12)
        val pf = PrecedenceFixture(fx)
        @JvmStatic @AfterAll fun close() = fx.close()
    }
    private val resolver get() = fx.config.effective
    private fun one(ctx: de.taktovia.platform.context.RequestContext, name: String, region: UUID? = null, service: UUID? = null, vehicle: UUID? = null, at: Instant? = null) =
        resolver.resolve(ctx, EffectiveRequest(name, region, service, vehicle, at)).items.single()

    @Test
    fun konflikt_liefert_wirksamen_wert_mit_vorrangregel() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = pf.region(t); val service = pf.service(t, region)
        val tenantRev = pf.publish(ctx, ScopeKind.TENANT, t.value, mapOf("max_wait_min" to 30))
        val regionRev = pf.publish(ctx, ScopeKind.REGION, region, mapOf("max_wait_min" to 20))
        val serviceRev = pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 10))
        val e = one(ctx, "max_wait_min", service = service)
        assertEquals(10, e.value); assertEquals(ConfigLevel.SERVICE, e.level); assertEquals(serviceRev.id, e.revisionId); assertEquals(serviceRev.revision, e.revision)
        assertEquals("MOST_SPECIFIC_LEVEL_WINS", e.rule.code); assertEquals("MINUTES", e.unit); assertEquals(EffectiveStatus.EFFECTIVE, e.status)
        assertEquals(service, e.scopeId); assertEquals(serviceRev.effectiveFrom, e.effectiveFrom)
        // die Reihenfolge zeigt jede Ebene mit ihrem Schicksal: Dienst gilt, Region und Mandant werden überstimmt (spezifischste zuerst, global zuletzt)
        assertEquals(listOf(ConfigLevel.VEHICLE, ConfigLevel.SERVICE, ConfigLevel.REGION, ConfigLevel.TENANT, ConfigLevel.GLOBAL), e.precedence.map { it.level })
        assertEquals(listOf(LevelOutcome.NOT_IN_CONTEXT, LevelOutcome.APPLIED, LevelOutcome.OVERRIDDEN, LevelOutcome.OVERRIDDEN, LevelOutcome.NOT_SET), e.precedence.map { it.outcome })
        assertEquals(listOf(null, 10, 20, 30, null), e.precedence.map { it.value })
        assertEquals(regionRev.id, e.precedence[2].revisionId); assertEquals(tenantRev.id, e.precedence[3].revisionId)
        // ohne Dienst gilt die Region, ohne Region der Mandant
        assertEquals(20, one(ctx, "max_wait_min", region = region).value); assertEquals(ConfigLevel.REGION, one(ctx, "max_wait_min", region = region).level)
        assertEquals(30, one(ctx, "max_wait_min").value); assertEquals(ConfigLevel.TENANT, one(ctx, "max_wait_min").level)
        // die Region folgt aus dem Dienst, wenn nur der Dienst angegeben ist; Dienst und Region müssen zusammenpassen
        assertEquals(region, one(ctx, "max_wait_min", service = service).precedence.single { it.level == ConfigLevel.REGION }.scopeId)
        val other = pf.region(t)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { one(ctx, "max_wait_min", region = other, service = service) }.code)
    }

    @Test
    fun aufloesung_auf_jeder_ebene() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = pf.region(t); val service = pf.service(t, region); val vehicle = pf.vehicle(t)
        // GLOBAL: die Sicherheitsinvariante hat die feste globale Vorgabe, ohne dass eine Ebene sie setzt
        val g = one(ctx, "vehicle_readiness_require_checks")
        assertEquals(true, g.value); assertEquals(ConfigLevel.GLOBAL, g.level); assertEquals("GLOBAL_DEFAULT", g.rule.code); assertNull(g.revisionId)
        // keine Ebene und keine globale Vorgabe: kein Wert
        val none = one(ctx, "min_capacity_reserve"); assertEquals(EffectiveStatus.NO_VALUE, none.status); assertNull(none.value); assertNull(none.level); assertEquals("NO_VALUE", none.rule.code)
        // MANDANT, REGION, DIENST je allein
        pf.publish(ctx, ScopeKind.TENANT, t.value, mapOf("pickup_window_min" to 15))
        assertEquals(ConfigLevel.TENANT, one(ctx, "pickup_window_min", region = region, service = service).level)
        pf.publish(ctx, ScopeKind.REGION, region, mapOf("pickup_window_min" to 20))
        assertEquals(ConfigLevel.REGION, one(ctx, "pickup_window_min", region = region, service = service).level)
        pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("pickup_window_min" to 25))
        assertEquals(ConfigLevel.SERVICE, one(ctx, "pickup_window_min", region = region, service = service).level)
        // FAHRZEUG: nur für freigegebene Parameter (Rollstuhl-Einstiegszeit); Dienst 100, Fahrzeug 80 -> Fahrzeug
        pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("wheelchair_boarding_sec" to 100, "pickup_window_min" to 25), from = "2026-02-01T00:00:00Z")
        pf.publish(ctx, ScopeKind.VEHICLE, vehicle, mapOf("wheelchair_boarding_sec" to 80))
        val v = one(ctx, "wheelchair_boarding_sec", service = service, vehicle = vehicle)
        assertEquals(80, v.value); assertEquals(ConfigLevel.VEHICLE, v.level); assertEquals("SECONDS", v.unit)
        assertEquals(100, one(ctx, "wheelchair_boarding_sec", service = service).value)   // ohne Fahrzeug im Kontext gilt der Dienst
        // alle Parameter des Katalogs auf einmal
        val all = resolver.resolve(ctx, EffectiveRequest(serviceId = service, vehicleId = vehicle))
        assertTrue(all.items.size >= 20); assertEquals(80, all.items.single { it.parameter == "wheelchair_boarding_sec" }.value)
    }

    @Test
    fun fahrzeug_override_fuer_nicht_freigegebenen_parameter_wird_abgewiesen() {
        val t = fx.tenant(); val ctx = fx.context(t); val vehicle = pf.vehicle(t)
        val e = assertThrows<ProblemException> { pf.publish(ctx, ScopeKind.VEHICLE, vehicle, mapOf("max_wait_min" to 20)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertTrue(e.violations.any { it.pointer == "/values/max_wait_min" && it.code == ViolationCode.UNKNOWN_FIELD }, e.violations.toString())
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ? AND scope_kind = 'VEHICLE'", t.value))
        // der freigegebene Parameter wird angenommen, ein fremdes oder unbekanntes Fahrzeug nicht (404)
        pf.publish(ctx, ScopeKind.VEHICLE, vehicle, mapOf("wheelchair_boarding_sec" to 60))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { pf.publish(ctx, ScopeKind.VEHICLE, UUID.randomUUID(), mapOf("wheelchair_boarding_sec" to 60)) }.code)
        val b = fx.tenant(); val vehicleB = pf.vehicle(b)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { pf.publish(ctx, ScopeKind.VEHICLE, vehicleB, mapOf("wheelchair_boarding_sec" to 60)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { one(ctx, "wheelchair_boarding_sec", vehicle = vehicleB) }.code)
    }

    @Test
    fun dienstwert_der_die_globale_sicherheitsgrenze_verletzt_wird_nie_wirksam() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = pf.region(t); val service = pf.service(t, region)
        pf.publish(ctx, ScopeKind.TENANT, t.value, mapOf("max_wait_min" to 90))
        pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 200))   // unter Version 1 zulässig (Katalogmaximum 240)
        assertEquals(200, one(ctx, "max_wait_min", service = service).value)
        // eine strengere Schema-Version (Maximum 120) gilt ab 2999: der Dienstwert 200 verletzt die globale Sicherheitsgrenze und wird nie als wirksam geliefert
        val raised = (fx.adminCount("SELECT max(schema_version::int) FROM taktovia.policy_schema_version") + 1).toString()
        fx.adminUpdate("INSERT INTO taktovia.policy_schema_version(schema_version, valid_from, description) VALUES (?, '2999-01-01T00:00:00Z', 'Testversion: strengere Wartezeit')", raised)
        fx.adminUpdate("INSERT INTO taktovia.policy_schema(schema_version, parameter, json_schema) SELECT ?, parameter, " +
            "CASE WHEN parameter = 'max_wait_min' THEN jsonb_set(json_schema, '{maximum}', '120') ELSE json_schema END FROM taktovia.policy_schema WHERE schema_version = '1'", raised)
        val e = one(ctx, "max_wait_min", service = service, at = Instant.parse("3000-01-01T00:00:00Z"))
        assertEquals(90, e.value); assertEquals(ConfigLevel.TENANT, e.level); assertEquals("SAFETY_LIMIT_FALLBACK", e.rule.code)
        val skipped = e.precedence.single { it.level == ConfigLevel.SERVICE }
        assertEquals(LevelOutcome.SKIPPED_LIMIT, skipped.outcome); assertEquals("ABOVE_MAXIMUM", skipped.violation); assertEquals(200, skipped.value)
        // kein anderer Wert auf einer Ebene: ohne zulässigen Wert gibt es keinen
        val t2 = fx.tenant(); val ctx2 = fx.context(t2); val s2 = pf.service(t2, null)
        pf.publish(ctx2, ScopeKind.SERVICE, s2, mapOf("max_wait_min" to 200))
        assertEquals(EffectiveStatus.NO_VALUE, one(ctx2, "max_wait_min", service = s2, at = Instant.parse("3000-01-01T00:00:00Z")).status)
    }

    @Test
    fun aufloesung_zum_zeitpunkt_t_beruecksichtigt_nur_revisionen_deren_intervall_t_enthaelt() {
        val t = fx.tenant(); val ctx = fx.context(t); val service = pf.service(t, null)
        val r1 = pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 10), from = "2026-01-01T00:00:00Z", to = "2026-06-01T00:00:00Z")
        val r2 = pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 20), from = "2026-06-01T00:00:00Z")
        assertEquals(10, one(ctx, "max_wait_min", service = service, at = Instant.parse("2026-03-01T00:00:00Z")).value)
        assertEquals(r1.id, one(ctx, "max_wait_min", service = service, at = Instant.parse("2026-03-01T00:00:00Z")).revisionId)
        assertEquals(10, one(ctx, "max_wait_min", service = service, at = Instant.parse("2026-05-31T23:59:59Z")).value)
        assertEquals(20, one(ctx, "max_wait_min", service = service, at = Instant.parse("2026-06-01T00:00:00Z")).value)   // das Ende der ersten ist exklusiv
        assertEquals(r2.id, one(ctx, "max_wait_min", service = service, at = Instant.parse("2027-01-01T00:00:00Z")).revisionId)
        // vor dem ersten Beginn: keine wirksame Revision, definierte Antwort ohne Wert
        val before = one(ctx, "max_wait_min", service = service, at = Instant.parse("2025-01-01T00:00:00Z"))
        assertEquals(EffectiveStatus.NO_VALUE, before.status); assertNull(before.value); assertNull(before.revisionId)
        // ein Entwurf und eine nur geprüfte Revision wirken nie
        pf.draft(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 5, "service_config" to pf.serviceConfig()["service_config"]))
        assertEquals(20, one(ctx, "max_wait_min", service = service).value)
        // ... sind aber als Vorschlag sichtbar
        val proposal = one(ctx, "max_wait_min", service = service).precedence.single { it.level == ConfigLevel.SERVICE }.proposal!!
        assertEquals(5, proposal.value); assertEquals(de.taktovia.config.api.RevisionState.DRAFT, proposal.state)
    }

    @Test
    fun zwei_regionen_je_ein_dienst_unterschiedliche_werte_ein_login() {
        val t = fx.tenant(); val region1 = pf.region(t, "Nord"); val region2 = pf.region(t, "Süd"); val s1 = pf.service(t, region1); val s2 = pf.service(t, region2)
        val admin = fx.context(t)
        pf.publish(admin, ScopeKind.TENANT, t.value, mapOf("max_wait_min" to 45, "pickup_window_min" to 10))
        pf.publish(admin, ScopeKind.REGION, region1, mapOf("max_wait_min" to 15)); pf.publish(admin, ScopeKind.REGION, region2, mapOf("max_wait_min" to 40))
        pf.publish(admin, ScopeKind.SERVICE, s1, mapOf("max_wait_min" to 12)); pf.publish(admin, ScopeKind.SERVICE, s2, mapOf("pickup_window_min" to 30))
        // ein Login mit beiden Diensten im Dienstscope (lesende Rolle) wechselt zwischen den Gebieten und sieht jeweils die Regeln des gewählten Gebiets
        val login = fx.context(t, roles = setOf("dispatcher"), scopes = setOf("config:read")).let { c ->
            de.taktovia.platform.context.RequestContext(c.actor, t, c.roles, c.scopes, setOf(s1, s2), c.correlationId)
        }
        val a1 = resolver.resolve(login, EffectiveRequest(serviceId = s1)).items.associateBy { it.parameter }
        val a2 = resolver.resolve(login, EffectiveRequest(serviceId = s2)).items.associateBy { it.parameter }
        assertEquals(12, a1.getValue("max_wait_min").value); assertEquals(ConfigLevel.SERVICE, a1.getValue("max_wait_min").level); assertEquals(10, a1.getValue("pickup_window_min").value)
        assertEquals(40, a2.getValue("max_wait_min").value); assertEquals(ConfigLevel.REGION, a2.getValue("max_wait_min").level); assertEquals(30, a2.getValue("pickup_window_min").value)
        assertNotEquals(a1.getValue("max_wait_min").value, a2.getValue("max_wait_min").value)
        // Dienst außerhalb des eigenen Dienstscopes: 404 für lesende Rollen, auch wenn der Dienst im Mandanten existiert; verwaltende Rollen sehen ihn
        val onlyS1 = de.taktovia.platform.context.RequestContext(login.actor, t, login.roles, login.scopes, setOf(s1), login.correlationId)
        assertEquals(12, resolver.resolve(onlyS1, EffectiveRequest("max_wait_min", serviceId = s1)).items.single().value)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { resolver.resolve(onlyS1, EffectiveRequest("max_wait_min", serviceId = s2)) }.code)
        assertEquals(40, resolver.resolve(admin, EffectiveRequest("max_wait_min", serviceId = s2)).items.single().value)
        val noScope = de.taktovia.platform.context.RequestContext(login.actor, t, login.roles, login.scopes, emptySet(), login.correlationId)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { resolver.resolve(noScope, EffectiveRequest("max_wait_min", serviceId = s1)) }.code)
    }

    @Test
    fun kontext_eines_fremden_mandanten_und_unbekannter_parameter() {
        val a = fx.tenant(); val b = fx.tenant(); val ctxA = fx.context(a)
        val regionB = pf.region(b); val serviceB = pf.service(b, regionB); val vehicleB = pf.vehicle(b)
        pf.publish(fx.context(b), ScopeKind.SERVICE, serviceB, mapOf("max_wait_min" to 5))
        for (req in listOf(EffectiveRequest("max_wait_min", regionId = regionB), EffectiveRequest("max_wait_min", serviceId = serviceB), EffectiveRequest("max_wait_min", vehicleId = vehicleB),
            EffectiveRequest("max_wait_min", regionId = UUID.randomUUID())))
            assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { resolver.resolve(ctxA, req) }.code, req.toString())
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { resolver.resolve(ctxA, EffectiveRequest("gibt_es_nicht")) }.code)
        // der Mandantenrahmen eines anderen Mandanten wirkt nie
        pf.publish(fx.context(b), ScopeKind.TENANT, b.value, mapOf("max_wait_min" to 7))
        assertEquals(EffectiveStatus.NO_VALUE, one(ctxA, "max_wait_min").status)
    }

    @Test
    fun publikation_waehrend_laufender_aufloesungen_jede_antwort_konsistent_zu_genau_einer_revision() {
        val t = fx.tenant(); val ctx = fx.context(t); val service = pf.service(t, null)
        // Revision n trägt den Wert 100 + n: jede Antwort muss Wert und Revisionsnummer als zusammengehöriges Paar liefern
        pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 101), from = "2026-01-01T00:00:00Z")
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(6)
        val readers = (1..4).map {
            pool.submit<List<Pair<Int, Long>>> {
                val seen = mutableListOf<Pair<Int, Long>>()
                while (!stop.get()) { val e = one(ctx, "max_wait_min", service = service); seen += (e.value as Int) to e.revision!! }
                seen
            }
        }
        val publisher = pool.submit { for (n in 2..13) pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 100 + n), from = "2026-01-%02dT00:00:00Z".format(n)) }
        publisher.get(120, TimeUnit.SECONDS); stop.set(true)
        val all = readers.flatMap { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(all.size > 20, "genug Antworten während der Publikationen: ${all.size}")
        all.forEach { (value, revision) -> assertEquals(100 + revision.toInt(), value, "Wert und Revision stammen aus genau einer Revision") }
        // jeder Leser sieht die Revisionen nur aufsteigend (nie einen älteren Stand nach einem neueren)
        readers.forEach { f -> val r = f.get().map { it.second }; assertEquals(r.sorted(), r) }
        assertEquals(113, one(ctx, "max_wait_min", service = service).value)
    }

    @Test
    fun cache_liefert_nach_publikation_nicht_den_abgeloesten_wert() {
        val t = fx.tenant(); val ctx = fx.context(t); val service = pf.service(t, null)
        val impl = resolver as JdbcEffectiveConfig
        val r1 = pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 10), from = "2026-01-01T00:00:00Z")
        val first = one(ctx, "max_wait_min", service = service)
        val hitsBefore = impl.cacheHits.get()
        val second = one(ctx, "max_wait_min", service = service)
        assertEquals(hitsBefore + 1, impl.cacheHits.get(), "die zweite gleiche Anfrage kommt aus dem Zwischenspeicher")
        assertEquals(first.value, second.value); assertEquals(r1.id, second.revisionId)
        // Publikation einer Folgerevision löst die erste ab: der Schlüssel (beteiligte Revisionen) ändert sich, nie der abgelöste Wert
        val r2 = pf.publish(ctx, ScopeKind.SERVICE, service, mapOf("max_wait_min" to 20), from = "2026-02-01T00:00:00Z")
        val third = one(ctx, "max_wait_min", service = service)
        assertEquals(20, third.value); assertEquals(r2.id, third.revisionId)
        // dieselbe Anfrage zu einem früheren Zeitpunkt liefert die abgelöste Revision (andere beteiligte Revision, eigener Schlüssel)
        assertEquals(r1.id, one(ctx, "max_wait_min", service = service, at = Instant.parse("2026-01-15T00:00:00Z")).revisionId)
        assertEquals(r2.id, one(ctx, "max_wait_min", service = service).revisionId)
    }
}
