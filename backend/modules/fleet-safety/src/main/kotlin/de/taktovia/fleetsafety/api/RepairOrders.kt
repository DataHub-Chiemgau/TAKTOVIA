package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ViolationCode
import java.time.Instant
import java.util.UUID

/** Zustände des Reparaturauftrags (`zustaende/reparaturauftrag.md`); `PROOF_RECORDED` und `CANCELED` sind endgültig. */
enum class RepairStatus { OPEN, IN_PROGRESS, PROOF_RECORDED, CANCELED;
    val isFinal get() = this == PROOF_RECORDED || this == CANCELED
    companion object { fun parse(code: String): RepairStatus? = entries.firstOrNull { it.name == code } }
}

/** Reparaturauftrag zu einer Schadenakte. [costReference] ist ein Verweis auf Kostenstelle/-träger, nie ein Betrag; [proofEvidenceId] der Reparaturnachweis (freigegebene Evidenz der Akte). */
data class RepairOrderView(
    val id: UUID, val defectId: UUID, val vehicleId: UUID, val status: RepairStatus, val workshopRef: String?, val plannedAt: Instant?, val costReference: String?,
    val proofEvidenceId: UUID?, val proofRecordedAt: Instant?, val createdAt: Instant, val revision: Long,
)

/** Anlage eines Reparaturauftrags (`RepairOrderCreate`): zulässig, wenn die Akte `IN_PROGRESS` ist; die Akte geht dabei auf `REPAIR`. */
data class CreateRepairOrder(val defectId: UUID, val workshopRef: String?, val plannedAt: Instant?, val costReference: String?) {
    companion object {
        private val FIELDS = setOf("defect_id", "workshop_ref", "planned_at", "cost_reference")
        fun parse(raw: Map<String, Any?>): CreateRepairOrder {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val defect = v.uuid(raw, "defect_id", true)
            val workshop = if (raw["workshop_ref"] == null) null else v.text(raw, "workshop_ref", 200, false)
            val planned = if (raw["planned_at"] == null) null else v.instant(raw, "planned_at", false)
            val cost = if (raw["cost_reference"] == null) null else v.text(raw, "cost_reference", 64, false)
            v.throwIfAny()
            return CreateRepairOrder(defect!!, workshop, planned, cost)
        }
    }
}

/** Statusübergang des Reparaturauftrags (`RepairOrderTransition`): `PROOF_RECORDED` mit Nachweis und Kostenbezug, `CANCELED` mit Begründung. */
data class RepairTransition(val to: RepairStatus, val proofEvidenceId: UUID?, val costReference: String?, val reason: String?) {
    companion object {
        private val FIELDS = setOf("to", "proof_evidence_id", "cost_reference", "reason")
        fun parse(raw: Map<String, Any?>): RepairTransition {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val to = when (val t = raw["to"]) {
                null -> { v.add("/to", ViolationCode.REQUIRED); null }
                is String -> RepairStatus.parse(t)?.takeIf { it != RepairStatus.OPEN } ?: run { v.add("/to", ViolationCode.NOT_IN_ENUM); null }
                else -> { v.add("/to", ViolationCode.TYPE_MISMATCH); null }
            }
            val proof = if (raw["proof_evidence_id"] == null) null else v.uuid(raw, "proof_evidence_id", false)
            val cost = if (raw["cost_reference"] == null) null else v.text(raw, "cost_reference", 64, false)
            val reason = if (raw["reason"] == null) null else v.text(raw, "reason", DefectCatalog.MAX_REASON, false)
            v.throwIfAny()
            return RepairTransition(to!!, proof, cost, reason)
        }
    }
}

/**
 * Reparaturaufträge (WP-M3-51). Anlegen, Übergänge und Nachweis nur durch die Werkstatt (`workshop`), Lesen auch Disposition und Betriebsleitung. Fremder Mandant, fremde Akte: 404;
 * Akte nicht in `IN_PROGRESS` oder zweiter aktiver Auftrag: 409; veraltete Revision: 412. Auftrag und Aktenstatus (`IN_PROGRESS -> REPAIR` bzw. bei Stornierung zurück) ändern sich in **einer**
 * Transaktion unter der Zeilensperre der Akte; Audit und Ereignis (`repair.order_created`, `repair.proof_recorded`) in derselben Transaktion.
 */
