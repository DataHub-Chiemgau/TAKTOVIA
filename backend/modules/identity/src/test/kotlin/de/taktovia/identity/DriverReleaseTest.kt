package de.taktovia.identity

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.identity.api.Decision
import de.taktovia.identity.api.DriverReleaseState
import de.taktovia.identity.api.DriverReleases
import de.taktovia.identity.api.GrantDriverRelease
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M1-35 / LH-I.4-MUL-005: zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion an Fixture-Fahrern des Stammteils (`driver`, `qualification_revision`;
 * der Fahrerstamm im Vollausbau folgt mit M3, die Bindung an Kooperationsvertrag und Grants mit M6, OP-26). Echte Datenbank unter der Runtime-Rolle: Eigentümer A
 * erteilt, die Empfänger B und C lesen nur ihre eigene Projektion; fremde Mandanten, abgelaufene, widerrufene und nicht freigegebene Dienste sind gleichartig 404.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriverReleaseTest {
    private lateinit var fx: IdentityFixture
    private lateinit var releases: DriverReleases
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())
    private val json = JsonMapper.builder().build()

    @BeforeAll fun up() {
        fx = IdentityFixture(poolSize = 16)
        releases = DriverReleases.create(ContextTransactions(fx.tx, fx.identity.revalidator), fx.audit, fx.outbox)
    }
    @AfterAll fun down() = fx.close()

    /** Ein Mandant mit einer Person je Rolle (Mitgliedschaft aus dem Testaufbau). */
    private inner class Side(val tenant: TenantId = fx.tenant()) {
        private val members = mutableMapOf<String, Pair<UUID, String>>()
        fun ctx(role: String): RequestContext {
            val (m, subject) = members.getOrPut(role) { val s = "$role-" + UUID.randomUUID(); fx.seedMembership(tenant, s, role) to s }
            return fx.context(tenant, subject, setOf(m), setOf(role))
        }
    }

    /** Fixture-Fahrer des Eigentümers (Administrator legt den Stamm an) mit zwei Qualifikationen, davon eine mit Folgerevision. */
    private fun driver(owner: TenantId, name: String = "Erika Muster"): UUID {
        val d = UUID.randomUUID()
        fx.adminExec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,?,'PHONE','+491701234567')", owner.value, d, name)
        fun q(code: String, rev: Int, from: String, to: String?, ev: String) =
            fx.adminExec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,valid_to,evidence_ref) VALUES (?,?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz),?)",
                owner.value, UUID.randomUUID(), d, code, rev, from, to, ev)
        q("PBEF", 1, "2020-01-01T00:00:00Z", "2020-12-31T00:00:00Z", "akte-1"); q("PBEF", 2, "2021-01-01T00:00:00Z", null, "akte-2"); q("ROLLSTUHL", 1, "2024-01-01T00:00:00Z", null, "akte-3")
        return d
    }

    private val now get() = Instant.now()
    private fun cmd(d: UUID, recipient: TenantId, vararg services: UUID, from: Instant = now.minus(1, ChronoUnit.HOURS), to: Instant = now.plus(1, ChronoUnit.HOURS)) =
        GrantDriverRelease(d, recipient.value, services.toList(), from, to)

    @Test
    fun projektion_nur_feste_feldliste_im_zeitraum() {
        val a = Side(); val b = Side(); val c = Side(); val d = driver(a.tenant); val svcB = UUID.randomUUID(); val svcC = UUID.randomUUID()
        val rb = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svcB))
        val rc = releases.grant(a.ctx("staff_planning"), cmd(d, c.tenant, svcC))   // derselbe Fahrer gleichzeitig für B und C
        assertEquals(DriverReleaseState.ACTIVE, rb.state); assertEquals(1L, rb.revision)

        val p = releases.projection(b.ctx("dispatcher"), rb.id, svcB)
        assertEquals(rb.id, p.id); assertEquals(listOf(svcB), p.serviceIds); assertEquals(rb.validFrom, p.validFrom); assertEquals(rb.validTo, p.validTo)
        assertEquals(listOf("PBEF", "ROLLSTUHL"), p.qualifications.map { it.code }, "je Qualifikation die höchste Revision")
        assertEquals(null, p.qualifications.first { it.code == "PBEF" }.validTo, "Revision 2 (unbefristet), nicht die abgelaufene Revision 1")
        assertEquals("PHONE", p.contactKind); assertEquals("+491701234567", p.contactValue)
        // feste Feldliste: nur die Schlüssel des Schemas; weder Name noch Nachweis noch Fahrer-ID des Eigentümers
        val text = json.writeValueAsString(p.toMap())
        assertEquals(setOf("id", "service_ids", "valid_from", "valid_to", "qualifications", "contact_channel"), json.readTree(text).propertyNames().toSet())
        listOf("Erika", "akte-", d.toString(), a.tenant.toString()).forEach { assertFalse(text.contains(it), "Projektion darf '$it' nicht enthalten") }
        assertEquals(emptyList<String>(), schemaErrors("docs/umsetzungsplan/vertraege/schemas/driver-release-projection.v1.schema.json", text))
        // jeder Empfänger sieht nur die eigene Projektion
        assertEquals(rc.id, releases.projection(c.ctx("dispatcher"), rc.id, svcC).id)
        assertEquals(404, status { releases.projection(c.ctx("dispatcher"), rb.id, svcB) }, "C sieht die Projektion von B nicht")
        assertEquals(404, status { releases.projection(b.ctx("dispatcher"), rc.id, svcC) })
    }

    @Test
    fun nicht_freigegebener_dienst_und_fremder_mandant_gleichartig_404() {
        val a = Side(); val b = Side(); val dritter = Side(); val d = driver(a.tenant); val svc = UUID.randomUUID()
        val r = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc))
        val antworten = listOf(
            "anderer Dienst" to { releases.projection(b.ctx("dispatcher"), r.id, UUID.randomUUID()) },
            "fremder Mandant" to { releases.projection(dritter.ctx("dispatcher"), r.id, svc) },
            "Eigentümer als Empfänger" to { releases.projection(a.ctx("dispatcher"), r.id, svc) },
            "unbekannte Freigabe" to { releases.projection(b.ctx("dispatcher"), UUID.randomUUID(), svc) },
        ).map { (what, call) -> what to assertThrows(ProblemException::class.java) { call() } }
        antworten.forEach { (what, e) -> assertEquals(ErrorCode.NOT_FOUND, e.code, what) }
        assertEquals(1, antworten.map { it.second.message }.toSet().size, "gleichartige Antwort: keine Unterscheidung nach Grund")
        // der Empfänger kann weder eigene noch fremde Freigaben des Eigentümers ändern oder lesen; der Dritte erst recht nicht
        assertEquals(404, status { releases.revoke(b.ctx("staff_planning"), r.id, 1) }); assertEquals(404, status { releases.get(b.ctx("staff_planning"), r.id) })
        // unbekannter Empfängermandant: gleichartig 404, nichts bleibt zurück
        assertEquals(404, status { releases.grant(a.ctx("staff_planning"), GrantDriverRelease(d, UUID.randomUUID(), listOf(svc), now, now.plusSeconds(3600))) })
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.driver_release WHERE tenant_id=?", a.tenant.value))
        // Eingabefehler 422: Selbstfreigabe, leerer Dienstkreis, Ende nicht nach Beginn, bereits abgelaufen
        listOf(
            cmd(d, a.tenant, svc), GrantDriverRelease(d, b.tenant.value, emptyList(), now, now.plusSeconds(60)), GrantDriverRelease(d, b.tenant.value, listOf(svc), now, now),
            cmd(d, b.tenant, svc, from = now.minusSeconds(7200), to = now.minusSeconds(3600)),
        ).forEach { assertEquals(422, status { releases.grant(a.ctx("staff_planning"), it) }) }
        // fremder oder unbekannter Fahrer des Eigentümers: 404
        assertEquals(404, status { releases.grant(a.ctx("staff_planning"), cmd(driver(b.tenant), b.tenant, svc)) })
        assertEquals(404, status { releases.grant(b.ctx("staff_planning"), cmd(d, a.tenant, svc)) }, "der Empfänger kann den Fahrer des Eigentümers nicht freigeben")
    }

    @Test
    fun freigabe_nur_fuer_berechtigte_rolle_des_eigentuemers() {
        val a = Side(); val b = Side(); val d = driver(a.tenant); val svc = UUID.randomUUID()
        val schreiben = listOf("createDriverRelease" to "driver-releases:write", "revokeDriverRelease" to "driver-releases:write")
        val rollen = listOf("passenger", "driver", "callcenter", "support", "dispatcher", "planner", "tenant_admin", "finance", "staff_planning", "workshop")
        for ((op, scope) in schreiben) for (rolle in rollen) {
            val decision = fx.identity.authorizer.decide(a.ctx(rolle), op, listOf(scope))
            assertEquals(rolle == "staff_planning", decision == Decision.Allowed, "$op für $rolle")
        }
        for (rolle in rollen) assertEquals(rolle in setOf("dispatcher", "planner", "staff_planning"),
            fx.identity.authorizer.decide(a.ctx(rolle), "getDriverReleaseProjection", listOf("driver-releases:read")) == Decision.Allowed, "Projektion lesen für $rolle")
        // Der Administrator hat auf Daten der Klasse HR keinen Zugriff ohne Fachrolle (Kap. 01 §1.7)
        assertTrue(fx.identity.rights.operation("createDriverRelease")!!.dataClass == "HR")
        // Der Empfängermandant kann trotz Rolle „Personalplanung“ nichts erteilen oder widerrufen, und es entsteht kein Erfolgsnachweis im Audit
        val r = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc))
        val auditVorher = fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE action LIKE 'staff.driver_release.%' AND tenant_id IN (?,?)", a.tenant.value, b.tenant.value)
        assertEquals(404, status { releases.grant(b.ctx("staff_planning"), cmd(d, a.tenant, svc)) })
        assertEquals(404, status { releases.revoke(b.ctx("staff_planning"), r.id, 1) })
        assertEquals(auditVorher, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE action LIKE 'staff.driver_release.%' AND tenant_id IN (?,?)", a.tenant.value, b.tenant.value))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.driver_release WHERE id=? AND revoked_at IS NULL", r.id))
        // Audit und Ereignis: je Erteilung und Widerruf genau eines, ohne Fahrerdaten, ausdrücklich an den Empfänger adressiert
        releases.revoke(a.ctx("staff_planning"), r.id, 1)
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.driver_release.granted' AND object_ref=?", a.tenant.value, "driver_release:${r.id}"))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.driver_release.revoked' AND object_ref=?", a.tenant.value, "driver_release:${r.id}"))
        for ((type, schema) in listOf("driver.release_granted" to "driver.releasegranted", "driver.release_revoked" to "driver.releaserevoked")) {
            val rows = fx.adminStrings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type=? AND recipient_tenant_id=?", a.tenant.value, r.id, type, b.tenant.value)
            assertEquals(1, rows.size, type); assertEquals(emptyList<String>(), schemaErrors("api/events/$schema.v1.schema.json", rows.single()))
            listOf("Erika", d.toString(), "+4917").forEach { assertFalse(rows.single().contains(it), "Ereignis darf '$it' nicht enthalten") }
        }
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE redacted_changes::text LIKE '%Erika%' OR redacted_changes::text LIKE '%+4917%'"))
        // der Widerruf ist nur einmal möglich; veraltete Revision 412
        assertEquals(409, status { releases.revoke(a.ctx("staff_planning"), r.id, 2) })
        val r2 = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc))
        assertEquals(412, status { releases.revoke(a.ctx("staff_planning"), r2.id, 5) })
    }

    @Test
    fun projektion_mit_zusatzfeld_wird_vom_schema_abgewiesen() {
        val a = Side(); val b = Side(); val d = driver(a.tenant); val svc = UUID.randomUUID()
        val r = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc))
        val ok = releases.projection(b.ctx("dispatcher"), r.id, svc).toMap()
        val schemaPath = "docs/umsetzungsplan/vertraege/schemas/driver-release-projection.v1.schema.json"
        assertEquals(emptyList<String>(), schemaErrors(schemaPath, json.writeValueAsString(ok)))
        // jedes zusätzliche Feld (Personalakte, Arbeitszeit, Abwesenheit, Gehalt, Name) auf jeder Ebene wird abgewiesen
        for (extra in listOf("display_name", "salary", "working_time", "absence", "personnel_file", "driver_id")) {
            assertTrue(schemaErrors(schemaPath, json.writeValueAsString(ok + (extra to "x"))).isNotEmpty(), "Feld $extra auf oberster Ebene")
        }
        @Suppress("UNCHECKED_CAST") val quals = (ok["qualifications"] as List<Map<String, Any?>>)
        assertTrue(schemaErrors(schemaPath, json.writeValueAsString(ok + ("qualifications" to quals.map { it + ("evidence_ref" to "akte") }))).isNotEmpty(), "Zusatzfeld in der Qualifikation")
        @Suppress("UNCHECKED_CAST") val contact = ok["contact_channel"] as Map<String, Any?>
        assertTrue(schemaErrors(schemaPath, json.writeValueAsString(ok + ("contact_channel" to (contact + ("home_address" to "x"))))).isNotEmpty(), "Zusatzfeld im Kontaktkanal")
        assertTrue(schemaErrors(schemaPath, json.writeValueAsString(ok - "contact_channel")).isNotEmpty(), "Pflichtfeld fehlt")
        // dieselbe Feldliste setzt die Datenbank durch: eine Projektion mit Zusatzfeld wird auch unter Umgehung des Dienstes abgewiesen
        val bad = assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            fx.tx.run(a.tenant) { j -> j.update("INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) " +
                "SELECT recipient_tenant_id,UUID_GENERATE_PLACEHOLDER,tenant_id,service_ids,valid_from,valid_to,'[]','PHONE','x' FROM taktovia.driver_release WHERE id = ?".replace("UUID_GENERATE_PLACEHOLDER", "gen_random_uuid()"), r.id) }
        }
        assertTrue(bad.message!!.contains("without matching active driver release") || bad.message!!.contains("fixed projection"), bad.message)
        val zusatz = assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            fx.tx.run(a.tenant) { j -> j.update("INSERT INTO taktovia.driver_release_projection(tenant_id,id,owner_tenant_id,service_ids,valid_from,valid_to,qualifications,contact_channel,contact_value) " +
                "SELECT recipient_tenant_id,id,tenant_id,service_ids,valid_from,valid_to,CAST(? AS jsonb),'PHONE','x' FROM taktovia.driver_release WHERE id = ?", """[{"code":"PBEF","salary":1}]""", r.id) }
        }
        assertTrue(zusatz.message!!.contains("fixed projection field list") || zusatz.message!!.contains("duplicate key"), zusatz.message)
    }

    @Test
    fun zeitraum_geplant_abgelaufen_widerrufen_und_kein_cache() {
        val a = Side(); val b = Side(); val d = driver(a.tenant); val svc = UUID.randomUUID()
        val geplant = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc, from = now.plus(1, ChronoUnit.HOURS), to = now.plus(2, ChronoUnit.HOURS)))
        val kurz = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc, to = now.plusMillis(1500)))
        val aktiv = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc))
        assertEquals(DriverReleaseState.PLANNED, geplant.state)
        assertEquals(404, status { releases.projection(b.ctx("dispatcher"), geplant.id, svc) }, "vor T1")
        assertEquals(kurz.id, releases.projection(b.ctx("dispatcher"), kurz.id, svc).id)
        Thread.sleep(1700)
        assertEquals(404, status { releases.projection(b.ctx("dispatcher"), kurz.id, svc) }, "nach T2, ohne jede Änderung")
        assertEquals(DriverReleaseState.EXPIRED, releases.get(a.ctx("staff_planning"), kurz.id).state)
        assertEquals(409, status { releases.revoke(a.ctx("staff_planning"), kurz.id, 1) }, "abgelaufen: kein Widerruf mehr")
        // Widerruf wirkt mit der nächsten Anfrage
        assertEquals(aktiv.id, releases.projection(b.ctx("dispatcher"), aktiv.id, svc).id)
        val revoked = releases.revoke(a.ctx("staff_planning"), aktiv.id, 1)
        assertEquals(DriverReleaseState.REVOKED, revoked.state); assertEquals(2L, revoked.revision)
        assertEquals(404, status { releases.projection(b.ctx("dispatcher"), aktiv.id, svc) }, "nach Widerruf")
        // ein geplanter Eintrag kann widerrufen werden
        assertEquals(DriverReleaseState.REVOKED, releases.revoke(a.ctx("staff_planning"), geplant.id, 1).state)
    }

    @Test
    fun widerruf_parallel_zu_lesezugriff() {
        val a = Side(); val b = Side(); val d = driver(a.tenant); val svc = UUID.randomUUID()
        val r = releases.grant(a.ctx("staff_planning"), cmd(d, b.tenant, svc))
        val readerCtx = b.ctx("dispatcher")
        val committed = AtomicBoolean(false); val stop = AtomicBoolean(false)
        val before = AtomicInteger(); val afterCommit = AtomicInteger(); val violations = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val readers = (1..6).map {
            pool.submit {
                while (!stop.get()) {
                    val committedBefore = committed.get()   // Zustand VOR dem Lesen: war der Widerruf bereits committet, darf kein Treffer mehr kommen
                    val visible = runCatching { releases.projection(readerCtx, r.id, svc) }.isSuccess
                    if (committedBefore) { afterCommit.incrementAndGet(); if (visible) violations.incrementAndGet() } else if (visible) before.incrementAndGet()
                }
            }
        }
        Thread.sleep(400)
        releases.revoke(a.ctx("staff_planning"), r.id, 1)
        committed.set(true)
        Thread.sleep(600); stop.set(true)
        readers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(0, violations.get(), "nach dem Commit des Widerrufs darf kein Lesezugriff mehr treffen")
        assertTrue(before.get() > 0, "vor dem Widerruf war die Projektion lesbar"); assertTrue(afterCommit.get() > 0)
    }

    private fun status(block: () -> Any?): Int = try { block(); 200 } catch (e: ProblemException) { e.code.status }

    private fun schemaErrors(schemaFile: String, document: String): List<String> =
        registry.getSchema(Files.readString(repo.resolve(schemaFile))).validate(document, InputFormat.JSON).map { it.toString() }
}
