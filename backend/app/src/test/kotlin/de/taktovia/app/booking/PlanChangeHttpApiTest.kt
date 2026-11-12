package de.taktovia.app.booking

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M2-28 über die echte Anwendung: Änderungsstrom und Snapshot je Empfängerkanal (`/v1/plan-changes`, `/v1/driver/plan-changes`), ETag und `If-Match` auf `GET /v1/bookings/{id}` (412 mit
 * aktuellem ETag), Kanalbindung (403) und Eingabeprüfung (422). Die Planänderung entsteht über den Fahrtenplan-Port (Disposition), wie sie die Einfügeheuristik auslösen würde.
 */
class PlanChangeHttpApiTest {
    private val s = BookingHttpSupport()
    private val env = s.env

    private inner class Scene {
        val h = s.held(passengers = 1); val booking = s.confirm(h).json["id"].asString()
        val other = s.held(passengers = 1, seats = 4)   // zweiter Fahrgast, anderer Umlauf
        val driverSubject = UUID.randomUUID(); val driver: String
        val dispatcher: RequestContext
        init {
            env.grant(s.tenant, driverSubject, "driver"); driver = env.token(driverSubject)
            val op = UUID.randomUUID(); val membership = env.grant(s.tenant, op, "dispatcher")
            dispatcher = RequestContext(Actor(ActorType.HUMAN, IdentityEnv.idp.issuer, op.toString(), "sid-op", true), s.tenant, setOf("dispatcher"), setOf("rides:read", "rides:write"), emptySet(), UUID.randomUUID(), membershipIds = setOf(membership))
            env.exec("UPDATE taktovia.staff_shift SET driver_ref = ?::uuid WHERE id = (SELECT shift_id FROM taktovia.vehicle_run WHERE id = ?)", driverSubject.toString(), h.run)
        }
        val runs = BookingdispatchApi.create(env.server.ctx.getBean(ContextTransactions::class.java), outbox = env.server.ctx.getBean(Outbox::class.java)).runs

        fun plan(expected: Long, shiftSec: Long = 0, cause: RevisionCause = RevisionCause.MANUAL_CHANGE): Long {
            val rev = env.string("SELECT pickup_stop_revision_id::text || ',' || dropoff_stop_revision_id::text FROM taktovia.ride_offer WHERE id = ?::uuid", h.offerId).single().split(",")
            val t0 = Instant.parse("2026-11-05T09:00:00Z").plusSeconds(shiftSec); val v = ResourceVector(1, 0); val id = UUID.fromString(booking)
            return runs.appendRevision(dispatcher, h.run, expected, listOf(
                PlanStop(0, UUID.fromString(rev[0]), t0, boardings = listOf(StopMovement(id, v)), legDurationSec = 600, legDistanceM = 4000),
                PlanStop(1, UUID.fromString(rev[1]), t0.plusSeconds(600), alightings = listOf(StopMovement(id, v))),
            ), cause, listOf(id)).revision
        }
    }

