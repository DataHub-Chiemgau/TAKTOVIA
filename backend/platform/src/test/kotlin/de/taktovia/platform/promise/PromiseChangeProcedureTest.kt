package de.taktovia.platform.promise

import de.taktovia.platform.TestDatabase
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M1-27 / LH-6.1-SYS-005, Originalabnahme: „Jede Änderung einer Zusage erzeugt ein Audit-Ereignis und eine Fahrgastinformation, soweit nötig.“
 * Echte Datenbank unter der Runtime-Rolle; Nachweis am Testaggregat (`promise_test_aggregate`), die Buchung folgt mit M2.
 */
class PromiseChangeProcedureTest {
    companion object {
        private val json = JsonMapper.builder().build()
        private lateinit var pool: com.zaxxer.hikari.HikariDataSource
        private lateinit var tx: TenantTransactions
        private lateinit var admin: JdbcTemplate
        private val audit = JdbcAuditPort()
        private val procedure = PromiseChangeProcedure(audit, OutboxPassengerInformationPort(Outbox()))

        @BeforeAll @JvmStatic fun setup() { TestDatabase.require(); pool = TestDatabase.runtime(poolSize = 14); tx = TenantTransactions(pool); admin = JdbcTemplate(TestDatabase.admin()) }
        @AfterAll @JvmStatic fun teardown() { if (::pool.isInitialized) pool.close() }

        fun content(gross: Int = 1000, vehicle: String = "standard", note: String? = null, pickup: String = "2026-12-01T08:00:00+01:00", policy: String = "std-24h"): ObjectNode =
            (json.readTree("""{"pickup_earliest":"$pickup","pickup_latest":"2026-12-01T08:15:00+01:00","arrival_earliest":"2026-12-01T08:40:00+01:00",
                "arrival_latest":"2026-12-01T08:55:00+01:00","gross_minor":$gross,"currency":"EUR","cancellation_policy_ref":"$policy","vehicle_class":"$vehicle"}""") as ObjectNode)
                .also { if (note != null) it.put("internal_note", note) }
    }

    private val actor = ActorRef.user(UUID.randomUUID())
    private val scopes = setOf(PromiseChangeProcedure.SCOPE_CHANGE)

    private class Fixture(val tenant: TenantId, val promise: UUID, val config: UUID)

