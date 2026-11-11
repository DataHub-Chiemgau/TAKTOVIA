package de.taktovia.servicegeo.internal

import de.taktovia.servicegeo.api.ServiceRevisionView
import de.taktovia.servicegeo.api.OperatingSchedule
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.util.UUID

internal object ServiceRevisionRows {
    private val json = JsonMapper.builder().build()
    const val COLUMNS = "r.id, r.service_id, r.revision, r.traffic_product, r.timezone, r.weekly_hours::text AS weekly_hours, r.exceptions::text AS exceptions, " +
        "r.valid_from, r.valid_to, r.reason, r.created_by, r.created_at"

    @Suppress("UNCHECKED_CAST")
    private fun list(text: String): List<Map<String, Any?>> = json.readValue(text, List::class.java) as List<Map<String, Any?>>

    val VIEW = RowMapper { rs, _ ->
        ServiceRevisionView(rs.getObject("id", UUID::class.java), rs.getObject("service_id", UUID::class.java), rs.getLong("revision"), rs.getString("traffic_product"),
            rs.getString("timezone"), list(rs.getString("weekly_hours")), list(rs.getString("exceptions")), rs.getTimestamp("valid_from").toInstant(),
            rs.getTimestamp("valid_to")?.toInstant(), rs.getString("reason"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant())
    }

    fun schedule(v: ServiceRevisionView): OperatingSchedule =
        OperatingSchedule.parse(ZoneId.of(v.timezone), v.weeklyHours, v.exceptions).first ?: error("gespeicherte Betriebszeit ist ungültig")

    fun write(value: Any?): String = json.writeValueAsString(value)
}
