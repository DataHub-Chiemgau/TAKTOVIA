package de.taktovia.app.driver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M3-26 / LH-6.6-DRV-002 über die echte Anwendung (HTTP, Runtime-Rolle, echter Fahrtenplan und echte Buchungen): Stoppliste des Fahrers in der Reihenfolge der `route_stop`-Sequenz mit Soll-
 * und Prognosezeit, Personenzahl und Ressourcenbedarf; `ETag` = Planrevision mit bedingtem Abruf; nur die eigene Schicht; Feldminimierung; konsistente Revision während eines Plan-Commits.
 * Die Prognosezeit ist bis zum ETA-Grundmodell die Planzeit aus dem Routing (OP-20); ohne Routingfahrzeit wird „keine Prognose“ ausgewiesen.
 */
class DriverRunProjectionTest {
    private val w = DriverRunHttpWorld()
    private val env = w.env

    private fun stops(r: JsonNode) = r["stops"].values().asSequence().toList()

    @Test
    fun stoppliste_folgt_route_stop_sequenz_mit_zeiten_personenzahl_und_ressourcenbedarf() {
        val d = w.driver(); val a = w.booking(); val b = w.booking(); val c = w.booking()
        val rev = w.plan(d.run, listOf(a, b, c))
        val r = d.get(); assertEquals(200, r.status, r.text)
        assertEquals("\"$rev\"", r.header("ETag")); assertEquals(rev, r.json["plan_revision"].asLong()); assertTrue(r.json["shift_valid"].asBoolean()); assertEquals("RELEASED", r.json["release_state"].asString())
        val s = stops(r.json)
        assertEquals(6, s.size); assertEquals((1..6).toList(), s.map { it["sequence"].asInt() })     // Reihenfolge = route_stop-Sequenz
        assertEquals(listOf("PICKUP", "PICKUP", "PICKUP", "DROPOFF", "DROPOFF", "DROPOFF"), s.map { it["kind"].asString() })
        assertEquals((0..5).map { w.t0.plusSeconds(300L * it) }, s.map { Instant.parse(it["planned_at"].asString()) })
        assertEquals(listOf(a.id, b.id, c.id, a.id, b.id, c.id).map { it.toString() }, s.map { it["booking_ids"][0].asString() })
        assertTrue(s.all { it["passenger_count"].asInt() == 1 }); assertEquals(listOf(1, 1, 1, 0, 0, 0), s.map { it["boarding_count"].asInt() }); assertEquals(listOf(0, 0, 0, 1, 1, 1), s.map { it["alighting_count"].asInt() })
        assertEquals(1, s[0]["resource_need"]["seats"].asInt()); assertEquals(0, s[0]["resource_need"]["wheelchair"].asInt()); assertTrue(s[3]["resource_need"].isNull, "Ausstieg ohne Einsteiger hat keinen Bedarf")
        assertEquals(a.from.toString(), s[0]["stop_revision_id"].asString()); assertTrue(s[0]["stop_name"].asString().isNotBlank()); assertTrue(s[0]["position"]["latitude"].asDouble() != 0.0)
    }

    @Test
    fun stopp_zeigt_sollzeit_prognose_oder_keine_prognose_und_personenzahl() {
        val d = w.driver(); val a = w.booking(); val b = w.booking()
        w.plan(d.run, listOf(a, b), legs = setOf(0, 2))            // Fahrzeit nur ab Stopp 0 und 2 aus dem Routing: Stopp 1 und 3 haben keine Routing-Fahrzeit davor ... siehe unten
        val s = stops(d.get().json)
        // Stopp 0: Abfahrt (Prognose = Planzeit); Stopp 1 folgt auf Abschnitt 0 mit Fahrzeit: Prognose; Stopp 2 folgt auf Abschnitt 1 ohne Fahrzeit: keine Prognose; Stopp 3 folgt auf Abschnitt 2: Prognose
        assertEquals(listOf(true, true, false, true), s.map { !it["forecast_at"].isNull })
        assertEquals(s[0]["planned_at"].asString(), s[0]["forecast_at"].asString()); assertTrue(s[2]["forecast_at"].isNull, "keine Prognose statt einer erfundenen Zeit")
        assertTrue(s.all { it["planned_at"].isString })
    }

