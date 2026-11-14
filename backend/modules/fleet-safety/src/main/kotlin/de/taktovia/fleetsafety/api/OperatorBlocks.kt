package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import java.util.UUID

/**
 * Sperrgrundkatalog der Leitstelle (LH-6.7-OPS-005, Planungsannahme OP-01): nur Gründe der Quelle `OPERATIONS`. [commentRequired] = der Grund verlangt eine Begründung im Freitext
 * (`MANUAL`, `OTHER`); `OPERATIONAL_DISRUPTION` ist selbst eine hinreichende Angabe. Sicherheitskritische Gründe gibt es hier nie (die setzt nur der Server).
 */
object OperationalBlockCatalog {
    val REASONS: Map<BlockReason, Boolean> = linkedMapOf(BlockReason.OPERATIONAL_DISRUPTION to false, BlockReason.MANUAL to true, BlockReason.OTHER to true)
    const val MAX_COMMENT = 500
}

/** Eingabe „Fahrzeug sperren“ (`VehicleBlockCreate`): Grund aus dem Leitstellenkatalog, Begründung je nach Grund Pflicht. Die Quelle `OPERATIONS` steht fest, `tenant_id` und Akteur kommen nie aus dem Body. */
data class OperationalBlockInput(val reason: BlockReason, val comment: String?) {
    companion object {
        fun parse(raw: Map<String, Any?>): OperationalBlockInput {
            val v = Violations()
            v.unknown(raw, setOf("reason_code", "comment"), "")
            val rawReason = raw["reason_code"]
            val reason: BlockReason? = when (rawReason) {
                null -> { v.add("/reason_code", ViolationCode.REQUIRED); null }
                is String -> BlockReason.entries.firstOrNull { it.name == rawReason }?.takeIf { it in OperationalBlockCatalog.REASONS } ?: run { v.add("/reason_code", ViolationCode.NOT_IN_ENUM); null }
                else -> { v.add("/reason_code", ViolationCode.TYPE_MISMATCH); null }
            }
            val comment = comment(raw, v, reason?.let { OperationalBlockCatalog.REASONS[it] } ?: false)
            v.throwIfAny()
            return OperationalBlockInput(reason!!, comment)
        }
    }
}

/** Eingabe „Sperre aufheben“ (`VehicleBlockLift`): optionale Begründung. */
data class OperationalLiftInput(val comment: String?) {
    companion object {
        fun parse(raw: Map<String, Any?>): OperationalLiftInput {
            val v = Violations()
            v.unknown(raw, setOf("comment"), "")
            val comment = comment(raw, v, false)
            v.throwIfAny()
            return OperationalLiftInput(comment)
        }
    }
}

private fun comment(raw: Map<String, Any?>, v: Violations, required: Boolean): String? {
    val c = raw["comment"]
    return when {
        c == null -> { if (required) v.add("/comment", ViolationCode.REQUIRED); null }
        c !is String -> { v.add("/comment", ViolationCode.TYPE_MISMATCH); null }
        c.isBlank() -> { v.add("/comment", ViolationCode.TOO_SHORT); null }
        c.length > OperationalBlockCatalog.MAX_COMMENT -> { v.add("/comment", ViolationCode.TOO_LONG); null }
        else -> c
    }
}

/**
 * Ergebnis von Sperren und Aufheben durch die Leitstelle. [created] = `false` bei Wiederholung desselben Sperrgrundes (die bestehende Sperre, kein Revisionssprung). [vehicleRevision] ist die Revision der
 * Fahrzeugsicht der Leitstelle (`OperatorVehicle.revision`) nach dem Aufruf; [stillBlocked] = es bestehen weitere aktive Sperrgründe (nach dem Aufheben).
 */
data class OperationalBlockResult(val block: VehicleBlockView, val created: Boolean, val safetyRevision: Long, val vehicleRevision: Long, val stillBlocked: Boolean)

/**
 * Operative Fahrzeugsperre durch die Leitstelle (WP-M3-41, LH-6.7-OPS-005; `POST /v1/operator/vehicles/{id}/blocks` und `.../{block_id}/lift`). Rolle Disposition; das Fahrzeug muss für den Aufrufer
 * sichtbar sein (Mandant per RLS, Dienstscope des Fahrzeugpools wie in der Leitstellenliste), sonst 404. Die Sperre läuft im Sperr- und Freigabekern ([Readiness]): selbe Transaktion, `safety_revision + 1`,
 * Widerruf der gültigen Freigaben, Audit und Outbox-Ereignis. `expectedVehicleRevision` (If-Match) ist die Revision der Fahrzeugsicht; veraltet 412. Sperre und Aufheben erhöhen sie, damit zwei
 * Disponenten mit demselben Stand nicht beide schreiben. Das Aufheben betrifft nur Sperren der Quelle `OPERATIONS` (Revision der Sperre als If-Match); eine `SAFETY`-Sperre ergibt 409
 * `INVALID_STATE_TRANSITION` und einen protokollierten Fehlversuch. Bereits zugesagte Fahrten bleiben dem Fahrzeug zugeordnet; ihre Behandlung ist der Störungsablauf (WP-M3-53/-54).
 */
interface OperatorBlocks {
    fun create(ctx: RequestContext, vehicleId: UUID, expectedVehicleRevision: Long, input: OperationalBlockInput): OperationalBlockResult

    fun lift(ctx: RequestContext, vehicleId: UUID, blockId: UUID, expectedBlockRevision: Long, input: OperationalLiftInput): OperationalBlockResult
}
