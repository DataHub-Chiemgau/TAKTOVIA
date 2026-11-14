package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-41 / LH-6.7-OPS-005 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `createVehicleBlock` und `liftVehicleBlock` mit Rolle, Dienstscope, Pflichtgrund, `If-Match`,
 * `Idempotency-Key`, Fahrzeugliste der Leitstelle (UI-07-Datenquelle), Audit und Ereignis. Die Oberfläche (Browser) ist nicht beteiligt.
 */
class OperationalBlockApiTest {
    private val env = IdentityEnv

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Sperre-" + UUID.randomUUID())
        val serviceA = service(); val serviceB = service()
        val vehicle = vehicle("OPS-A", serviceA); val otherService = vehicle("OPS-B", serviceB); val free = vehicle("OPS-N")
        val dispatcherA = UUID.randomUUID(); val dispatcherA2 = UUID.randomUUID(); val driver = UUID.randomUUID(); val admin = UUID.randomUUID()
        init { env.grant(tenant, dispatcherA, "dispatcher", listOf(serviceA)); env.grant(tenant, dispatcherA2, "dispatcher", listOf(serviceA)); env.grant(tenant, driver, "driver"); env.grant(tenant, admin, "tenant_admin") }
        val tokenA = env.token(dispatcherA); val tokenA2 = env.token(dispatcherA2); val driverToken = env.token(driver); val adminToken = env.token(admin)

