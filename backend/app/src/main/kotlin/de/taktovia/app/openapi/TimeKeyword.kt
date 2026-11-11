package de.taktovia.app.openapi

import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.IanaZones
import de.taktovia.platform.time.LocalDateTimes
import de.taktovia.platform.time.Rfc3339
import de.taktovia.platform.time.ValidityInterval
import de.taktovia.platform.time.ZonedMoments
import tools.jackson.databind.JsonNode
import java.time.DateTimeException
import java.time.ZoneOffset

/**
 * Schlüsselwort `x-time` (WP-M1-18, LH-6.1-SYS-006): bindet die Prüfer aus `platform.time` an die Zeittypen aus
 * `api/common.yaml`. Argument: `instant` (Timestamp), `zone` (TimeZone), `zoned-moment` (`desired_at` + `time_zone`),
 * `local-moment` (`local` + `time_zone` + `utc_offset`), `interval` (`valid_from` + `valid_to`). Reine Typfehler überlässt
 * das Schema (`type`); hier entstehen die fachlichen Codes (OFFSET_REQUIRED, LOCAL_TIME_NONEXISTENT, …).
 */
object TimeKeyword {
    fun check(node: JsonNode, argument: String, pointer: String): List<Violation> = when (argument) {
        "instant" -> if (node.isString) Rfc3339.parse(node.asString(), pointer.ifEmpty { "/" }).violations else emptyList()
        "zone" -> if (node.isString) IanaZones.check(node.asString(), pointer.ifEmpty { "/" }).violations else emptyList()
        "zoned-moment" -> {
            val d = node.path("desired_at"); val z = node.path("time_zone")
            if (d.isString && z.isString) ZonedMoments.parse(d.asString(), z.asString(), "$pointer/desired_at", "$pointer/time_zone").violations else emptyList()
        }
        "local-moment" -> localMoment(node, pointer)
        "interval" -> {
            val f = node.path("valid_from"); val t = node.path("valid_to")
            if (f.isString && (t.isString || t.isNull || t.isMissingNode))
                ValidityInterval.parse(f.asString(), if (t.isString) t.asString() else null, "$pointer/valid_from", "$pointer/valid_to").violations
            else emptyList()
        }
        else -> error("unbekanntes x-time-Argument: $argument")
    }

    private fun localMoment(node: JsonNode, pointer: String): List<Violation> {
        val l = node.path("local"); val z = node.path("time_zone"); val o = node.path("utc_offset")
        if (!l.isString || !z.isString) return emptyList()
        val zone = IanaZones.check(z.asString(), "$pointer/time_zone")
        val local = LocalDateTimes.parse(l.asString(), "$pointer/local")
        if (!zone.ok || !local.ok) return zone.violations + local.violations
        if (!o.isString) return ZonedMoments.fromLocal(local.value!!, zone.value!!, "$pointer/local").violations
        val offset = try { ZoneOffset.of(o.asString()) } catch (_: DateTimeException) { return listOf(Violation("$pointer/utc_offset", ViolationCode.INVALID_FORMAT)) }
        return ZonedMoments.fromLocal(local.value!!, zone.value!!, offset, "$pointer/utc_offset").violations
    }
}
