package de.taktovia.documentsfinance.api

import de.taktovia.documentsfinance.internal.DocumentsfinanceServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Öffentliche Ports des Moduls `documents-finance`; wächst mit den Arbeitspaketen (bisher: Kostenstellen als Stammobjekt, WP-M1-09). */
interface DocumentsfinanceApi {
    val costCenters: CostCenterAdmin

    companion object {
        fun create(ctxTx: ContextTransactions, audit: AuditPort): DocumentsfinanceApi = DocumentsfinanceServicesFactory.create(ctxTx, audit)
    }
}

/** Kostenstelle (`cost_object`, `kind = 'COST_CENTER'`) mit optionaler Zuordnung zu einem Gebiet und/oder Dienst. Finanzlogik (Zuordnung von Kosten) ist M4. */
data class CostCenterView(
    val id: UUID, val code: String, val name: String, val legalEntityId: UUID, val regionId: UUID?, val serviceId: UUID?,
    val revision: Long, val createdAt: Instant,
)

data class CreateCostCenter(val code: String, val name: String, val legalEntityId: UUID, val regionId: UUID? = null, val serviceId: UUID? = null)
/** Kennung ([CreateCostCenter.code]) und Rechtsträger sind nach dem Anlegen unveränderlich. */
data class UpdateCostCenter(val name: String, val regionId: UUID? = null, val serviceId: UUID? = null)

/**
 * Kostenstellen des Mandanten im Kontext (WP-M1-09, LH-I.4-MUL-002): mehrfach anlegbar (Kennung je Mandant eindeutig, 409 bei Doppelung), einem Gebiet/Dienst
 * zuordenbar. Fachzeile und genau ein Auditeintrag (`finance.cost_center.changed`) in einer Transaktion; fremde Mandanten und Verweise auf sie: 404;
 * veraltete Revision 412; Änderung ohne Wirkung erhöht die Revision nicht.
 */
interface CostCenterAdmin {
    fun create(ctx: RequestContext, cmd: CreateCostCenter): CostCenterView
    fun update(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateCostCenter): CostCenterView
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<CostCenterView>
}
