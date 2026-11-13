package de.taktovia.app.reports

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.KmCategory
import de.taktovia.analytics.api.KmSummary
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Kennzahlen-Datenbasis `GET /v1/analytics/vehicle-km` (WP-M4-42, Kap. 05 §5.7, Modul `analytics`): Fahrzeugkilometer je Kategorie, nicht zuordenbare Kilometer separat. Recht `costs:read` aus der
 * Rechtematrix im Filter und im Dienst. `metric`, `from` und `to` sind Pflicht (422 statt 400); der Mandant steht nie in der Anfrage.
 */
@RestController
class AnalyticsMetricsController(private val analytics: AnalyticsApi) {

    @GetMapping("/v1/analytics/vehicle-km")
    fun metrics(@RequestParam(required = false) metric: String?, @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?,
        @RequestParam(name = "vehicle_id", required = false) vehicleId: String?, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val v = mutableListOf<Violation>()
        if (metric == null) v += Violation("/\$query/metric", ViolationCode.REQUIRED) else if (metric != "VEHICLE_KM_CLASSES") v += Violation("/\$query/metric", ViolationCode.NOT_IN_ENUM)
        fun day(name: String, s: String?): LocalDate? = if (s == null) { v += Violation("/\$query/$name", ViolationCode.REQUIRED); null } else try { LocalDate.parse(s) } catch (e: DateTimeParseException) { v += Violation("/\$query/$name", ViolationCode.INVALID_FORMAT); null }
        val f = day("from", from); val t = day("to", to)
        val vehicle = vehicleId?.let { s -> try { UUID.fromString(s) } catch (e: IllegalArgumentException) { v += Violation("/\$query/vehicle_id", ViolationCode.INVALID_FORMAT); null } }
        if (v.isNotEmpty() || f == null || t == null) throw ProblemException.invalid(*v.toTypedArray())
        return json(analytics.km.summary(ctx, vehicle, f, t))
    }

    private fun cats(m: Map<KmCategory, Long>): Map<String, Any?> = linkedMapOf("PASSENGER" to m.getValue(KmCategory.PASSENGER), "EMPTY" to m.getValue(KmCategory.EMPTY),
        "TRANSFER" to m.getValue(KmCategory.TRANSFER), "WORKSHOP" to m.getValue(KmCategory.WORKSHOP))

    private fun json(s: KmSummary): Map<String, Any?> = linkedMapOf(
        "metric" to "VEHICLE_KM_CLASSES", "definition_version" to s.definitionVersion, "from" to s.from.toString(), "to" to s.to.toString(), "vehicle_id" to s.vehicleId?.toString(),
        "as_of" to s.asOf?.toString(), "completeness" to s.completeness.name, "total_km_thousandths" to s.totalKmThousandths, "km_thousandths_by_category" to cats(s.byCategory),
        "unassigned_km_thousandths" to s.byCategory.getValue(KmCategory.UNASSIGNED),
        "days" to s.days.map { d -> linkedMapOf("service_day" to d.serviceDay.toString(), "vehicle_id" to d.vehicleId.toString(), "run_id" to d.runId.toString(), "as_of" to d.dataAsOf.toString(),
            "total_km_thousandths" to d.totalKmThousandths, "km_thousandths_by_category" to cats(d.byCategory), "unassigned_km_thousandths" to d.byCategory.getValue(KmCategory.UNASSIGNED), "events_complete" to d.eventsComplete) },
    )
}