    @Test
    fun neu_zugewiesener_auftrag_an_korrekter_position() {
        val d = w.driver(); val a = w.booking(); val b = w.booking(); val c = w.booking()
        w.plan(d.run, listOf(a, b))
        assertEquals(listOf(a.id, b.id, a.id, b.id).map { it.toString() }, stops(d.get().json).map { it["booking_ids"][0].asString() })
        // die Disposition fügt eine dritte Buchung ein: Einstieg c nach a, Ausstieg c vor b
        val v = de.taktovia.bookingdispatch.api.ResourceVector(1, 0)
        val stopsNew = listOf(
            de.taktovia.bookingdispatch.api.PlanStop(0, a.from, w.t0, boardings = listOf(de.taktovia.bookingdispatch.api.StopMovement(a.id, v)), legDurationSec = 240, legDistanceM = 1000),
            de.taktovia.bookingdispatch.api.PlanStop(1, c.from, w.t0.plusSeconds(300), boardings = listOf(de.taktovia.bookingdispatch.api.StopMovement(c.id, v)), legDurationSec = 240, legDistanceM = 1000),
            de.taktovia.bookingdispatch.api.PlanStop(2, b.from, w.t0.plusSeconds(600), boardings = listOf(de.taktovia.bookingdispatch.api.StopMovement(b.id, v)), legDurationSec = 240, legDistanceM = 1000),
            de.taktovia.bookingdispatch.api.PlanStop(3, c.to, w.t0.plusSeconds(900), alightings = listOf(de.taktovia.bookingdispatch.api.StopMovement(c.id, v)), legDurationSec = 240, legDistanceM = 1000),
            de.taktovia.bookingdispatch.api.PlanStop(4, a.to, w.t0.plusSeconds(1200), alightings = listOf(de.taktovia.bookingdispatch.api.StopMovement(a.id, v)), legDurationSec = 240, legDistanceM = 1000),
            de.taktovia.bookingdispatch.api.PlanStop(5, b.to, w.t0.plusSeconds(1500), alightings = listOf(de.taktovia.bookingdispatch.api.StopMovement(b.id, v))),
        )
        val rev = w.runs.appendRevision(dispatcherCtx(), d.run, d.revision(), stopsNew, de.taktovia.bookingdispatch.api.RevisionCause.BOOKING_INSERTED, listOf(c.id)).revision
        val r = d.get(); assertEquals("\"$rev\"", r.header("ETag"))
        assertEquals(listOf(a.id, c.id, b.id, c.id, a.id, b.id).map { it.toString() }, stops(r.json).map { it["booking_ids"][0].asString() })
    }

    private fun dispatcherCtx(): de.taktovia.platform.context.RequestContext {
        val op = UUID.randomUUID(); val m = env.grant(w.tenant, op, "dispatcher")
        return de.taktovia.platform.context.RequestContext(de.taktovia.platform.context.Actor(de.taktovia.platform.context.ActorType.HUMAN, de.taktovia.app.identity.IdentityEnv.idp.issuer, op.toString(), "sid-x", true), w.tenant,
            setOf("dispatcher"), setOf("rides:write"), emptySet(), UUID.randomUUID(), membershipIds = setOf(m))
    }

