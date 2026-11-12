package de.taktovia.journeyfederation.internal

import de.taktovia.journeyfederation.api.JourneyShell
import de.taktovia.journeyfederation.api.JourneyShells
import de.taktovia.journeyfederation.api.SingleLegJourney
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.util.UUID

/** Schreibt `journey` und `journey_leg` (Besitz dieses Moduls) in der Transaktion des Aufrufers. */
internal class JdbcJourneyShells : JourneyShells {
    override fun createSingleLeg(j: JdbcOperations, spec: SingleLegJourney): JourneyShell {
        val journeyId = UUID.randomUUID(); val legId = UUID.randomUUID()
        j.update(
            "INSERT INTO taktovia.journey(tenant_id, id, passenger_id, quote_id, state, revision, created_at, currency) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 1, clock_timestamp(), ?)",
            journeyId, spec.passengerId, spec.quoteId, spec.state, spec.currency,
        )
        j.update(
            "INSERT INTO taktovia.journey_leg(tenant_id, id, journey_id, sequence, operator_ref, service_ref, federation_ref, capability, state, planned_departure, planned_arrival, " +
                "origin_snapshot, destination_snapshot, promise_revision, pickup_earliest, pickup_latest, arrival_earliest, arrival_latest, gross_minor, currency) " +
                "VALUES (taktovia.current_tenant(), ?, ?, 1, ?, ?, ?, 'PLATFORM_BOOKABLE', ?, ?, ?, jsonb_build_object('stop_revision_id', ?::text), jsonb_build_object('stop_revision_id', ?::text), ?, ?, ?, ?, ?, ?, ?)",
            legId, journeyId, spec.operatorRef, spec.serviceRef, spec.legRef, spec.state, Timestamp.from(spec.pickupEarliest), Timestamp.from(spec.arrivalLatest),
            spec.originRef, spec.destinationRef, spec.promiseRevision, Timestamp.from(spec.pickupEarliest), Timestamp.from(spec.pickupLatest),
            Timestamp.from(spec.arrivalEarliest), Timestamp.from(spec.arrivalLatest), spec.grossMinor, spec.currency,
        )
        return JourneyShell(journeyId, legId)
    }

    override fun mirrorState(j: JdbcOperations, journeyId: UUID, state: String) {
        j.update("UPDATE taktovia.journey SET state = ?, revision = revision + 1 WHERE id = ?", state, journeyId)
        j.update("UPDATE taktovia.journey_leg SET state = ? WHERE journey_id = ?", state, journeyId)
    }
}
