package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectListQuery
import de.taktovia.fleetsafety.api.DefectSortColumn
import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.TriageDefect
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.KeyedCursorPosition
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-49 / LH-6.22-DMG-009: Schadenliste der Leitstelle gegen die echte Datenbank unter der Runtime-Rolle. Originalabnahme: Testfälle sind eindeutig auffindbar und sortierbar. Positiv: je Filter
 * (Fahrzeug, Priorität, Status, Zeitraum, Depot, Verantwortlicher) genau die erwartete Akte, Filterkombination, Sortierung nach jeder angezeigten Spalte auf- und absteigend mit stabiler Reihenfolge bei
 * gleichen Werten (Tiebreaker `id`) über Seitengrenzen. Negativ: ungültiger Filterwert, `limit` außerhalb 1..100, manipulierter Cursor, fremder Mandant, nicht berechtigter Dienst und fremdes Depot,
 * Listenprojektion ohne vertrauliche Inhalte. Konkurrierend: Statuswechsel während der Paginierung, parallele Abrufe zweier Mandanten über wiederverwendete Poolverbindungen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefectListTest {
    private lateinit var fx: FleetFixture
    private val codec = CursorCodec(ByteArray(32) { (it * 5 + 1).toByte() })

    @BeforeAll fun up() { fx = FleetFixture() }
    @AfterAll fun down() = fx.close()

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    private fun q(vararg kv: Pair<String, String?>) = DefectListQuery.parse(mapOf(*kv))
    private fun ids(w: TriageWorld, query: DefectListQuery, role: String = "dispatcher") = fx.fleet.defects.listPage(w.ctx(role), query).items.map { it.id }

    /** Alle Seiten einer Paginierung (Cursor über Signatur und Bindung wie im Controller); liefert die Akten in der gelieferten Reihenfolge. [after] läuft nach der ersten Seite. */
    private fun pages(w: TriageWorld, sort: String?, limit: Int, after: (() -> Unit)? = null, vararg kv: Pair<String, String?>): List<DefectView> {
        val ctx = w.ctx("dispatcher"); val out = mutableListOf<DefectView>()
        var position: KeyedCursorPosition? = null; var first = true
        val base = mapOf("sort" to sort, "limit" to limit.toString()) + kv
        while (true) {
            val query = DefectListQuery.parse(base, position)
            val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, query.resourceKey)
            val page = fx.fleet.defects.listPage(ctx, query)
            out += page.items
            if (first) { first = false; after?.invoke() }
            position = page.next?.let { codec.decodeKeyed(codec.encodeKeyed(it, scope), scope) } ?: break
        }
        return out
    }

    private fun depot(w: TriageWorld): UUID {
        val region = UUID.randomUUID(); val depot = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:t')", w.tenant.value, region)
        fx.exec("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,'Depot','user:t')", w.tenant.value, depot, region)
        return depot
    }

    private fun vehicle(w: TriageWorld, label: String? = null, depot: UUID? = null): UUID = w.rw.vehicle().also {
        if (label != null) fx.exec("UPDATE taktovia.vehicle SET vehicle_label = ? WHERE id = ?", label, it)
        if (depot != null) fx.exec("UPDATE taktovia.vehicle SET depot_id = ? WHERE id = ?", depot, it)
    }

    @Test
    fun filter_je_kriterium_findet_eindeutig() {
        val w = TriageWorld(fx); val d1 = depot(w); val d2 = depot(w); val ctx = w.ctx("dispatcher"); val assignee = UUID.randomUUID()
        val v1 = vehicle(w, "A-1", d1); val v2 = vehicle(w, "A-2", d2); val v3 = vehicle(w, "A-3"); val v4 = vehicle(w, "A-4")
        val oldOne = w.report(ctx, v1, "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")                    // Priorität 3, NEW, Depot 1
        Thread.sleep(60); val cut = Instant.now(); Thread.sleep(60)
        val critical = w.report(ctx, v2, "BRAKES", "MALFUNCTION", "WHEELS_TIRES", "NOT_ROADWORTHY")       // Priorität 1, Depot 2
        val reviewed = w.report(ctx, v3, "LIGHTS", "MALFUNCTION", "FRONT", "FUNCTION_LIMITED")             // Priorität 2
        val assigned = fx.fleet.defects.triage(ctx, reviewed.id, reviewed.revision, TriageDefect("OPERATIONALLY_RELEVANT", "REVIEWED", true, assignee, null)).defect   // REVIEWED, Verantwortlicher
        val other = w.report(ctx, v4, "OTHER", "OTHER", null, "OTHER")                                      // Priorität 4

        assertEquals(listOf(oldOne.id), ids(w, q("vehicle_id" to v1.toString())))
        assertEquals(listOf(critical.id), ids(w, q("priority" to "1")))
        assertEquals(listOf(other.id), ids(w, q("priority" to "4")))
        assertEquals(listOf(assigned.id), ids(w, q("status" to "REVIEWED")))
        assertEquals(setOf(oldOne.id, critical.id, other.id), ids(w, q("status" to "NEW")).toSet())
        assertEquals(listOf(oldOne.id), ids(w, q("reported_to" to cut.toString())))
        assertEquals(setOf(critical.id, reviewed.id, other.id), ids(w, q("reported_from" to cut.toString())).toSet())
        assertEquals(listOf(oldOne.id), ids(w, q("depot_id" to d1.toString())))
        assertEquals(listOf(critical.id), ids(w, q("depot_id" to d2.toString())))
        assertEquals(listOf(assigned.id), ids(w, q("assignee_ref" to assignee.toString())))
        // Kombination: Priorität und Status und Zeitraum zugleich; widersprüchliche Kombination ist leer
        assertEquals(listOf(critical.id), ids(w, q("priority" to "1", "status" to "NEW", "reported_from" to cut.toString(), "depot_id" to d2.toString())))
        assertEquals(emptyList<UUID>(), ids(w, q("priority" to "1", "vehicle_id" to v1.toString())))
        assertEquals(emptyList<UUID>(), ids(w, q("priority" to "1", "severity" to "COSMETIC")))
        // ohne Filter: alle vier Akten, Standardreihenfolge (created_at, id), Standardlimit 50
        assertEquals(4, ids(w, q()).size); assertEquals(DefectListQuery.DEFAULT_LIMIT, q().limit)
        // der Treffer trägt die Anzeigewerte der Listenspalten
        val item = fx.fleet.defects.listPage(ctx, q("priority" to "1")).items.single()
        assertEquals("SAFETY_CRITICAL", item.severity); assertEquals(critical.caseNumber, item.caseNumber); assertTrue(item.blocksVehicle)
    }

    @Test
    fun sortierung_je_spalte_stabil_mit_tiebreaker() {
        val w = TriageWorld(fx); val ctx = w.ctx("dispatcher"); val a1 = UUID.randomUUID(); val a2 = UUID.randomUUID()
        // sieben Akten mit absichtlich gleichen Werten in Klasse, Status und Zuständigem (die Fahrzeugbezeichnung ist je Mandant eindeutig)
        val labels = listOf("B2", "A2", "B1", "A1", "C1", "B3", "A3")
        val rows = labels.mapIndexed { i, label ->
            val v = vehicle(w, label); val d = w.report(ctx, v, if (i % 3 == 0) "BODY" else "LIGHTS", "SCRATCH", "FRONT", if (i % 3 == 0) "COSMETIC_ONLY" else "FUNCTION_LIMITED")
            when (i % 4) {
                1 -> fx.fleet.defects.triage(ctx, d.id, d.revision, TriageDefect(d.severity!!, "REVIEWED", true, a1, null)).defect
                2 -> fx.fleet.defects.triage(ctx, d.id, d.revision, TriageDefect(d.severity!!, "REVIEWED", true, a2, null)).defect
                else -> d
            }
        }
        val label = rows.associate { it.id to labels[rows.indexOf(it)] }
        fun key(column: DefectSortColumn, d: DefectView): String = when (column) {
            DefectSortColumn.CASE_NUMBER -> d.caseNumber
            DefectSortColumn.VEHICLE -> label.getValue(d.id)
            DefectSortColumn.PRIORITY -> DefectListQuery.PRIORITIES.entries.first { it.value == d.severity }.key.toString()
            DefectSortColumn.STATUS -> listOf("NEW", "REVIEWED", "IN_PROGRESS", "REPAIR", "CLOSED", "DISMISSED").indexOf(d.status).toString()
            DefectSortColumn.ASSIGNEE -> d.assigneeRef?.toString() ?: ""
            DefectSortColumn.REPORTED_AT -> d.reportedAt.toString()
            DefectSortColumn.CREATED -> d.createdAt.toString()
        }
        for (column in DefectSortColumn.entries) for (desc in listOf(false, true)) {
            val token = (if (desc) "-" else "") + column.token
            val numeric = column == DefectSortColumn.PRIORITY || column == DefectSortColumn.STATUS
            val cmp = Comparator<DefectView> { x, y -> val a = key(column, x); val b = key(column, y); (if (numeric) a.toInt().compareTo(b.toInt()) else a.compareTo(b)).takeIf { it != 0 } ?: x.id.toString().compareTo(y.id.toString()) }
            val expected = rows.sortedWith(if (desc) cmp.reversed() else cmp).map { it.id }
            // in einer Seite und über Seitengrenzen (limit 3) dieselbe Reihenfolge, jede Akte genau einmal
            val whole = ids(w, DefectListQuery.parse(mapOf("sort" to if (column == DefectSortColumn.CREATED) null else token, "limit" to "100")).let { if (column == DefectSortColumn.CREATED) it.copy(sort = de.taktovia.fleetsafety.api.DefectSort(column, desc)) else it })
            assertEquals(expected, whole, "Sortierung $token")
            val paged = pages(w, if (column == DefectSortColumn.CREATED) null else token, 3).map { it.id }
            if (column != DefectSortColumn.CREATED) assertEquals(expected, paged, "Paginierung $token")
        }
        // gleiche Werte: die Reihenfolge ist wiederholbar (zweiter Lauf identisch)
        assertEquals(ids(w, q("sort" to "priority")), ids(w, q("sort" to "priority")))
    }

    @Test
    fun sortierung_stabil_bei_statuswechsel_waehrend_paginierung() {
        for (sort in listOf("status", "-status", "priority", "-priority", "case_number", "assignee")) {
            val w = TriageWorld(fx); val ctx = w.ctx("dispatcher"); val workshop = w.ctx("workshop")   // je Sortierung ein eigener Bestand von sieben Akten mit gleichem Status und gleicher Klasse
            val all = (1..7).map { w.report(ctx, w.rw.vehicle(), "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY") }
            val expected = all.map { it.id }.toSet(); var late: DefectView? = null; var moved = emptyList<UUID>()
            val seen = pages(w, sort, 3, after = {
                // nach der ersten Seite: eine bereits gelieferte und eine noch nicht gelieferte Akte wechseln Status, Klasse und Zuständigen; zusätzlich entsteht eine neue Akte
                val delivered = fx.fleet.defects.listPage(ctx, q("sort" to sort, "limit" to "3")).items
                val undelivered = all.first { a -> delivered.none { it.id == a.id } }
                moved = listOf(delivered.first().id, undelivered.id)
                for (id in moved) { val now = fx.fleet.defects.get(workshop, id); fx.fleet.defects.triage(workshop, id, now.revision, TriageDefect("OPERATIONALLY_RELEVANT", "IN_PROGRESS", true, UUID.randomUUID(), "höher eingestuft")) }
                late = w.report(ctx, w.rw.vehicle(), "BODY", "SCRATCH", "LEFT_SIDE", "COSMETIC_ONLY")
            }).map { it.id }
            assertEquals(2, moved.size)
            assertEquals(expected, seen.toSet(), "$sort: keine Akte geht verloren und keine fremde kommt hinzu")
            assertEquals(seen.size, seen.toSet().size, "$sort: keine Akte erscheint doppelt")
            assertFalse(late!!.id in seen, "$sort: eine nach der ersten Seite angelegte Akte gehört nicht zur Momentaufnahme")
            // die Momentaufnahme gilt je Paginierung: ein neuer Durchlauf sieht den neuen Stand (alle acht Akten)
            assertEquals(8, ids(w, q("limit" to "100")).size)
        }
    }

    @Test
    fun ungueltiger_filter_limit_cursor_abgewiesen() {
        fun pointers(vararg kv: Pair<String, String?>) = problem { q(*kv) }.also { assertEquals(422, it.code.status) }.violations.map { it.pointer }
        assertEquals(listOf("/\$query/vehicle_id"), pointers("vehicle_id" to "kein-uuid"))
        assertEquals(listOf("/\$query/depot_id"), pointers("depot_id" to "x"))
        assertEquals(listOf("/\$query/assignee_ref"), pointers("assignee_ref" to "x"))
        assertEquals(listOf("/\$query/priority"), pointers("priority" to "9")); assertEquals(listOf("/\$query/priority"), pointers("priority" to "0")); assertEquals(listOf("/\$query/priority"), pointers("priority" to "hoch"))
        assertEquals(listOf("/\$query/status"), pointers("status" to "ERLEDIGT")); assertEquals(listOf("/\$query/severity"), pointers("severity" to "S1"))
        assertEquals(listOf("/\$query/reported_from"), pointers("reported_from" to "gestern"))
        assertEquals(listOf("/\$query/reported_to"), pointers("reported_from" to "2026-02-01T00:00:00Z", "reported_to" to "2026-01-01T00:00:00Z"))
        assertEquals(listOf("/\$query/sort"), pointers("sort" to "updated_at")); assertEquals(listOf("/\$query/sort"), pointers("sort" to "--status"))
        for (bad in listOf("0", "101", "-3", "abc", "1000")) assertEquals(listOf("/\$query/limit"), pointers("limit" to bad), "limit=$bad")
        assertEquals(setOf(1, 100), listOf("1", "100").map { q("limit" to it).limit }.toSet())
        // alle Fehler zugleich gesammelt
        assertEquals(3, problem { q("limit" to "0", "status" to "X", "vehicle_id" to "y") }.violations.size)
        // Cursor: manipuliert, fremd (anderer Mandant, Akteur, Abfrage), abgeschnitten, alter Positionscursor
        val w = TriageWorld(fx); val ctx = w.ctx("dispatcher"); val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, q("sort" to "status").resourceKey)
        val good = codec.encodeKeyed(KeyedCursorPosition("1700000000000000~1", UUID.randomUUID()), scope)
        assertEquals(1, codec.decodeKeyed(good, scope).key.length.coerceAtMost(1))
        fun bad(token: String, s: CursorScope = scope) = assertEquals(ErrorCode.INVALID_CURSOR, problem { codec.decodeKeyed(token, s) }.code, token)
        bad(good.dropLast(2) + (if (good.last() == 'A') "BB" else "AA")); bad(good.substring(0, good.length - 8)); bad(good.replaceFirst("v2.", "v3.")); bad("v2.x.y"); bad("")
        bad(good, CursorScope(UUID.randomUUID().toString(), ctx.actor.subject, scope.resource)); bad(good, CursorScope(ctx.tenant.toString(), UUID.randomUUID().toString(), scope.resource)); bad(good, CursorScope(ctx.tenant.toString(), ctx.actor.subject, q("sort" to "-status").resourceKey))
        bad(codec.encode(de.taktovia.platform.api.CursorPosition(Instant.now(), UUID.randomUUID()), scope))
        // gültig signierter, aber unpassender Sortierwert (Zahl erwartet) wird wie ein manipulierter Cursor abgewiesen
        val odd = codec.decodeKeyed(codec.encodeKeyed(KeyedCursorPosition("1700000000000000~kein-int", UUID.randomUUID()), scope), scope)
        assertEquals(ErrorCode.INVALID_CURSOR, problem { fx.fleet.defects.listPage(ctx, DefectListQuery.parse(mapOf("sort" to "status"), odd)) }.code)
        assertEquals(ErrorCode.INVALID_CURSOR, problem { fx.fleet.defects.listPage(ctx, DefectListQuery.parse(mapOf("sort" to "status"), KeyedCursorPosition("kaputt", UUID.randomUUID()))) }.code)
    }

    @Test
    fun fremder_mandant_und_nicht_berechtigtes_depot_nicht_sichtbar() {
        val a = TriageWorld(fx); val b = TriageWorld(fx); val ctxA = a.ctx("dispatcher"); val ctxB = b.ctx("dispatcher")
        val depotA = depot(a); val depotB = depot(b); val va = vehicle(a, "A", depotA); val vb = vehicle(b, "B", depotB)
        val da = a.report(ctxA, va, "BODY"); val db = b.report(ctxB, vb, "BODY")
        assertEquals(listOf(da.id), ids(a, q())); assertEquals(listOf(db.id), ids(b, q()))
        // Filter dürfen nur einschränken: Fahrzeug, Depot und Zuständiger des anderen Mandanten ergeben nichts, auch nicht die Akte selbst
        assertEquals(emptyList<UUID>(), ids(a, q("vehicle_id" to vb.toString()))); assertEquals(emptyList<UUID>(), ids(a, q("depot_id" to depotB.toString())))
        assertEquals(404, problem { fx.fleet.defects.get(ctxA, db.id) }.code.status)
        // Dienstscope: Fahrzeug im Pool eines Dienstes ist nur mit diesem Dienst im Scope sichtbar (fail-closed bei leerem Scope), ein Fahrzeug ohne Dienstzuordnung für alle des Mandanten
        val s1 = service(a); val s2 = service(a); val pooled1 = vehicle(a, "P1"); val pooled2 = vehicle(a, "P2")
        for ((v, s) in listOf(pooled1 to s1, pooled2 to s2)) fx.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','user:t')", a.tenant.value, UUID.randomUUID(), s, v)
        val p1 = a.report(ctxA, pooled1, "BODY"); val p2 = a.report(ctxA, pooled2, "BODY")
        val scoped1 = a.ctx("dispatcher", services = setOf(s1)); val noScope = a.ctx("dispatcher")
        assertEquals(setOf(da.id, p1.id), fx.fleet.defects.listPage(scoped1, q()).items.map { it.id }.toSet(), "nur Dienst 1 und Akten ohne Dienstbezug")
        assertEquals(setOf(da.id), fx.fleet.defects.listPage(noScope, q()).items.map { it.id }.toSet(), "ohne Dienstscope nur Akten ohne Dienstbezug")
        assertEquals(setOf(da.id, p1.id, p2.id), ids(a, q()).toSet().let { if (it.size == 3) it else it }.let { fx.fleet.defects.listPage(a.ctx("dispatcher", services = setOf(s1, s2)), q()).items.map { x -> x.id }.toSet() })
        assertEquals(404, problem { fx.fleet.defects.get(scoped1, p2.id) }.code.status); assertEquals(p1.id, fx.fleet.defects.get(scoped1, p1.id).id)
        // ein Filter auf den anderen Dienst erweitert die Sicht nicht (Dienst 2 ist nicht im Scope), Fahrer sehen nur eigene Meldungen
        assertEquals(emptyList<UUID>(), fx.fleet.defects.listPage(scoped1, q("vehicle_id" to pooled2.toString())).items.map { it.id })
        val driver = a.ctx("driver"); assertEquals(emptyList<UUID>(), fx.fleet.defects.listPage(driver, q()).items.map { it.id })
    }

    private fun service(w: TriageWorld): UUID {
        val t = w.tenant.value; val cfg = UUID.randomUUID(); val svc = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, svc)
        val org = UUID.randomUUID(); val le = fx.strings("SELECT id::text FROM taktovia.legal_entity WHERE tenant_id = ? LIMIT 1", t).single()
        fx.exec("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,CAST(? AS uuid),?,?,CAST(? AS text[]))", t, org, le, "Org", "f-$org", "{TRANSPORT_COMPANY}")
        fx.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t, svc, org, cfg)
        return svc
    }

    @Test
    fun fremder_mandant_poolverbindung_isoliert() {
        val a = TriageWorld(fx); val b = TriageWorld(fx)
        val idsA = (1..4).map { a.report(a.ctx("dispatcher"), a.rw.vehicle(), "BODY").id }.toSet(); val idsB = (1..3).map { b.report(b.ctx("dispatcher"), b.rw.vehicle(), "BODY").id }.toSet()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val tasks = (1..160).map { i ->
                pool.submit<Pair<Boolean, Set<UUID>>> {
                    start.await(); val ownerA = i % 2 == 0; val w = if (ownerA) a else b
                    ownerA to fx.fleet.defects.listPage(w.ctx("dispatcher"), q("limit" to "100")).items.map { it.id }.toSet()
                }
            }
            start.countDown()
            for (t in tasks) { val (ownerA, seen) = t.get(60, TimeUnit.SECONDS); assertEquals(if (ownerA) idsA else idsB, seen, "jeder Abruf liefert genau die Akten des eigenen Mandanten") }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun projektion_ohne_vertrauliche_inhalte() {
        val w = TriageWorld(fx); val ctx = w.ctx("workshop"); val v = w.rw.vehicle()
        val d = w.report(ctx, v, "BODY", extra = mapOf("description" to "Ausführliche Schilderung mit Details"))
        fx.exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by,confidentiality) VALUES (?,?,?,'obj-1',?,now(),'user:t','CONFIDENTIAL')", w.tenant.value, UUID.randomUUID(), d.id, "0".repeat(64))
        fx.exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (?,?,?,'obj-2',?,now(),'user:t')", w.tenant.value, UUID.randomUUID(), d.id, "1".repeat(64))
        val full = fx.fleet.defects.get(ctx, d.id)
        assertEquals("Ausführliche Schilderung mit Details", full.description); assertTrue(full.evidence.isNotEmpty())
        val item = fx.fleet.defects.listPage(ctx, q()).items.single()
        assertNull(item.description, "kein Beschreibungstext in der Liste"); assertTrue(item.evidence.isEmpty()); assertEquals(0, item.evidencePending)
        assertEquals(d.caseNumber, item.caseNumber); assertEquals(v, item.vehicleId)
    }
}
