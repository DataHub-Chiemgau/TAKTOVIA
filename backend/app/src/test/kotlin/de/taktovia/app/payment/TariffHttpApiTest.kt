package de.taktovia.app.payment

import de.taktovia.app.geo.GeoWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M2-07 / PAY-003 über die echte Anwendung: Anlegen mit Pflichtangaben (422), ETag/If-Match (412/428), Vier-Augen (403), Überlappung (409), Unveränderlichkeit, wirksame Revision, Rechte, Mandantentrennung, Audit. */
class TariffHttpApiTest {
    private fun body(service: String, from: String = "2026-12-01T00:00:00+01:00", to: String? = null, amount: Int = 450) =
        """{"service_id":"$service","tariff_class":"STANDARD","valid_from":"$from","valid_to":${to?.let { "\"$it\"" } ?: "null"},"currency":"EUR","reason":"Wintertarif","rules":[{"rule_type":"FESTPREIS","amount_minor":$amount,"tax_rate":"0.07","tax_rule_revision":"DE-2026-1"}]}"""
    private fun update(from: String, amount: Int) =
        """{"valid_from":"$from","valid_to":null,"reason":"Korrigiert","rules":[{"rule_type":"FESTPREIS","amount_minor":$amount,"tax_rate":"0.07","tax_rule_revision":"DE-2026-1"}]}"""

