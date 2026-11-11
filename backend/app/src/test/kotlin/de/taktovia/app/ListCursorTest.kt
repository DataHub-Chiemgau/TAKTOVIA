package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Listen: `{items,next_cursor,as_of}`, stabile Sortierung `(created_at,id)`, signierter Cursor (Kap. 05 §5.1). */
class ListCursorTest {
    private val s get() = ProbeServer.get()
    private fun hdr(tenant: java.util.UUID, actor: String) = mapOf("X-Test-Tenant" to tenant.toString(), "X-Test-Actor" to actor)

    @Test
    fun stabile_reihenfolge_und_manipulierter_cursor_400() {
        val a = hdr(ProbeDb.tenantA, "list-actor")
        val ids = (1..7).map { s.post("/v1/probe/items", """{"name":"liste-$it"}""", a).json["id"].asString() }
        // alle Seiten à 3 durchlaufen; Reihenfolge stabil und ohne Lücke oder Dublette
        val seen = mutableListOf<String>(); val createdAt = mutableListOf<String>()
        var cursor: String? = null; var pages = 0
        do {
            val r = s.get("/v1/probe/items?limit=3" + (cursor?.let { "&cursor=$it" } ?: ""), a)
            assertEquals(200, r.status, r.text)
            assertTrue(r.json.has("as_of") && r.json.has("next_cursor") && r.json.has("items"))
            r.json["items"].values().forEach { seen += it["id"].asString(); createdAt += it["created_at"].asString() }
            cursor = r.json["next_cursor"].takeUnless { it.isNull }?.asString()
            pages++
        } while (cursor != null && pages < 50)
        assertTrue(seen.containsAll(ids), "alle angelegten Einträge sind enthalten")
        assertEquals(seen.size, seen.toSet().size, "keine Dublette über Seiten")
        assertEquals(createdAt.sorted(), createdAt, "Reihenfolge (created_at, id) stabil")
        assertTrue(pages >= 3)
        // zweiter Durchlauf liefert dieselbe Reihenfolge
        val again = mutableListOf<String>(); cursor = null
        do {
            val r = s.get("/v1/probe/items?limit=3" + (cursor?.let { "&cursor=$it" } ?: ""), a)
            r.json["items"].values().forEach { again += it["id"].asString() }
            cursor = r.json["next_cursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        assertEquals(seen, again)
        // letzte Seite hat next_cursor null
        val all = s.get("/v1/probe/items?limit=100", a)
        assertTrue(all.json["next_cursor"].isNull)
        assertNull(all.json["next_cursor"].takeUnless { it.isNull })

        // manipulierter Cursor: 400 INVALID_CURSOR, nie Daten
        val first = s.get("/v1/probe/items?limit=2", a)
        val good = first.json["next_cursor"].asString()
        val tampered = good.dropLast(3) + (if (good.takeLast(3) == "AAA") "BBB" else "AAA")
        listOf(tampered, "murks", good.replace("v1.", "v9."), good.substringBeforeLast('.')).forEach {
            val r = s.get("/v1/probe/items?cursor=$it", a)
            assertEquals(400, r.status, it); assertEquals("INVALID_CURSOR", r.json["code"].asString())
            assertTrue(!r.json.has("items"))
        }
        // Cursor eines anderen Akteurs oder Mandanten: ebenfalls 400, kein Datenzugriff über fremden Cursor
        assertEquals(400, s.get("/v1/probe/items?cursor=$good", hdr(ProbeDb.tenantA, "anderer-akteur")).status)
        assertEquals(400, s.get("/v1/probe/items?cursor=$good", hdr(ProbeDb.tenantB, "list-actor")).status)
        assertEquals(200, s.get("/v1/probe/items?cursor=$good", a).status)
    }

    @Test
    fun limit_grenzen() {
        val a = hdr(ProbeDb.tenantA, "limit-actor")
        listOf("0", "101", "-3", "abc").forEach {
            val r = s.get("/v1/probe/items?limit=$it", a)
            assertEquals(422, r.status, it)
            assertEquals("/\$query/limit", r.json["violations"][0]["pointer"].asString())
        }
        assertEquals(200, s.get("/v1/probe/items?limit=1", a).status)
        assertEquals(200, s.get("/v1/probe/items?limit=100", a).status)
        assertEquals(200, s.get("/v1/probe/items", a).status)
    }

    @Test
    fun mandanten_sehen_nur_eigene_eintraege_in_der_liste() {
        s.post("/v1/probe/items", """{"name":"nur-b"}""", hdr(ProbeDb.tenantB, "x"))
        val names = s.get("/v1/probe/items?limit=100", hdr(ProbeDb.tenantA, "x")).json["items"].values().map { it["name"].asString() }
        assertTrue("nur-b" !in names)
    }
}
