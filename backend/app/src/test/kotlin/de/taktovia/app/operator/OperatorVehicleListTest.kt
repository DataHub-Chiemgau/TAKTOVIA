package de.taktovia.app.operator

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-36 / LH-6.7-OPS-001 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `GET /v1/operator/vehicles` und `/{vehicle_id}` mit Dienstscope, Filtern, Seitenform,
 * Standortalter und Aufträgen. Testbetrieb mit zwei Diensten A und B: Fahrzeuge in A, in B, in beiden und ohne Dienst. Die Karte (Web) ist nicht beteiligt; die Gegenstelle ist der echte Server.
 */
class OperatorVehicleListTest {
    private val env = IdentityEnv

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Livebetrieb-" + UUID.randomUUID())
        val serviceA = service(); val serviceB = service()
        val va1 = vehicle("A-1", serviceA); val va2 = vehicle("A-2", serviceA); val vb = vehicle("B-1", serviceB); val vab = vehicle("AB-1", serviceA, serviceB); val vn = vehicle("N-1")
        val dispatcherA = UUID.randomUUID(); val dispatcherB = UUID.randomUUID(); val dispatcherAll = UUID.randomUUID(); val driver = UUID.randomUUID()
        val membershipA = env.grant(tenant, dispatcherA, "dispatcher", listOf(serviceA)); init { env.grant(tenant, dispatcherB, "dispatcher", listOf(serviceB)); env.grant(tenant, dispatcherAll, "dispatcher", listOf(serviceA, serviceB)); env.grant(tenant, driver, "driver") }
        val tokenA = env.token(dispatcherA); val tokenB = env.token(dispatcherB); val tokenAll = env.token(dispatcherAll); val driverToken = env.token(driver)

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
        fun shift(vehicle: UUID, state: String = "STARTED"): UUID = UUID.randomUUID().also { id ->
            env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() - interval '1 hour', now() + interval '5 hours','Europe/Berlin',0,?)", tenant.value, id, UUID.randomUUID(), vehicle, state)
        }
        fun position(vehicle: UUID, shift: UUID, ageSeconds: Long, lat: Double, lon: Double) = env.exec(
            "INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,created_by) VALUES (?,?,?,?,CAST(ST_SetSRID(ST_MakePoint(?,?),4326) AS geography),5,?,?,'user:t')",
            tenant.value, UUID.randomUUID(), vehicle, shift, lon, lat, Timestamp.from(Instant.now().minusSeconds(ageSeconds)), Timestamp.from(Instant.now().minusSeconds(maxOf(0, ageSeconds - 1))))
        fun labels(token: String, query: String = ""): List<String> = env.get("/v1/operator/vehicles$query", token).also { check(it.status == 200) { it.text } }.json["items"].values().map { it["vehicle_label"].asString() }.sorted()
    }

    @Test
    fun disponent_dienst_a_sieht_nur_fahrzeuge_dienst_a() {
        val w = World(env)
        // Dienst A: Fahrzeuge in A, das Fahrzeug in beiden Diensten und das Fahrzeug ohne Dienstbezug (nur Mandantengrenze); nie das Fahrzeug nur in B
        assertEquals(listOf("A-1", "A-2", "AB-1", "N-1"), w.labels(w.tokenA))
        assertEquals(listOf("AB-1", "B-1", "N-1"), w.labels(w.tokenB))
        assertEquals(listOf("A-1", "A-2", "AB-1", "B-1", "N-1"), w.labels(w.tokenAll))
        // Filter nach Dienst schneidet nur ein: Dienst A für den Disponenten A ohne Fahrzeug ohne Dienstbezug; Dienst B liegt außerhalb seines Scopes
        assertEquals(listOf("A-1", "A-2", "AB-1"), w.labels(w.tokenA, "?service_id=${w.serviceA}"))
        assertEquals(emptyList<String>(), w.labels(w.tokenA, "?service_id=${w.serviceB}"))
        assertEquals(listOf("AB-1", "B-1"), w.labels(w.tokenAll, "?service_id=${w.serviceB}"))
        // Filter nach Status: eine aktive Sperre ergibt BLOCKED, ein Fahrzeug mit angetretener Schicht ohne Freigabe RELEASE_PENDING
        env.exec("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (?,?,?,'OPERATIONS','MANUAL','user:t')", w.tenant.value, UUID.randomUUID(), w.va2)
        w.shift(w.va1)
        assertEquals(listOf("A-2"), w.labels(w.tokenA, "?state=BLOCKED"))
        assertEquals(listOf("A-1"), w.labels(w.tokenA, "?state=RELEASE_PENDING"))
        assertEquals(listOf("A-2"), w.labels(w.tokenA, "?state=BLOCKED&service_id=${w.serviceA}"))
        // je Fahrzeug: Position (hier keine, daher veraltet und ohne Alter), Verfügbarkeit, Aufträge, Dienste, Sperren und fehlende Voraussetzungen
        val items = env.get("/v1/operator/vehicles", w.tokenA).json["items"].values().associateBy { it["vehicle_label"].asString() }
        val a2 = items.getValue("A-2"); assertEquals("BLOCKED", a2["operational_state"].asString()); assertEquals(1, a2["active_blocks"].size()); assertEquals("OPERATIONS", a2["active_blocks"][0]["source"].asString())
        assertTrue(a2["position"].isNull); assertTrue(a2["position_stale"].asBoolean()); assertTrue(a2["position_age_seconds"].isNull); assertEquals(0, a2["orders"].size())
        val a1 = items.getValue("A-1"); assertEquals("RELEASE_PENDING", a1["operational_state"].asString()); assertTrue(a1["missing_requirements"].values().any { it["code"].asString() == "NO_INSPECTION" }, "fehlende Voraussetzungen nennen die Prüfung")
        assertEquals(w.serviceA.toString(), a1["service_ids"].values().single().asString())
        assertEquals(setOf(w.serviceA.toString(), w.serviceB.toString()), items.getValue("AB-1")["service_ids"].values().map { it.asString() }.toSet())
        val page = env.get("/v1/operator/vehicles", w.tokenA); assertTrue(page.json["next_cursor"].isNull); assertTrue(page.json["as_of"].asString().endsWith("Z"))
        // Leserecht: der Fahrer hat keinen Zugriff
        assertEquals(403, env.get("/v1/operator/vehicles", w.driverToken).status)
    }

    @Test
    fun dienstrecht_entzug_bei_offener_liste_entfernt_fahrzeuge() {
        val w = World(env)
        val before = env.get("/v1/operator/vehicles", w.tokenA); assertTrue(before.json["items"].values().any { it["vehicle_id"].asString() == w.va1.toString() })
        assertEquals(200, env.get("/v1/operator/vehicles/${w.va1}", w.tokenA).status)
        // Entzug des Dienstrechts A (der Token der offenen Sitzung bleibt gültig): ab dem Entzug keine Fahrzeuge dieses Dienstes mehr, weder in der Liste noch im Detail
        env.exec("UPDATE taktovia.membership SET service_scope = CAST('{}' AS uuid[]) WHERE tenant_id = ? AND id = ?", w.tenant.value, w.membershipA)
        assertEquals(listOf("N-1"), w.labels(w.tokenA))
        assertEquals(404, env.get("/v1/operator/vehicles/${w.va1}", w.tokenA).status)
        assertEquals(404, env.get("/v1/operator/vehicles/${w.vab}", w.tokenA).status)
        assertEquals(emptyList<String>(), w.labels(w.tokenA, "?service_id=${w.serviceA}"))
    }

    @Test
    fun fremder_mandant_und_fremder_dienst_404_und_manipulierter_filter_leer() {
        val w = World(env); val other = World(env)
        // Detail: fremder Dienst, fremder Mandant und unbekannt gleichermaßen 404 (nie 403 mit Existenzhinweis); das eigene Fahrzeug 200
        assertEquals(404, env.get("/v1/operator/vehicles/${w.vb}", w.tokenA).status)
        assertEquals(404, env.get("/v1/operator/vehicles/${other.va1}", w.tokenA).status)
        assertEquals(404, env.get("/v1/operator/vehicles/${UUID.randomUUID()}", w.tokenA).status)
        assertEquals(422, env.get("/v1/operator/vehicles/kein-uuid", w.tokenA).status, "kein UUID-Format: Formatfehler, kein Existenzhinweis")
        val own = env.get("/v1/operator/vehicles/${w.va1}", w.tokenA); assertEquals(200, own.status, own.text); assertEquals("A-1", own.json["vehicle_label"].asString())
        // Liste: Fahrzeuge anderer Mandanten erscheinen nie; manipulierter Filter auf fremden Dienst (auch eines anderen Mandanten) ergibt eine leere Liste, kein Fehler, keinen Hinweis
        assertFalse(env.get("/v1/operator/vehicles", w.tokenAll).text.contains(other.va1.toString()))
        assertEquals(emptyList<String>(), w.labels(w.tokenA, "?service_id=${other.serviceA}"))
        assertEquals(emptyList<String>(), w.labels(w.tokenA, "?service_id=${UUID.randomUUID()}"))
        assertEquals(422, env.get("/v1/operator/vehicles?service_id=egal", w.tokenA).status)
        assertEquals(422, env.get("/v1/operator/vehicles?open_incidents=vielleicht", w.tokenA).status)
        assertEquals(422, env.get("/v1/operator/vehicles?state=EGAL", w.tokenA).status)
        assertEquals(422, env.get("/v1/operator/vehicles?depot_id=${UUID.randomUUID()}", w.tokenA).status)
    }

    @Test
    fun limit_ueber_100_und_manipulierter_cursor_abgewiesen() {
        val w = World(env)
        for (bad in listOf("101", "0", "-1", "abc")) assertEquals(422, env.get("/v1/operator/vehicles?limit=$bad", w.tokenAll).status, "limit=$bad")
        assertEquals(200, env.get("/v1/operator/vehicles?limit=100", w.tokenAll).status)
        // Seitenfolge ohne Lücke und ohne Doppel (stabiler Sortierschlüssel), `next_cursor` bis zum Ende
        val seen = mutableListOf<String>(); var cursor: String? = null; var pages = 0
        do {
            val r = env.get("/v1/operator/vehicles?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""), w.tokenAll); assertEquals(200, r.status, r.text)
            seen += r.json["items"].values().map { it["vehicle_label"].asString() }; cursor = r.json["next_cursor"].takeIf { !it.isNull }?.asString(); pages++
        } while (cursor != null && pages < 10)
        assertEquals(listOf("A-1", "A-2", "AB-1", "B-1", "N-1"), seen.sorted()); assertEquals(5, seen.toSet().size); assertEquals(3, pages)
        // manipulierter, fremder und abgeschnittener Cursor: 400 INVALID_CURSOR, keine Daten
        val first = env.get("/v1/operator/vehicles?limit=1", w.tokenAll).json["next_cursor"].asString()
        val tampered = first.dropLast(2) + (if (first.takeLast(2) == "AA") "BB" else "AA")
        for (bad in listOf(tampered, first.take(10), "x", "v1.a.b")) { val r = env.get("/v1/operator/vehicles?cursor=$bad", w.tokenAll); assertEquals(400, r.status, bad); assertEquals("INVALID_CURSOR", r.json["code"].asString()) }
        assertEquals(400, env.get("/v1/operator/vehicles?cursor=$first", w.tokenA).status, "Cursor eines anderen Akteurs")
        // Cursor und Zustandsfilter: die gefilterte Seite bleibt vollständig, auch wenn Fahrzeuge dazwischen herausfallen
        env.exec("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (?,?,?,'OPERATIONS','MANUAL','user:t')", w.tenant.value, UUID.randomUUID(), w.vn)
        env.exec("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (?,?,?,'OPERATIONS','MANUAL','user:t')", w.tenant.value, UUID.randomUUID(), w.va1)
        val filtered = env.get("/v1/operator/vehicles?state=BLOCKED&limit=1", w.tokenAll); assertEquals(1, filtered.json["items"].size()); assertFalse(filtered.json["next_cursor"].isNull)
        val second = env.get("/v1/operator/vehicles?state=BLOCKED&limit=1&cursor=${filtered.json["next_cursor"].asString()}", w.tokenAll)
        assertEquals(setOf("A-1", "N-1"), setOf(filtered.json["items"][0]["vehicle_label"].asString(), second.json["items"][0]["vehicle_label"].asString())); assertTrue(second.json["next_cursor"].isNull)
    }

    @Test
    fun unterbrochener_positionsstrom_zeigt_veraltet_ohne_extrapolation() {
        val w = World(env)
        val s1 = w.shift(w.va1); val s2 = w.shift(w.va2)
        // A-1 sendet laufend (Messung vor 3 s), bei A-2 ist der Strom unterbrochen: letzte Messung vor 90 s; eine ältere Messung davor ändert „letzte Position“ nicht
        w.position(w.va1, s1, 3, 51.0500, 13.7400)
        w.position(w.va2, s2, 300, 51.0000, 13.0000); w.position(w.va2, s2, 90, 51.0300, 13.7000)
        val items = env.get("/v1/operator/vehicles", w.tokenA).json["items"].values().associateBy { it["vehicle_label"].asString() }
        val live = items.getValue("A-1"); assertFalse(live["position_stale"].asBoolean()); assertTrue(live["position_age_seconds"].asLong() in 2L..30L)
        val stale = items.getValue("A-2"); assertTrue(stale["position_stale"].asBoolean(), "nach 90 s veraltet")
        assertTrue(stale["position_age_seconds"].asLong() in 89L..120L, "Alter folgt der Messzeit")
        // die letzte gemessene Position bleibt sichtbar (keine fiktive Liveposition, keine Fortschreibung): exakt die Koordinaten der jüngsten Messung
        assertEquals(51.0300, stale["position"]["latitude"].asDouble(), 1e-6); assertEquals(13.7000, stale["position"]["longitude"].asDouble(), 1e-6)
        assertTrue(stale["position_measured_at"].asString() < stale["position_received_at"].asString() || stale["position_measured_at"].asString() == stale["position_received_at"].asString())
        // ohne jede Messung: keine Position, veraltet
        val none = items.getValue("AB-1"); assertTrue(none["position"].isNull); assertTrue(none["position_stale"].asBoolean())
        // das Detail liefert denselben Stand
        val d = env.get("/v1/operator/vehicles/${w.va2}", w.tokenA); assertEquals(200, d.status); assertTrue(d.json["position_stale"].asBoolean()); assertEquals(51.0300, d.json["position"]["latitude"].asDouble(), 1e-6)
    }

    @Test
    fun parallele_abrufe_zweier_mandanten_bleiben_getrennt() {
        val a = World(env); val b = World(env)
        val own = mapOf(a to a.tenant.value.toString(), b to b.tenant.value.toString())
        val pool = Executors.newFixedThreadPool(16)
        try {
            val jobs = (0 until 160).map { i ->
                val w = if (i % 2 == 0) a else b; val other = if (w === a) b else a
                pool.submit<Boolean> {
                    val r = env.get(if (i % 4 < 2) "/v1/operator/vehicles" else "/v1/operator/vehicles?limit=3", w.tokenAll)
                    val ids = r.json["items"].values().map { it["vehicle_id"].asString() }
                    r.status == 200 && ids.isNotEmpty() && ids.none { it in setOf(other.va1, other.va2, other.vb, other.vab, other.vn).map(UUID::toString) } && ids.all { it in setOf(w.va1, w.va2, w.vb, w.vab, w.vn).map(UUID::toString) }
                }
            }
            assertTrue(jobs.all { it.get(60, TimeUnit.SECONDS) }, "jede Antwort enthält nur Fahrzeuge des eigenen Mandanten (${own.size} Mandanten)")
        } finally { pool.shutdownNow() }
    }
}
