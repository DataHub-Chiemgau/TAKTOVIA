package de.taktovia.app.driver

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-05 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Fahrerstamm mit Zuordnung zu genau einer Mitgliedschaft, Qualifikationsrevisionen (Freigabe, Entzug, Gültigkeit),
 * HR-nahe Felder nur für die Personalplanung, 404 für fremde Fahrer, Rechte, Revisionsschutz, Audit; die Projektion der Fahrerfreigabe (WP-M1-35) zeigt nur freigegebene Qualifikationen.
 */
class DriverMasterApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Fahrer-" + UUID.randomUUID())
        val hr = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val support = UUID.randomUUID(); val admin = UUID.randomUUID()
        init { env.grant(tenant, hr, "staff_planning"); env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, support, "support"); env.grant(tenant, admin, "tenant_admin") }
        val hrToken = env.token(hr); val dispatcherToken = env.token(dispatcher); val supportToken = env.token(support); val adminToken = env.token(admin)
        fun driverMembership(): UUID = env.grant(tenant, UUID.randomUUID(), "driver")
        fun body(membership: UUID = driverMembership(), name: String = "Erika Muster") = """{"membership_id":"$membership","display_name":"$name","contact_channel":"PHONE","contact_value":"+491701234567"}"""
        fun qual(code: String = "PBEF", decision: String = "APPROVED", from: String = "2026-01-01T00:00:00Z", to: String? = null, evidence: String? = "akte-4711") =
            """{"qualification_code":"$code","valid_from":"$from"${if (to != null) ""","valid_to":"$to"""" else ""}${if (evidence != null) ""","evidence_ref":"$evidence"""" else ""},"decision":"$decision"}"""
    }

    private fun create(w: World, body: String = w.body(), token: String = w.hrToken) = env.post("/v1/drivers", body, token, null, idem())
    private fun addQual(w: World, id: String, body: String, revision: Long, token: String = w.hrToken) =
        env.server.send("POST", "/v1/drivers/$id/qualifications", body, env.headers(token, extra = idem() + ("If-Match" to "\"$revision\"")))

    @Test
    fun anlegen_lesen_hr_felder_und_rechte() {
        val w = World(env)
        val c = create(w, w.body(name = "Erika Geheimname")); assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag"))
        val id = c.json["id"].asString(); assertEquals("/v1/drivers/$id", c.header("Location"))
        assertEquals("ACTIVE", c.json["status"].asString()); assertEquals(0, c.json["qualification_seq"].asInt()); assertTrue(c.json["hr_fields_visible"].asBoolean())
        val hr = env.get("/v1/drivers/$id", w.hrToken); assertEquals("Erika Geheimname", hr.json["display_name"].asString()); assertEquals("+491701234567", hr.json["contact_value"].asString())
        // Dispositionssicht: lesen erlaubt, HR-nahe Felder null
        val dispo = env.get("/v1/drivers/$id", w.dispatcherToken); assertEquals(200, dispo.status)
        assertFalse(dispo.json["hr_fields_visible"].asBoolean()); assertTrue(dispo.json["display_name"].isNull); assertTrue(dispo.json["contact_value"].isNull)
        assertFalse(dispo.text.contains("Geheimname") || dispo.text.contains("+49170"))
        assertTrue(env.get("/v1/drivers", w.dispatcherToken).json["items"].values().all { it["display_name"].isNull })
        assertEquals(1, env.get("/v1/drivers?status=ACTIVE", w.hrToken).json["items"].size()); assertEquals(0, env.get("/v1/drivers?status=INACTIVE", w.hrToken).json["items"].size())
        assertEquals(422, env.get("/v1/drivers?status=EGAL", w.hrToken).status)
        // Rechte: Disposition schreibt nicht, Support und Administrator haben keinen Zugriff (HR)
        assertEquals(403, create(w, w.body(), w.dispatcherToken).status); assertEquals(403, env.get("/v1/drivers", w.supportToken).status); assertEquals(403, env.get("/v1/drivers/$id", w.adminToken).status)
        // Auditeintrag mit Akteur, ohne Klarnamen und Kontakt
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.driver.created' AND object_ref=? AND actor_ref=?", w.tenant.value, "driver:$id", "user:${w.hr}"))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND (redacted_changes::text LIKE '%Geheimname%' OR redacted_changes::text LIKE '%+49170%')", w.tenant.value))
    }

    @Test
    fun mitgliedschaft_genau_einem_fahrer_felder_und_fremder_mandant() {
        val w = World(env); val other = World(env)
        val m = w.driverMembership()
        val first = create(w, w.body(m)); assertEquals(201, first.status, first.text)
        val dup = create(w, w.body(m)); assertEquals(409, dup.status, dup.text); assertTrue(dup.json["violations"].values().any { it["pointer"].asString() == "/membership_id" })
        assertEquals(422, create(w, w.body(UUID.randomUUID())).status, "unbekannte Mitgliedschaft")
        assertEquals(422, create(w, w.body(other.driverMembership())).status, "Mitgliedschaft eines fremden Mandanten")
        assertEquals(422, create(w, w.body(env.grant(w.tenant, UUID.randomUUID(), "dispatcher"))).status, "falsche Rolle")
        assertEquals(422, create(w, w.body().replace("\"display_name\":\"Erika Muster\",", "")).status)
        assertEquals(422, create(w, w.body().replace("}", ",\"tenant_id\":\"${w.tenant.value}\"}")).status)
        val id = first.json["id"].asString()
        // fremder Mandant: 404 in allen Verben
        assertEquals(404, env.get("/v1/drivers/$id", other.hrToken).status)
        assertEquals(404, env.get("/v1/drivers/$id/qualifications", other.hrToken).status)
        assertEquals(404, addQual(other, id, other.qual(), 1).status)
        assertEquals(404, env.put("/v1/drivers/$id", """{"display_name":"X","contact_channel":"APP","contact_value":"x","active":false}""", other.hrToken, "\"1\"").status)
        // Änderung mit If-Match: 428/412; Deaktivieren
        val body = """{"display_name":"Erika Neu","contact_channel":"EMAIL","contact_value":"e@example.org","active":false}"""
        assertEquals(428, env.server.send("PUT", "/v1/drivers/$id", body, env.headers(w.hrToken, extra = idem())).status)
        val u = env.put("/v1/drivers/$id", body, w.hrToken, "\"1\""); assertEquals(200, u.status, u.text); assertEquals("INACTIVE", u.json["status"].asString()); assertEquals("\"2\"", u.header("ETag"))
        assertEquals(412, env.put("/v1/drivers/$id", body, w.hrToken, "\"1\"").status)
        assertEquals(422, env.put("/v1/drivers/$id", body.replace("}", ",\"membership_id\":\"${m}\"}"), w.hrToken, "\"2\"").status, "Mitgliedschaft nicht änderbar")
    }

    @Test
    fun qualifikationsrevisionen_freigabe_entzug_gueltigkeit_und_alte_revisionen_lesbar() {
        val w = World(env)
        val id = create(w).json["id"].asString()
        val noMatch = env.server.send("POST", "/v1/drivers/$id/qualifications", w.qual(), env.headers(w.hrToken, extra = idem()))
        assertEquals(428, noMatch.status)
        val q1 = addQual(w, id, w.qual(), 1); assertEquals(201, q1.status, q1.text); assertEquals("\"2\"", q1.header("ETag")); assertEquals(2, q1.json["driver_revision"].asInt())
        assertEquals(1, q1.json["revision"].asInt()); assertEquals(1, q1.json["driver_seq"].asInt()); assertTrue(q1.json["valid_at"].asBoolean()); assertEquals("user:${w.hr}", q1.json["approved_by"].asString())
        assertEquals(412, addQual(w, id, w.qual(), 1).status)
        // wartende Revision, abgelaufene, zukünftige und entzogene Qualifikation
        var rev = 2L
        fun add(body: String): Long { val r = addQual(w, id, body, rev); assertEquals(201, r.status, r.text); rev = r.json["driver_revision"].asLong(); return r.json["revision"].asLong() }
        add(w.qual("WARTEND", "PENDING")); add(w.qual("ABGELAUFEN", to = "2026-06-01T00:00:00Z")); add(w.qual("ZUKUENFTIG", from = "2027-01-01T00:00:00Z"))
        add(w.qual("ENTZOGEN")); add(w.qual("ENTZOGEN", "REVOKED"))
        assertEquals(2L, add(w.qual("PBEF", to = "2027-12-31T00:00:00Z")), "Änderung = neue Revision")
        fun valid(at: String? = null) = env.get("/v1/drivers/$id/qualifications?only_valid=true${if (at != null) "&at=$at" else ""}", w.hrToken).json["items"].values().map { it["qualification_code"].asString() }.sorted()
        assertEquals(listOf("PBEF"), valid("2026-11-12T08:00:00Z"), "abgelaufen, wartend, zukünftig, entzogen gelten als nicht vorhanden")
        assertEquals(listOf("PBEF", "ZUKUENFTIG"), valid("2027-02-01T00:00:00Z"))
        val all = env.get("/v1/drivers/$id/qualifications?at=2026-11-12T08:00:00Z", w.hrToken).json["items"].values()
        assertEquals(listOf(2, 1), all.filter { it["qualification_code"].asString() == "PBEF" }.map { it["revision"].asInt() }, "die alte Revision bleibt lesbar")
        assertEquals(setOf("akte-4711"), all.mapNotNull { it["evidence_ref"].takeIf { n -> !n.isNull }?.asString() }.toSet())
        // Dispositionssicht ohne Nachweisverweis
        assertTrue(env.get("/v1/drivers/$id/qualifications", w.dispatcherToken).json["items"].values().all { it["evidence_ref"].isNull })
        // fortlaufende Nummer des Fahrers zählt nur wirksame Änderungen (wartend zählt nicht): 1 PBEF, ABGELAUFEN, ZUKUENFTIG, 2 ENTZOGEN, PBEF-Revision 2 = 6
        assertEquals(6, env.get("/v1/drivers/$id", w.hrToken).json["qualification_seq"].asInt())
        assertEquals(422, addQual(w, id, w.qual("klein"), rev).status); assertEquals(422, addQual(w, id, w.qual(decision = "VIELLEICHT"), rev).status)
        assertEquals(422, addQual(w, id, w.qual(from = "2026-02-01T00:00:00Z", to = "2026-01-01T00:00:00Z"), rev).status)
        assertEquals(7, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.qualification.changed' AND object_ref=?", w.tenant.value, "driver:$id"), "jede Änderung genau ein Audit")
    }

    @Test
    fun projektion_der_fahrerfreigabe_zeigt_nur_freigegebene_qualifikationen() {
        val owner = World(env); val recipient = env.tenant("Empf-" + UUID.randomUUID()); val dispatcher = UUID.randomUUID(); val service = UUID.randomUUID()
        env.grant(recipient, dispatcher, "dispatcher")
        val id = create(owner).json["id"].asString(); var rev = 1L
        fun add(body: String) { val r = addQual(owner, id, body, rev); check(r.status == 201) { r.text }; rev = r.json["driver_revision"].asLong() }
        add(owner.qual("PBEF")); add(owner.qual("WARTEND", "PENDING")); add(owner.qual("ENTZOGEN")); add(owner.qual("ENTZOGEN", "REVOKED")); add(owner.qual("NEU_WARTEND")); add(owner.qual("NEU_WARTEND", "PENDING"))
        val grant = env.post("/v1/driver-releases", """{"driver_id":"$id","recipient_tenant_id":"${recipient.value}","service_ids":["$service"],"valid_from":"2026-01-01T00:00:00Z","valid_to":"2099-01-01T00:00:00Z"}""", owner.hrToken, null, idem())
        assertEquals(201, grant.status, grant.text)
        val p = env.get("/v1/driver-releases/${grant.json["id"].asString()}/projection?service_id=$service", env.token(dispatcher))
        assertEquals(200, p.status, p.text)
        assertEquals(setOf("PBEF", "NEU_WARTEND"), p.json["qualifications"].values().map { it["code"].asString() }.toSet(), "wartende und entzogene Qualifikationen werden nie projiziert; eine wartende Neufassung ändert die geltende Freigabe nicht")
        assertFalse(p.text.contains("akte-4711"))
    }
}
