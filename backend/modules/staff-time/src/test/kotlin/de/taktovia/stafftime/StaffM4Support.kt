package de.taktovia.stafftime

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/** Anfragekontexte für die M4-Tests von `staff-time` (WP-M4-06, WP-M4-08): Rollen und Funktionsscopes wie die Rechtematrix sie vergibt. */
object StaffM4Support {
    val HR_SCOPES = setOf("absences:write", "absences:read", "absences:detail", "time-events:write", "time-events:read", "time-events:manage")
    val PLANNER_SCOPES = setOf("absences:read", "time-events:write", "time-events:read")
    val EMPLOYEE_SCOPES = setOf("time-events:write", "time-events:read")

    fun ctx(tenant: TenantId, subject: UUID, roles: Set<String>, scopes: Set<String>) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/m4", subject.toString(), "sid-$subject", true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    fun hr(tenant: TenantId, subject: UUID = UUID.randomUUID()) = ctx(tenant, subject, setOf("staff_planning"), HR_SCOPES)
    fun planner(tenant: TenantId, subject: UUID = UUID.randomUUID()) = ctx(tenant, subject, setOf("planner"), PLANNER_SCOPES)
    fun employee(tenant: TenantId, subject: UUID = UUID.randomUUID()) = ctx(tenant, subject, setOf("driver"), EMPLOYEE_SCOPES)
}