    @Test
    fun anlegen_pruefen_veroeffentlichen_etag_vier_augen_und_wirksame_revision() {
        val w = GeoWorld(); val svc = w.service()
        val created = w.post("/v1/tariffs", body(svc))
        assertEquals(201, created.status, created.text)
        val id = created.json["id"].asString()
        assertEquals("\"1\"", created.header("ETag")); assertEquals("/v1/tariffs/$id", created.header("Location"))
        assertEquals("ENTWURF", created.json["state"].asString()); assertEquals(1, created.json["revision_no"].asInt()); assertEquals("0.07000000", created.json["rules"][0]["tax_rate"].asString())
        // Entwurf ändern: If-Match Pflicht (428), veraltet 412
        assertEquals(428, w.send("PUT", "/v1/tariffs/$id", update("2026-12-01T00:00:00+01:00", 500)).status)
        assertEquals(412, w.put("/v1/tariffs/$id", update("2026-12-01T00:00:00+01:00", 500), "7").status)
        val upd = w.put("/v1/tariffs/$id", update("2026-12-01T00:00:00+01:00", 500), "1")
        assertEquals(200, upd.status, upd.text); assertEquals(500, upd.json["rules"][0]["amount_minor"].asInt()); assertEquals("\"2\"", upd.header("ETag"))
        // Vier-Augen: der Ersteller prüft nicht selbst, eine zweite Person schon; Veröffentlichen ohne Prüfung geht nicht
        assertEquals(403, w.post("/v1/tariffs/$id/review", "{}", w.admin, "2").status)
        assertEquals(428, w.post("/v1/tariffs/$id/review", "{}", w.planner).status)
        assertEquals(409, w.post("/v1/tariffs/$id/publish", "{}", w.planner, "2").status)
        val reviewed = w.post("/v1/tariffs/$id/review", "{}", w.planner, "2")
        assertEquals(200, reviewed.status, reviewed.text); assertEquals("GEPRUEFT", reviewed.json["state"].asString()); assertEquals("user:${w.plannerSubject}", reviewed.json["reviewed_by"].asString())
        assertEquals(409, w.put("/v1/tariffs/$id", update("2026-12-01T00:00:00+01:00", 600), "3").status, "geprüft: nicht mehr änderbar")
        assertEquals(403, w.post("/v1/tariffs/$id/publish", "{}", w.admin, "3").status)
        assertEquals(412, w.post("/v1/tariffs/$id/publish", "{}", w.planner, "2").status)
        val pub = w.post("/v1/tariffs/$id/publish", "{}", w.planner, "3")
        assertEquals(200, pub.status, pub.text); assertEquals("VEROEFFENTLICHT", pub.json["state"].asString()); assertEquals("\"4\"", pub.header("ETag"))
        // veröffentlicht: unveränderlich (409), Korrektur nur als neue Revision
        assertEquals(409, w.put("/v1/tariffs/$id", update("2026-12-01T00:00:00+01:00", 600), "4").status)
        // wirksame Revision zum Preisstichtag: links geschlossen; davor kein Tarif
        val eff = w.get("/v1/services/$svc/tariff?at=2026-12-01T00:00:00%2B01:00")
        assertEquals(200, eff.status, eff.text); assertEquals(id, eff.json["id"].asString())
        val before = w.get("/v1/services/$svc/tariff?at=2026-11-30T23:59:59%2B01:00")
        assertEquals(404, before.status, before.text); assertTrue(before.json["detail"].asString().contains("Kein Tarif"))
        assertEquals(404, w.get("/v1/services/$svc/tariff?at=2027-01-01T00:00:00Z&tariff_class=ERMAESSIGT").status)
        assertEquals(422, w.get("/v1/services/$svc/tariff?tariff_class=klein").status)
        // Audit: je Änderung ein Eintrag mit Akteur; Ereignis veröffentlicht
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='tariff.revision.published' AND object_ref=? AND actor_ref=?", w.tenant.value, "tariff_revision:$id", "user:${w.plannerSubject}"))
        assertEquals(3, w.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='tariff.revision.changed' AND object_ref=?", w.tenant.value, "tariff_revision:$id"))
        assertEquals(1, w.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=?::uuid AND event_type='tariff.revision_published'", w.tenant.value, id))
        // Liste und Filter
        val list = w.get("/v1/tariffs?service_id=$svc&state=VEROEFFENTLICHT")
        assertEquals(200, list.status, list.text); assertEquals(1, list.json["items"].values().count())
        assertEquals(0, w.get("/v1/tariffs?service_id=$svc&state=ENTWURF").json["items"].values().count())
        assertEquals(422, w.get("/v1/tariffs?state=FERTIG").status)
    }

    @Test
    fun pflichtangaben_422_ueberlappung_409_rechte_403_fremder_mandant_404() {
        val w = GeoWorld(); val other = GeoWorld(); val svc = w.service()
        // ohne Tarifklasse / Gültigkeit / Dienst: 422 mit Feldverweis, nichts gespeichert
        val noClass = w.post("/v1/tariffs", body(svc).replace("\"tariff_class\":\"STANDARD\",", ""))
        assertEquals(422, noClass.status, noClass.text); assertEquals("/tariff_class", noClass.json["violations"][0]["pointer"].asString())
        val noFrom = w.post("/v1/tariffs", body(svc).replace("\"valid_from\":\"2026-12-01T00:00:00+01:00\",", ""))
        assertEquals(422, noFrom.status); assertEquals("/valid_from", noFrom.json["violations"][0]["pointer"].asString())
        assertEquals(422, w.post("/v1/tariffs", body(svc).replace("\"service_id\":\"$svc\",", "")).status)
        assertEquals(422, w.post("/v1/tariffs", body(svc, to = "2026-11-01T00:00:00+01:00")).status, "valid_to <= valid_from")
        assertEquals(422, w.post("/v1/tariffs", body(svc).replace("\"amount_minor\":450", "\"amount_minor\":4.5")).status, "Betrag ist eine ganze Zahl")
        assertEquals(0, w.get("/v1/tariffs?service_id=$svc").json["items"].values().count())
        // Überlappung: beide Revisionen lassen sich anlegen (Entwürfe), die zweite Veröffentlichung scheitert mit 409
        val a = w.post("/v1/tariffs", body(svc, to = "2027-06-01T00:00:00+02:00")).json["id"].asString()
        val b = w.post("/v1/tariffs", body(svc, from = "2027-01-01T00:00:00+01:00", amount = 700)).json["id"].asString()
        for (t in listOf(a, b)) assertEquals(200, w.post("/v1/tariffs/$t/review", "{}", w.planner, "1").status)
        assertEquals(200, w.post("/v1/tariffs/$a/publish", "{}", w.planner, "2").status)
        val overlap = w.post("/v1/tariffs/$b/publish", "{}", w.planner, "2")
        assertEquals(409, overlap.status, overlap.text)
        assertEquals("GEPRUEFT", w.get("/v1/tariffs/$b").json["state"].asString(), "die abgewiesene Veröffentlichung ändert nichts")
        // Rechte: Fahrgast 403 (kein tariffs-Recht), Lesen ohne Schreibrecht nicht möglich für Fahrgäste
        assertEquals(403, w.post("/v1/tariffs", body(svc), w.passenger).status)
        assertEquals(403, w.get("/v1/tariffs", w.passenger).status)
        assertEquals(403, w.post("/v1/tariffs/$a/review", "{}", w.passenger, "1").status)
        // fremder Mandant: Dienst unbekannt, Revision nicht sichtbar
        assertEquals(404, other.post("/v1/tariffs", body(svc)).status)
        assertEquals(404, other.get("/v1/tariffs/$a").status)
        assertEquals(404, other.post("/v1/tariffs/$b/review", "{}", other.planner, "2").status)
        assertEquals(404, other.get("/v1/services/$svc/tariff?at=2027-02-01T00:00:00Z").status)
        assertEquals(0, other.get("/v1/tariffs").json["items"].values().count())
        assertEquals(404, w.get("/v1/tariffs/${UUID.randomUUID()}").status)
    }
}