interface RepairOrders {
    fun create(ctx: RequestContext, cmd: CreateRepairOrder): RepairOrderView
    fun get(ctx: RequestContext, id: UUID): RepairOrderView
    fun list(ctx: RequestContext, defectId: UUID?, vehicleId: UUID?, limit: Int, after: Pair<Instant, UUID>?): List<RepairOrderView>
    fun transition(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: RepairTransition): RepairOrderView
}

/** Entscheidung über die Wiederfreigabe (unveränderlich): [decision] `PENDING_SECOND_APPROVAL` (erste Person), `RELEASED` (zweite Person bestätigt), `REJECTED`. */
data class ReleaseDecisionView(
    val id: UUID, val vehicleId: UUID, val repairOrderId: UUID, val defectId: UUID?, val decision: String, val decidedBy: String, val decidedByRole: String?, val secondApprover: String?,
    val secondApproverRole: String?, val decidedAt: Instant, val safetyRevision: Long, val proofEvidenceId: UUID?, val liftedBlockIds: List<UUID>, val revision: Long, val replayed: Boolean = false,
)

/** Wiederfreigabe (`VehicleReleaseCreate`): Reparaturauftrag mit Nachweis, bestandene Folgeprüfung, Begründung; [secondApprover] benennt optional die zweite Person (andere Identität). */
data class ReleaseVehicle(val repairOrderId: UUID, val followUpInspectionId: UUID, val reason: String, val secondApprover: UUID?) {
    companion object {
        private val FIELDS = setOf("repair_order_id", "follow_up_inspection_id", "reason", "second_approver_ref")
        fun parse(raw: Map<String, Any?>): ReleaseVehicle {
            val v = Violations()
            v.unknown(raw, FIELDS, "")
            val order = v.uuid(raw, "repair_order_id", true)
            val inspection = v.uuid(raw, "follow_up_inspection_id", true)
            val reason = v.text(raw, "reason", DefectCatalog.MAX_REASON, true)
            val second = if (raw["second_approver_ref"] == null) null else v.uuid(raw, "second_approver_ref", false)
            v.throwIfAny()
            return ReleaseVehicle(order!!, inspection!!, reason!!, second)
        }
    }
}

/**
 * Wiederfreigabe eines nach Sicherheitsschaden gesperrten Fahrzeugs (WP-M3-51; AT-35, AT-51, AT-60). Die **einzige** Stelle, die eine `SAFETY`-Sperre aufhebt: nur mit Reparaturnachweis (Auftrag `PROOF_RECORDED`),
 * bestandener Folgeprüfung nach dem Nachweis und zwei verschiedenen berechtigten Identitäten (Vieraugenpflicht: erste Entscheidung `workshop` -> `PENDING_SECOND_APPROVAL`, zweite durch eine andere Person mit
 * `workshop` oder `tenant_admin` -> `RELEASED`). `expectedSafetyRevision` (`If-Match`) = `safety_revision` des Fahrzeugs: eine zwischenzeitliche neue Sperre (z. B. eine neue kritische Meldung) macht die Freigabe 412.
 * Die Entscheidung hebt nur die Sperre dieser Akte auf (weitere Sperrgründe bleiben), erhöht `safety_revision` und erteilt **keine** Einsatzfreigabe (`resource_release` entsteht erst mit einer neuen Prüfung
 * nach der Entscheidung, gültiger Schicht und Qualifikation). Jede abgewiesene Freigabe (403, 409, 412, 422) wird als Fehlversuch protokolliert (Audit und Ereignis), ohne Fallinhalt.
 */
interface VehicleReleases {
    fun release(ctx: RequestContext, vehicleId: UUID, expectedSafetyRevision: Long, cmd: ReleaseVehicle): ReleaseDecisionView
}
