package de.taktovia.app.fleet

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.WorkflowEvent
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-54 (LH-6.22-DMG-010) über die echte Anwendung: Fahrzeugausfall -> Incident -> Ersatzsuche als Betriebsauftrag (echte Verdrahtung `fleet-safety` und `booking-dispatch`) -> Störungsansicht am Incident ->
 * Umbuchung, Storno und Fahrgastmitteilung per HTTP, mit Rechten, Mandanten- und Dienstgrenzen, `If-Match`, Idempotenz und Ablaufprotokoll. Test-Issuer statt Keycloak, echte Datenbank unter der Runtime-Rolle.
 */
class OutageHttpApiTest {
    private val s = BookingHttpSupport()
    private val env = s.env
    private val ctx get() = env.server.ctx
    private val fleet: FleetsafetyApi get() = ctx.getBean(FleetsafetyApi::class.java)

    private fun Scene() = OutageHttpScene(s)

    private fun tools.jackson.databind.JsonNode.first() = this[0]

    @Test
    fun stoerungsansicht_zeigt_betroffene_fahrt_vorschlag_und_aktionen_und_umbuchung_schliesst_den_incident() {
        val sc = Scene()
        val d = sc.detail(); assertEquals(200, d.status, d.text)
        assertEquals("REPLACEMENT_SEARCH", d.json["status"].asString()); assertTrue(sc.a.run.toString() in d.json["affected_run_ids"].values().map { it.asString() })
        val o = d.json["outage"]; assertEquals(1, o["bookings"].size()); assertEquals(1, o["open_count"].asInt()); assertEquals(0, o["boarded_booking_ids"].size())
        val b = o["bookings"].first()
        assertEquals(sc.bookingId, b["booking_id"].asString()); assertEquals("PROPOSED", b["state"].asString()); assertEquals(sc.a.run.toString(), b["run_id"].asString())
        val p = b["proposals"].first()
        assertEquals(sc.vehicleB.toString(), p["vehicle_id"].asString()); assertEquals(sc.b.run.toString(), p["run_id"].asString())
        assertEquals(listOf("CHECK_OPTIONS", "REBOOK", "CANCEL"), b["actions"].values().map { it.asString() })
        assertFalse(d.text.contains("passenger") && d.text.contains("@"), "keine Fahrgastdaten in der Störungsansicht")
        // das Ablaufprotokoll zeigt die Suche mit Ergebnis (Zeitleiste)
        assertTrue(d.json["workflow_log"].values().any { it["action"].asString() == "SEARCH_RESULT" && it["outcome"].asString().startsWith("PROPOSALS:") })
        val rev = b["revision"].asLong(); val proposal = p["id"].asString()
        val body = """{"proposal_id":"$proposal","reason_code":"VEHICLE_OUTAGE","accept_window_effect":${p["window_effect"].asString() == "OUTSIDE_WINDOW"}}"""
        // Pflichten: Rolle (403), Mandant (404), If-Match (428), veraltet (412), Idempotency-Key
        val callcenter = s.operator(listOf(sc.service), "callcenter")
        assertEquals(403, sc.post("${sc.path}/rebook", body, "$rev", callcenter).status)
        assertEquals(404, sc.post("${sc.path}/rebook", body, "$rev", s.run { val t = env.tenant(); val sub = UUID.randomUUID(); env.grant(t, sub, "dispatcher"); env.token(sub) }).status)
        assertEquals(428, sc.post("${sc.path}/rebook", body, null).status)
        val stale = sc.post("${sc.path}/rebook", body, "${rev + 5}"); assertEquals(412, stale.status, stale.text); assertEquals("\"$rev\"", stale.header("ETag"))
        assertEquals(422, sc.post("${sc.path}/rebook", """{"reason_code":"VEHICLE_OUTAGE"}""", "$rev").status)
        assertEquals(422, sc.post("${sc.path}/rebook", """{"proposal_id":"$proposal","reason_code":"freitext mit leerzeichen"}""", "$rev").status)
        assertEquals(404, sc.post("/outage/bookings/${UUID.randomUUID()}/rebook", body, "$rev").status)
        // fremder Dienst im Scope: 404
        assertEquals(404, sc.post("${sc.path}/rebook", body, "$rev", s.operator(listOf(UUID.randomUUID()))).status)
        // Umbuchung
        val ok = sc.post("${sc.path}/rebook", body, "$rev"); assertEquals(200, ok.status, ok.text)
        assertEquals("REBOOKED", ok.json["state"].asString()); assertEquals(sc.b.run.toString(), ok.json["replacement_run_id"].asString()); assertEquals("\"${rev + 1}\"", ok.header("ETag"))
        assertTrue(ok.json["decided_by"].asString().startsWith("user:")); assertEquals("VEHICLE_OUTAGE", ok.json["decision_reason"].asString()); assertNotNull(ok.json["decided_at"].asText())
        // die Buchung liegt jetzt im Plan von B, die Reservierung folgt ihr; die Ersatzfahrt ist erst jetzt zugesagt
        assertEquals(sc.b.run.toString(), env.string("SELECT run_id::text FROM taktovia.capacity_hold WHERE id = (SELECT hold_id FROM taktovia.booking WHERE id = ?::uuid)", sc.bookingId).single())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.booking_change WHERE booking_id = ?::uuid AND to_run_id = ?", sc.bookingId, sc.b.run))
        // zweiter Versuch: bereits entschieden (409), mit alter Revision (412); nichts doppelt
        assertEquals(409, sc.post("${sc.path}/rebook", body, "${rev + 1}").status); assertEquals(412, sc.post("${sc.path}/rebook", body, "$rev").status)
        assertEquals(409, sc.post("${sc.path}/cancel", """{"reason_code":"VEHICLE_OUTAGE"}""", "${rev + 1}").status)
        // der Incident ist abgearbeitet (RESOLVED), die Entscheidung steht mit Akteur, Code und Zeit im Ablaufprotokoll
        val after = sc.detail(); assertEquals("RESOLVED", after.json["status"].asString()); assertFalse(after.json["closed_at"].isNull)
        val decision = after.json["workflow_log"].values().single { it["action"].asString() == "DISPATCH_DECISION" }
        assertEquals("REBOOK_BOOKING", decision["outcome"].asString()); assertEquals("VEHICLE_OUTAGE", decision["reason_code"].asString()); assertTrue(decision["actor"].asString().startsWith("user:"))
        assertEquals(0, after.json["outage"]["open_count"].asInt())
        // Fahrgast informieren: genau eine Mitteilung je Änderung, Wiederholung wirkungslos
        val info = sc.post("${sc.path}/inform", null, "${rev + 1}"); assertEquals(200, info.status, info.text); assertTrue(info.json["passenger_notice_requested"].asBoolean())
        assertEquals(200, sc.post("${sc.path}/inform", null, "${rev + 1}").status)
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'outage.passenger_notice_requested' AND payload->>'booking_id' = ?", sc.bookingId))
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'dispatch.outage.rebooked'", s.tenant.value), "Audit der Umbuchung")
    }

