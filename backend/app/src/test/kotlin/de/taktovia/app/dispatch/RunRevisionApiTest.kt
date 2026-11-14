package de.taktovia.app.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-40 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `POST` und `GET /v1/runs/{run_id}/revisions` mit Plan-CAS (`If-Match`), Begründung, Dienstberechtigung je Fahrzeug,
 * Sicherheitsprüfung im Commit, geschütztem Stopphorizont, Ausnahmeweg, Idempotenz und Verteilung der neuen Revision an den Fahrer.
 */
class RunRevisionApiTest {
    private val w = ReplanHttpWorld()
    private val env = w.env

    @Test
    fun plan_cas_428_412_201_mit_etag_begruendung_und_planvergleich() {
        val run = w.driverA.run; val rev = w.revision(run)
        // ohne If-Match: 428; mit veraltetem If-Match: 412 mit dem aktuellen ETag; abweichendes base_plan_revision: 422
        assertEquals(428, w.post(run, w.body(w.swap())).status)
        val stale = w.post(run, w.body(w.swap(), base = rev - 1), ifMatch = rev - 1)
        assertEquals(412, stale.status, stale.text); assertEquals("REVISION_MISMATCH", stale.json["code"].asString()); assertEquals("\"$rev\"", stale.header("ETag"))
        assertEquals(422, w.post(run, w.body(w.swap(), base = rev), ifMatch = rev + 1).status)
        assertEquals(rev, w.revision(run))
        // gelesene Revision: 201, ETag = neue Planrevision, Planvergleich und Folgen für die Buchungen
        val ok = w.post(run, w.body(w.swap()), ifMatch = rev)
        assertEquals(201, ok.status, ok.text); assertEquals("\"${rev + 1}\"", ok.header("ETag"))
        assertEquals(rev + 1, ok.json["plan_revision"].asLong()); assertEquals(rev, ok.json["previous_revision"].asLong()); assertTrue(ok.json["committed"].asBoolean())
        assertEquals("MANUAL_CHANGE", ok.json["cause"].asString()); assertEquals("Disposition ordnet die Abholungen um", ok.json["reason"].asString())
        assertTrue(ok.json["created_by"].asString().startsWith("user:")); assertTrue(ok.json["diff"].size() >= 1)
        assertEquals(setOf(w.b1.id.toString(), w.b2.id.toString()), ok.json["impacts"].values().map { it["booking_id"].asString() }.toSet())
        // Begründung und Akteur stehen im Audit
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'dispatch.run.revised' AND object_ref = ? AND redacted_changes->>'reason' = ? AND actor_ref LIKE 'user:%'",
            w.w.tenant.value, "vehicle_run:$run", "Disposition ordnet die Abholungen um"))
        // dieselbe (nun veraltete) Revision erneut: 412
        assertEquals(412, w.post(run, w.body(w.swap(rev), base = rev), ifMatch = rev).status)
    }

    @Test
    fun ohne_begruendung_422_und_ungueltige_aenderungen_ohne_wirkung() {
        val run = w.driverA.run; val rev = w.revision(run)
        for (reason in listOf(null, "", "  ")) {
            val r = w.post(run, w.body(w.swap(), reason = reason), ifMatch = rev)
            assertEquals(422, r.status, "reason=$reason"); assertEquals("VALIDATION_FAILED", r.json["code"].asString()); assertEquals("/reason", r.json["violations"][0]["pointer"].asString())
        }
        assertEquals(422, w.post(run, w.body("""{"change":"MOVE","route_stop_id":"${UUID.randomUUID()}","new_sequence":1}"""), ifMatch = rev).status)
        assertEquals(422, w.post(run, w.body("""{"change":"MOVE","route_stop_id":"${w.stop(run, rev, 1)}","new_sequence":99}"""), ifMatch = rev).status)
        assertEquals(422, w.post(run, w.body(""), ifMatch = rev).status, "mindestens eine Änderung")
        assertEquals(rev, w.revision(run))
    }

    @Test
    fun fremder_run_fremder_dienst_und_fahrer_scheitern_ohne_existenzhinweis() {
        val run = w.driverA.run; val rev = w.revision(run)
        val other = w.post(run, w.body(w.swap()), ifMatch = rev, token = w.otherToken)
        assertEquals(404, other.status, other.text); assertEquals("NOT_FOUND", other.json["code"].asString())
        assertEquals(404, w.get(run, token = w.otherToken).status)
        assertEquals(404, w.post(UUID.randomUUID(), w.body(w.swap()), ifMatch = rev).status)
        assertEquals(404, w.get(UUID.randomUUID()).status)
        // fremder Mandant
        val foreign = ReplanHttpWorld()
        assertEquals(404, w.post(foreign.driverA.run, foreign.body(foreign.swap()), ifMatch = foreign.revision(foreign.driverA.run)).status)
        // Rolle: Fahrer dürfen weder umplanen noch lesen
        assertEquals(403, w.post(run, w.body(w.swap()), ifMatch = rev, token = w.driverA.token).status)
        assertEquals(403, w.get(run, token = w.driverA.token).status)
        assertEquals(rev, w.revision(run))
    }

    @Test
    fun umbuchung_von_a_nach_b_gesperrtes_ziel_horizont_und_ausnahmeweg() {
        val a = w.driverA.run; val b = w.driverB.run
        // das Zielfahrzeug von B ist gesperrt: die Umplanung scheitert im Commit mit 409 CONFLICT, nichts ändert sich
        fun transfer(rev: Long = w.revision(a)) = """{"change":"INSERT","route_stop_id":"${w.stop(a, rev, 0)}","new_sequence":1,"booking_id":"${w.b1.id}"},""" +
            """{"change":"INSERT","route_stop_id":"${w.stop(a, rev, 2)}","new_sequence":2,"booking_id":"${w.b1.id}"}"""
        env.exec("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?::uuid", w.vehicleOf(w.driverB))
        val blocked = w.post(b, w.body(transfer(), base = w.revision(b)), ifMatch = w.revision(b))
        assertEquals(409, blocked.status, blocked.text); assertEquals("CONFLICT", blocked.json["code"].asString())
        assertEquals(1L, w.revision(b))
        env.exec("UPDATE taktovia.vehicle SET active_block = false WHERE id = ?::uuid", w.vehicleOf(w.driverB))
        // Vergleich ohne Commit: 200, committed=false, nichts geschrieben
        val preview = w.post(b, w.body(transfer(), base = 1, extra = ",\"compare_only\":true"), ifMatch = 1)
        assertEquals(200, preview.status, preview.text); assertFalse(preview.json["committed"].asBoolean()); assertEquals(2, preview.json["diff"].size())
        assertEquals(1L, w.revision(b))
        // Umbuchung: B bekommt die Buchung, A verliert sie (je eine neue Revision), der Fahrer von B erhält die Änderung über den Änderungsstrom
        val ok = w.post(b, w.body(transfer(), base = 1), ifMatch = 1)
        assertEquals(201, ok.status, ok.text); assertEquals(2L, w.revision(b)); assertEquals(3L, w.revision(a))
        assertEquals(w.b1.id.toString(), env.string("SELECT booking_id::text FROM taktovia.booking_change WHERE to_run_id = ?", b).single())
        val feed = env.get("/v1/driver/plan-changes?after=0", w.driverB.token)
        assertEquals(200, feed.status, feed.text); assertTrue(feed.json["items"].values().any { it["revision"].asLong() == 2L }, "Fahrer von B sieht die neue Revision")
        // geschützter Stopphorizont: Stopps der Buchung in B liegen in der Zukunft; mit Horizont 24 h (Mandantenparameter) wären sie geschützt – hier wirkt der Planungsdefault; eine Verschiebung in A nahe jetzt
        // wird unten im eigenen Test geprüft
    }

    @Test
    fun geschuetzter_stopphorizont_409_und_dokumentierter_ausnahmeweg() {
        val run = w.driverA.run
        // Plan der nächsten Minuten: der erste Stopp liegt innerhalb des Planungsdefault-Horizonts (10 Minuten)
        val soon = java.time.Instant.now().plusSeconds(120)
        w.w.plan(run, listOf(w.b1, w.b2), base = soon)
        val rev = w.revision(run)
        val denied = w.post(run, w.body(w.swap(rev)), ifMatch = rev)
        assertEquals(409, denied.status, denied.text); assertEquals("PROTECTED_STOP_HORIZON", denied.json["code"].asString())
        assertEquals(rev, w.revision(run))
        val exception = ",\"exception\":{\"rules\":[\"PROTECTED_STOP_HORIZON\"],\"justification\":\"Fahrgast wartet bereits, Disposition genehmigt die Umordnung\"}"
        val ok = w.post(run, w.body(w.swap(rev), extra = exception), ifMatch = rev)
        assertEquals(201, ok.status, ok.text)
        assertEquals(listOf("PROTECTED_STOP_HORIZON"), ok.json["exception"]["rules"].values().map { it.asString() })
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'dispatch.run.replan_exception' AND object_ref = ?", w.w.tenant.value, "vehicle_run:$run"))
        // eine Ausnahme für eine andere Regel hilft nicht
        val rev2 = w.revision(run)
        val wrong = w.post(run, w.body(w.swap(rev2), extra = ",\"exception\":{\"rules\":[\"MAX_REASSIGNMENTS\"],\"justification\":\"Fahrgast wartet bereits, Disposition genehmigt\"}"), ifMatch = rev2)
        assertEquals(409, wrong.status); assertEquals("PROTECTED_STOP_HORIZON", wrong.json["code"].asString())
    }

    @Test
    fun idempotente_wiederholung_erzeugt_keine_zweite_revision() {
        val run = w.driverA.run; val rev = w.revision(run)
        val key = "idem-" + UUID.randomUUID()
        val body = w.body(w.swap())
        val first = w.post(run, body, ifMatch = rev, key = key); assertEquals(201, first.status, first.text)
        val second = w.post(run, body, ifMatch = rev, key = key)
        assertEquals(201, second.status, second.text); assertEquals(first.json["plan_revision"].asLong(), second.json["plan_revision"].asLong())
        assertEquals(rev + 1, w.revision(run))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.plan_change WHERE tenant_id = ? AND run_id = ? AND run_revision = ?", w.w.tenant.value, run, rev + 1))
    }

    @Test
    fun zwei_disponenten_gleichzeitig_genau_eine_revision_gewinnt_der_andere_erhaelt_412() {
        val run = w.driverA.run; val rev = w.revision(run)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val results = (1..2).map { n -> pool.submit<Int> { start.await(); w.post(run, w.body(w.swap(rev), reason = "Disponent $n"), ifMatch = rev).status } }
            start.countDown()
            val statuses = results.map { it.get(60, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(201, 412), statuses)
        } finally { pool.shutdownNow() }
        assertEquals(rev + 1, w.revision(run))
    }

    @Test
    fun revisionsliste_absteigend_mit_begruendung_cursor_und_seitengroesse() {
        val run = w.driverA.run
        var rev = w.revision(run)
        repeat(3) { i -> assertEquals(201, w.post(run, w.body(w.swap(rev), reason = "Änderung ${i + 1}"), ifMatch = rev).status); rev = w.revision(run) }
        val all = w.get(run)
        assertEquals(200, all.status, all.text)
        val items = all.json["items"].values().toList()
        assertEquals(listOf(rev, rev - 1, rev - 2, rev - 3), items.map { it["plan_revision"].asLong() }.take(4))
        assertEquals(listOf("Änderung 3", "Änderung 2", "Änderung 1"), items.take(3).map { it["reason"].asString() })
        assertTrue(items.all { it["created_by"].asString().isNotBlank() })
        // Seitenfolge ohne Lücke und Doppel
        val seen = mutableListOf<Long>(); var cursor: String? = null
        do {
            val page = w.get(run, "?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")); assertEquals(200, page.status, page.text)
            seen += page.json["items"].values().map { it["plan_revision"].asLong() }; cursor = page.json["next_cursor"].takeIf { !it.isNull }?.asString()
        } while (cursor != null)
        assertEquals(items.map { it["plan_revision"].asLong() }, seen)
        assertEquals(422, w.get(run, "?limit=101").status)
        assertEquals(400, w.get(run, "?cursor=kaputt").status)
    }
}
