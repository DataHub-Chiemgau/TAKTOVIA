package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-16 / LH-6.21-CHK-004 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): „Driverprüfpfad mit kritischem Nein“ (Kap. 06 §6.9) gegen den Server. Das kritische Nein sperrt das
 * Fahrzeug (oder die Schicht) im selben Commit, die App-Antwort trägt Sperrart, Vorgangsnummer und Kontakt zur Leitstelle, die Leitstelle sieht „gesperrt“, die Bereitmeldung scheitert, genau ein Alarm je
 * Sperre; der Fahrer hat keinen Weg zum Aufheben. WP-M3-12: eingeschränktes Ergebnis über die Schnittstelle. Die Fahrer-App (Flutter, Screenreader) ist nicht beteiligt (nicht ausgeführt).
 */
class CriticalDefectBlockApiTest {
    private val env = IdentityEnv

    /** Welt mit qualifiziertem Fahrer, Vorlage (BREMSEN kritisch mit Sperrart, SCHEIBEN unkritisch, optional Rampenpunkte), Fahrzeug und angetretener Schicht. */
    private class Scene(val env: IdentityEnv, blockKind: String? = null, equipment: Boolean = false) {
        val w = InspectionApiTest.World(env)
        val vehicle: UUID; val shift: UUID; val device = UUID.randomUUID()
        init {
            env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
            env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
            val tpl = UUID.randomUUID(); val t = w.tenant.value
            env.exec("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) VALUES (?,?,?,1,'PKW','STANDARD','V','user:a')", t, tpl, UUID.randomUUID())
            env.exec("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,block_kind,answer_kind,created_by) VALUES (?,?,?,'BREMSEN',1,'Bremsen',true,true,?,'OK_DEFECT_NA','user:a')", t, UUID.randomUUID(), tpl, blockKind)
            env.exec("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (?,?,?,'SCHEIBEN',2,'Scheiben',true,false,'OK_DEFECT_NA','user:a')", t, UUID.randomUUID(), tpl)
            if (equipment) env.exec("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,equipment_condition,restricts_dimension,created_by) VALUES (?,?,?,'RAMPE',3,'Rampe',true,false,'OK_DEFECT_NA','RAMP_OR_LIFT','wheelchair','user:a')", t, UUID.randomUUID(), tpl)
            env.exec("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by='user:b', revision=revision+1 WHERE tenant_id=? AND id=?", t, tpl)
            vehicle = w.vehicle("PKW")
            if (equipment) env.exec("UPDATE taktovia.vehicle SET equipment = ARRAY['RAMP']::text[], capacity_config = CAST(? AS jsonb) WHERE id = ?", """{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}""", vehicle)
            shift = w.shift(vehicle = vehicle, started = false)
            check(env.post("/v1/driver/devices", """{"device_id":"$device","platform":"ANDROID","app_version":"1.0.0"}""", w.token, extra = mapOf("Idempotency-Key" to "d-" + UUID.randomUUID())).status == 201)
            check(env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicle","device_id":"$device"}""", w.token, extra = mapOf("Idempotency-Key" to "st-" + UUID.randomUUID())).status == 200)
        }
        fun start() = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, mapOf("Idempotency-Key" to "i-" + UUID.randomUUID())).also { check(it.status == 201) { it.text } }.json["id"].asString()
        fun body(vararg answers: Pair<String, String>) = """{"answers":[${answers.joinToString(",") { (c, a) -> """{"step_code":"$c","answer":"$a"${if (a == "DEFECT") ""","note":"Befund"""" else ""},"answered_at":"${Instant.now().minusSeconds(20)}"}""" }}],"client_submitted_at":"${Instant.now().minusSeconds(2)}"}"""
        fun submitBody(id: String, body: String, key: String = "s-" + UUID.randomUUID()) = env.server.send("POST", "/v1/inspections/$id/submit", body,
            env.headers(w.token, extra = mapOf("Idempotency-Key" to key, "If-Match" to "\"1\"")), "application/json")
        fun submit(id: String, vararg answers: Pair<String, String>, key: String = "s-" + UUID.randomUUID()) = submitBody(id, body(*answers), key)
        fun ready() = env.post("/v1/driver/shifts/$shift/service-status", """{"event_id":"${UUID.randomUUID()}","target_status":"AVAILABLE","occurred_at":"${Instant.now()}","device_recorded_at":"${Instant.now()}","offline_recorded":false}""", w.token, extra = mapOf("Idempotency-Key" to "r-" + UUID.randomUUID()))
        fun run() = env.get("/v1/driver/shifts/$shift/run", w.token)
        fun operatorVehicle() = env.get("/v1/operator/vehicles/$vehicle", w.dispatcherToken)
        fun alerts(): Int = env.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ? AND vehicle_id = ? AND alert_kind = 'VEHICLE_BLOCK_CRITICAL'", w.tenant.value, vehicle)
    }

    @Test
    fun kritisches_nein_ueber_die_schnittstelle_sperrt_zeigt_vorgangsnummer_und_kontakt() {
        val s = Scene(env)
        // Kontakt zur Leitstelle: Telefonnummer des Unternehmensprofils des Mandanten
        val admin = UUID.randomUUID(); env.grant(s.w.tenant, admin, "tenant_admin")
        val put = env.put("/v1/company-profile", """{"legal_name":"Test GmbH","contact_email":"info@example.org","contact_phone":"+49 351 123456","default_currency":"EUR"}""", env.token(admin), "\"0\"")
        assertEquals(200, put.status, put.text)
        val id = s.start()
        val r = s.submit(id, "BREMSEN" to "DEFECT", "SCHEIBEN" to "OK")
        assertEquals(200, r.status, r.text); assertEquals("BLOCKED", r.json["outcome"].asString()); assertTrue(r.json["release"].isNull)
        val b = r.json["block"]; assertEquals("VEHICLE", b["block_kind"].asString()); assertTrue(Regex("^SPE-[0-9]{4}-[0-9]{6,}$").matches(b["case_number"].asString())); assertEquals("+49 351 123456", b["dispatch_contact"].asString())
        assertEquals(listOf("BREMSEN"), b["step_codes"].values().map { it.asString() })
        // Sperre und Ergebnis im selben Commit: lesen liefert dasselbe; keine gültige Einsatzfreigabe
        val g = env.get("/v1/inspections/$id", s.w.token); assertEquals("BLOCKED", g.json["outcome"].asString()); assertEquals(b["case_number"].asString(), g.json["block"]["case_number"].asString())
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ? AND inspection_ref = ?", s.w.tenant.value, UUID.fromString(id)))
        // Bereitmeldung scheitert (Server weist ab), der Fahrerlauf zeigt die Sperre statt Aufträgen
        val ready = s.ready(); assertEquals(409, ready.status, ready.text); assertTrue(ready.text.contains("DEFECT_BLOCK"))
        val run = s.run(); assertEquals("BLOCKED", run.json["release_state"].asString()); assertEquals("DEFECT_BLOCK", run.json["missing_requirements"][0]["code"].asString()); assertEquals(0, run.json["stops"].size())
        // die Leitstelle sieht „gesperrt · kritischer Mangel“ mit Vorgangsnummer (UI-07) und genau einen Alarm je Sperre
        val v = s.operatorVehicle().json; assertEquals("BLOCKED", v["operational_state"].asString())
        val blk = v["active_blocks"][0]; assertEquals("INSPECTION_FAILED", blk["reason_code"].asString()); assertEquals("SAFETY", blk["source"].asString()); assertEquals(b["case_number"].asString(), blk["case_number"].asString()); assertEquals("VEHICLE", blk["block_kind"].asString())
        assertEquals(1, s.alerts())
        val alerts = env.get("/v1/operator/alerts", s.w.dispatcherToken).json["items"].values().filter { it["kind"].asString() == "VEHICLE_BLOCK_CRITICAL" }; assertEquals(1, alerts.size); assertEquals("CRITICAL", alerts[0]["priority"].asString())
        assertFalse(alerts[0].toString().contains("Befund"), "der Alarm enthält keine Diagnosetexte")
        // der Fahrer hat keinen Weg zum Aufheben: der Sperrendpunkt der Leitstelle ist für ihn nicht erreichbar, die Sperre bleibt aktiv
        val lift = env.post("/v1/operator/vehicles/${s.vehicle}/blocks/${UUID.randomUUID()}/lift", """{"reason":"ok"}""", s.w.token, null, mapOf("Idempotency-Key" to "l-" + UUID.randomUUID(), "If-Match" to "\"1\""))
        assertTrue(lift.status in 400..499, "Fahrer-Aufhebeversuch: ${lift.status}"); assertNotEquals(200, lift.status)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id = ? AND vehicle_id = ? AND status = 'ACTIVE'", s.w.tenant.value, s.vehicle))
        // Wiederholung mit demselben Schlüssel liefert dieselbe Antwort, ohne zweite Sperre und ohne zweiten Alarm
        val key = "wdh-" + UUID.randomUUID(); val s2 = Scene(env); val id2 = s2.start()
        val same = s2.body("BREMSEN" to "DEFECT", "SCHEIBEN" to "OK"); val first = s2.submitBody(id2, same, key); val again = s2.submitBody(id2, same, key)
        assertEquals(200, first.status); assertEquals(200, again.status); assertEquals(first.json["block"]["case_number"].asString(), again.json["block"]["case_number"].asString())
        assertEquals(1, s2.alerts()); assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id = ? AND vehicle_id = ?", s2.w.tenant.value, s2.vehicle))
    }

    @Test
    fun unkritischer_mangel_sperrt_nicht_und_gibt_frei() {
        val s = Scene(env); val id = s.start()
        val r = s.submit(id, "BREMSEN" to "OK", "SCHEIBEN" to "DEFECT")
        assertEquals(200, r.status, r.text); assertEquals("PASSED", r.json["outcome"].asString()); assertTrue(r.json["block"].isNull); assertFalse(r.json["release"].isNull)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id = ?", s.w.tenant.value)); assertEquals(0, s.alerts())
        assertEquals(200, s.ready().status)
    }

    @Test
    fun dienstsperre_ueber_die_schnittstelle_sperrt_nur_die_schicht() {
        val s = Scene(env, blockKind = "SERVICE"); val id = s.start()
        val r = s.submit(id, "BREMSEN" to "DEFECT", "SCHEIBEN" to "OK")
        assertEquals(200, r.status, r.text); assertEquals("SERVICE", r.json["block"]["block_kind"].asString()); assertTrue(r.json["release"].isNull)
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE tenant_id = ?", s.w.tenant.value)); assertEquals(1, env.count("SELECT count(*) FROM taktovia.shift_block WHERE tenant_id = ? AND shift_id = ?", s.w.tenant.value, s.shift))
        assertEquals(409, s.ready().status); assertEquals("BLOCKED", s.run().json["release_state"].asString())
        val v = s.operatorVehicle().json; assertEquals("BLOCKED", v["operational_state"].asString()); assertEquals("SERVICE", v["active_blocks"][0]["block_kind"].asString()); assertEquals(s.shift.toString(), v["active_blocks"][0]["shift_id"].asString())
        assertEquals(1, s.alerts())
    }

    @Test
    fun defekte_rampe_ueber_die_schnittstelle_ergibt_eingeschraenkt() {
        val s = Scene(env, equipment = true); val id = s.start()
        assertEquals(listOf("BREMSEN", "SCHEIBEN", "RAMPE"), env.get("/v1/inspections/$id", s.w.token).json["template"]["steps"].values().map { it["step_code"].asString() })
        val r = s.submit(id, "BREMSEN" to "OK", "SCHEIBEN" to "OK", "RAMPE" to "DEFECT")
        assertEquals(200, r.status, r.text); assertEquals("PASSED_RESTRICTED", r.json["outcome"].asString()); assertFalse(r.json["release"].isNull)
        assertEquals("wheelchair", r.json["restriction"]["unavailable_dimensions"][0].asString()); assertEquals("RAMPE", r.json["restriction"]["step_codes"][0].asString())
        // bereit melden ist möglich (andere Fahrten bleiben möglich); die Leitstelle sieht „eingeschränkt“ mit der entfallenen Dimension
        assertEquals(200, s.ready().status)
        val v = s.operatorVehicle().json; assertEquals("RESTRICTED", v["operational_state"].asString()); assertEquals("wheelchair", v["unavailable_dimensions"][0].asString())
        // ohne Rampe/Lift keine Ausstattungspunkte (anderes Fahrzeug derselben Klasse): Antwort auf RAMPE wird abgewiesen
        val plain = Scene(env); val pid = plain.start()
        assertEquals(listOf("BREMSEN", "SCHEIBEN"), env.get("/v1/inspections/$pid", plain.w.token).json["template"]["steps"].values().map { it["step_code"].asString() })
    }
}
