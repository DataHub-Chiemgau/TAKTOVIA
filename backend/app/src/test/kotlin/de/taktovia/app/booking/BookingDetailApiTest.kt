package de.taktovia.app.booking

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M2-43 (UI-04 Buchungsdetail, LH-6.3-PAX-006) über die echte Anwendung: Detailfelder mit Treffpunkt, Plan-ETA getrennt von der Zusage, Fahrzeugidentifikation erst nach Zuordnung,
 * ETA-Änderung durch eine neue Planrevision (Zusage unverändert, alt -> neu), Datentrennung (fremde Buchung, fremder Mandant: 404, nie fremde Personal- oder Fahrzeugakten). Die Planänderung
 * entsteht über den Fahrtenplan-Port (Disposition), wie sie die Einfügeheuristik auslösen würde (Planbindung der Angebote: siehe Vorbehalt in [OfferHttpSupport]).
 */
class BookingDetailApiTest {
    private val s = BookingHttpSupport()
    private val env = s.env

    private inner class Scene {
        val h = s.held(passengers = 1); val booking = s.confirm(h).json["id"].asString()
        val dispatcher: RequestContext
        init {
            val op = UUID.randomUUID(); val membership = env.grant(s.tenant, op, "dispatcher")
            dispatcher = RequestContext(Actor(ActorType.HUMAN, IdentityEnv.idp.issuer, op.toString(), "sid-op", true), s.tenant, setOf("dispatcher"), setOf("rides:read", "rides:write"), emptySet(), UUID.randomUUID(), membershipIds = setOf(membership))
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
        fun detail(token: String = h.account.token) = env.get("/v1/bookings/$booking", token)
    }

    @Test
    fun detail_zeigt_nummer_status_treffpunkt_eta_fahrzeug() {
        val sc = Scene()
        val d = sc.detail()
        assertEquals(200, d.status, d.text)
        assertTrue(d.json["booking_number"].asString().startsWith("BK-")); assertEquals("CONFIRMING", d.json["state"].asString()); assertEquals("bevorstehend", d.json["bereich"].asString())
        // Treffpunkt: Name, Lage, Zugänglichkeitsangaben der Haltepunktrevision des Angebots
        val pickup = d.json["pickup_stop"]
        assertFalse(pickup["name"].asString().isBlank()); assertTrue(pickup["point"]["latitude"].isNumber); assertTrue(pickup["accessibility"].isObject)
        assertEquals(env.string("SELECT pickup_stop_revision_id::text FROM taktovia.ride_offer WHERE id = ?::uuid", sc.h.offerId).single(), pickup["revision_id"].asString())
        // vor der Planung: weder ETA noch Fahrzeugidentifikation (nie geschätzt oder erfunden)
        assertNull(d.json["eta"]); assertNull(d.json["vehicle"]); assertFalse(d.json["data_as_of"].asString().isBlank())
        // nach der Planung: ETA aus der Planrevision, Quelle PLAN, Datenstand; Fahrzeug erst jetzt (Bezeichnung und Klasse, sonst nichts)
        val vehicle = env.string("SELECT vehicle_id::text FROM taktovia.vehicle_run WHERE id = ?::uuid", sc.h.run.toString()).single()
        env.exec("UPDATE taktovia.vehicle SET vehicle_label = 'Bus 12', vehicle_type = 'KLEINBUS' WHERE id = ?::uuid", vehicle)
        sc.plan(1)
        val d2 = sc.detail()
        assertEquals(Instant.parse("2026-11-05T09:00:00Z"), Instant.parse(d2.json["eta"]["pickup_at"].asString())); assertEquals("PLAN", d2.json["eta"]["source"].asString())
        assertEquals(1L, d2.json["eta"]["plan_sequence"].asLong()); assertNull(d2.json["eta"]["previous_pickup_at"].takeUnless { it.isNull }); assertFalse(d2.json["eta"]["data_as_of"].asString().isBlank())
        assertEquals("Bus 12", d2.json["vehicle"]["label"].asString()); assertEquals("KLEINBUS", d2.json["vehicle"]["vehicle_class"].asString())
        // nie interne Felder der Fahrzeug- oder Personalakte
        for (forbidden in listOf("owner_org_ref", "driver_ref", "capacity_config", "safety_revision", "federation_ref")) assertFalse(d2.text.contains(forbidden), forbidden)
        assertEquals(setOf("label", "vehicle_class"), d2.json["vehicle"].propertyNames().toSet())
    }

    @Test
    fun eta_aenderung_neue_planrevision_zusage_bleibt_unveraendert() {
        val sc = Scene(); val r2 = sc.plan(1)
        val before = sc.detail(); val promiseBefore = before.json["promise"].toString()
        sc.plan(r2, 900, RevisionCause.MANUAL_CHANGE)       // 15 Minuten später
        val after = sc.detail()
        assertEquals(Instant.parse("2026-11-05T09:15:00Z"), Instant.parse(after.json["eta"]["pickup_at"].asString()))
        assertEquals(Instant.parse("2026-11-05T09:00:00Z"), Instant.parse(after.json["eta"]["previous_pickup_at"].asString()), "alt -> neu")
        assertEquals(2L, after.json["eta"]["plan_sequence"].asLong())
        assertEquals(promiseBefore, after.json["promise"].toString(), "die verbindliche Zusage ändert sich durch die ETA-Änderung nicht")
        // die Planänderung ändert die Buchungsrevision nicht: ETag stabil, Änderungsstrom trägt die Sequenz
        assertEquals(before.header("ETag"), after.header("ETag"))
        val feed = env.get("/v1/plan-changes", sc.h.account.token).json["items"].values().asSequence().toList()
        assertEquals(listOf(1L, 2L), feed.map { it["bookings"][0]["sequence"].asLong() })
    }

    @Test
    fun fremde_buchung_ergibt_404() {
        val sc = Scene(); val other = s.newAccount()
        assertEquals(404, sc.detail(other.token).status)
        assertEquals(404, env.get("/v1/bookings/${UUID.randomUUID()}", sc.h.account.token).status)
        // Betrieb ohne Dienstscope sieht die Buchung ebenfalls nicht
        assertEquals(404, sc.detail(s.operator(emptyList())).status)
    }

    @Test
    fun fremder_mandant_ergibt_404_und_keine_fremden_akten() {
        val sc = Scene(); sc.plan(1)
        val foreign = BookingHttpSupport(); val foreignToken = foreign.newAccount().token
        val r = foreign.get(sc.booking, foreignToken)
        assertEquals(404, r.status, r.text)
        for (forbidden in listOf("Bus", "pickup_stop", "eta", "vehicle")) assertFalse(r.text.contains("\"$forbidden\""), forbidden)
        // Fahrgast des eigenen Mandanten, aber anderes Konto: ebenfalls 404 und keine Plan-ETA-Angaben im Problem
        val other = s.newAccount(); val o = sc.detail(other.token)
        assertEquals(404, o.status); assertFalse(o.text.contains("pickup_at"))
    }
}
