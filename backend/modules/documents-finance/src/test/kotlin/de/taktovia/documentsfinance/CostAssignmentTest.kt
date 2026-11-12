package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.AssignCosts
import de.taktovia.documentsfinance.api.CostObjectKind
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.ReleaseBlocker
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-29 (DOC-004): Kostenzuordnung eines Belegs zu Firma, Kostenstelle oder Fahrzeug. Echte Datenbank unter der Runtime-Rolle; Mandantentrennung, Rechtsträgerbindung, Objektberechtigung und
 * Rennen zwischen Zuordnung und Deaktivierung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CostAssignmentTest {
    private lateinit var bf: BelegFixture

    @BeforeAll fun start() { bf = BelegFixture(InMemoryObjectStore()) }
    @AfterAll fun stop() { bf.close() }

    /** Mandant mit zwei Rechtsträgern, einer Kostenstelle je Rechtsträger, einem Fahrzeug und einem Beleg im Zustand STORED. */
    private inner class Scenario {
        val w = bf.fx.world()
        val le2 = UUID.randomUUID()
        init { bf.fx.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE2','DE')", w.tenant.value, le2) } }
        val fin = bf.financeCtx(w)
        val driver = bf.driverCtx(w)
        val doc = bf.upload(w, fin)
        val ks = costCenter(w.tenant, w.legalEntity)
        val ksOther = costCenter(w.tenant, le2)
        val vehicle = vehicle(w.tenant)
    }

    private fun costCenter(t: TenantId, le: UUID, service: UUID? = null): UUID {
        val id = UUID.randomUUID()
        bf.fx.exec("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,name,service_id) VALUES (?,?,?,'COST_CENTER',?,?,?)", t.value, id, le, "K-${id.toString().take(8)}", "Kostenstelle", service)
        return id
    }

    private fun vehicle(t: TenantId): UUID {
        val id = UUID.randomUUID()
        bf.fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org',CAST('{}' AS jsonb))", t.value, id)
        return id
    }

    private fun service(t: TenantId, le: UUID): UUID {
        val org = UUID.randomUUID(); val rev = UUID.randomUUID(); val svc = UUID.randomUUID()
        bf.fx.tx.run(t) { j ->
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t.value, org, le, "Org", "f-$org", "{TRANSPORT_COMPANY}")
            j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t.value, rev, svc)
            j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'D','Europe/Berlin',?,'user:test')", t.value, svc, org, rev)
        }
        return svc
    }

    private fun ctxWithServices(w: UploadFixture.World, services: Set<UUID>) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", UUID.randomUUID().toString(), "sid", true), w.tenant, setOf("finance"), setOf("receipts:write", "receipts:read"), services, UUID.randomUUID())

    @Test
    fun zuordnung_zu_kostenstelle_und_fahrzeug_macht_beleg_freigabefaehig() {
        val s = Scenario()
        val before = bf.svc.expenses.get(s.fin, s.doc.documentId)
        assertEquals(listOf(ReleaseBlocker.COST_ASSIGNMENT_MISSING), before.blockers); assertFalse(before.approvable)   // Beleg ohne Kostenzuordnung: nicht freigabefähig
        val v = bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks), vehicleIds = setOf(s.vehicle)))
        assertEquals(2L, v.document.revision); assertTrue(v.approvable); assertEquals(emptyList<ReleaseBlocker>(), v.blockers)
        assertEquals(setOf(CostObjectKind.COST_CENTER, CostObjectKind.VEHICLE), v.costObjects.map { it.kind }.toSet())
        assertEquals(setOf(s.w.legalEntity), v.costObjects.map { it.legalEntityId }.toSet(), "genau ein Rechtsträger: der des Belegs")
        assertEquals(s.vehicle, v.costObjects.single { it.kind == CostObjectKind.VEHICLE }.vehicleId)
        assertTrue(bf.svc.expenses.get(s.fin, s.doc.documentId).approvable)
        // Revision, Audit und Ereignis
        assertEquals(listOf(1L, 2L), bf.svc.expenses.revisions(s.fin, s.doc.documentId).map { it.revision }); assertEquals("COST_ASSIGNMENT", bf.svc.expenses.revisions(s.fin, s.doc.documentId).last().reason)
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.expense.cost_assigned' AND object_ref=?", s.w.tenant.value, "expense:${s.doc.documentId}"))
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='expense.cost_assigned' AND aggregate_id=?", s.w.tenant.value, s.doc.documentId))
        // gleiche Menge: keine neue Revision; Ersetzen: neue Revision
        assertEquals(2L, bf.svc.costs.assign(s.fin, s.doc.documentId, 2, AssignCosts(costObjectIds = setOf(s.ks), vehicleIds = setOf(s.vehicle))).document.revision)
        val replaced = bf.svc.costs.assign(s.fin, s.doc.documentId, 2, AssignCosts(vehicleIds = setOf(s.vehicle)))
        assertEquals(3L, replaced.document.revision); assertEquals(listOf(CostObjectKind.VEHICLE), replaced.costObjects.map { it.kind })
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.cost_object WHERE tenant_id=? AND kind='VEHICLE' AND vehicle_id=?", s.w.tenant.value, s.vehicle), "Fahrzeug-Kostenobjekt wird wiederverwendet")
    }

    @Test
    fun leere_zuordnung_und_veraltete_revision_werden_abgewiesen() {
        val s = Scenario()
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts()) }.code)
        bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks)))
        assertEquals(ErrorCode.REVISION_MISMATCH, bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(vehicleIds = setOf(s.vehicle))) }.code)
    }

    @Test
    fun fremde_id_ergibt_404_ohne_existenzhinweis() {
        val s = Scenario(); val fremd = Scenario()
        val unbekannt = bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(UUID.randomUUID()))) }
        for (cmd in listOf(AssignCosts(costObjectIds = setOf(fremd.ks)), AssignCosts(costObjectIds = setOf(s.ks, fremd.ks)), AssignCosts(vehicleIds = setOf(fremd.vehicle)), AssignCosts(vehicleIds = setOf(UUID.randomUUID())))) {
            val p = bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, cmd) }
            assertEquals(ErrorCode.NOT_FOUND, p.code, "fremde Kennung: 404")
            assertEquals(unbekannt.detail, p.detail, "keine Unterscheidung zwischen fremd und nicht vorhanden"); assertEquals(unbekannt.code, p.code); assertEquals(unbekannt.violations, p.violations)
        }
        // fremder Beleg und fremder Rechtsträger-Beleg: ebenfalls 404
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.costs.assign(s.fin, fremd.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks))) }.code)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_cost_assignment WHERE tenant_id IN (?, ?)", s.w.tenant.value, fremd.w.tenant.value))
        assertEquals(1L, bf.svc.expenses.get(s.fin, s.doc.documentId).document.revision, "abgewiesene Zuordnung ändert den Beleg nicht")
    }

    @Test
    fun kostenobjekt_eines_anderen_rechtstraegers_wird_abgewiesen() {
        val s = Scenario()
        val p = bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ksOther))) }
        assertEquals(ErrorCode.VALIDATION_FAILED, p.code)
        assertTrue(bf.svc.expenses.get(s.fin, s.doc.documentId).blockers.contains(ReleaseBlocker.COST_ASSIGNMENT_MISSING))
        // gemischt: ein gültiges und ein fremdes Objekt – nichts wird zugeordnet
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks, s.ksOther))) }.code)
        assertEquals(0, bf.fx.count("SELECT count(*) FROM taktovia.expense_cost_assignment WHERE document_id=?", s.doc.documentId))
    }

    @Test
    fun kostenobjekt_ausserhalb_der_objektberechtigung_ergibt_404() {
        val s = Scenario(); val svc = service(s.w.tenant, s.w.legalEntity); val gebunden = costCenter(s.w.tenant, s.w.legalEntity, svc)
        val ohneScope = ctxWithServices(s.w, emptySet()); val mitScope = ctxWithServices(s.w, setOf(svc))
        val ref = bf.problem { bf.svc.costs.assign(ohneScope, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(UUID.randomUUID()))) }
        val p = bf.problem { bf.svc.costs.assign(ohneScope, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(gebunden))) }
        assertEquals(ErrorCode.NOT_FOUND, p.code); assertEquals(ref.detail, p.detail); assertEquals(ref.violations, p.violations)
        assertFalse(bf.svc.costs.costObjects(ohneScope, CostObjectKind.COST_CENTER).any { it.id == gebunden }, "nur erlaubte Objekte sichtbar")
        assertTrue(bf.svc.costs.costObjects(mitScope, CostObjectKind.COST_CENTER).any { it.id == gebunden })
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.costs.deactivate(ohneScope, gebunden) }.code)
        assertTrue(bf.svc.costs.assign(mitScope, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(gebunden))).approvable)
    }

    @Test
    fun erfasser_ordnet_nur_fahrzeuge_zu_kostenstellen_nur_die_finanzrolle() {
        val s = Scenario()
        val own = bf.upload(s.w, s.driver)
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.costs.assign(s.driver, own.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks))) }.code)
        assertTrue(bf.svc.costs.assign(s.driver, own.documentId, 1, AssignCosts(vehicleIds = setOf(s.vehicle))).approvable)
        assertEquals(setOf(CostObjectKind.VEHICLE), bf.svc.costs.costObjects(s.driver).map { it.kind }.toSet())
        assertEquals(setOf(CostObjectKind.VEHICLE, CostObjectKind.COST_CENTER), bf.svc.costs.costObjects(s.fin).map { it.kind }.toSet())
        // ein fremder Fahrer sieht den Beleg nicht
        assertEquals(ErrorCode.NOT_FOUND, bf.problem { bf.svc.costs.assign(bf.driverCtx(s.w), own.documentId, 2, AssignCosts(vehicleIds = setOf(s.vehicle))) }.code)
        assertEquals(ErrorCode.FORBIDDEN, bf.problem { bf.svc.costs.deactivate(s.driver, s.ks) }.code)
    }

    @Test
    fun inaktives_kostenobjekt_wird_abgewiesen_und_sperrt_zugeordnete_belege() {
        val s = Scenario(); val zweiter = bf.upload(s.w, s.fin, Samples.jpeg(seed = 9))
        bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks)))
        assertTrue(bf.svc.costs.deactivate(s.fin, s.ks).let { !it.active }); bf.svc.costs.deactivate(s.fin, s.ks)   // idempotent
        assertEquals(ErrorCode.VALIDATION_FAILED, bf.problem { bf.svc.costs.assign(s.fin, zweiter.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks))) }.code)
        val v = bf.svc.expenses.get(s.fin, s.doc.documentId)
        assertFalse(v.approvable); assertTrue(ReleaseBlocker.COST_OBJECT_INACTIVE in v.blockers, "nie ein freigabefähiger Beleg mit inaktivem Kostenobjekt")
        assertTrue(bf.svc.costs.costObjects(s.fin, CostObjectKind.COST_CENTER).single { it.id == s.ks }.let { !it.active })
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='finance.cost_object.deactivated'", s.w.tenant.value))
    }

    @Test
    fun deaktiviertes_kostenobjekt_parallel_zur_zuordnung() {
        repeat(6) {
            val s = Scenario(); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val assign = pool.submit<Result<Any>> { start.await(); runCatching { bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks))) } }
            val off = pool.submit<Result<Any>> { start.await(); runCatching { bf.svc.costs.deactivate(s.fin, s.ks) } }
            start.countDown(); val a = assign.get(60, TimeUnit.SECONDS); val d = off.get(60, TimeUnit.SECONDS); pool.shutdown()
            assertTrue(d.isSuccess, d.toString())
            val view = bf.svc.expenses.get(s.fin, s.doc.documentId)
            if (a.isSuccess) {   // Zuordnung kam zuerst: der Beleg ist danach wegen des inaktiven Kostenobjekts nicht freigabefähig
                assertEquals(2L, view.document.revision); assertTrue(ReleaseBlocker.COST_OBJECT_INACTIVE in view.blockers, view.blockers.toString())
            } else {             // Deaktivierung kam zuerst: Zuordnung abgewiesen, Beleg unverändert
                assertEquals(ErrorCode.VALIDATION_FAILED, (a.exceptionOrNull() as ProblemException).code, a.toString())
                assertEquals(1L, view.document.revision); assertEquals(listOf(ReleaseBlocker.COST_ASSIGNMENT_MISSING), view.blockers)
            }
            assertFalse(view.approvable, "nie ein freigabefähiger Beleg mit inaktivem Kostenobjekt")
        }
    }

    @Test
    fun parallele_fahrzeugzuordnung_legt_genau_ein_kostenobjekt_an() {
        val s = Scenario(); val docs = (1..4).map { bf.upload(s.w, s.fin, Samples.jpeg(seed = 20 + it)) }
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        val futures = docs.map { d -> pool.submit<Result<Any>> { start.await(); runCatching { bf.svc.costs.assign(s.fin, d.documentId, 1, AssignCosts(vehicleIds = setOf(s.vehicle))) } } }
        start.countDown(); val out = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(4, out.count { it.isSuccess }, out.toString())
        assertEquals(1, bf.fx.count("SELECT count(*) FROM taktovia.cost_object WHERE tenant_id=? AND kind='VEHICLE' AND vehicle_id=?", s.w.tenant.value, s.vehicle))
    }

    @Test
    fun nach_freigabe_ist_die_zuordnung_unveraenderlich() {
        val s = Scenario()
        bf.svc.costs.assign(s.fin, s.doc.documentId, 1, AssignCosts(costObjectIds = setOf(s.ks)))
        bf.fx.exec("UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=?", s.doc.documentId)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, bf.problem { bf.svc.costs.assign(s.fin, s.doc.documentId, 2, AssignCosts(vehicleIds = setOf(s.vehicle))) }.code)
    }

    @Test
    fun beleg_in_quarantaene_erlaubt_keine_zuordnung() {
        val s = Scenario(); val infected = bf.upload(s.w, s.fin, Samples.pdf(EicarSignatureScanner.EICAR))
        assertEquals("QUARANTINE_REJECTED", infected.state.name)
        assertEquals(ErrorCode.CONFLICT, bf.problem { bf.svc.costs.assign(s.fin, infected.documentId, infected.revision, AssignCosts(costObjectIds = setOf(s.ks))) }.code)
    }
}
