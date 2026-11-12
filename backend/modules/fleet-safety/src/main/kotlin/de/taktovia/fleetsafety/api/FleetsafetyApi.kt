package de.taktovia.fleetsafety.api

import de.taktovia.fleetsafety.internal.FleetServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Öffentliche Ports des Moduls `fleet-safety`; wächst mit den Arbeitspaketen (bisher: Fahrzeugrollen WP-M1-34, Fahrzeugstamm WP-M3-03, Kapazitätskonfiguration WP-M3-04, Fahrzeugpool WP-M3-06). */
interface FleetsafetyApi {
    val vehicleRoles: VehicleRoles

    /** Fahrzeugstamm (WP-M3-03). */
    val vehicles: Vehicles

    /** Kapazitäts- und Stellplatzkonfiguration (WP-M3-04). */
    val vehicleCapacities: VehicleCapacities

    /** Leseport Fahrzeugstamm und Rollen für M4 (WP-M4-02). */
    val fleetReadPort: de.taktovia.platform.readports.FleetReadPort
    /** Fahrzeugpool: Dienstzuordnung mit Gültigkeitsintervall und Gebietsbezug (WP-M3-06). */
    val vehiclePool: VehiclePool

    companion object {
        fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC()): FleetsafetyApi =
            FleetServicesFactory.create(ctxTx, audit, outbox, clock)
    }
}

/**
 * Die vier Rollen eines Fahrzeugs (LH-I.4-MUL-004), unabhängig und je zeitlich gültig. Abbildung auf Kap. 02 §2 ist Planungsannahme (OP-26):
 * [OWNER] Eigentümer, [OPERATOR] Betreiber (Einsatzbetreiber), [DISPATCH_PROVIDER] Einsatzleister, [BILLING_PARTNER] Abrechnungspartner.
 */
enum class VehicleRole { OWNER, OPERATOR, DISPATCH_PROVIDER, BILLING_PARTNER;
    companion object { fun parse(code: String): VehicleRole? = entries.firstOrNull { it.name == code } }
}

/** Zuordnung einer Rolle am Fahrzeug mit halboffenem Intervall `[validFrom, validTo)`; `validTo == null` = unbegrenzt. */
data class VehicleRoleAssignmentView(
    val id: UUID, val vehicleId: UUID, val organizationId: UUID, val role: VehicleRole,
    val validFrom: Instant, val validTo: Instant?, val revision: Long, val createdAt: Instant,
)

data class AssignVehicleRole(val vehicleId: UUID, val role: VehicleRole, val organizationId: UUID, val validFrom: Instant, val validTo: Instant? = null)

/** Verweis auf die zum Zeitpunkt gültige Zuordnung (Intervall-ID und Organisation). */
data class RoleAt(val assignmentId: UUID, val organizationId: UUID, val validFrom: Instant, val validTo: Instant?)

/**
 * Unveränderlicher Snapshot „Rollen zum Leistungszeitpunkt“ (Snapshot-Vertrag WP-M1-34): abrechnende Module (`vehicle_run`, Kostenereignis; M2/M3/M4) speichern ihn
 * zum Leistungszeitpunkt und lesen später nie live. Eine Rolle ohne gültige Zuordnung steht als `null`.
 */
data class VehicleRoleSnapshot(val vehicleId: UUID, val at: Instant, val roles: Map<VehicleRole, RoleAt?>) {
    /** Stabile, serialisierbare Form zum Ablegen (Schlüssel in fester Reihenfolge der Rollen). */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to vehicleId.toString(), "as_of" to at.toString(),
        "roles" to VehicleRole.entries.associate { r ->
            r.name to roles[r]?.let { linkedMapOf("assignment_id" to it.assignmentId.toString(), "organization_id" to it.organizationId.toString(), "valid_from" to it.validFrom.toString(), "valid_to" to it.validTo?.toString()) }
        },
    )
}

/**
 * Fahrzeugrollen (WP-M1-34): zuordnen (Wechsel zum Stichtag), lesen, As-of-Auflösung und Snapshot. Fremde Mandanten sind nicht sichtbar (404, auch für Organisationen);
 * ein Wechsel schließt das laufende Intervall der Rolle zum Stichtag und legt ein neues an (eine Transaktion, ein Audit, genau ein Outbox-Ereignis
 * `vehicle.role_assignment_changed`); Überschneidung mit einem laufenden oder künftigen Intervall derselben Rolle: 409; Überschneidung mit einem bereits abgeschlossenen
 * Intervall (rückwirkende Änderung): 422. Gleichzeitige Wechsel desselben Fahrzeugs werden über die Fahrzeugzeile serialisiert.
 */
interface VehicleRoles {
    fun assign(ctx: RequestContext, cmd: AssignVehicleRole): VehicleRoleAssignmentView

    /** Zuordnungen des Mandanten nach (`created_at`, `id`), optional nur eines Fahrzeugs und nur die zum Zeitpunkt [asOf] gültigen; je Seite höchstens [limit]+1 Zeilen. */
    fun list(ctx: RequestContext, vehicleId: UUID?, asOf: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<VehicleRoleAssignmentView>

    /** Snapshot im Kontext der Anfrage (404, wenn das Fahrzeug im Mandanten nicht existiert). */
    fun rolesAt(ctx: RequestContext, vehicleId: UUID, at: Instant): VehicleRoleSnapshot

    /** Snapshot innerhalb der **laufenden Mandantentransaktion** eines abrechnenden Moduls (ein Statement, daher konsistent: nie ein Mischstand aus Wechsel und Altzustand). */
    fun rolesAt(jdbc: JdbcOperations, vehicleId: UUID, at: Instant): VehicleRoleSnapshot
}
