package de.taktovia.app.isolation

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M5-17 (Sicherheitsabnahme, konkurrierende Fälle und URL-Fälle) gegen die echte Anwendung (HTTP, Runtime-Rolle, echte Datenbank, lokaler JWT-Test-Issuer statt Keycloak):
 *
 * - Rollenentzug während einer aktiven Sitzung unter Last: sechs Threads rufen mit dem Token der Sitzung dauernd einen geschützten Endpunkt auf, die Mitgliedschaft wird entzogen;
 *   keine Anfrage, die **nach** dem Entzug begonnen hat, darf noch Erfolg haben, und eine unbeteiligte Sitzung desselben Mandanten bleibt unberührt.
 * - Parallele Anfragen zweier Mandanten über denselben Verbindungspool der Anwendung: jede Antwort enthält ausschließlich Daten des eigenen Mandanten.
 * - Erratene, manipulierte, abgelaufene und mandantenfremde Abruf-Adressen sind von einem nicht vorhandenen Objekt nicht zu unterscheiden (gleichartige 404-Antwort, kein Hinweis auf Existenz).
 *
 * Grenze: Lastgröße und Dauer sind klein (Sekundenbereich); das ist ein Nebenläufigkeits-, kein Leistungstest (Lastläufe: tests/performance/refload).
 */
class SicherheitsabnahmeApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "sec-" + UUID.randomUUID())

    @Test
    fun rollenentzug_waehrend_aktiver_sitzung_unter_last() {
        val t = env.tenant("Entzug-" + UUID.randomUUID())
        val victim = UUID.randomUUID(); val bystander = UUID.randomUUID()
        val m = env.grant(t, victim, "tenant_admin"); env.grant(t, bystander, "tenant_admin")
        val victimToken = env.token(victim); val bystanderToken = env.token(bystander)
        assertEquals(200, env.get("/v1/memberships", victimToken).status)

        val revokedAtNanos = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val stop = AtomicBoolean(false); val before = AtomicInteger(); val lateSuccess = AtomicInteger(); val lateDenied = AtomicInteger(); val bystanderFailures = AtomicInteger()
        val started = CountDownLatch(6)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val workers = List(6) { i ->
                pool.submit(Callable {
                    started.countDown()
                    while (!stop.get()) {
                        val start = System.nanoTime()
                        val r = env.get("/v1/memberships", victimToken)
                        if (start > revokedAtNanos.get()) { if (r.status == 200) lateSuccess.incrementAndGet() else if (r.status == 403 || r.status == 401) lateDenied.incrementAndGet() }
                        else if (r.status == 200) before.incrementAndGet()
                        if (i == 0 && env.get("/v1/memberships", bystanderToken).status != 200) bystanderFailures.incrementAndGet()
                    }
                })
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            Thread.sleep(800)
            env.revokeDirect(t, m)
            revokedAtNanos.set(System.nanoTime())
            Thread.sleep(1500)
            stop.set(true)
            workers.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { stop.set(true); pool.shutdownNow() }
        assertTrue(before.get() > 0, "vor dem Entzug liefen Anfragen mit Erfolg (Lastaufbau)")
        assertEquals(0, lateSuccess.get(), "nach dem Entzug darf keine neu begonnene Anfrage der Sitzung Erfolg haben")
        assertTrue(lateDenied.get() > 0, "nach dem Entzug wurden Anfragen verweigert")
        assertEquals(0, bystanderFailures.get(), "die Sitzung eines anderen Mitglieds bleibt unberührt")
        assertEquals(403, env.get("/v1/memberships", victimToken).status)
    }

    @Test
    fun parallele_anfragen_zweier_mandanten_ueber_denselben_pool_vermischen_nichts() {
        class Side(val tenant: de.taktovia.platform.tenant.TenantId, val token: String, val names: Set<String>)
        fun side(tag: String): Side {
            val t = env.tenant("Pool-$tag-" + UUID.randomUUID()); val a = UUID.randomUUID(); env.grant(t, a, "tenant_admin"); val token = env.token(a)
            val names = (1..3).map { "Region-$tag-" + UUID.randomUUID().toString().take(8) }.toSet()
            names.forEach { n -> assertEquals(201, env.post("/v1/regions", """{"name":"$n"}""", token, extra = idem()).status) }
            return Side(t, token, names)
        }
        val a = side("A"); val b = side("B")
        val pool = Executors.newFixedThreadPool(12); val failures = java.util.concurrent.ConcurrentLinkedQueue<String>(); val ok = AtomicInteger()
        try {
            val jobs = List(12) { i ->
                val own = if (i % 2 == 0) a else b; val other = if (i % 2 == 0) b else a
                pool.submit(Callable {
                    repeat(25) {
                        val r: Resp = env.get("/v1/regions", own.token)
                        val names = r.json["items"].values().map { it["name"].asString() }.toSet()
                        if (r.status != 200 || !names.containsAll(own.names) || names.any { it in other.names } || other.tenant.toString() in r.text) failures += "Mandant ${own.tenant}: Status ${r.status}, ${r.text.take(160)}"
                        else ok.incrementAndGet()
                    }
                })
            }
            jobs.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(emptyList<String>(), failures.toList().take(5))
        assertEquals(300, ok.get())
    }

    /** Antwortform ohne Zeit- und Spurenfelder: gleichartig heißt gleicher Status, Typ, Code, Titel und Detail. */
    private fun shape(r: Resp): String = r.status.toString() + "|" + listOf("type", "code", "title", "detail").joinToString("|") { r.json.path(it).asText("") }

    @Test
    fun erratene_abgelaufene_und_fremde_abrufadressen_sind_nicht_von_fehlenden_objekten_zu_unterscheiden() {
        val a = env.tenant("Url-A-" + UUID.randomUUID()); val b = env.tenant("Url-B-" + UUID.randomUUID())
        val fin = UUID.randomUUID(); val work = UUID.randomUUID(); val hr = UUID.randomUUID()
        env.grant(a, fin, "finance"); env.grant(a, work, "workshop"); env.grant(a, hr, "staff_planning")
        val finToken = env.token(fin); val workToken = env.token(work); val hrToken = env.token(hr)
        val zero = "0".repeat(64); val guess = "a1b2".repeat(16)
        val missing = shape(env.get("/v1/ledger/exports/${UUID.randomUUID()}/content?exp=4102444800&sig=$zero", finToken))
        assertTrue(missing.startsWith("404|"), missing)
        // Ledger-Export: erratene und abgelaufene Signatur, Ablauf in der Vergangenheit, sehr alter und negativer Ablauf, Signatur mit falscher Form
        val ledger = listOf(
            "exp=4102444800&sig=$guess", "exp=1&sig=$zero", "exp=1&sig=$guess", "exp=${System.currentTimeMillis() / 1000 - 5}&sig=$guess",
        )
        ledger.forEach { q -> assertEquals(missing, shape(env.get("/v1/ledger/exports/${UUID.randomUUID()}/content?$q", finToken)), "Ledger-Adresse $q") }
        assertEquals(422, env.get("/v1/ledger/exports/${UUID.randomUUID()}/content?exp=1&sig=zz", finToken).status, "formal ungültige Signatur wird als ungültige Eingabe abgewiesen")
        assertEquals(422, env.get("/v1/ledger/exports/${UUID.randomUUID()}/content?exp=-1&sig=$zero", finToken).status)
        // signierte Objekte (Schadenfotos): eigener Mandant mit erratener/abgelaufener Signatur und fremder Mandant im Pfad
        val own = shape(env.get("/v1/objects/$a/defect-evidence-${UUID.randomUUID()}?exp=1&purpose=defect_evidence&sig=$guess", workToken))
        assertTrue(own.startsWith("404|"), own)
        listOf("/v1/objects/$b/defect-evidence-x?exp=4102444800&purpose=defect_evidence&sig=$guess", "/v1/objects/${UUID.randomUUID()}/defect-evidence-x?exp=1&purpose=defect_evidence&sig=$zero",
            "/v1/objects/$a/..%2f$b%2fdefect-evidence-x?exp=4102444800&purpose=defect_evidence&sig=$guess", "/v1/objects/$a/defect-evidence-x?exp=4102444800&purpose=report_export&sig=$guess")
            .forEach { p -> val r = env.get(p, workToken); assertTrue(r.status in setOf(400, 404, 422), "$p -> ${r.status}"); assertFalse(r.text.contains(b.toString()) && r.status == 200) }
        assertEquals(own.substringBefore('|'), shape(env.get("/v1/objects/$b/defect-evidence-x?exp=4102444800&purpose=defect_evidence&sig=$guess", workToken)).substringBefore('|'),
            "fremder Mandant im Pfad: gleicher Status wie ein eigenes, nicht vorhandenes Objekt")
        // Berichtsauftrag: erratene Auftrags-ID und manipuliertes/abgelaufenes Token
        val job = UUID.randomUUID()
        val noJob = shape(env.get("/v1/reports/jobs/$job", hrToken))
        assertTrue(noJob.startsWith("404|"), noJob)
        listOf("download_token=abc.def", "download_token=${"A".repeat(40)}.${"B".repeat(43)}", "download_token=").forEach { q ->
            val r = env.get("/v1/reports/jobs/$job?$q", hrToken); assertTrue(r.status in setOf(400, 404, 422), "$q -> ${r.status}"); assertFalse(r.text.contains("download_url"))
        }
        // alle Abweisungen ohne interne Details
        val body = env.get("/v1/ledger/exports/${UUID.randomUUID()}/content?exp=1&sig=$guess", finToken).text
        assertFalse(Regex("(?i)exception|stack|select |postgres|signature mismatch|expired").containsMatchIn(body), body)
    }

    @Test
    fun plattformbetreiber_und_supportrolle_sehen_ohne_mitgliedschaft_keine_mandantendaten() {
        val a = env.tenant("Support-A-" + UUID.randomUUID()); val b = env.tenant("Support-B-" + UUID.randomUUID())
        val admin = UUID.randomUUID(); env.grant(b, admin, "tenant_admin")
        val region = "Region-B-" + UUID.randomUUID().toString().take(8)
        assertEquals(201, env.post("/v1/regions", """{"name":"$region"}""", env.token(admin), extra = idem()).status)
        val support = UUID.randomUUID(); env.grant(a, support, "support"); val supportToken = env.token(support)
        val operator = env.operator(); val operatorToken = env.token(operator)
        val paths = listOf("/v1/regions", "/v1/memberships", "/v1/company-profile", "/v1/depots")
        for (path in paths) {
            // Plattformbetreiber ohne Mitgliedschaft: weder mit eigenem Kontext noch mit manipuliertem Mandantenheader
            for (extra in listOf(emptyMap(), mapOf("X-Tenant-Id" to b.toString()))) {
                val r = env.get(path, operatorToken, extra = extra)
                assertTrue(r.status in setOf(401, 403, 404), "Plattformbetreiber $path $extra -> ${r.status}"); assertFalse(r.text.contains(region) || r.text.contains(b.toString()))
            }
            // Support von A: eigener Mandant ja (soweit die Rolle es erlaubt), fremder Mandant nie
            val foreign = env.get(path, supportToken, extra = mapOf("X-Tenant-Id" to b.toString()))
            assertFalse(foreign.text.contains(region) || foreign.text.contains(b.toString()), "Support A sieht Daten von B über $path")
        }
        assertEquals(403, env.switchTo(supportToken, b).status, "Mandantenwechsel in einen Mandanten ohne Mitgliedschaft")
        assertEquals(403, env.switchTo(operatorToken, b).status, "Plattformbetreiber hat keinen Mandantenwechsel ohne Mitgliedschaft")
    }
}
