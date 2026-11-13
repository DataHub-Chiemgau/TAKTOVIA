package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReleaseRevokeReason
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-09 / LH-6.21-CHK-001 (Readiness-Gate) über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): ohne gültige, abgeschlossene und serverbestätigte Vor-Schicht-Prüfung dieser
 * Kombination (Fahrer, Fahrzeug, Schicht) gibt es weder Bereitstatus noch ausführbare Aufträge. Negativ: AT-26 (Fahrzeug ohne Prüfung verfügbar schalten), manipulierter Client (Freigabefelder im Body),
 * offener Lauf, Prüfung einer anderen Schicht. Positiv: nach bestätigter Prüfung ist die Bereitmeldung möglich (AT-27 gemeinsam mit WP-M3-14). Konkurrierend: Bereitmeldung parallel zum Widerruf
 * der Freigabe, zwei gleichzeitige Bereitmeldungen. Die Fahrer-App (Flutter) ist nicht beteiligt (nicht ausgeführt); Gegenstelle ist der echte Server.
 */
class ReadinessGateApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    /** Fahrer mit Qualifikation, freigegebener Vorlage, Fahrzeug und angetretener Schicht (echter Dienstantritt über die Schnittstelle). */
    private class Scene(val env: IdentityEnv, qualified: Boolean = true) {
        val w = InspectionApiTest.World(env)
        val vehicle: UUID; val shift: UUID; val device = UUID.randomUUID()
        init {
            if (qualified) {
                env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
                env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
            }
            w.template("PKW", steps = 3)
            vehicle = w.vehicle("PKW"); shift = w.shift(vehicle = vehicle, started = false)
            check(env.post("/v1/driver/devices", """{"device_id":"$device","platform":"ANDROID","app_version":"1.0.0"}""", w.token, extra = mapOf("Idempotency-Key" to "d-" + UUID.randomUUID())).status == 201)
            val start = env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicle","device_id":"$device"}""", w.token, extra = mapOf("Idempotency-Key" to "st-" + UUID.randomUUID()))
            check(start.status == 200) { "Antritt: ${start.status} ${start.text}" }
        }
        fun ready(body: String? = null, token: String = w.token) = env.post("/v1/driver/shifts/$shift/service-status",
            body ?: """{"event_id":"${UUID.randomUUID()}","target_status":"AVAILABLE","occurred_at":"${Instant.now()}","device_recorded_at":"${Instant.now()}","offline_recorded":false}""", token, extra = mapOf("Idempotency-Key" to "r-" + UUID.randomUUID()))
        fun run(token: String = w.token) = env.get("/v1/driver/shifts/$shift/run", token)
        fun startInspection() = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, mapOf("Idempotency-Key" to "i-" + UUID.randomUUID())).also { check(it.status == 201) { it.text } }.json["id"].asString()
        fun submit(id: String, revision: Int = 1) = env.server.send("POST", "/v1/inspections/$id/submit", """{"answers":[${(1..3).joinToString(",") { """{"step_code":"S$it","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"}""" }}],"client_submitted_at":"${Instant.now().minusSeconds(2)}"}""",
            env.headers(w.token, extra = mapOf("Idempotency-Key" to "s-" + UUID.randomUUID(), "If-Match" to "\"$revision\"")), "application/json")
        fun refusals(): List<String> = env.string("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.readiness.refused' AND object_ref = ? ORDER BY occurred_at", w.tenant.value, "staff_shift:$shift")
        fun status(): String = env.get("/v1/driver/shifts/$shift/service-status", w.token).json["status"].asString()
    }

    @Test
    fun bereitmeldung_ohne_pruefung_wird_serverseitig_abgewiesen() {
        val s = Scene(env)
        // AT-26: Fahrzeug ohne Prüfung verfügbar schalten: serverseitig abgewiesen, Status bleibt NOT_READY, die fehlende Voraussetzung steht im Problem
        val r = s.ready()
        assertEquals(409, r.status, r.text); assertEquals("INVALID_STATE_TRANSITION", r.json["code"].asString()); assertTrue(r.text.contains("NO_INSPECTION"), "Problem nennt die fehlende Voraussetzung: ${r.text}")
        assertEquals("NOT_READY", s.status())
        // der abgewiesene Versuch steht mit Akteur und fehlender Voraussetzung im Audit (Prüfspur committet, obwohl die Anfrage abgewiesen wurde)
        val audit = s.refusals(); assertEquals(1, audit.size); assertTrue(audit[0].contains("NO_INSPECTION") && audit[0].contains(s.vehicle.toString()))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'fleet.readiness.refused' AND actor_ref = ?", s.w.tenant.value, "user:${s.w.account}"))
        // manipulierter Client: Freigabe und Prüfergebnis im Body gelten nicht (unbekanntes Feld, 422), und „bereit“ ohne Prüfung bleibt abgewiesen
        val forged = s.ready("""{"event_id":"${UUID.randomUUID()}","target_status":"AVAILABLE","occurred_at":"${Instant.now()}","device_recorded_at":"${Instant.now()}","offline_recorded":false,"release_state":"RELEASED","inspection_ref":"${UUID.randomUUID()}"}""")
        assertEquals(422, forged.status, forged.text); assertEquals("NOT_READY", s.status())
        // Aufträge erst nach Einsatzfreigabe: vorher nur Dienst, Fahrzeug und fehlende Voraussetzungen
        val run = s.run(); assertEquals(200, run.status); assertEquals("NOT_RELEASED", run.json["release_state"].asString()); assertEquals(0, run.json["stops"].size()); assertEquals("NO_INSPECTION", run.json["missing_requirements"][0]["code"].asString())
        // die Leitstelle sieht das Fahrzeug als nicht einsatzbereit mit Grund (UI-07)
        val v = env.get("/v1/operator/vehicles/${s.vehicle}", s.w.dispatcherToken); assertEquals(200, v.status, v.text)
        assertEquals("RELEASE_PENDING", v.json["operational_state"].asString()); assertTrue(v.json["missing_requirements"].values().any { it["code"].asString() == "NO_INSPECTION" })
        // fremder Mandant: Schicht nicht vorhanden
        val other = Scene(env); assertEquals(404, env.post("/v1/driver/shifts/${s.shift}/service-status", """{"event_id":"${UUID.randomUUID()}","target_status":"AVAILABLE","occurred_at":"${Instant.now()}","device_recorded_at":"${Instant.now()}","offline_recorded":false}""", other.w.token, extra = idem()).status)
    }

    @Test
    fun pruefung_anderer_kombination_oder_offener_lauf_erfuellt_nicht() {
        val s = Scene(env)
        // offener Lauf: Prüfung nicht abgeschlossen, die offenen Pflichtschritte stehen in der Antwort (AT-26: Liste fehlender Prüfschritte)
        val id = s.startInspection()
        val open = s.run(); assertEquals("INSPECTION_OPEN", open.json["missing_requirements"][0]["code"].asString())
        val refused = s.ready(); assertEquals(409, refused.status); assertTrue(refused.text.contains("INSPECTION_OPEN"))
        val v = env.get("/v1/operator/vehicles/${s.vehicle}", s.w.dispatcherToken).json
        val m = v["missing_requirements"].values().single { it["code"].asString() == "INSPECTION_OPEN" }
        assertEquals(id, m["inspection_id"].asString()); assertEquals(listOf("S1", "S2", "S3"), m["step_codes"].values().map { it.asString() })
        // eine bestandene Prüfung einer anderen Schicht (anderes Fahrzeug, anderer Fahrer) erfüllt die Bedingung nicht
        val foreign = Scene(env); val fid = foreign.startInspection(); assertEquals(200, foreign.submit(fid).status)
        assertEquals(409, s.ready().status, "Prüfung einer anderen Kombination gilt nicht")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", foreign.w.tenant.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", s.w.tenant.value))
    }

    @Test
    fun nach_bestaetigter_pruefung_ist_die_bereitmeldung_moeglich_und_auftraege_werden_ausfuehrbar() {
        val s = Scene(env)
        val id = s.startInspection(); val done = s.submit(id); assertEquals(200, done.status, done.text); assertFalse(done.json["release"].isNull)
        val run = s.run(); assertEquals("RELEASED", run.json["release_state"].asString()); assertEquals(0, run.json["missing_requirements"].size())
        val r = s.ready(); assertEquals(200, r.status, r.text); assertEquals("AVAILABLE", r.json["status"]["status"].asString()); assertEquals("AVAILABLE", s.status())
        val v = env.get("/v1/operator/vehicles/${s.vehicle}", s.w.dispatcherToken).json
        assertEquals("AVAILABLE", v["operational_state"].asString()); assertEquals(0, v["missing_requirements"].size()); assertFalse(v["release_valid_until"].isNull)
        assertEquals(0, s.refusals().size)
    }

    @Test
    fun bereitmeldung_parallel_zu_freigabewiderruf() {
        val fleet = env.server.ctx.getBean(FleetsafetyApi::class.java)
        repeat(10) { round ->
            val s = Scene(env); val id = s.startInspection(); assertEquals(200, s.submit(id).status)
            val release = env.string("SELECT id::text FROM taktovia.resource_release WHERE tenant_id = ? AND shift_id = ?", s.w.tenant.value, s.shift).single()
            val op = RequestContext(Actor(ActorType.SERVICE, IdentityEnv.idp.issuer, "svc-readiness", "sid-svc", false), s.w.tenant, emptySet(), emptySet(), emptySet(), UUID.randomUUID())
            val pool = Executors.newFixedThreadPool(3); val go = CountDownLatch(1)
            val ready1 = pool.submit(Callable { go.await(); s.ready() }); val ready2 = pool.submit(Callable { go.await(); s.ready() })
            val revoke = pool.submit(Callable { go.await(); fleet.readiness.revokeRelease(op, UUID.fromString(release), ReleaseRevokeReason.MANUAL) })
            go.countDown(); val a = ready1.get(60, TimeUnit.SECONDS); val b = ready2.get(60, TimeUnit.SECONDS); revoke.get(60, TimeUnit.SECONDS); pool.shutdown()
            // zwei gleichzeitige Bereitmeldungen: höchstens eine wirkt, jede Antwort ist 200 oder 409; es bleibt bei genau einer Freigabe (widerrufen)
            assertTrue(a.status in setOf(200, 409) && b.status in setOf(200, 409), "Runde $round: ${a.status}/${b.status}")
            assertTrue(listOf(a, b).count { it.status == 200 && it.json["state"].asString() == "APPLIED" } <= 1, "Runde $round: höchstens ein Wechsel")
            assertEquals(1, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ? AND shift_id = ?", s.w.tenant.value, s.shift))
            // nach dem Widerruf ist das Fahrzeug in jeder Serialisierung nicht mehr freigegeben: eine neue Bereitmeldung nach AVAILABLE ist ausgeschlossen, die Anzeige nennt den Widerruf
            val after = s.run(); assertEquals("NOT_RELEASED", after.json["release_state"].asString(), "Runde $round"); assertEquals("RELEASE_REVOKED", after.json["missing_requirements"][0]["code"].asString())
            val applied = env.string("SELECT to_status FROM taktovia.vehicle_status_history WHERE tenant_id = ? AND shift_id = ? AND to_status = 'AVAILABLE'", s.w.tenant.value, s.shift).size
            if (applied == 1) {
                // die Bereitmeldung gewann: sie lief vor dem Widerruf (Serialisierung über die Fahrzeugzeile); die Freigabe war damals gültig
                assertTrue(env.count("SELECT count(*) FROM taktovia.vehicle_status_history h JOIN taktovia.resource_release r ON r.tenant_id = h.tenant_id AND r.shift_id = h.shift_id WHERE h.tenant_id = ? AND h.shift_id = ? AND h.to_status = 'AVAILABLE' AND h.created_at <= r.revoked_at", s.w.tenant.value, s.shift) == 1, "Runde $round: AVAILABLE nur vor dem Widerruf")
            } else assertEquals(0, applied)
            assertEquals(if (applied == 1) "AVAILABLE" else "NOT_READY", s.status())
        }
    }
}
