package de.taktovia.app.operator

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.OperatorFlags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-42 / LH-6.7-OPS-006 über die echte Anwendung (HTTP, Runtime-Rolle, echte Alarm-Grundlage): Hinweise in der Auftragsliste, in der Fahrzeugansicht und in der Arbeitsliste
 * `GET /v1/operator/flags`, Dienstscope, Getrenntheit von Verspätung und ausstehender Information, der Auswertungslauf der Anwendung mit echten Meldungen aus `staff-time` und der Alarm.
 */
class OverdueFlagsApiTest {
    private val s = BookingHttpSupport()
    private val env = s.env
    private val evaluator: OperatorFlags get() = IdentityEnv.server.ctx.getBean(OperatorFlags::class.java)

    private fun seed(service: UUID, kind: String, cause: String, bookings: List<String>, vehicle: String? = null, threshold: Int = 5): UUID = UUID.randomUUID().also { id ->
        env.exec("INSERT INTO taktovia.operator_flag(tenant_id, id, service_id, vehicle_id, kind, cause, dedupe_key, affected_bookings, affected_count, threshold_min) VALUES (?, ?, ?, ?::uuid, ?, ?, ?, CAST(? AS uuid[]), ?, ?)",
            s.tenant.value, id, service, vehicle, kind, cause, "seed-" + UUID.randomUUID(), bookings.joinToString(",", "{", "}"), bookings.size, threshold)
    }

    @Test
    fun hinweise_in_auftragsliste_arbeitsliste_und_scope_getrennt_nach_art() {
        val h1 = s.held(); val h2 = s.held()
        val b1 = s.confirm(h1).json; val b2 = s.confirm(h2).json
        val service = UUID.fromString(b1["service_id"].asString()); val op = s.operator(listOf(service))
        seed(service, "OVERDUE", "PICKUP_OVERDUE", listOf(b1["id"].asString(), b2["id"].asString()))
        seed(service, "INFO_PENDING", "PLAN_ACK_MISSING", listOf(b1["id"].asString()), threshold = 5)
        // Auftragsliste: b1 trägt beide Arten getrennt, b2 nur die Verspätung; die Zahl der Betroffenen steht am Hinweis
        val rows = env.get("/v1/operator/bookings", op).json["items"].values().associateBy { it["id"].asString() }
        fun flags(id: String) = rows.getValue(id)["flags"].values().map { it["kind"].asString() + ":" + it["cause"].asString() + ":" + it["affected_count"].asInt() }.sorted()
        assertEquals(listOf("INFO_PENDING:PLAN_ACK_MISSING:1", "OVERDUE:PICKUP_OVERDUE:2"), flags(b1["id"].asString()))
        assertEquals(listOf("OVERDUE:PICKUP_OVERDUE:2"), flags(b2["id"].asString()))
        // Arbeitsliste: beide Hinweise, Filter nach Art, kein Name, Datenstand
        val list = env.get("/v1/operator/flags", op)
        assertEquals(200, list.status, list.text); assertEquals(2, list.json["items"].size()); assertTrue(list.json["as_of"].asString().endsWith("Z"))
        assertEquals(listOf("INFO_PENDING"), env.get("/v1/operator/flags?kind=INFO_PENDING", op).json["items"].values().map { it["kind"].asString() })
        assertFalse(list.text.contains(b1["booking_number"].asString()), "Hinweise nennen Kennungen und Zahlen, nie Buchungsnummern oder Namen")
        // anderer Dienst, anderer Mandant, Fahrgast: nichts bzw. 403; ein Dienstfilter außerhalb des Scopes ist leer
        assertEquals(0, env.get("/v1/operator/flags", s.operator(listOf(UUID.randomUUID()))).json["items"].size())
        assertEquals(0, env.get("/v1/operator/flags?service_id=${UUID.randomUUID()}", op).json["items"].size())
        assertEquals(0, env.get("/v1/operator/flags", BookingHttpSupport().operator(listOf(service))).json["items"].size())
        assertEquals(403, env.get("/v1/operator/flags", h1.account.token).status)
        assertEquals(422, env.get("/v1/operator/flags?state=NOPE", op).status); assertEquals(422, env.get("/v1/operator/flags?kind=NOPE", op).status)
        assertEquals(400, env.get("/v1/operator/flags?limit=1&cursor=kaputt", op).status); assertEquals(422, env.get("/v1/operator/flags?limit=101", op).status)
        // Leerzustand in der Arbeitsliste: erledigte Hinweise gibt es nicht, 200 mit leerer Liste
        val done = env.get("/v1/operator/flags?state=RESOLVED", op)
        assertEquals(200, done.status); assertEquals(0, done.json["items"].size())
    }

