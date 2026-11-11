package de.taktovia.identity

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.identity.api.CreateLegalEntity
import de.taktovia.identity.api.CreateOrganization
import de.taktovia.identity.api.NewTenant
import de.taktovia.identity.api.OrganizationRole
import de.taktovia.identity.api.PlatformActor
import de.taktovia.identity.api.UpdateLegalEntity
import de.taktovia.identity.api.UpdateOrganization
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-05 / LH-I.4-MUL-003: Rechtsträger, Organisationen und die sechs vertraglichen Rollen (Plattformbetreiber, Verkehrsunternehmen,
 * Aufgabenträger, Auftraggeber, Vermittler, tatsächlicher Beförderer) getrennt gespeichert; Mandantentrennung, Mandantenanlage der
 * Plattformverwaltung und Statuswechsel. Echte Datenbank, Runtime-Rolle; nur Testdaten legt der Administrator an.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationRolesTest {
    private lateinit var fx: IdentityFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = IdentityFixture() }
    @AfterAll fun down() = fx.close()

    private class World(val tenant: TenantId, val ctx: de.taktovia.platform.context.RequestContext, val legalEntity: UUID)

    private fun world(): World {
        val t = fx.tenant(); val subject = "admin-" + UUID.randomUUID()
        val ctx = fx.context(t, subject, setOf(fx.seedMembership(t, subject)))
        return World(t, ctx, fx.identity.organizations.createLegalEntity(ctx, CreateLegalEntity("Rechtsträger", "DE")).id)
    }

    private fun create(w: World, roles: List<String>, ref: String = "fed-" + UUID.randomUUID(), name: String = "Org") =
        fx.identity.organizations.createOrganization(w.ctx, CreateOrganization(w.legalEntity, name, ref, roles))

    private fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)

    private fun operator(): PlatformActor {
        val subject = "op-" + UUID.randomUUID()
        fx.adminExec("INSERT INTO taktovia.platform_operator_account(issuer, subject) VALUES (?, ?)", fx.issuer, subject)
        return PlatformActor(fx.issuer, subject, UUID.randomUUID(), true)
    }

    private fun newTenant(admin: String = "first-" + UUID.randomUUID()) = NewTenant("Mandant " + UUID.randomUUID(), "Stadtwerke GmbH", "DE", null, fx.issuer, admin)

    @Test
    fun vier_organisationen_speichern_vertragliche_rollen_getrennt() {
        val w = world()
        val roles = listOf("PLATFORM_OPERATOR", "TASK_AUTHORITY", "BROKER", "ACTUAL_CARRIER")
        val created = roles.map { create(w, listOf(it), name = "Org $it") }
        // getrennt gelesen: jede Organisation trägt genau ihre eine Rolle, keine ist aus einer anderen abgeleitet
        created.forEach { c ->
            val read = fx.identity.organizations.getOrganization(w.ctx, c.id)
            assertEquals(c.roles, read.roles); assertEquals(1, read.roles.size)
        }
        assertEquals(roles, created.map { it.roles.single() })
        val listed = fx.identity.organizations.listOrganizations(w.ctx, 50, null).associate { it.id to it.roles }
        created.forEach { assertEquals(it.roles, listed[it.id]) }
        // auch die zwei übrigen Rollen des Katalogs sind getrennte Werte
        assertEquals(setOf("TRANSPORT_COMPANY", "CLIENT"), setOf("TRANSPORT_COMPANY", "CLIENT").map { create(w, listOf(it)).roles.single() }.toSet())
        assertEquals(6, OrganizationRole.ALL.size)
    }

    @Test
    fun unbekannte_rolle_ergibt_422() {
        val w = world()
        for (bad in listOf(listOf("OPERATOR"), listOf("transport_company"), emptyList(), listOf("CLIENT", "CLIENT"), listOf("CLIENT", "SUPERUSER"))) {
            val e = problem { create(w, bad) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, bad.toString())
            assertTrue(e.violations.any { it.pointer.startsWith("/roles") }, e.violations.toString())
        }
        val org = create(w, listOf("CLIENT"))
        assertEquals(422, problem { fx.identity.organizations.updateOrganization(w.ctx, org.id, 1, UpdateOrganization(w.legalEntity, "x", listOf("KUNDE"))) }.code.status)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.organization WHERE tenant_id=? AND roles && ARRAY['OPERATOR','KUNDE','SUPERUSER']", w.tenant.value))
    }

    @Test
    fun mehrere_rollen_je_organisation_und_keine_rolle_wird_abgeleitet() {
        val w = world()
        val multi = create(w, listOf("BROKER", "CLIENT"))
        assertEquals(setOf("BROKER", "CLIENT"), fx.identity.organizations.getOrganization(w.ctx, multi.id).roles.toSet())
        // Plattformbetreiber ist nicht automatisch Beförderer, Vermittler oder Auftraggeber (und umgekehrt)
        val operator = create(w, listOf("PLATFORM_OPERATOR"))
        assertEquals(listOf("PLATFORM_OPERATOR"), fx.identity.organizations.getOrganization(w.ctx, operator.id).roles)
        // Rollen ändern ersetzt die Menge genau; es kommt nichts hinzu
        val changed = fx.identity.organizations.updateOrganization(w.ctx, operator.id, 1, UpdateOrganization(w.legalEntity, operator.name, listOf("ACTUAL_CARRIER")))
        assertEquals(listOf("ACTUAL_CARRIER"), changed.roles); assertEquals(2, changed.revision)
        // Ein Gültigkeitsintervall mit Ende vor dem Beginn wird abgewiesen
        val from = java.time.Instant.parse("2026-12-01T00:00:00Z")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.identity.organizations.createOrganization(w.ctx, CreateOrganization(w.legalEntity, "x", "f-" + UUID.randomUUID(), listOf("CLIENT"), from, from.minusSeconds(1))) }.code)
    }

    @Test
    fun organisation_des_mandanten_b_aus_a_nicht_les_oder_referenzierbar_404() {
        val a = world(); val b = world()
        val orgB = create(b, listOf("CLIENT"))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.identity.organizations.getOrganization(a.ctx, orgB.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.identity.organizations.updateOrganization(a.ctx, orgB.id, 1, UpdateOrganization(a.legalEntity, "x", listOf("CLIENT"))) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.identity.organizations.updateLegalEntity(a.ctx, b.legalEntity, 1, UpdateLegalEntity("x", "DE")) }.code)
        assertTrue(fx.identity.organizations.listOrganizations(a.ctx, 50, null).none { it.id == orgB.id })
        assertTrue(fx.identity.organizations.listLegalEntities(a.ctx, 50, null).none { it.id == b.legalEntity })
        // B ist unverändert
        assertEquals(1, fx.identity.organizations.getOrganization(b.ctx, orgB.id).revision)
    }

    @Test
    fun fremder_rechtstraeger_wird_abgewiesen() {
        val a = world(); val b = world()
        // als Verweis im Body: nicht auffindbar (404), nichts wird gespeichert
        val e = problem { fx.identity.organizations.createOrganization(a.ctx, CreateOrganization(b.legalEntity, "x", "f-" + UUID.randomUUID(), listOf("CLIENT"))) }
        assertEquals(ErrorCode.NOT_FOUND, e.code)
        val own = create(a, listOf("CLIENT"))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.identity.organizations.updateOrganization(a.ctx, own.id, 1, UpdateOrganization(b.legalEntity, "x", listOf("CLIENT"))) }.code)
        assertEquals(1, fx.identity.organizations.getOrganization(a.ctx, own.id).revision)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.organization WHERE tenant_id=? AND legal_entity_id=?", a.tenant.value, b.legalEntity))
    }

    @Test
    fun paralleles_anlegen_gleicher_federation_ref_genau_ein_erfolg() {
        val w = world(); val ref = "par-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        val results = (1..8).map { pool.submit<String> { start.await(); try { create(w, listOf("BROKER"), ref); "ok" } catch (e: ProblemException) { e.code.name } } }
        start.countDown()
        val r = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == "ok" }, r.toString()); assertEquals(7, r.count { it == "CONFLICT" }, r.toString())
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.organization WHERE tenant_id=? AND federation_ref=?", w.tenant.value, ref))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='organization.changed' AND payload->>'federation_ref'=?", w.tenant.value, ref))
    }

    @Test
    fun aenderung_erzeugt_genau_ein_audit_und_ein_outbox_ereignis() {
        val w = world()
        val le = fx.identity.organizations.updateLegalEntity(w.ctx, w.legalEntity, 1, UpdateLegalEntity("Neuer Name", "AT"))
        assertEquals(2, le.revision)
        val org = create(w, listOf("TRANSPORT_COMPANY"))
        val updated = fx.identity.organizations.updateOrganization(w.ctx, org.id, 1, UpdateOrganization(w.legalEntity, "Umbenannt", listOf("TRANSPORT_COMPANY", "ACTUAL_CARRIER")))
        assertEquals(2, updated.revision)

        fun audits(obj: String) = fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.organization.changed' AND object_ref=?", w.tenant.value, obj)
        fun events(id: UUID) = fx.adminStrings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='organization.changed' ORDER BY aggregate_revision", w.tenant.value, id)
        assertEquals(2, audits("organization:${org.id}")); assertEquals(2, events(org.id).size, "angelegt + geändert: je genau ein Ereignis")
        assertEquals(2, audits("legal_entity:${w.legalEntity}")); assertEquals(2, events(w.legalEntity).size)
        // Ereignisse sind gültig gegen das Schema; der Urheber steht im Audit als Pseudonym, nie als Name
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/organization.changed.v1.schema.json")))
        (events(org.id) + events(w.legalEntity)).forEach { assertEquals(emptyList<String>(), schema.validate(it, InputFormat.JSON).map { m -> m.toString() }, it) }
        assertEquals(listOf("user:" + w.ctx.actor.subject), fx.adminStrings("SELECT created_by FROM taktovia.organization WHERE id=?", org.id))
        // eine Änderung ohne Wirkung ist keine Änderung: weder Revision noch Audit noch Ereignis
        val same = fx.identity.organizations.updateOrganization(w.ctx, org.id, 2, UpdateOrganization(w.legalEntity, "Umbenannt", listOf("TRANSPORT_COMPANY", "ACTUAL_CARRIER")))
        assertEquals(2, same.revision); assertEquals(2, audits("organization:${org.id}")); assertEquals(2, events(org.id).size)
        // Fehlerfall hinterlässt nichts (eine Transaktion)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.identity.organizations.updateOrganization(w.ctx, org.id, 2, UpdateOrganization(w.legalEntity, "", listOf("CLIENT"))) }.code)
        assertEquals(2, audits("organization:${org.id}"))
    }

    @Test
    fun zwei_parallele_aenderungen_derselben_organisation_eine_412() {
        val w = world(); val org = create(w, listOf("CLIENT"))
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val r = listOf("A", "B").map { n -> pool.submit<String> { start.await(); try { fx.identity.organizations.updateOrganization(w.ctx, org.id, 1, UpdateOrganization(w.legalEntity, "Name $n", listOf("CLIENT", "BROKER"))); "ok" } catch (e: ProblemException) { e.code.name } } }
        start.countDown()
        val res = r.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(listOf("REVISION_MISMATCH", "ok"), res.sorted(), res.toString())
        assertEquals(2, fx.identity.organizations.getOrganization(w.ctx, org.id).revision)
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='organization.changed'", w.tenant.value, org.id))
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.identity.organizations.updateOrganization(w.ctx, org.id, 1, UpdateOrganization(w.legalEntity, "alt", listOf("CLIENT"))) }.code)
    }

    @Test
    fun mandantenanlage_nur_durch_plattformbetreiber_und_ohne_fachdatensicht() {
        val op = operator(); val firstAdmin = "first-" + UUID.randomUUID()
        val seeded = mutableListOf<TenantId>()
        val created = fx.identity.platform.createTenant(op, newTenant(firstAdmin)) { _, t -> seeded += t }
        val tenant = TenantId(created.tenantId)
        assertEquals("ACTIVE", created.status); assertEquals(listOf(tenant), seeded, "Erstausstattung läuft in derselben Transaktion")
        // Mandant, Rechtsträger und erste Administrator-Mitgliedschaft; Audit und Ereignis für Anlage und Mitgliedschaft
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.legal_entity WHERE tenant_id=? AND id=?", tenant.value, created.legalEntityId))
        assertEquals(listOf("tenant_admin"), fx.adminStrings("SELECT role_code FROM taktovia.membership WHERE tenant_id=? AND subject=?", tenant.value, firstAdmin))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.tenant.created' AND actor_ref=?", tenant.value, "user:" + op.subject))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.membership.granted'", tenant.value))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='membership.granted'", tenant.value))
        // der erste Administrator ist im neuen Mandanten handlungsfähig
        assertEquals(tenant, fx.identity.lookup.activeIn(de.taktovia.identity.api.SubjectRef(fx.issuer, firstAdmin), tenant)?.tenant)
        // der Plattformbetreiber hat keine Mitgliedschaft und damit keine Fachdatensicht
        assertNull(fx.identity.lookup.activeIn(de.taktovia.identity.api.SubjectRef(op.issuer, op.subject), tenant))
        assertEquals(0, fx.identity.lookup.selectableTenants(de.taktovia.identity.api.SubjectRef(op.issuer, op.subject)).size)

        // negativ: Mandantenadministrator (kein Betreiberkonto) und Betreiberkonto ohne zweiten Faktor
        val adminOfTenant = PlatformActor(fx.issuer, firstAdmin, UUID.randomUUID(), true)
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.identity.platform.createTenant(adminOfTenant, newTenant()) }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.identity.platform.createTenant(PlatformActor(op.issuer, op.subject, UUID.randomUUID(), false), newTenant()) }.code)
        // widerrufenes Betreiberkonto
        fx.adminExec("UPDATE taktovia.platform_operator_account SET revoked_at = now() WHERE issuer=? AND subject=?", op.issuer, op.subject)
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.identity.platform.createTenant(op, newTenant()) }.code)
        // Eingabefehler: nichts wird angelegt
        val op2 = operator(); val before = fx.adminCount("SELECT count(*) FROM taktovia.tenant")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.identity.platform.createTenant(op2, newTenant().copy(legalEntityCountry = "Deutschland")) }.code)
        assertEquals(before, fx.adminCount("SELECT count(*) FROM taktovia.tenant"))
        // Fehler in der Erstausstattung rollt alles zurück
        val failing = newTenant(); assertThrows(IllegalStateException::class.java) { fx.identity.platform.createTenant(op2, failing) { _, _ -> throw IllegalStateException("Erstausstattung fehlgeschlagen") } }
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.tenant WHERE name=?", failing.name))
    }

    @Test
    fun suspended_mandant_verweigert_fachzugriff() {
        val op = operator()
        val created = fx.identity.platform.createTenant(op, newTenant("adm-" + UUID.randomUUID()))
        val tenant = TenantId(created.tenantId)
        val admin = fx.adminStrings("SELECT subject FROM taktovia.membership WHERE tenant_id=?", tenant.value).single()
        val who = de.taktovia.identity.api.SubjectRef(fx.issuer, admin)
        assertTrue(fx.identity.lookup.activeIn(who, tenant) != null)

        val suspended = fx.identity.platform.changeTenantStatus(op, tenant, "SUSPENDED")
        assertEquals("ACTIVE" to "SUSPENDED", suspended.from to suspended.to)
        assertNull(fx.identity.lookup.activeIn(who, tenant), "in SUSPENDED schlägt jeder Fachzugriff fehl")
        assertEquals(0, fx.identity.lookup.selectableTenants(who).size)
        // eine bereits laufende Anfrage (Kontext aus der Zeit vor der Sperre) scheitert in der Fachtransaktion
        val staleCtx = fx.context(tenant, admin, fx.adminStrings("SELECT id::text FROM taktovia.membership WHERE tenant_id=?", tenant.value).map { UUID.fromString(it) }.toSet())
        assertThrows(de.taktovia.platform.context.ContextRevokedException::class.java) { fx.identity.organizations.listOrganizations(staleCtx, 10, null) }
        // Wiederaufnahme, dann Schließen; CLOSED ist endgültig
        fx.identity.platform.changeTenantStatus(op, tenant, "ACTIVE")
        assertTrue(fx.identity.lookup.activeIn(who, tenant) != null)
        fx.identity.platform.changeTenantStatus(op, tenant, "CLOSED")
        assertNull(fx.identity.lookup.activeIn(who, tenant))
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { fx.identity.platform.changeTenantStatus(op, tenant, "ACTIVE") }.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { fx.identity.platform.changeTenantStatus(op, tenant, "GELOESCHT") }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.identity.platform.changeTenantStatus(op, TenantId(UUID.randomUUID()), "SUSPENDED") }.code)
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.identity.platform.changeTenantStatus(PlatformActor(fx.issuer, admin, UUID.randomUUID(), true), tenant, "ACTIVE") }.code)
        // jeder Statuswechsel ist auditiert (Akteur = Betreiber) und erzeugt das Ereignis des Automaten
        assertEquals(3, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.tenant.status_changed' AND actor_ref=?", tenant.value, "user:" + op.subject))
        assertEquals(3, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='tenant.state_changed'", tenant.value))
    }

    private fun assertNull(v: Any?, msg: String? = null) = org.junit.jupiter.api.Assertions.assertNull(v, msg)
}