    private fun newTenant(): TenantId = TenantId(UUID.randomUUID()).also { admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "pr-$it") }
    private fun newConfig(t: TenantId, revision: Long = 1, scope: UUID = UUID.randomUUID()): UUID = UUID.randomUUID().also {
        admin.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by) VALUES (?,?,'tenant',?,?,'1.0','{}'::jsonb,now(),'user:x')",
            t.value, it, scope, revision) }
    private fun fixture(): Fixture {
        val t = newTenant(); val cfg = newConfig(t); val id = UUID.randomUUID()
        tx.run(t) { j -> procedure.bind(j, id, content(), cfg, actor, UUID.randomUUID()) }
        return Fixture(t, id, cfg)
    }

    private fun change(f: Fixture, c: JsonNode, expected: Long? = 1, procedureName: String = "OPERATIONAL_CHANGE", reason: String? = "Umplanung wegen Baustelle",
                       scopes: Set<String> = this.scopes, tenant: TenantId = f.tenant, config: UUID? = null) =
        tx.run(tenant) { j -> procedure.change(j, PromiseChangeCommand(f.promise, expected, c, procedureName, reason, actor, scopes, UUID.randomUUID(), config)) }

    private fun audits(f: Fixture, action: String) = tx.run(f.tenant, readOnly = true) { j ->
        j.queryForList("SELECT actor_ref, redacted_changes::text AS changes FROM taktovia.audit_event WHERE object_ref = ? AND action = ?", "promise:${f.promise}", action) }
    private fun events(f: Fixture) = tx.run(f.tenant, readOnly = true) { j ->
        j.queryForList("SELECT aggregate_revision, payload::text AS payload FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = 'promise.changed' ORDER BY aggregate_revision", f.promise) }
    private fun head(f: Fixture) = tx.run(f.tenant, readOnly = true) { j -> j.queryForObject("SELECT revision FROM taktovia.promise_test_aggregate WHERE id = ?", Long::class.java, f.promise)!! }
    private fun snapshots(f: Fixture) = tx.run(f.tenant, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.promise_test_snapshot WHERE promise_id = ?", Int::class.java, f.promise)!! }
    @Test fun aenderung_erzeugt_audit_und_informationsauftrag() {
        val f = fixture()
        // fahrgastrelevant: Preis geändert -> genau ein Audit und genau ein Informationsauftrag
        val r1 = change(f, content(gross = 1200))
        assertEquals(1L, r1.previousRevision); assertEquals(2L, r1.newRevision); assertEquals(setOf("gross_minor"), r1.changedFields); assertTrue(r1.passengerInformationRequired)
        val a1 = audits(f, "promise.snapshot.changed").single()
        assertEquals(actor.value, a1["actor_ref"])
        val changes = json.readTree(a1["changes"].toString())
        assertEquals("Umplanung wegen Baustelle", changes["reason"].asString()); assertEquals(1, changes["previous_revision"].asInt()); assertEquals(2, changes["new_revision"].asInt())
        assertEquals("OPERATIONAL_CHANGE", changes["procedure"].asString()); assertTrue(changes["information_required"].asBoolean())
        val ev = events(f).single()
        assertEquals(2L, (ev["aggregate_revision"] as Number).toLong())
        val payload = json.readTree(ev["payload"].toString())
        assertTrue(payload["passenger_information_required"].asBoolean()); assertEquals(1, payload["previous_revision"].asInt()); assertEquals(2, payload["revision"].asInt())
        assertEquals("promise_changed", payload["information_order"]["kind"].asString()); assertEquals(listOf("gross_minor"), payload["information_order"]["changed_fields"].values().map { it.asString() })
        assertTrue(!payload.toString().contains("Baustelle"), "der Freitext des Grundes gehört nicht in das Ereignis")
        // nicht relevant: Fahrzeugklasse und interne Notiz -> genau ein weiteres Audit, kein weiteres Ereignis
        val r2 = change(f, content(gross = 1200, vehicle = "xl", note = "intern"), expected = 2, procedureName = "CORRECTION")
        assertEquals(setOf("internal_note", "vehicle_class"), r2.changedFields); assertTrue(!r2.passengerInformationRequired); assertEquals(null, r2.outboxEventId)
        assertEquals(2, audits(f, "promise.snapshot.changed").size); assertEquals(1, events(f).size)
        // alte Revisionen bleiben lesbar und unverändert
        tx.run(f.tenant, readOnly = true) { j ->
            assertEquals(1000, procedure.read(j, f.promise, 1).content["gross_minor"].asInt())
            assertEquals(1200, procedure.read(j, f.promise, 2).content["gross_minor"].asInt()); assertEquals("standard", procedure.read(j, f.promise, 2).content["vehicle_class"].asString())
            assertEquals("xl", procedure.read(j, f.promise).content["vehicle_class"].asString()); assertEquals(3L, procedure.read(j, f.promise).revision)
        }
        // das Binden ist auditiert, getrennt vom Ändern
        assertEquals(1, audits(f, "promise.snapshot.bound").size)
    }

    @Test fun konfigurationsaenderung_laesst_bestehenden_snapshot_unveraendert() {
        val f = fixture()
        val before = tx.run(f.tenant, readOnly = true) { procedure.read(it, f.promise, 1) }
        // neue Konfigurationsrevision, die alte wird fortgeschrieben (Gültigkeitsende): der bestehende Snapshot bleibt unberührt
        val scope = UUID.randomUUID()
        val cfg2 = newConfig(f.tenant, 2, scope)
        tx.run(f.tenant) { it.update("UPDATE taktovia.config_revision SET effective_to = now() + interval '1 day' WHERE tenant_id = taktovia.current_tenant() AND id = ?", f.config) }
        val after = tx.run(f.tenant, readOnly = true) { procedure.read(it, f.promise, 1) }
        assertEquals(before.contentHash, after.contentHash); assertEquals(before.content, after.content); assertEquals(f.config, after.configRevisionId)
        // erst eine bewusste Änderung bindet die neue Revision an die neue Konfiguration; Revision 1 bleibt an der alten
        change(f, content(), config = cfg2).let { assertEquals(2L, it.newRevision); assertTrue(it.changedFields.isEmpty() && !it.passengerInformationRequired) }
        tx.run(f.tenant, readOnly = true) {
            assertEquals(f.config, procedure.read(it, f.promise, 1).configRevisionId); assertEquals(cfg2, procedure.read(it, f.promise, 2).configRevisionId)
        }
    }

    @Test fun aenderung_ohne_grund_oder_unberechtigt_wird_abgewiesen() {
        val f = fixture(); val other = newTenant()
        fun rejected(code: ErrorCode, body: () -> Unit): ProblemException = assertThrows<ProblemException> { body() }.also { assertEquals(code, it.code) }
        val v = content(gross = 1300)
        // ohne Grund
        for (r in listOf(null, "", "   ", "x".repeat(501))) rejected(ErrorCode.VALIDATION_FAILED) { change(f, v, reason = r) }
        assertEquals(ViolationCode.REQUIRED, rejected(ErrorCode.VALIDATION_FAILED) { change(f, v, reason = " ") }.violations.single().code)
        // unberechtigte Rolle: 403, auch mit anderen Rechten
        rejected(ErrorCode.FORBIDDEN) { change(f, v, scopes = emptySet()) }
        rejected(ErrorCode.FORBIDDEN) { change(f, v, scopes = setOf("promise:read", "booking:change")) }
        // unbekanntes Verfahren, ungültiger Inhalt
        rejected(ErrorCode.VALIDATION_FAILED) { change(f, v, procedureName = "WILLKUER") }
        val noPrice = (v.deepCopy() as ObjectNode).also { it.remove("gross_minor") }
        assertEquals("/content/gross_minor", rejected(ErrorCode.VALIDATION_FAILED) { change(f, noPrice) }.violations.single().pointer)
        rejected(ErrorCode.VALIDATION_FAILED) { change(f, (v.deepCopy() as ObjectNode).also { it.put("gross_minor", 12.5) }) }
        rejected(ErrorCode.VALIDATION_FAILED) { change(f, (v.deepCopy() as ObjectNode).also { it.put("gross_minor", -1) }) }
        rejected(ErrorCode.VALIDATION_FAILED) { change(f, (v.deepCopy() as ObjectNode).also { it.put("pickup_earliest", "2026-12-01T08:00:00") }) }   // ohne Offset
        rejected(ErrorCode.VALIDATION_FAILED) { change(f, (v.deepCopy() as ObjectNode).also { it.put("extra", "x") }) }                              // unbekanntes Feld
        // keine Änderung
        rejected(ErrorCode.VALIDATION_FAILED) { change(f, content()) }
        // Revisionsbedingung: fehlend 428, veraltet 412; fremder Mandant 404
        rejected(ErrorCode.PRECONDITION_REQUIRED) { change(f, v, expected = null) }
        rejected(ErrorCode.REVISION_MISMATCH) { change(f, v, expected = 5) }
        rejected(ErrorCode.NOT_FOUND) { change(f, v, tenant = other) }
        // nichts wurde geschrieben: Revision, Snapshots, Audit (nur das Binden), Ereignisse
        assertEquals(1L, head(f)); assertEquals(1, snapshots(f)); assertEquals(0, audits(f, "promise.snapshot.changed").size); assertEquals(0, events(f).size)
        assertEquals(1, audits(f, "promise.snapshot.bound").size)
        // unbekannte Schema-Version und nicht passender Hash beim Übernehmen eines Snapshots
        assertEquals("/schema_version", rejected(ErrorCode.VALIDATION_FAILED) { BoundSnapshot.bind(f.promise, 1, "9.9", v, f.config) }.violations.single().pointer)
        assertEquals("/content_hash", rejected(ErrorCode.VALIDATION_FAILED) { BoundSnapshot.adopt(f.promise, 1, "1.0", v, "0".repeat(64), f.config) }.violations.single().pointer)
        assertEquals(BoundSnapshot.hashOf(v), BoundSnapshot.adopt(f.promise, 1, "1.0", v, BoundSnapshot.hashOf(v), f.config).contentHash)
        // der Schlüsselreihenfolge ist der Hash gleichgültig (kanonisch)
        assertEquals(BoundSnapshot.hashOf(v), BoundSnapshot.hashOf(json.readTree(json.writeValueAsString(v.properties().reversed().associate { it.key to it.value }))))
        // Rollback: schlägt der Informationsauftrag fehl, bleibt auch die Revision aus
        val failing = PromiseChangeProcedure(audit, PassengerInformationPort { _, _ -> error("Port nicht erreichbar") })
        assertThrows<IllegalStateException> { tx.run(f.tenant) { j -> failing.change(j, PromiseChangeCommand(f.promise, 1, v, "CORRECTION", "Test", actor, scopes, UUID.randomUUID())) } }
        assertEquals(1L, head(f)); assertEquals(1, snapshots(f)); assertEquals(0, audits(f, "promise.snapshot.changed").size)
    }

    @Test fun parallele_aenderung_liefert_412_und_genau_ein_audit_und_outbox_eintrag() {
        val exec = Executors.newFixedThreadPool(10)
        try {
            repeat(10) {
                val f = fixture(); val go = CountDownLatch(1)
                val results = (1..8).map { i -> exec.submit(Callable { go.await(); runCatching { change(f, content(gross = 1000 + i), expected = 1) } }) }
                go.countDown()
                val rs = results.map { it.get() }
                assertEquals(1, rs.count { it.isSuccess }, "genau eine Änderung gewinnt")
                rs.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code) }
                assertEquals(2L, head(f)); assertEquals(2, snapshots(f))
                assertEquals(1, audits(f, "promise.snapshot.changed").size, "genau ein Audit-Eintrag"); assertEquals(1, events(f).size, "genau ein Outbox-Eintrag")
                val winner = rs.single { it.isSuccess }.getOrThrow()
                assertEquals(winner.changedFields, setOf("gross_minor"))
                assertEquals(winner.newRevision, tx.run(f.tenant, readOnly = true) { procedure.read(it, f.promise).revision })
            }
        } finally { exec.shutdownNow() }
    }

    @Test fun json_schema_datei_und_kotlin_felddefinition_sind_synchron() {
        val repo = Path.of(System.getProperty("taktovia.repoRoot") ?: Path.of("").toAbsolutePath().let { generateSequence(it) { p -> p.parent }.first { p -> Files.exists(p.resolve("db/migrations")) }.toString() })
        val schema = json.readTree(Files.readString(repo.resolve("docs/umsetzungsplan/vertraege/schemas/promise-snapshot.v1.schema.json")))
        val props = schema["properties"]
        assertEquals(PromiseSnapshotSchema.FIELDS_V1.map { it.name }.toSet(), props.propertyNames().toSet())
        assertEquals(PromiseSnapshotSchema.FIELDS_V1.filter { it.required }.map { it.name }.toSet(), schema["required"].values().map { it.asString() }.toSet())
        assertEquals(PromiseSnapshotSchema.passengerRelevant("1.0"), props.propertyNames().filter { props[it]["x-passenger-relevant"]?.asBoolean() == true }.toSet())
        assertEquals(false, schema["additionalProperties"].asBoolean())
        // das Beispielobjekt des Schemas ist nach Kotlin-Regeln gültig
        assertEquals(emptyList<Any>(), PromiseSnapshotSchema.validate("1.0", schema["examples"][0]))
    }
}