    @Test
    fun auswertungslauf_der_anwendung_meldet_veralteten_standort_in_fahrzeugansicht_und_alarm() {
        val h = s.held(); val b = s.confirm(h).json
        val service = UUID.fromString(b["service_id"].asString()); val op = s.operator(listOf(service))
        val vehicle = env.string("SELECT r.vehicle_id::text FROM taktovia.booking bk JOIN taktovia.capacity_hold ho ON ho.id = bk.hold_id JOIN taktovia.vehicle_run r ON r.id = ho.run_id WHERE bk.id = ?::uuid", b["id"].asString()).single()
        // Dienst angetreten, seit zwei Stunden keine Position: ausstehende Information (nicht Verspätung)
        val device = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version) VALUES (?,?,?,?,?,'ANDROID','1.0.0')", s.tenant.value, device, IdentityEnv.idp.issuer, "fahrer-" + device, "sid-$device")
        env.exec("UPDATE taktovia.staff_shift SET state = 'STARTED', started_at = now() - interval '2 hours', started_device_id = ?::uuid WHERE vehicle_id = ?::uuid AND tenant_id = ?", device, vehicle, s.tenant.value)
        val result = evaluator.evaluate(s.tenant)
        assertTrue(result.opened >= 1, result.toString())
        val flag = env.get("/v1/operator/flags", op).json["items"].values().single { it["vehicle_id"].asString() == vehicle }
        assertEquals("INFO_PENDING", flag["kind"].asString()); assertEquals("POSITION_STALE", flag["cause"].asString()); assertEquals(15, flag["threshold_min"].asInt())
        // Fahrzeugansicht (Liste und Detail): das Fahrzeug trägt den Hinweis als ausstehende Information
        val detail = env.get("/v1/operator/vehicles/$vehicle", op)
        assertEquals(200, detail.status, detail.text)
        assertEquals(listOf("INFO_PENDING:POSITION_STALE"), detail.json["flags"].values().map { it["kind"].asString() + ":" + it["cause"].asString() })
        assertEquals(1, env.get("/v1/operator/vehicles", op).json["items"].values().single { it["vehicle_id"].asString() == vehicle }["flags"].size())
        // der Hinweis hat einen Alarm der Alarm-Grundlage ausgelöst (Art INFO_PENDING, Bezug auf den Hinweis), genau einen auch nach erneutem Lauf
        evaluator.evaluate(s.tenant)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.business_alert WHERE tenant_id = ? AND alert_kind = 'INFO_PENDING' AND ref_id = ?::uuid", s.tenant.value, flag["id"].asString()))
        // Position trifft ein: Ursache weg, Hinweis erledigt, Alarm gelöst
        env.exec("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,received_at,created_by) SELECT tenant_id, ?, vehicle_id, id, CAST(ST_SetSRID(ST_MakePoint(13.7,51.0),4326) AS geography), 5, now(), now(), 'user:t' FROM taktovia.staff_shift WHERE vehicle_id = ?::uuid AND tenant_id = ?",
            UUID.randomUUID(), vehicle, s.tenant.value)
        assertEquals(1, evaluator.evaluate(s.tenant).resolved)
        assertEquals(0, env.get("/v1/operator/vehicles/$vehicle", op).json["flags"].size())
        assertEquals(1, env.get("/v1/operator/flags?state=RESOLVED", op).json["items"].size())
        assertEquals("RESOLVED", env.string("SELECT state FROM taktovia.business_alert WHERE tenant_id = ? AND ref_id = ?::uuid", s.tenant.value, flag["id"].asString()).single())
    }
}
