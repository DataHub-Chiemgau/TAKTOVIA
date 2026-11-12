package de.taktovia.stafftime

import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.StafftimeSettings
import org.junit.jupiter.api.Assertions.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Veränderbare Serveruhr für Dienstantritt und Standortalter (WP-M3-25/-34): Tests stellen die Zeit, der Produktivcode nutzt `Clock.systemUTC()`. */
class MovableClock(@Volatile var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * Welt für die Schicht-Tests (WP-M3-07/-25/-34): Mandant, Fahrer mit Mitgliedschaft und Konto, Fahrzeuge, Schichten; echte Runtime-Rolle, echte Datenbank.
 * Nutzt [StaffFixture] für Verbindungen und Hilfsabfragen; keine Duplikate dieser Fixture.
 */
class ShiftWorld(val fx: StaffFixture, val clock: MovableClock = MovableClock(Instant.parse("2026-12-01T05:50:00Z")), source: DriverRunSource? = null, settings: StafftimeSettings = StafftimeSettings()) {
    val tenant: TenantId = TenantId(UUID.randomUUID()).also { fx.exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", it.value, "t-${it.value}") }
    val staff: StafftimeApi = StafftimeApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), clock, settings, source)

    class Person(val driverId: UUID, val subject: String, val membershipId: UUID)

    fun vehicle(label: String = "A-" + UUID.randomUUID().toString().take(8), active: Boolean = true): UUID = UUID.randomUUID().also { v ->
        fx.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label,active) VALUES (?,?,'fixture','{}',?,?)", tenant.value, v, label, active) }
    }

    /** Fahrer des Stamms mit Mitgliedschaft (Rolle driver) und Konto-Subject. */
    fun person(role: String = "driver", status: String = "ACTIVE"): Person {
        val m = UUID.randomUUID(); val d = UUID.randomUUID(); val sub = UUID.randomUUID().toString()
        fx.exec("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (?,?,?,?,?)", tenant.value, m, ISSUER, sub, role)
        fx.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value,status) VALUES (?,?,?,'Fahrer','APP','x',?)", tenant.value, d, m, status) }
        return Person(d, sub, m)
    }

    fun dispatcher(scopes: Set<String> = setOf("shifts:write", "shifts:read"), roles: Set<String> = setOf("dispatcher")) =
        RequestContext(Actor(ActorType.HUMAN, ISSUER, "disp-" + UUID.randomUUID(), "sid-disp", true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    fun driverCtx(p: Person, session: String = "sid-" + p.subject, roles: Set<String> = setOf("driver"), scopes: Set<String> = setOf("driver:run", "driver:devices", "driver:positions"), human: Boolean = true) =
        RequestContext(Actor(if (human) ActorType.HUMAN else ActorType.SERVICE, ISSUER, p.subject, session, true), tenant, roles, scopes, emptySet(), UUID.randomUUID())

    /** Gerät des Fahrers, an dessen Sitzung gebunden (wie nach `POST /v1/driver/devices`). */
    fun device(p: Person, session: String = "sid-" + p.subject, revoked: Boolean = false): UUID = UUID.randomUUID().also { d ->
        fx.exec("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version,revoked_at) VALUES (?,?,?,?,?,'ANDROID','1.0.0',?)", tenant.value, d, ISSUER, p.subject, session,
            if (revoked) java.sql.Timestamp.from(Instant.now()) else null)
    }

    /** Direkt per SQL (Administration, ohne Konfliktprüfung des Dienstes aber mit dem Trigger) angelegte Schicht. */
    fun shiftRow(p: Person, vehicle: UUID, from: String, to: String, state: String = "PLANNED"): UUID = UUID.randomUUID().also { s ->
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,?::timestamptz,?::timestamptz,'Europe/Berlin',0,?)",
            tenant.value, s, p.driverId, vehicle, from, to, state)
    }

    fun release(shift: UUID, vehicle: UUID, validUntil: String = "2026-12-01T18:00:00Z", safetyRevision: Long = 1, revoked: Boolean = false) {
        fx.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until,revoked_at) VALUES (?,?,?,?,?,?,1,?::timestamptz,?)",
            tenant.value, UUID.randomUUID(), vehicle, shift, safetyRevision, UUID.randomUUID(), validUntil, if (revoked) java.sql.Timestamp.from(Instant.now()) else null)
    }

    fun unblock(vehicle: UUID) = fx.exec("UPDATE taktovia.vehicle SET active_block = false WHERE tenant_id = ? AND id = ?", tenant.value, vehicle)

    companion object {
        const val ISSUER = "https://idp.test/realms/m3"
        fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java, block)
    }
}