        fun service(): UUID {
            val cfg = UUID.randomUUID(); val svc = UUID.randomUUID(); val le = UUID.randomUUID(); val org = UUID.randomUUID()
            env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", tenant.value, cfg, svc)
            env.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", tenant.value, le)
            env.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Org',?,CAST('{TRANSPORT_COMPANY}' AS text[]))", tenant.value, org, le, "f-$org")
            env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", tenant.value, svc, org, cfg)
            return svc
        }
        fun vehicle(label: String, vararg services: UUID): UUID = UUID.randomUUID().also { id ->
            env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}',?)", tenant.value, id, label)
            services.forEach { s -> env.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,now() - interval '1 day','user:t')", tenant.value, UUID.randomUUID(), s, id) }
        }
        fun view(v: UUID, token: String) = env.get("/v1/operator/vehicles/$v", token)
        fun block(v: UUID, token: String, body: String, revision: Long, key: String = "b-" + UUID.randomUUID()) =
            env.post("/v1/operator/vehicles/$v/blocks", body, token, null, mapOf("Idempotency-Key" to key, "If-Match" to "\"$revision\""))
        fun lift(v: UUID, block: String, token: String, body: String, revision: Long, key: String = "l-" + UUID.randomUUID()) =
            env.post("/v1/operator/vehicles/$v/blocks/$block/lift", body, token, null, mapOf("Idempotency-Key" to key, "If-Match" to "\"$revision\""))
    }

    private val disruption = """{"reason_code":"OPERATIONAL_DISRUPTION"}"""

    @Test
    fun sperre_ueber_die_schnittstelle_sichtbar_mit_grund_und_urheber_und_aufhebbar() {
        val w = World(env)
        val v0 = w.view(w.vehicle, w.tokenA); assertEquals(200, v0.status, v0.text)
        val rev = v0.json["revision"].asLong(); val safety = v0.json["safety_revision"].asLong()
        val r = w.block(w.vehicle, w.tokenA, """{"reason_code":"MANUAL","comment":"Reifen platt"}""", rev)
        assertEquals(201, r.status, r.text); assertEquals("\"1\"", r.header("ETag")); assertTrue(r.header("Location")!!.endsWith("/blocks/${r.json["id"].asString()}"))
        assertEquals("OPERATIONS", r.json["source"].asString()); assertEquals("MANUAL", r.json["reason_code"].asString()); assertEquals("VEHICLE", r.json["block_kind"].asString()); assertTrue(r.json["lifted_at"].isNull)
        assertTrue(r.json["set_by"].asString().endsWith(w.dispatcherA.toString()))
        // Liste und Detail der Leitstelle: gesperrt mit Grund und Urheber, safety_revision erhöht, Fahrzeugrevision gewechselt
        val v1 = w.view(w.vehicle, w.tokenA).json
        assertEquals("BLOCKED", v1["operational_state"].asString()); assertEquals(safety + 1, v1["safety_revision"].asLong()); assertNotEquals(rev, v1["revision"].asLong())
        val b = v1["active_blocks"].single(); assertEquals(r.json["id"].asString(), b["id"].asString()); assertEquals("MANUAL", b["reason_code"].asString()); assertTrue(b["set_by"].asString().endsWith(w.dispatcherA.toString()))
        val inList = env.get("/v1/operator/vehicles", w.tokenA).json["items"].values().single { it["vehicle_id"].asString() == w.vehicle.toString() }
        assertEquals("BLOCKED", inList["operational_state"].asString()); assertEquals("MANUAL", inList["active_blocks"][0]["reason_code"].asString())
        // Audit (Akteur, Grund, Zeitpunkt) und Ereignis „Fahrzeug gesperrt“
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.vehicle.blocked' AND object_ref=? AND redacted_changes->>'reason_code'='MANUAL' AND redacted_changes->>'comment'='Reifen platt' AND actor_ref LIKE ?",
            w.tenant.value, "vehicle:${w.vehicle}", "%${w.dispatcherA}"))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='fleet.vehicle_blocked' AND payload->>'vehicle_id'=? AND payload->>'source'='OPERATIONS'", w.tenant.value, w.vehicle.toString()))
        // Aufheben: If-Match = Revision der Sperre; danach nicht mehr gesperrt, nie wieder öffnen
        val l = w.lift(w.vehicle, r.json["id"].asString(), w.tokenA2, """{"comment":"repariert"}""", 1)
        assertEquals(200, l.status, l.text); assertEquals("\"2\"", l.header("ETag")); assertFalse(l.json["lifted_at"].isNull)
        assertEquals(0, w.view(w.vehicle, w.tokenA).json["active_blocks"].size())
        val again = w.lift(w.vehicle, r.json["id"].asString(), w.tokenA, "{}", 2); assertEquals(409, again.status); assertEquals("INVALID_STATE_TRANSITION", again.json["code"].asString())
    }

    @Test
    fun sperre_ohne_grund_ohne_rolle_oder_fremdes_fahrzeug_abgewiesen() {
        val w = World(env); val rev = w.view(w.vehicle, w.tokenA).json["revision"].asLong()
        fun blocks(v: UUID) = env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id=? AND vehicle_id=?", w.tenant.value, v)
        // ohne Grund bzw. mit Grund außerhalb des Katalogs: 422 mit Feldverweis; MANUAL und OTHER verlangen die Begründung
        val none = w.block(w.vehicle, w.tokenA, "{}", rev); assertEquals(422, none.status); assertTrue(none.text.contains("/reason_code"))
        val safety = w.block(w.vehicle, w.tokenA, """{"reason_code":"DEFECT_CRITICAL"}""", rev); assertEquals(422, safety.status)
        val noComment = w.block(w.vehicle, w.tokenA, """{"reason_code":"OTHER"}""", rev); assertEquals(422, noComment.status); assertTrue(noComment.text.contains("/comment"))
        val source = w.block(w.vehicle, w.tokenA, """{"reason_code":"MANUAL","comment":"x","source":"SAFETY"}""", rev); assertEquals(422, source.status, "die Quelle ist keine Eingabe")
        // manipuliertes tenant_id im Body: kein Recht und keine Information (404 wie nicht vorhanden), nichts geschrieben
        assertEquals(404, w.block(w.vehicle, w.tokenA, """{"reason_code":"OPERATIONAL_DISRUPTION","tenant_id":"${UUID.randomUUID()}"}""", rev).status)
        // ohne If-Match 428, ohne Idempotency-Key 422
        assertEquals(428, env.post("/v1/operator/vehicles/${w.vehicle}/blocks", disruption, w.tokenA, null, mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())).status)
        assertEquals(422, env.post("/v1/operator/vehicles/${w.vehicle}/blocks", disruption, w.tokenA, null, mapOf("If-Match" to "\"$rev\"")).status)
        // ohne Rolle: Fahrer und Administrator (Rechtematrix: nur Disposition) 403, kein Sperrdatensatz
        assertEquals(403, w.block(w.vehicle, w.driverToken, disruption, rev).status)
        assertEquals(403, w.block(w.vehicle, w.adminToken, disruption, rev).status)
        // nicht berechtigter Dienst und fremder Mandant: 404 (auch fremde Sperr-ID beim Aufheben)
        assertEquals(404, w.block(w.otherService, w.tokenA, disruption, 1).status)
        val foreign = World(env)
        assertEquals(404, w.block(foreign.free, w.tokenA, disruption, 1).status)
        assertEquals(404, w.block(UUID.randomUUID(), w.tokenA, disruption, 1).status)
        assertEquals(404, w.lift(w.vehicle, UUID.randomUUID().toString(), w.tokenA, "{}", 1).status)
        assertEquals(0, blocks(w.vehicle) + blocks(w.otherService) + blocks(foreign.free))
        // veraltete Revision: 412, Problem-Antwort, nichts geschrieben
        val stale = w.block(w.vehicle, w.tokenA, disruption, rev + 7); assertEquals(412, stale.status); assertEquals("REVISION_MISMATCH", stale.json["code"].asString()); assertEquals(0, blocks(w.vehicle))
        // Fahrzeug ohne Dienstbezug: nur die Mandantengrenze gilt
        assertEquals(201, w.block(w.free, w.tokenA, disruption, w.view(w.free, w.tokenA).json["revision"].asLong()).status)
    }

    @Test
    fun aufhebungsversuch_kritischer_sperre_ueber_operativen_weg_scheitert_und_operative_aufhebung_laesst_sie_bestehen() {
        val w = World(env)
        val critical = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by,reason_ref) VALUES (?,?,?,'SAFETY','DEFECT_CRITICAL','service:system','defect-1')", w.tenant.value, critical, w.vehicle)
        env.exec("UPDATE taktovia.vehicle SET active_block = true, safety_revision = safety_revision + 1 WHERE id = ?", w.vehicle)
        val v = w.view(w.vehicle, w.tokenA).json
        // der Aufhebungsversuch der kritischen Sperre scheitert (409), protokolliert als Fehlversuch, die Sperre bleibt
        val try1 = w.lift(w.vehicle, critical.toString(), w.tokenA, """{"comment":"bitte"}""", 1)
        assertEquals(409, try1.status, try1.text); assertEquals("INVALID_STATE_TRANSITION", try1.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id=? AND id=? AND status='ACTIVE'", w.tenant.value, critical))
        // eine operative Sperre daneben: Aufheben lässt die kritische bestehen
        val ops = w.block(w.vehicle, w.tokenA, disruption, v["revision"].asLong()); assertEquals(201, ops.status, ops.text)
        val lifted = w.lift(w.vehicle, ops.json["id"].asString(), w.tokenA, "{}", 1); assertEquals(200, lifted.status, lifted.text)
        val after = w.view(w.vehicle, w.tokenA).json
        assertEquals("BLOCKED", after["operational_state"].asString()); assertEquals(listOf(critical.toString()), after["active_blocks"].values().map { it["id"].asString() })
        assertEquals("SAFETY", after["active_blocks"][0]["source"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle WHERE id=? AND active_block", w.vehicle))
    }

    @Test
    fun idempotente_wiederholung_und_zwei_disponenten_ein_aktiver_sperreintrag() {
        val w = World(env); val rev = w.view(w.vehicle, w.tokenA).json["revision"].asLong()
        fun active() = env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id=? AND vehicle_id=? AND status='ACTIVE'", w.tenant.value, w.vehicle)
        val key = "same-" + UUID.randomUUID()
        val first = w.block(w.vehicle, w.tokenA, disruption, rev, key); assertEquals(201, first.status, first.text)
        val safety = env.count("SELECT safety_revision FROM taktovia.vehicle WHERE id=?", w.vehicle)
        // derselbe Schlüssel und Inhalt: dieselbe Antwort, keine zweite Sperre, kein zweiter Revisionssprung
        val replay = w.block(w.vehicle, w.tokenA, disruption, rev, key)
        assertEquals(201, replay.status); assertEquals("true", replay.header("Idempotent-Replayed")); assertEquals(first.json["id"].asString(), replay.json["id"].asString())
        assertEquals(1, active()); assertEquals(safety, env.count("SELECT safety_revision FROM taktovia.vehicle WHERE id=?", w.vehicle))
        // derselbe Schlüssel mit anderem Inhalt: 409 IDEMPOTENCY_CONFLICT
        val conflict = w.block(w.vehicle, w.tokenA, """{"reason_code":"MANUAL","comment":"anders"}""", rev, key); assertEquals(409, conflict.status); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString())
        // anderer Schlüssel, aktueller Stand, gleicher Grund: die bestehende Sperre (200), keine zweite
        val cur = w.view(w.vehicle, w.tokenA).json["revision"].asLong()
        val same = w.block(w.vehicle, w.tokenA, disruption, cur); assertEquals(200, same.status, same.text); assertEquals(first.json["id"].asString(), same.json["id"].asString()); assertEquals(1, active())
        // zwei Disponenten gleichzeitig mit demselben Stand: genau einer 201, der andere 412 bzw. 200 mit derselben Sperre; ein aktiver Eintrag
        val v2 = w.free; val rev2 = w.view(v2, w.tokenA).json["revision"].asLong(); val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val res = listOf(w.tokenA, w.tokenA2).map { t -> pool.submit<Int> { start.await(); w.block(v2, t, disruption, rev2).status } }
            start.countDown(); val codes = res.map { it.get(30, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(201, 412), codes)
            assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id=? AND vehicle_id=? AND status='ACTIVE'", w.tenant.value, v2))
        } finally { pool.shutdownNow() }
    }
}
