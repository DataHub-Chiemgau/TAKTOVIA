package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingNotificationFacts
import de.taktovia.bookingdispatch.api.BookingNotificationFactsPort
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** Lesesicht auf `booking`, Dienst und Treffpunkt (Haltepunktname des Angebots) für Benachrichtigungen (WP-M2-34); siehe [BookingNotificationFactsPort]. */
internal class JdbcBookingNotificationFacts : BookingNotificationFactsPort {
    private val json = JsonMapper.builder().build()

    override fun facts(jdbc: JdbcOperations, bookingId: UUID, lock: Boolean): BookingNotificationFacts? {
        val sql = "SELECT b.booking_no, b.passenger_id, b.service_id, b.state, b.promise_snapshot::text, s.timezone, " +
            "(SELECT sr.name FROM taktovia.ride_offer ro JOIN taktovia.stop_revision sr ON sr.tenant_id = ro.tenant_id AND sr.id = ro.pickup_stop_revision_id WHERE ro.hold_id = b.hold_id LIMIT 1) AS meeting_point " +
            "FROM taktovia.booking b JOIN taktovia.service s ON s.tenant_id = b.tenant_id AND s.id = b.service_id WHERE b.id = ? AND b.booking_no IS NOT NULL AND b.passenger_id IS NOT NULL"
        val rows = jdbc.query(if (lock) "$sql FOR SHARE OF b" else sql, { rs, _ ->   // sql-lint: ok Zusatz aus Konstante, Werte gebunden
            val snapshot = json.readTree(rs.getString(5))
            BookingNotificationFacts(bookingId, rs.getString(1), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getString(4), rs.getString("meeting_point"),
                OffsetDateTime.parse(snapshot.get("pickup_earliest").asString()).toInstant(), OffsetDateTime.parse(snapshot.get("pickup_latest").asString()).toInstant(), rs.getString(6))
        }, bookingId)
        return rows.firstOrNull()
    }
}
