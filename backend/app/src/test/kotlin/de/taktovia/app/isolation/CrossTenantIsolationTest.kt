package de.taktovia.app.isolation

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * WP-M1-11 / LH-6.1-SYS-001, LH-I.4-MUL-007: Cross-Tenant-Scan über **alle gebauten M1-Endpunkte** der OpenAPI-Verträge (echte Anwendung, Runtime-Rolle, echte Datenbank).
 * Mandant A kennt Objekt-IDs von Mandant B und ruft jeden Endpunkt mit Pfadparameter (Detail, Änderung, Löschung) damit auf: die Antwort muss gleichartig zu der für
 * eine nicht vorhandene ID sein (404, gleicher Problemtyp, Code, Titel und Detail, ohne Fremdwerte). Listenendpunkte zeigen nie Objekte von B, und B bleibt danach unverändert.
 * Vollständigkeit: jeder gebaute Endpunkt mit Pfadparameter und jeder Listenweg muss in diesem Scan stehen, sonst schlägt der Test fehl (neue Endpunkte kommen so in den Scan).
 * Abgrenzung: Such-, Medien-, Beleg-, Export- und Finanzendpunkte entstehen mit M2/M4 und werden dann in denselben Scan aufgenommen (PH-AT-001 vollständig erst dann).
 */
class CrossTenantIsolationTest {
    private val env = IdentityEnv
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val pending: Set<String> = Files.readAllLines(repo.resolve("api/contract-pending.txt")).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
    private fun idem() = mapOf("Idempotency-Key" to "iso-" + UUID.randomUUID())

    private class Side(val tenant: TenantId, val admin: UUID, val adminToken: String, val callcenter: UUID, val ccToken: String, val passenger: UUID, val passengerToken: String,
        val planner: UUID, val plannerToken: String, val dispatcher: UUID, val dispatcherToken: String, val driver: UUID, val driverToken: String)

    private fun side(): Side {
        val t = env.tenant("Iso-" + UUID.randomUUID()); val a = UUID.randomUUID(); val c = UUID.randomUUID(); val p = UUID.randomUUID()
        env.grant(t, a, "tenant_admin"); env.grant(t, c, "callcenter"); env.grant(t, p, "passenger")
        val pl = UUID.randomUUID(); val di = UUID.randomUUID(); val dr = UUID.randomUUID()
        env.grant(t, pl, "staff_planning"); env.grant(t, di, "dispatcher"); env.grant(t, dr, "driver")
        return Side(t, a, env.token(a), c, env.token(c), p, env.token(p), pl, env.token(pl), di, env.token(di), dr, env.token(dr))
    }

    private class Ids(val map: Map<String, String>, val subject: String)

