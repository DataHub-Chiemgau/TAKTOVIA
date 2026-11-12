package de.taktovia.stafftime

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.CreateRoster
import de.taktovia.stafftime.api.RosterShiftInput
import de.taktovia.stafftime.api.RosterView
import java.time.LocalDate
import java.util.UUID

/**
 * Welt der Dienstplan-Tests (WP-M4-04 bis -16): Mandant mit Betreiber, Region und Depot(s), Planer- und Personalkontexte, Dienstpläne je Woche. Eigene Klasse, nutzt nur [StaffFixture].
 * Die Depotberechtigung (WP-M4-16) wird hier an einer Stelle vergeben: [planner] erhält das Recht für [depot], solange `grantDepot` verwendet wird.
 */
class RosterTestWorld(val fx: StaffFixture) {
    val tenant: TenantId
    val operator: UUID = UUID.randomUUID()
    val depot: UUID = UUID.randomUUID()
    private val region: UUID = UUID.randomUUID()

    init {
        val t = UUID.randomUUID(); val le = UUID.randomUUID()
        fx.exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        tenant = TenantId(t)
        fx.tx.run(tenant) { j ->
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,'Betrieb',?,CAST('{TRANSPORT_COMPANY}' AS text[]))", t, operator, le, "f-$operator")
            j.update("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (?,?,'R','user:t')", t, region)
            j.update("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,'Depot Nord','user:t')", t, depot, region)
        }
    }

    fun newDepot(name: String = "Depot Süd"): UUID = UUID.randomUUID().also { d ->
        fx.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (?,?,?,?,'user:t')", tenant.value, d, region, name) }
    }

    /** Planer mit Funktionsscopes wie die Rechtematrix sie der Rolle `planner` gibt. */
    fun planner(subject: UUID = UUID.randomUUID(), depots: Collection<UUID> = listOf(depot)): RequestContext =
        StaffM4Support.ctx(tenant, subject, setOf("planner"), setOf("rosters:write", "rosters:read", "rosters:publish", "absences:read")).also { c -> grantDepots(c, depots) }

    /** Rolle ohne Dienstplanrechte (Fahrer, Disposition): kein Funktionsscope für Dienstpläne. */
    fun driver(subject: UUID = UUID.randomUUID()): RequestContext = StaffM4Support.employee(tenant, subject)

    fun hr(): RequestContext = StaffM4Support.hr(tenant)

    /** Fahrer des Stamms mit Mitgliedschaft (Konto-[subject]); [qualifications] sind Qualifikationscodes mit Gültigkeit `[from, to)` (null = unbegrenzt). */
    inner class Person(val driverId: UUID, val subject: String)

    fun person(qualifications: Map<String, Pair<String, String?>> = emptyMap()): Person {
        val m = UUID.randomUUID(); val d = UUID.randomUUID(); val sub = UUID.randomUUID().toString()
        fx.exec("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (?,?,?,?,'driver')", tenant.value, m, "https://idp.test/realms/m4", sub)
        fx.exec("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value,status) VALUES (?,?,?,'Fahrer','APP','x','ACTIVE')", tenant.value, d, m)
        qualifications.forEach { (code, range) ->
            fx.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,valid_to) VALUES (?,?,?,?,1,CAST(? AS timestamptz),CAST(? AS timestamptz))",
                tenant.value, UUID.randomUUID(), d, code, range.first, range.second)
        }
        return Person(d, sub)
    }

    /** Administration des Mandanten (Scope `organization:write`): vergibt und entzieht Depotrechte (WP-M4-16). */
    val admin: RequestContext = StaffM4Support.ctx(tenant, UUID.randomUUID(), setOf("tenant_admin"), setOf("organization:write"))

    /** Vergibt der Person des Kontexts das Recht für die Depots (WP-M4-16); ohne Vergabe ist ihr Depotscope leer (fail-closed). */
    fun grantDepots(ctx: RequestContext, depots: Collection<UUID>) { depots.forEach { fx.staff.rosterDepotAccess.grantDepot(admin, ctx.actor.subject, it) } }

    /** Dienstplan des Depots für die Woche, die [day] enthält (Montag als Wochenbeginn). */
    fun roster(ctx: RequestContext, day: LocalDate = LocalDate.of(2026, 12, 1), depotId: UUID = depot): RosterView =
        fx.staff.rosters.create(ctx, CreateRoster(depotId, day.minusDays((day.dayOfWeek.value - 1).toLong())))

    fun input(day: String = "2026-12-01", start: String = "06:00", end: String = "14:00", driver: UUID? = null, need: Int = 1, function: String = "Fahrdienst", qualification: String? = null,
              depotId: UUID = depot, endDay: String = day) =
        RosterShiftInput("${day}T$start:00", null, "${endDay}T$end:00", null, "Europe/Berlin", null, operator, depotId, function, qualification, need, driver)
}
