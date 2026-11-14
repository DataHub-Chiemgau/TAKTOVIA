package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.BlockReason
import de.taktovia.fleetsafety.api.BlockSource
import de.taktovia.fleetsafety.api.IssueRelease
import de.taktovia.fleetsafety.api.ReleaseOutcome
import de.taktovia.fleetsafety.api.SetBlock
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.inspection.InspectionStatus
import de.taktovia.platform.inspection.InspectionStatusDerivation
import de.taktovia.platform.inspection.InspectionStatusFacts
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-23 / LH-6.21-CHK-016 (Prüfstatus): die sechs Statuswerte werden gegen die echte Datenbank aus dem Serverzustand (Prüflauf, Sperre, Freigabe) abgeleitet und von der Leitstellenprojektion
 * ausgeliefert; Statusrevision monoton; Fahrzeuge eines nicht berechtigten Dienstes sind nicht vorhanden (404). Die Fahrerseite (fremdes Fahrzeug 404, gleicher Status) prüft
 * `app/driver/InspectionStatusDriverHttpTest`, die Darstellung `web/operator/src/inspection/InspectionStatus.test.tsx` und `mobile/driver/test/pruefstatus_anzeige_test.dart`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectionStatusProjectionTest {
    private lateinit var fx: FleetFixture
    private lateinit var w: ReadinessWorld

    @BeforeAll fun up() { fx = FleetFixture(); w = ReadinessWorld(fx) }
    @AfterAll fun down() = fx.close()

    private fun status(v: UUID) = fx.fleet.operatorVehicles.get(w.ctx("dispatcher"), v)

    private fun inspectionRaw(shift: UUID, vehicle: UUID, driver: UUID, status: String, outcome: String?, restriction: String? = null): UUID = UUID.randomUUID().also { id ->
        val done = status == "COMPLETED"
        fx.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,restriction,started_at,completed_at,received_at,created_by) " +
            "VALUES (?,?,?,?,?,?,'PRE_SHIFT',?,?,CAST(? AS jsonb),?,?,?,'user:test')", w.tenant.value, id, shift, vehicle, driver, w.template, status, if (done) outcome else null, restriction,
            Timestamp.from(Instant.now().minusSeconds(600)), if (done) Timestamp.from(Instant.now().minusSeconds(60)) else null, if (done) Timestamp.from(Instant.now().minusSeconds(59)) else null)
    }

    private fun release(v: UUID, s: UUID, i: UUID) { assertTrue(w.fx.fleet.readiness.issueRelease(w.safetyService(), IssueRelease(v, s, i)) is ReleaseOutcome.Issued) }

    @Test
    fun sechs_statuswerte_eindeutig_aus_serverzustand() {
        // 1 ausstehend: Schicht ohne Prüfung
        val d1 = w.driver(); val v1 = w.vehicle(); val s1 = w.shift(d1, v1)
        assertEquals(InspectionStatus.PENDING, status(v1).inspectionStatus)
        // 2 in Bearbeitung: offener Lauf
        val d2 = w.driver(); val v2 = w.vehicle(); val s2 = w.shift(d2, v2); inspectionRaw(s2, v2, d2, "IN_PROGRESS", null)
        assertEquals(InspectionStatus.IN_PROGRESS, status(v2).inspectionStatus)
        // 3 bestanden: abgeschlossen, Freigabe erteilt
        val d3 = w.driver(); val v3 = w.vehicle(); val s3 = w.shift(d3, v3); release(v3, s3, inspectionRaw(s3, v3, d3, "COMPLETED", "PASSED"))
        assertEquals(InspectionStatus.PASSED, status(v3).inspectionStatus)
        // 4 eingeschränkt: Freigabe mit entfallener Ressourcendimension
        val d4 = w.driver(); val v4 = w.vehicle(); val s4 = w.shift(d4, v4)
        release(v4, s4, inspectionRaw(s4, v4, d4, "COMPLETED", "PASSED_RESTRICTED", """{"unavailable_dimensions":["wheelchair"],"step_codes":["RAMPE_LIFT_FUNKTION"]}"""))
        assertEquals(InspectionStatus.RESTRICTED, status(v4).inspectionStatus)
        // 5 gesperrt: aktive Sicherheitssperre
        val d5 = w.driver(); val v5 = w.vehicle(); val s5 = w.shift(d5, v5); release(v5, s5, inspectionRaw(s5, v5, d5, "COMPLETED", "PASSED"))
        w.fx.fleet.readiness.setBlock(w.safetyService(), SetBlock(v5, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defekt-1"))
        assertEquals(InspectionStatus.BLOCKED, status(v5).inspectionStatus)
        // 6 technisch ungeklärt: Ergebnis CLARIFICATION_REQUIRED, keine Freigabe
        val d6 = w.driver(); val v6 = w.vehicle(); val s6 = w.shift(d6, v6); inspectionRaw(s6, v6, d6, "COMPLETED", "CLARIFICATION_REQUIRED")
        assertEquals(InspectionStatus.UNCLEAR, status(v6).inspectionStatus)
        // genau diese sechs Werte, jeder genau einmal erreicht
        assertEquals(InspectionStatus.entries.toSet(), listOf(v1, v2, v3, v4, v5, v6).map { status(it).inspectionStatus }.toSet())
        // reine Ableitung: Sperre schlägt offenen Lauf, offener Lauf schlägt alte Freigabe; ohne Fakten ausstehend
        assertEquals(InspectionStatus.BLOCKED, InspectionStatusDerivation.derive(InspectionStatusFacts(true, true, true, false, "PASSED")))
        assertEquals(InspectionStatus.IN_PROGRESS, InspectionStatusDerivation.derive(InspectionStatusFacts(false, true, true, false, "PASSED")))
        assertEquals(InspectionStatus.PENDING, InspectionStatusDerivation.derive(InspectionStatusFacts(false, false, false, false, null)))
    }

    @Test
    fun fahrer_anderes_fahrzeug_und_fremder_dienst_erhalten_404() {
        // Disponent eines nicht berechtigten Dienstes: das Fahrzeug des Dienstes A ist für einen Disponenten nur des Dienstes B nicht vorhanden (404), mit Status auch nicht über die Liste
        val d = w.driver(); val v = w.vehicle(); val s = w.shift(d, v); release(v, s, inspectionRaw(s, v, d, "COMPLETED", "PASSED"))
        val a = service(); val b = service()
        fx.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,now() - interval '1 day','user:t')", w.tenant.value, UUID.randomUUID(), a, v)
        fun ctx(services: Set<UUID>) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", UUID.randomUUID().toString(), "sid", true), w.tenant, setOf("dispatcher"), setOf("operator:read"), services, UUID.randomUUID())
        assertEquals(InspectionStatus.PASSED, fx.fleet.operatorVehicles.get(ctx(setOf(a)), v).inspectionStatus)
        assertThrows(ProblemException::class.java) { fx.fleet.operatorVehicles.get(ctx(setOf(b)), v) }
        assertTrue(fx.fleet.operatorVehicles.list(ctx(setOf(b)), null, null, 100, null).none { it.id == v })
        // fremder Mandant: nie sichtbar
        val other = ReadinessWorld(fx)
        assertThrows(ProblemException::class.java) { fx.fleet.operatorVehicles.get(other.ctx("dispatcher"), v) }
    }

    @Test
    fun zwei_wechsel_hintereinander_endet_auf_letztem_stand() {
        val d = w.driver(); val v = w.vehicle(); val s = w.shift(d, v)
        val r0 = status(v).inspectionStatusRevision
        val run = inspectionRaw(s, v, d, "IN_PROGRESS", null); val r1 = status(v)
        assertEquals(InspectionStatus.IN_PROGRESS, r1.inspectionStatus)
        fx.exec("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now(), revision=revision+1 WHERE id=?", run)
        release(v, s, run)
        val passed = status(v); assertEquals(InspectionStatus.PASSED, passed.inspectionStatus)
        w.fx.fleet.readiness.setBlock(w.safetyService(), SetBlock(v, BlockSource.SAFETY, BlockReason.DEFECT_CRITICAL, "defekt-2"))
        val blocked = status(v)
        // bestanden -> gesperrt: die Revision steigt streng monoton, der letzte Serverstand ist gesperrt; ein Client mit der Regel „nur höhere Revision“ endet nie auf dem älteren Stand
        assertEquals(InspectionStatus.BLOCKED, blocked.inspectionStatus)
        assertTrue(r0 < r1.inspectionStatusRevision && r1.inspectionStatusRevision < passed.inspectionStatusRevision && passed.inspectionStatusRevision < blocked.inspectionStatusRevision,
            "Revisionen: $r0 ${r1.inspectionStatusRevision} ${passed.inspectionStatusRevision} ${blocked.inspectionStatusRevision}")
        assertEquals(InspectionStatus.BLOCKED, status(v).inspectionStatus)
    }

    private fun service(): UUID {
        val t = w.tenant.value; val cfg = UUID.randomUUID(); val svc = UUID.randomUUID(); val operator = w.iw.w.orgs[0]
        fx.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", t, cfg, svc)
        fx.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Dienst','Europe/Berlin',?,'user:t')", t, svc, operator, cfg)
        return svc
    }
}