    @Test
    fun betreiberstorno_mit_ursache_fahrzeugausfall_idempotent_und_ohne_doppelte_erstattung() {
        val sc = Scene()
        val b = sc.outage()["bookings"].first(); val rev = b["revision"].asLong()
        val key = "cancel-" + UUID.randomUUID(); val body = """{"reason_code":"NO_VEHICLE_AVAILABLE"}"""
        val first = sc.post("${sc.path}/cancel", body, "$rev", key = key); assertEquals(200, first.status, first.text); assertEquals("CANCELLED", first.json["state"].asString())
        // dieselbe Anfrage mit demselben Schlüssel: dieselbe Antwort, keine zweite Wirkung; anderer Inhalt zum selben Schlüssel: 409 IDEMPOTENCY_CONFLICT
        val replay = sc.post("${sc.path}/cancel", body, "$rev", key = key); assertEquals(200, replay.status); assertEquals(first.json, replay.json)
        val conflict = sc.post("${sc.path}/cancel", """{"reason_code":"ANDERER_GRUND"}""", "$rev", key = key); assertEquals(409, conflict.status); assertEquals("IDEMPOTENCY_CONFLICT", conflict.json["code"].asString())
        assertEquals(409, sc.post("${sc.path}/cancel", body, "${rev + 1}").status)                       // neuer Schlüssel auf entschiedenem Fall
        assertEquals("CANCELED", env.string("SELECT state FROM taktovia.booking WHERE id = ?::uuid", sc.bookingId).single())
        assertEquals("VEHICLE_OUTAGE", env.string("SELECT cancel_reason_code FROM taktovia.booking WHERE id = ?::uuid", sc.bookingId).single())
        assertEquals("FAHRZEUGAUSFALL", env.string("SELECT cancel_cause_class FROM taktovia.booking WHERE id = ?::uuid", sc.bookingId).single())
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.outbox WHERE event_type = 'booking.cancelled' AND aggregate_id = ?::uuid", sc.bookingId), "eine Stornomitteilung über den Stornoweg der M2")
        // ohne Ersatz abgeschlossen: CLOSED_NO_REPLACEMENT; die Mitteilung zur Umbuchung gibt es zum Storno nicht (409)
        assertEquals("CLOSED_NO_REPLACEMENT", sc.detail().json["status"].asString())
        assertEquals(409, sc.post("${sc.path}/inform", null, "${rev + 1}").status)
    }

    @Test
    fun ersatzoptionen_pruefen_und_incident_ohne_betroffene_fahrten() {
        val sc = Scene()
        val check = sc.post("/outage/check", null, null); assertEquals(200, check.status, check.text)
        assertEquals(1, check.json["bookings"].size()); assertEquals("PROPOSED", check.json["bookings"].first()["state"].asString())
        assertEquals(403, sc.post("/outage/check", null, null, s.operator(listOf(sc.service), "callcenter")).status)
        assertEquals(1, s.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'dispatch.outage.options_checked'", s.tenant.value))
        // Fahrzeug ohne offene Buchung: kein Fahrgast betroffen, der Incident ist sofort abgearbeitet
        val empty = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'fixture','{}')", s.tenant.value, empty)
        val id = env.tx.run(s.tenant) { j -> fleet.incidentWorkflow.handle(j, WorkflowEvent(UUID.randomUUID(), WorkflowEventKinds.VEHICLE_BLOCKED, empty, Instant.now(), correlationId = UUID.randomUUID())).incidentId }!!
        OperationRunner(ctx.getBean(TenantTransactions::class.java), listOf(ctx.getBean("replacementSearchHandler", OperationHandler::class.java))).runNext(s.tenant)
        val d = env.get("/v1/operator/incidents/$id", sc.dispatcher)
        assertEquals("RESOLVED", d.json["status"].asString()); assertEquals(0, d.json["outage"]["bookings"].size())
    }
}
