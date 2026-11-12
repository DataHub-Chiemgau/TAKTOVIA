package de.taktovia.app.staff

import de.taktovia.app.fleet.InspectionApiTest
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-11 / WP-M4-12 (LH-6.24-TIM-002, -003) über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): AT-45 mit echter Vor-Schicht-Checkliste (Stempeln vor der
 * Fahrzeugfreigabe, Prüfung innerhalb der begonnenen Arbeitszeit, Pause, Ausstempeln, Auswertung), 401 ohne Sitzung, 403 bei fremder Personen-ID, Konflikt bei doppeltem Einstempeln,
 * Offline-Übernahme mit Ergebniscodes `APPLIED`/`DUPLICATE`/`REVIEW_REQUIRED` auch nach Ablauf des Idempotenzschlüssels und bei parallelen Sync-Läufen.
 */
class TimeEventStempelnApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())
    private val base: Instant = Instant.now().minusSeconds(3 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS)

    private fun body(kind: String, min: Long, id: UUID = UUID.randomUUID(), origin: String? = null, shift: UUID? = null, person: String? = null, device: Instant? = null): String {
        val at = base.plusSeconds(min * 60)
        return """{"event_id":"$id","kind":"$kind","occurred_at":"$at","device_recorded_at":"${device ?: at.plusSeconds(1)}","channel":"APP","shift_id":${shift?.let { "\"$it\"" } ?: "null"}""" +
            (origin?.let { ""","origin":"$it"""" } ?: "") + (person?.let { ""","person_ref":"$it"""" } ?: "") + "}"
    }

    private fun qualify(w: InspectionApiTest.World) {
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
        env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
    }

    @Test
    fun einstempeln_vor_fahrzeugfreigabe_und_checkliste_innerhalb_arbeitszeit() {
        val w = InspectionApiTest.World(env); qualify(w); w.template("PKW", steps = 2)
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle)
        fun release() = env.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id=?", w.tenant.value)
        // 1. Einstempeln vor der Fahrzeugfreigabe und vor Fahrtbeginn: angenommen, keine Freigabe, kein Fahrtzustand
        val start = env.post("/v1/time-events", body("WORK_START", 0, shift = shift), w.token, extra = idem())
        assertEquals(201, start.status, start.text); assertEquals("APPLIED", start.json["result"].asString()); assertEquals("ONLINE", start.json["origin"].asString()); assertTrue(start.json["conflict_flag"].isNull)
        assertEquals(0, release(), "Einstempeln erteilt keine Fahrzeug- oder Einsatzfreigabe")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_run WHERE tenant_id=?", w.tenant.value), "kein Fahrtbeginn durch Stempeln")
        // 2. Vor-Schicht-Checkliste innerhalb der begonnenen Arbeitszeit
        val c = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, idem()); assertEquals(201, c.status, c.text)
        val id = c.json["id"].asString()
        val answers = (1..2).joinToString(",") { """{"step_code":"S$it","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"}""" }
        val sub = env.server.send("POST", "/v1/inspections/$id/submit", """{"answers":[$answers],"client_submitted_at":"${Instant.now().minusSeconds(2)}"}""",
            env.headers(w.token, extra = idem() + mapOf("If-Match" to "\"1\"")), "application/json")
        assertEquals(200, sub.status, sub.text); assertEquals("COMPLETED", sub.json["status"].asString()); assertEquals(1, release(), "die Freigabe kommt aus der Prüfung, nicht aus dem Stempel")
        // 3. Pause und Ausstempeln
        listOf("BREAK_START" to 100L, "BREAK_END" to 130L, "WORK_END" to 180L).forEach { (k, m) -> assertEquals("APPLIED", env.post("/v1/time-events", body(k, m, shift = shift), w.token, extra = idem()).json["result"].asString(), k) }
        // 4. erwartete Ist-Auswertung: 3 h abzüglich 30 min Pause; die Arbeitszeit begann vor Prüfung und Freigabe
        val iv = env.get("/v1/time-events/intervals?from=${base.minusSeconds(60)}&to=${base.plusSeconds(86_400)}", w.token)
        assertEquals(200, iv.status, iv.text); val i = iv.json["intervals"].single()
        assertEquals(3 * 3600 - 1800, i["net_seconds"].asLong()); assertEquals(0, i["flags"].size()); assertEquals(1, i["breaks"].size())
        val first = ZoneId.of("Europe/Berlin").let { z -> base.atZone(z).toLocalDate() }
        val ta = env.get("/v1/time-events/target-actual?from=$first&to=${first.plusDays(1)}", w.token)
        assertEquals(200, ta.status, ta.text)
        assertEquals(3 * 3600 - 1800L, ta.json["days"].sumOf { it["actual_net_seconds"].asLong(0) }, "Soll/Ist zeigt die Arbeitszeit ab dem Einstempeln, nicht ab der Prüfung")
        assertEquals(4, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun ohne_sitzung_401_fremde_person_403_doppeltes_einstempeln_409() {
        val w = InspectionApiTest.World(env)
        assertEquals(401, env.server.post("/v1/time-events", body("WORK_START", 0), idem()).status, "ohne gültige Sitzung")
        val other = env.post("/v1/time-events", body("WORK_START", 0, person = w.other.toString()), w.token, extra = idem())
        assertEquals(403, other.status, other.text); assertEquals(0, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        assertEquals(201, env.post("/v1/time-events", body("WORK_START", 0, person = w.account.toString()), w.token, extra = idem()).status, "eigene Personen-ID ist zulässig")
        val dbl = env.post("/v1/time-events", body("WORK_START", 10), w.token, extra = idem())
        assertEquals(409, dbl.status, dbl.text); assertEquals("INVALID_STATE_TRANSITION", dbl.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        assertEquals(403, env.post("/v1/time-events", body("WORK_START", 0), w.passengerToken, extra = idem()).status, "Fahrgast darf nicht stempeln")
    }

    @Test
    fun offline_stempel_genau_einmal_ergebniscodes_und_dauerhafte_deduplikation() {
        val w = InspectionApiTest.World(env)
        val kommen = UUID.randomUUID(); val gehen = UUID.randomUUID()
        val kb = body("WORK_START", 0, kommen, "OFFLINE"); val gb = body("WORK_END", 90, gehen, "OFFLINE")
        val k1 = env.post("/v1/time-events", kb, w.token, extra = idem()); val g1 = env.post("/v1/time-events", gb, w.token, extra = idem())
        assertEquals(listOf(201, 201), listOf(k1.status, g1.status), k1.text + g1.text)
        assertEquals("APPLIED", k1.json["result"].asString()); assertEquals("OFFLINE", k1.json["origin"].asString())
        assertEquals(base.plusSeconds(1), Instant.parse(k1.json["device_recorded_at"].asString())); assertTrue(Instant.parse(k1.json["received_at"].asString()).isAfter(Instant.now().minusSeconds(120)), "Serverempfangszeit")
        // Wiederholung des Sync-Laufs, jeweils mit neuem Idempotenzschlüssel (Cache abgelaufen bzw. nach Neustart der App): DUPLICATE, dieselbe Zeile
        repeat(3) {
            val r = env.post("/v1/time-events", kb, w.token, extra = idem())
            assertEquals(200, r.status, r.text); assertEquals("DUPLICATE", r.json["result"].asString()); assertEquals(k1.json["received_at"].asString(), r.json["received_at"].asString())
        }
        assertEquals(2, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", w.tenant.value))
        // dieselbe ID mit anderem Inhalt (manipulierte Wiederholung) ist ein Konflikt, kein stilles Überschreiben
        assertEquals(409, env.post("/v1/time-events", body("WORK_END", 5, kommen, "OFFLINE"), w.token, extra = idem()).status)
        // Fremde Person
        assertEquals(403, env.post("/v1/time-events", body("WORK_START", 0, origin = "OFFLINE", person = w.other.toString()), w.token, extra = idem()).status)
        // manipulierte Gerätezeit: übernommen, aber zu prüfen; nichts korrigiert
        val w2 = InspectionApiTest.World(env); val future = Instant.now().plusSeconds(6 * 3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val m = env.post("/v1/time-events", """{"event_id":"${UUID.randomUUID()}","kind":"WORK_START","occurred_at":"$future","device_recorded_at":"$future","channel":"APP","origin":"OFFLINE"}""", w2.token, extra = idem())
        assertEquals(201, m.status, m.text); assertEquals("REVIEW_REQUIRED", m.json["result"].asString()); assertEquals("CLOCK_SKEW", m.json["conflict_flag"].asString())
        assertEquals(future, Instant.parse(m.json["occurred_at"].asString()), "nicht still korrigiert")
        val listed = env.get("/v1/time-events", w2.token).json["items"].single()
        assertEquals("CLOCK_SKEW", listed["conflict_flag"].asString()); assertEquals("OFFLINE", listed["origin"].asString()); assertFalse(listed.has("result") && !listed["result"].isNull, "result nur in der Annahmeantwort")
    }

    @Test
    fun paralleler_sync_ueber_http_ergibt_genau_eine_zeile() {
        val w = InspectionApiTest.World(env); val id = UUID.randomUUID(); val b = body("WORK_START", 0, id, "OFFLINE")
        val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1)
        val fs = (1..32).map { pool.submit<Pair<Int, String>> { go.await(); val r = env.post("/v1/time-events", b, w.token, extra = idem()); r.status to r.json["result"].asString() } }
        go.countDown(); val rs = fs.map { it.get(120, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs.count { it == (201 to "APPLIED") }, rs.toString()); assertEquals(31, rs.count { it == (200 to "DUPLICATE") }, rs.toString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=? AND id=?", w.tenant.value, id))
    }
}