    @Test
    fun fahrgast_und_fahrer_erhalten_die_planaenderung_und_nur_ihre_eigene() {
        val sc = Scene()
        val r2 = sc.plan(1); val r3 = sc.plan(r2, 900)               // Revision 2 (Einfügen), Revision 3 (15 Minuten später)
        // Fahrgast: zwei Einträge für die eigene Buchung, fortlaufende sequence; kein Eintrag für fremde Buchungen
        val p = env.get("/v1/plan-changes", sc.h.account.token)
        assertEquals(200, p.status, p.text)
        val items = p.json["items"].values().asSequence().toList()
        assertEquals(2, items.size)
        assertEquals(listOf("ADDED", "TIME_CHANGED"), items.map { it["bookings"][0]["kind"].asString() })
        assertEquals(listOf(1L, 2L), items.map { it["bookings"][0]["sequence"].asLong() })
        assertTrue(items.all { it["bookings"].size() == 1 && it["bookings"][0]["booking_id"].asString() == sc.booking })
        assertEquals(Instant.parse("2026-11-05T09:15:00Z"), Instant.parse(items[1]["bookings"][0]["pickup_after"].asString()))
        assertEquals(items[1]["cursor"].asLong(), p.json["next_cursor"].asLong()); assertEquals(false, p.json["has_more"].asBoolean())
        // Wiederaufnahme ab Cursor: nur das Neue; Seitengröße 1 meldet weitere
        val resumed = env.get("/v1/plan-changes?after=${items[0]["cursor"].asLong()}", sc.h.account.token)
        assertEquals(1, resumed.json["items"].size())
        val page = env.get("/v1/plan-changes?limit=1", sc.h.account.token)
        assertEquals(1, page.json["items"].size()); assertEquals(true, page.json["has_more"].asBoolean())
        // Snapshot: Stand der eigenen Buchung
        val snap = env.get("/v1/plan-changes/snapshot", sc.h.account.token)
        assertEquals(200, snap.status); assertEquals(2L, snap.json["bookings"][0]["sequence"].asLong()); assertEquals(sc.booking, snap.json["bookings"][0]["booking_id"].asString())
        assertEquals(items[1]["cursor"].asLong(), snap.json["cursor"].asLong())
        // anderer Fahrgast: nichts (keine Daten fremder Buchungen)
        assertEquals(0, env.get("/v1/plan-changes", sc.other.account.token).json["items"].size())
        assertEquals(0, env.get("/v1/plan-changes/snapshot", sc.other.account.token).json["bookings"].size())
        // Fahrer der Schicht: beide Revisionen lückenlos verkettet; Snapshot nennt die aktuelle Revision
        val d = env.get("/v1/driver/plan-changes", sc.driver)
        assertEquals(200, d.status, d.text)
        assertEquals(listOf(r2, r3), d.json["items"].values().asSequence().map { it["revision"].asLong() }.toList())
        assertEquals(listOf(1L, r2), d.json["items"].values().asSequence().map { it["previous_revision"].asLong() }.toList())
        assertEquals(r3, env.get("/v1/driver/plan-changes/snapshot", sc.driver).json["runs"][0]["revision"].asLong())
        // fremder Fahrer: nichts
        val foreign = UUID.randomUUID(); env.grant(s.tenant, foreign, "driver")
        assertEquals(0, env.get("/v1/driver/plan-changes", env.token(foreign)).json["items"].size())
    }

    @Test
    fun fremder_kanal_und_ungueltige_eingaben() {
        val sc = Scene()
        assertEquals(403, env.get("/v1/driver/plan-changes", sc.h.account.token).status)         // Fahrgast am Fahrerkanal
        assertEquals(403, env.get("/v1/driver/plan-changes/snapshot", sc.h.account.token).status)
        assertEquals(403, env.get("/v1/plan-changes", sc.driver).status)                          // Fahrer am Fahrgastkanal
        assertEquals(403, env.get("/v1/plan-changes/snapshot", s.operator(emptyList())).status)   // Betriebspersonal hat keinen Empfängerkanal
        assertEquals(401, env.server.get("/v1/plan-changes", emptyMap()).status)
        assertEquals(422, env.get("/v1/plan-changes?after=abc", sc.h.account.token).status)
        assertEquals(422, env.get("/v1/driver/plan-changes?after=-1", sc.driver).status)
        assertEquals(422, env.get("/v1/plan-changes?limit=0", sc.h.account.token).status)
    }

    @Test
    fun get_booking_mit_veraltetem_if_match_liefert_412_mit_aktuellem_etag() {
        val sc = Scene(); val tok = sc.h.account.token
        val fresh = env.get("/v1/bookings/${sc.booking}", tok)
        assertEquals(200, fresh.status); assertEquals("\"1\"", fresh.header("ETag"))
        assertEquals(200, env.get("/v1/bookings/${sc.booking}", tok, extra = mapOf("If-Match" to "\"1\"")).status)   // aktueller Stand: unverändert lesbar
        val stale = env.get("/v1/bookings/${sc.booking}", tok, extra = mapOf("If-Match" to "\"7\""))
        assertEquals(412, stale.status, stale.text); assertEquals("REVISION_MISMATCH", stale.json["code"].asString()); assertEquals("\"1\"", stale.header("ETag"))
        // schreibender Zugriff mit veraltetem ETag: 412, ohne ETag 428 (bestehendes Verhalten, hier als Teil des Vertrags mitgeprüft)
        assertEquals(412, s.cancel(sc.booking, tok, ifMatch = "7").status)
        assertEquals(428, s.cancel(sc.booking, tok, ifMatch = null).status)
        // ein anderer Fahrgast liest nie fremde Buchungen, auch nicht mit If-Match
        assertEquals(404, env.get("/v1/bookings/${sc.booking}", sc.other.account.token, extra = mapOf("If-Match" to "\"1\"")).status)
    }
}