    @Test
    fun fremde_schicht_und_fremder_mandant_ergeben_404() {
        val d = w.driver(); val other = w.driver(); w.plan(d.run, listOf(w.booking()))
        assertEquals(404, d.get(t = other.token).status, "Fahrer einer anderen Schicht")
        assertEquals(404, env.get("/v1/driver/shifts/${UUID.randomUUID()}/run", d.token).status)
        val foreign = DriverRunHttpWorld(); val fd = foreign.driver()
        assertEquals(404, env.get("/v1/driver/shifts/${fd.shift}/run", d.token).status, "Schicht eines anderen Mandanten")
        assertEquals(404, d.get(t = fd.token).status, "Konto eines anderen Mandanten")
        // tenant_id im Request ohne Wirkung: ein fremder Mandant als Query oder Header ändert nichts (fremder Wert 404)
        val q = env.get("/v1/driver/shifts/${d.shift}/run?tenant_id=${foreign.tenant}", d.token)
        assertTrue(q.status in setOf(200, 404, 422), q.text); if (q.status == 200) assertEquals(d.shift.toString(), q.json["shift_id"].asString())
        assertEquals(401, env.server.get("/v1/driver/shifts/${d.shift}/run", emptyMap()).status)
        assertEquals(403, env.get("/v1/driver/shifts/${d.shift}/run", w.s.newAccount().token).status, "Konto ohne Fahrerrolle")
    }

    @Test
    fun run_ohne_stopps_leerzustand() {
        val d = w.driver()
        val r = d.get(); assertEquals(200, r.status, r.text)
        assertEquals(0, r.json["stops"].size()); assertTrue(r.json["shift_valid"].asBoolean()); assertEquals("RELEASED", r.json["release_state"].asString()); assertEquals("\"1\"", r.header("ETag"))
        // bedingter Abruf im Leerzustand liefert nie 304 (der Client behält so nie einen alten Plan)
        assertEquals(200, d.get("\"1\"").status)
    }

    @Test
    fun bedingter_abruf_etag_304() {
        val d = w.driver(); val a = w.booking(); val rev = w.plan(d.run, listOf(a))
        val first = d.get(); val etag = first.header("ETag")!!; assertEquals("\"$rev\"", etag)
        // WP-M3-27: solange eine bestätigungspflichtige Änderung offen ist, antwortet der bedingte Abruf nie 304; nach der Bestätigung gilt er wie zuvor
        assertEquals(200, d.get(etag).status)
        val ack = env.server.send("POST", "/v1/driver/shifts/${d.shift}/run/acknowledgements", """{"plan_revision":$rev,"acknowledged_at":"2026-11-05T09:00:00Z"}""",
            env.headers(d.token, extra = mapOf("Idempotency-Key" to "ack-" + java.util.UUID.randomUUID(), "If-Match" to etag)))
        assertEquals(200, ack.status, ack.text)
        val same = d.get(etag); assertEquals(304, same.status); assertEquals(etag, same.header("ETag")); assertTrue(same.text.isNullOrEmpty())
        // neue Planrevision: anderer ETag, 200 mit neuem Inhalt
        val rev2 = w.plan(d.run, listOf(a), base = w.t0.plusSeconds(600))
        val changed = d.get(etag); assertEquals(200, changed.status); assertEquals("\"$rev2\"", changed.header("ETag")); assertTrue(rev2 > rev)
        // Schichtende: auch mit passendem ETag 200 (Leerzustand), nie 304
        d.endShift(); val ended = d.get(changed.header("ETag")); assertEquals(200, ended.status); assertFalse(ended.json["shift_valid"].asBoolean()); assertEquals(0, ended.json["stops"].size())
    }

