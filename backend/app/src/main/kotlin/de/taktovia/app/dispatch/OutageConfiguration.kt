package de.taktovia.app.dispatch

import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.IncidentGateway
import de.taktovia.bookingdispatch.api.IncidentRef
import de.taktovia.bookingdispatch.api.OutageReplacement
import de.taktovia.bookingdispatch.api.OutageSearchOutcome
import de.taktovia.config.api.ConfigApi
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReplacementSearchPort
import de.taktovia.fleetsafety.api.ReplacementSearchResult
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Verdrahtung des Störungsablaufs (WP-M3-54, LH-6.22-DMG-010): `booking-dispatch` führt betroffene Buchungen, Vorschläge und Entscheidungen; der Incident gehört `fleet-safety`. Die Anwendung verbindet beide
 * über Ports (kein Modul kennt das andere): [IncidentGateway] (Sichtbarkeit, Entscheidungsprotokoll, Abschluss) und [ReplacementSearchPort] (Ersatzsuche des Workflows). Das Gateway löst `fleet-safety` erst beim
 * Aufruf auf, weil der Workflow selbst den Suchport braucht (kein Beanzyklus).
 */
@Configuration
class OutageConfiguration {
    @Bean fun incidentGateway(fleet: ObjectProvider<FleetsafetyApi>): IncidentGateway = object : IncidentGateway {
        private fun ref(i: de.taktovia.fleetsafety.api.OutageIncident) = IncidentRef(i.id, i.vehicleId, i.serviceId, i.status.name, i.revision, i.kind)
        override fun access(j: org.springframework.jdbc.core.JdbcOperations, ctx: de.taktovia.platform.context.RequestContext, incidentId: UUID, write: Boolean) =
            ref(fleet.getObject().incidentWorkflow.outageIncident(j, ctx, incidentId, write))
        override fun accessSystem(j: org.springframework.jdbc.core.JdbcOperations, incidentId: UUID) = fleet.getObject().incidentWorkflow.outageIncidentSystem(j, incidentId)?.let(::ref)
        override fun recordDecision(j: org.springframework.jdbc.core.JdbcOperations, ctx: de.taktovia.platform.context.RequestContext, incidentId: UUID, decision: String, reasonCode: String) =
            fleet.getObject().incidentWorkflow.recordDecision(j, ctx, incidentId, decision, reasonCode)
        override fun conclude(j: org.springframework.jdbc.core.JdbcOperations, incidentId: UUID, resolved: Boolean, actor: String) = fleet.getObject().incidentWorkflow.concludeOutage(j, incidentId, resolved)
    }

    @Bean fun outageReplacement(ctxTx: ContextTransactions, outbox: Outbox, audit: AuditPort, config: ConfigApi, bookings: Bookings, gateway: IncidentGateway, staffing: de.taktovia.platform.readports.ShiftStaffingReadPort): OutageReplacement =
        OutageReplacement.create(ctxTx, outbox, audit, config.effective, bookings, gateway, staffing = staffing)

    /** Die Ersatzfahrzeugsuche des Workflows (Betriebsauftrag `REPLACEMENT_SEARCH`): betroffene Buchungen registrieren und Vorschläge je Buchung berechnen. */
    @Bean fun replacementSearchPort(outage: ObjectProvider<OutageReplacement>): ReplacementSearchPort = ReplacementSearchPort { tenant, incidentId, vehicleId ->
        when (val r = outage.getObject().search(tenant, incidentId, vehicleId)) {
            is OutageSearchOutcome.Proposed -> ReplacementSearchResult.Proposed(r.count)
            is OutageSearchOutcome.NoneAvailable -> ReplacementSearchResult.NoneAvailable
            OutageSearchOutcome.NothingAffected -> ReplacementSearchResult.Proposed(0)   // kein Fahrgast betroffen: der Incident ist bereits abgeschlossen
        }
    }
}