    /** Legt in B über die API je einen Bestand an (Region, Depot, Rechtsträger, Organisation, Dienst, Kostenstelle, Revision, Wirkungsbericht, Webhook, Fahrgast, Operation, Sitzung). */
    private fun populate(b: Side): Ids {
        fun create(path: String, body: String, token: String = b.adminToken): Resp = env.post(path, body, token, extra = idem()).also { check(it.status == 201) { "$path: ${it.text}" } }
        val region = create("/v1/regions", """{"name":"Region B"}""").json["id"].asString()
        val depot = create("/v1/depots", """{"region_id":"$region","name":"Hof B"}""").json["id"].asString()
        val le = create("/v1/legal-entities", """{"name":"Rechtstraeger B","country":"DE"}""").json["id"].asString()
        val org = create("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber B","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""").json["id"].asString()
        val service = create("/v1/services", """{"operator_id":"$org","name":"Rufbus B","timezone":"Europe/Berlin"}""").json["id"].asString()
        val cost = create("/v1/cost-centers", """{"code":"KST-B","name":"Kostenstelle B","legal_entity_id":"$le"}""").json["id"].asString()
        val rev = create("/v1/config/revisions", """{"scope_kind":"TENANT","scope_id":"${b.tenant}","schema_version":"1","values":{"max_wait_min":20},"effective_from":"2030-01-01T00:00:00Z","reason":"Bestand B"}""").json["id"].asString()
        val report = env.server.send("POST", "/v1/config/revisions/$rev/impact", null, env.headers(b.adminToken, extra = idem() + ("If-Match" to "\"1\""))).json["id"].asString()
        val hook = create("/v1/webhook-subscriptions", """{"url":"https://93.184.216.34/hook","event_types":["membership.granted"]}""").json["id"].asString()
        // Fahrgast registriert sich selbst (Registrierungstoken ohne Mitgliedschaft, wie PassengerContractCases)
        val enrol = env.idp.sign(env.idp.humanClaims(sub = UUID.randomUUID(), roles = listOf("passenger"), acr = "loa1", tenants = listOf(b.tenant.value), azp = "passenger-web").also { it["sid"] = "sid-" + UUID.randomUUID() })
        val passenger = create("/v1/passengers", """{"contact":{"channel":"EMAIL","value":"b.${UUID.randomUUID().toString().take(8)}@example.org"},"locale":"de-DE"}""", enrol).json["id"].asString()
        val operation = env.tx.run(b.tenant) { OperationService().enqueue(it, "TEST_COMMAND", Instant.now().plusSeconds(3600)) }.toString()
        val membership = env.get("/v1/memberships", b.adminToken).json["items"].values().first { it["subject"].asString() == b.passenger.toString() }["id"].asString()
        env.get("/v1/me", b.passengerToken)
        val session = env.get("/v1/me", b.passengerToken).json["session_id"].asString()
        // Fahrerfreigabe (Eigentümer B, Empfänger C), Fahrergerät und Nachricht in B
        val recipientTenant = env.tenant("Iso-Empf-" + UUID.randomUUID())
        val driverRef = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,'Erika Muster','PHONE','+491701234567')", b.tenant.value, driverRef)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,evidence_ref) VALUES (?,?,?,'PBEF',1,now()-interval '1 year','akte-1')", b.tenant.value, UUID.randomUUID(), driverRef)
        val release = create("/v1/driver-releases", """{"driver_id":"$driverRef","recipient_tenant_id":"${recipientTenant.value}","service_ids":["${UUID.randomUUID()}"],"valid_from":"2026-01-01T00:00:00Z","valid_to":"2099-01-01T00:00:00Z"}""", b.plannerToken).json["id"].asString()
        val deviceId = UUID.randomUUID().toString()
        create("/v1/driver/devices", """{"device_id":"$deviceId","platform":"ANDROID","app_version":"1.0.0"}""", b.driverToken)
        val recipient = UUID.randomUUID()
        val notification = env.tx.run(b.tenant) { j ->
            j.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (taktovia.current_tenant(),?,?,now() + interval '1 year')", recipient, "tok-$recipient")
            env.server.ctx.getBean(CommunicationsupportApi::class.java).notifications
                .send(j, NotificationRequest(UUID.randomUUID(), recipient, NotificationChannel.EMAIL, "booking.confirmed", null, mapOf("booking_reference" to "BK-1"))).notificationId
        }.toString()
        return Ids(mapOf(
            "release_id" to release, "device_id" to deviceId, "notification_id" to notification,
            "region_id" to region, "depot_id" to depot, "legal_entity_id" to le, "organization_id" to org, "service_id" to service, "cost_center_id" to cost,
            "revision_id" to rev, "impact_report_id" to report, "subscription_id" to hook, "passenger_id" to passenger, "operation_id" to operation,
            "membership_id" to membership, "session_id" to session,
        ), subject = b.passenger.toString())
    }

    /** Anfrage je Endpunkt mit Pfadparameter: Pfadvorlage, Body, Rolle des Aufrufers aus Mandant A. */
    private class Probe(val method: String, val path: String, val body: String? = null, val ifMatch: Boolean = false, val cc: Boolean = false, val purpose: Boolean = false, val role: String? = null)

    private fun probes(a: Side): Map<String, Probe> {
        val aRegion = env.post("/v1/regions", """{"name":"Region A"}""", a.adminToken, extra = idem()).json["id"].asString()
        val aLe = env.post("/v1/legal-entities", """{"name":"Rechtstraeger A","country":"DE"}""", a.adminToken, extra = idem()).json["id"].asString()
        val aOp = env.post("/v1/organizations", """{"legal_entity_id":"$aLe","name":"Betreiber A","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""", a.adminToken, extra = idem()).json["id"].asString()
        return mapOf(
            "publishConfig" to Probe("POST", "/v1/config/revisions/{revision_id}/publish", """{"effective_from":"2031-01-01T00:00:00Z","reason":"Fremdversuch","impact_report_id":"${UUID.randomUUID()}"}""", true),
            "revokeMembership" to Probe("DELETE", "/v1/memberships/{membership_id}", null, true),
            "updateLegalEntity" to Probe("PUT", "/v1/legal-entities/{legal_entity_id}", """{"name":"Gekapert","country":"DE"}""", true),
            "updateRegion" to Probe("PUT", "/v1/regions/{region_id}", """{"name":"Gekapert"}""", true),
            "updateDepot" to Probe("PUT", "/v1/depots/{depot_id}", """{"region_id":"$aRegion","name":"Gekapert"}""", true),
            "updateCostCenter" to Probe("PUT", "/v1/cost-centers/{cost_center_id}", """{"name":"Gekapert"}""", true),
            "revokeSession" to Probe("DELETE", "/v1/sessions/{session_id}", null, true),
            "getOrganization" to Probe("GET", "/v1/organizations/{organization_id}"),
            "updateOrganization" to Probe("PUT", "/v1/organizations/{organization_id}", """{"legal_entity_id":"$aLe","name":"Gekapert","roles":["CLIENT"]}""", true),
            "getOperation" to Probe("GET", "/v1/operations/{operation_id}"),
            "getPassengerSupportNeeds" to Probe("GET", "/v1/passengers/{passenger_id}/support-needs", cc = true, purpose = true),
            "getPassenger" to Probe("GET", "/v1/passengers/{passenger_id}", cc = true),
            "getConfigRevision" to Probe("GET", "/v1/config/revisions/{revision_id}"),
            "updateConfigRevision" to Probe("PATCH", "/v1/config/revisions/{revision_id}", """{"reason":"Gekapert"}""", true),
            "validateConfigRevision" to Probe("POST", "/v1/config/revisions/{revision_id}/validate", null, true),
            "approveConfigRevision" to Probe("POST", "/v1/config/revisions/{revision_id}/approve", null, true),
            "rollbackConfigRevision" to Probe("POST", "/v1/config/revisions/{revision_id}/rollback", """{"effective_from":"2031-01-01T00:00:00Z","reason":"Fremdversuch"}""", true),
            "requestConfigImpact" to Probe("POST", "/v1/config/revisions/{revision_id}/impact", null, true),
            "getConfigImpactReport" to Probe("GET", "/v1/config/impact-reports/{impact_report_id}"),
            "getServiceConfiguration" to Probe("GET", "/v1/services/{service_id}/configuration"),
            "updateService" to Probe("PUT", "/v1/services/{service_id}", """{"name":"Gekapert","timezone":"Europe/Berlin"}""", true),
            "revokeMySession" to Probe("DELETE", "/v1/me/sessions/{session_id}", null, true),
            "unlockAccount" to Probe("DELETE", "/v1/account-locks/{subject}", null, true),
            "getWebhookSubscription" to Probe("GET", "/v1/webhook-subscriptions/{subscription_id}"),
            "deactivateWebhookSubscription" to Probe("POST", "/v1/webhook-subscriptions/{subscription_id}/deactivate", null, true),
            "revokeDriverRelease" to Probe("DELETE", "/v1/driver-releases/{release_id}", null, true, role = "planner"),
            "getDriverReleaseProjection" to Probe("GET", "/v1/driver-releases/{release_id}/projection?service_id=${UUID.randomUUID()}", role = "dispatcher"),
            "revokeDriverDevice" to Probe("DELETE", "/v1/driver/devices/{device_id}", null, true, role = "driver"),
            "getNotification" to Probe("GET", "/v1/notifications/{notification_id}"),
        ).also { check(aOp.isNotEmpty()) }
    }

    /** Listenwege und mandantenbezogene Leseendpunkte ohne Pfadparameter: (Pfad, Rolle). Reine Eigen-Ressourcen (`/me`, Profil) und der globale Katalog der Angebotsmodelle kennen keine fremden Objekte. */
    private val listPaths = mapOf(
        "listMemberships" to "/v1/memberships", "listLegalEntities" to "/v1/legal-entities", "listRegions" to "/v1/regions", "listDepots" to "/v1/depots",
        "listCostCenters" to "/v1/cost-centers", "listOrganizations" to "/v1/organizations", "listServices" to "/v1/services", "listConfigRevisions" to "/v1/config/revisions",
        "listSessions" to "/v1/sessions", "listWebhookSubscriptions" to "/v1/webhook-subscriptions", "listVehicleRoles" to "/v1/vehicle-roles", "listAuditEvents" to "/v1/audit-events?purpose=security_review",
        "getEffectiveConfig" to "/v1/config/effective", "listPassengers" to "/v1/passengers", "listNotifications" to "/v1/notifications",
    )
    private val selfScoped = setOf(
        "getMe", "listServiceOfferModels", "getCompanyProfile", "getMyProfile", "getMyPrivacySettings", "getMyTransportSupport", "listMySessions", "listConfigParameters",
    )

    /**
     * Per Signatur (HMAC) authentifizierter Provider-Eingang ohne Mandantentoken: der Mandant wird aus dem verifizierten Signaturschlüssel/der Konfiguration abgeleitet,
     * der Pfadparameter `{provider}` ist kein Mandantenobjekt (kein fremdes Objekt adressierbar). Isolation ist nachgewiesen durch
     * `payment/ProviderEventApiTest.manipulierte_tenant_id_ist_wirkungslos_der_mandant_folgt_dem_schluessel` (und `PspSimulatorWebhookTest`).
     */
    private val providerAuthenticated = setOf("receiveProviderEvent")

    private fun normalized(r: Resp): String {
        val j = r.json
        return listOf(r.status, j["type"]?.asString(), j["code"]?.asString(), j["title"]?.asString(), j["detail"]?.asString()).joinToString("|")
    }

    private fun call(a: Side, p: Probe, path: String, token: String = when { p.role == "planner" -> a.plannerToken; p.role == "dispatcher" -> a.dispatcherToken; p.role == "driver" -> a.driverToken; p.cc -> a.ccToken; else -> a.adminToken }): Resp =
        env.server.send(p.method, path, p.body, env.headers(token, extra = (if (p.method != "GET") idem() else emptyMap()) + (if (p.ifMatch) mapOf("If-Match" to "\"1\"") else emptyMap()) + (if (p.purpose) mapOf("X-Access-Purpose" to "BOOKING_ASSISTANCE") else emptyMap())))

    private fun snapshot(b: Side): String = listOf("/v1/regions", "/v1/depots", "/v1/legal-entities", "/v1/organizations", "/v1/services", "/v1/cost-centers", "/v1/config/revisions", "/v1/memberships", "/v1/webhook-subscriptions")
        .joinToString("\n") { env.get(it, b.adminToken).text.replace(Regex("\"as_of\":\"[^\"]*\""), "") }

    @Test
    fun fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen() {
        val a = side(); val b = side(); val ids = populate(b); val probes = probes(a)
        val registry = env.server.registry
        val built = registry.operations.filter { it.operationId !in pending && it.path.startsWith("/v1/") }
        // Vollständigkeit: jeder gebaute Endpunkt mit Pfadparameter steht im Scan, jeder Listenweg ebenfalls
        val withParams = built.filter { '{' in it.path }.map { it.operationId }.toSet()
        assertEquals(emptySet<String>(), withParams - probes.keys - providerAuthenticated, "gebaute Endpunkte mit Pfadparameter ohne Isolationsfall (CrossTenantIsolationTest.probes ergänzen)")
        val noParams = built.filter { '{' !in it.path && it.method == "GET" }.map { it.operationId }.toSet()
        assertEquals(emptySet<String>(), noParams - listPaths.keys - selfScoped, "gebaute Leseendpunkte ohne Pfadparameter ohne Isolationsfall")
        val before = snapshot(b)

        for ((op, probe) in probes) {
            val params = Regex("\\{(\\w+)}").findAll(probe.path).map { it.groupValues[1] }.toList()
            fun fill(value: (String) -> String) = params.fold(probe.path) { acc, p -> acc.replace("{$p}", value(p)) }
            val foreign = call(a, probe, fill { p -> if (p == "subject") ids.subject else ids.map.getValue(p) })
            val missing = call(a, probe, fill { p -> if (p == "subject") UUID.randomUUID().toString() else UUID.randomUUID().toString() })
            assertEquals(404, missing.status, "$op: nicht vorhandene ID: ${missing.text}")
            assertEquals(normalized(missing), normalized(foreign), "$op: fremde und nicht vorhandene ID müssen gleichartig beantwortet werden")
            ids.map.values.forEach { assertFalse(foreign.text.contains(it), "$op: Antwort enthält eine Kennung aus Mandant B") }
            assertFalse(foreign.text.contains(b.tenant.toString()), "$op: Antwort nennt den Mandanten B")
        }

        // Listen: nie Objekte oder Kennungen von B
        for ((op, path) in listPaths) {
            val r = env.get(path, if (op == "listPassengers") a.ccToken else a.adminToken)
            assertTrue(r.status == 200 || r.status == 403, "$op: ${r.status} ${r.text}")
            if (r.status == 200) {
                ids.map.filterKeys { it != "session_id" }.values.forEach { assertFalse(r.text.contains(it), "$op: Liste von A enthält Kennung aus B") }
                assertFalse(r.text.contains(b.tenant.toString()), "$op: Liste von A nennt Mandant B")
            }
        }
        assertEquals(before, snapshot(b), "Mandant B ist nach allen Fremdversuchen unverändert (Änderung und Löschung wirkungslos)")
    }

    @Test
    fun manipulierte_mandantenangabe_wird_nicht_beachtet_und_sicherheitsereignis_enthaelt_keine_b_daten() {
        val a = side(); val b = side(); val ids = populate(b)
        val before = env.events(a.tenant, "security.tenant.mismatch")
        val attempts = listOf(
            env.get("/v1/regions", a.adminToken, extra = mapOf("X-Tenant-Id" to b.tenant.toString())),
            env.get("/v1/regions?tenant_id=${b.tenant}", a.adminToken),
            env.get("/v1/memberships", a.adminToken, extra = mapOf("Tenant-Id" to b.tenant.toString())),
        )
        attempts.forEach { assertEquals(404, it.status, it.text); assertFalse(it.text.contains(b.tenant.toString())) }
        assertEquals(before + attempts.size, env.events(a.tenant, "security.tenant.mismatch"), "je Fremdzugriff ein Sicherheitsereignis im Mandanten des Akteurs")
        assertEquals(0, env.events(b.tenant, "security.tenant.mismatch"), "nichts im Mandanten B")
        // Inhalt der Sicherheitsereignisse: weder Kennungen noch Personendaten von B
        val admin = TaktoviaTestSupport.adminDataSourceOrNull()!!
        admin.connection.use { c ->
            c.prepareStatement("SELECT actor_ref, object_ref, redacted_changes::text FROM taktovia.audit_event WHERE tenant_id=? AND action LIKE 'security.%'").use { ps ->
                ps.setObject(1, a.tenant.value)
                ps.executeQuery().use { rs ->
                    var n = 0
                    while (rs.next()) {
                        n++
                        val row = (1..3).joinToString(" ") { rs.getString(it) }
                        val foreignValues = ids.map.values + listOf(b.tenant.toString(), b.admin.toString(), b.passenger.toString(), b.callcenter.toString())
                        foreignValues.forEach { assertFalse(row.contains(it), "Sicherheitsereignis enthält Daten von Mandant B: $it") }
                    }
                    assertTrue(n >= attempts.size)
                }
            }
        }
    }

    @Test
    fun a_und_b_arbeiten_parallel_und_unabhaengig() {
        val a = side(); val b = side()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val futures = (1..24).map { i ->
            val s = if (i % 2 == 0) a else b
            pool.submit<Pair<TenantId, String>> { s.tenant to env.post("/v1/regions", """{"name":"R-$i"}""", s.adminToken, extra = idem()).json["id"].asString() }
        }
        val created = futures.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }; pool.shutdown()
        for (s in listOf(a, b)) {
            val own = created.filter { it.first == s.tenant }.map { it.second }.toSet()
            val listed = env.get("/v1/regions?limit=100", s.adminToken).json["items"].values().map { it["id"].asString() }.toSet()
            assertEquals(own, listed, "jeder Mandant sieht genau die eigenen Regionen")
        }
    }
}