    @Test
    fun projektion_ohne_kontakt_und_zahlungsdaten() {
        val d = w.driver(); val a = w.booking(assistanceCodes = listOf("BOARDING_HELP"), wheelchair = true); w.plan(d.run, listOf(a))
        val r = d.get(); val s = stops(r.json)
        val allowedStop = setOf("route_stop_id", "sequence", "kind", "planned_at", "forecast_at", "stop_name", "position", "passenger_count", "booking_ids", "change", "previous_planned_at", "support_hints", "stop_revision_id",
            "boarding_count", "alighting_count", "resource_need", "assistance")
        s.forEach { st -> assertTrue(st.propertyNames().all { it in allowedStop }, "unerwartetes Feld: ${st.propertyNames()}") }
        assertEquals(setOf("shift_id", "vehicle_id", "shift_state", "shift_valid", "release_state", "missing_requirements", "plan_revision", "plan_as_of", "stops", "acknowledged_revision",
            "revision_state", "acknowledgement_required", "base_revision", "removed_stops"), r.json.propertyNames().toSet())
        val text = r.text
        for (forbidden in listOf("contact", "email", "phone", "payment", "gross", "amount", "iban", "diagnos", "passenger_id", "booking_no")) assertFalse(text.contains(forbidden, ignoreCase = true), "Feld/Wert '$forbidden' in der Fahrerprojektion")
    }

    @Test
    fun abruf_waehrend_plan_commit_konsistent() {
        val d = w.driver(); val a = w.booking(); val b = w.booking(); w.plan(d.run, listOf(a, b))
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val writer = pool.submit {
            go.await()
            // jede Revision kodiert ihre Nummer in der Planzeit des ersten Stopps: Inhalt und ETag müssen zusammenpassen
            repeat(12) { w.plan(d.run, if (it % 2 == 0) listOf(a, b) else listOf(b, a), base = w.t0.plusSeconds(60L * (d.revision() + 1))) }
            stop.set(true)
        }
        val reader = pool.submit<List<Triple<Long, Instant, String>>> {
            go.await(); val seen = mutableListOf<Triple<Long, Instant, String>>()
            while (!stop.get() || seen.size < 5) { val r = d.get(); if (r.status == 200) seen += Triple(r.header("ETag")!!.trim('"').toLong(), Instant.parse(r.json["stops"][0]["planned_at"].asString()), r.json["stops"][0]["booking_ids"][0].asString()); if (stop.get()) break }
            seen
        }
        go.countDown(); writer.get(120, TimeUnit.SECONDS); val seen = reader.get(120, TimeUnit.SECONDS); pool.shutdown()
        assertTrue(seen.isNotEmpty())
        seen.forEach { (rev, planned, first) ->
            assertEquals(w.t0.plusSeconds(60L * rev), planned, "ETag $rev passt zum Inhalt (Planzeit)")
            assertEquals(if ((rev - 3) % 2 == 0L) a.id.toString() else b.id.toString(), first, "Reihenfolge der Revision $rev")
        }
        assertEquals(seen.map { it.first }, seen.map { it.first }.sorted(), "Revisionen gehen nie zurück")
    }

    @Test
    fun abruf_parallel_zu_schichtende_keine_fremden_stopps() {
        val d = w.driver(); val own = w.booking(); w.plan(d.run, listOf(own))
        val other = w.driver(); val foreign = w.booking(); w.plan(other.run, listOf(foreign))
        val ownIds = stops(d.get().json).flatMap { st -> st["booking_ids"].values().asSequence().map { it.asString() }.toList() }.toSet()
        assertEquals(setOf(own.id.toString()), ownIds)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val ender = pool.submit { go.await(); Thread.sleep(30); d.endShift() }
        val reader = pool.submit<List<JsonNode>> { go.await(); (1..25).map { d.get().json } }
        go.countDown(); ender.get(30, TimeUnit.SECONDS); val seen = reader.get(60, TimeUnit.SECONDS); pool.shutdown()
        for (r in seen) {
            val ids = stops(r).flatMap { st -> st["booking_ids"].values().asSequence().map { it.asString() }.toList() }
            assertTrue(ids.all { it == own.id.toString() }, "nie Stopps einer fremden Schicht: $ids")
            if (!r["shift_valid"].asBoolean()) assertEquals(0, r["stops"].size())
        }
        assertFalse(d.get().json["shift_valid"].asBoolean())
    }
}
