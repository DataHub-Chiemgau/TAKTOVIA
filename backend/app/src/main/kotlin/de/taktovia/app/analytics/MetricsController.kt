package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.EnergyKind
import de.taktovia.analytics.api.MetricExclusion
import de.taktovia.analytics.api.FunnelReport
import de.taktovia.analytics.api.MetricDefinition
import de.taktovia.analytics.api.MetricDimensions
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.RejectionReport
import de.taktovia.analytics.api.RejectionRow
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.app.identity.RequestContexts
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Kennzahlregister und Kennzahlwerte (`GET /v1/analytics/metrics`, WP-M4-46 bis -50, Modul `analytics`). Das Funktionsrecht `analytics:read` prüft der Filter aus der Rechtematrix; der Mandant kommt nie aus der
 * Anfrage. Der Berichtszeitraum besteht aus Betriebstagen in der `reporting_timezone` des Mandanten (Konfigurationsparameter, Ebene Mandant; ohne wirksamen Wert Europe/Berlin wie im Pilotbetrieb, Arbeitsannahme
 * bis zur Anbindung der Mandantenkonfiguration), nie aus festen 24 Stunden. Ungültige Parameter sind 400, eine unbekannte Kennzahl oder Version 404.
 */
@RestController
class MetricsController(private val analytics: AnalyticsApi, private val config: ConfigApi) {

    @GetMapping("/v1/analytics/metrics")
    fun get(
        @RequestParam(name = "metric_id", required = false) metricId: String?, @RequestParam(name = "version", required = false) version: String?,
        @RequestParam(name = "from", required = false) from: String?, @RequestParam(name = "to", required = false) to: String?,
        @RequestParam(name = "service_id", required = false) serviceId: String?, @RequestParam(name = "channel", required = false) channel: String?,
        @RequestParam(name = "vehicle_id", required = false) vehicleId: String?, @RequestParam(name = "energy", required = false) energyParam: String?,
        @RequestParam(name = "currency", required = false) currency: String?,
        @RequestParam(name = "breakdown", required = false) breakdown: String?, @RequestParam(name = "reason_code", required = false) reasonCode: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        val v = mutableListOf<Violation>()
        fun bad(name: String, code: ViolationCode) { v += Violation("/\$query/$name", code) }
        if (metricId != null && !METRIC_ID.matches(metricId)) bad("metric_id", ViolationCode.PATTERN_MISMATCH)
        val versionNo = version?.let { s -> s.toIntOrNull()?.takeIf { it >= 1 } ?: run { bad("version", ViolationCode.INVALID_VALUE); null } }
        if (version != null && metricId == null) bad("version", ViolationCode.INVALID_VALUE)
        val fromDay = from?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() ?: run { bad("from", ViolationCode.INVALID_FORMAT); null } }
        val toDay = to?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() ?: run { bad("to", ViolationCode.INVALID_FORMAT); null } }
        if (to != null && from == null) bad("from", ViolationCode.REQUIRED)
        if (fromDay != null && toDay != null && toDay.isBefore(fromDay)) bad("to", ViolationCode.INTERVAL_INVALID)
        if (fromDay != null && ChronoUnit.DAYS.between(fromDay, toDay ?: fromDay) > MAX_DAYS) bad("to", ViolationCode.ABOVE_MAXIMUM)
        val service = serviceId?.let { s -> runCatching { UUID.fromString(s) }.getOrNull() ?: run { bad("service_id", ViolationCode.INVALID_FORMAT); null } }
        if (channel != null && channel !in MetricDimensions.CHANNELS) bad("channel", ViolationCode.NOT_IN_ENUM)
        val vehicle = vehicleId?.let { s -> runCatching { UUID.fromString(s) }.getOrNull() ?: run { bad("vehicle_id", ViolationCode.INVALID_FORMAT); null } }
        val energy = energyParam?.let { s -> EnergyKind.entries.firstOrNull { it.name == s } ?: run { bad("energy", ViolationCode.NOT_IN_ENUM); null } }
        if (currency != null && !CURRENCY.matches(currency)) bad("currency", ViolationCode.PATTERN_MISMATCH)

        if (breakdown != null && breakdown != "rejection_reason" && breakdown != "funnel" && breakdown != "not_performed") bad("breakdown", ViolationCode.NOT_IN_ENUM)
        if (reasonCode != null && breakdown != "rejection_reason") bad("reason_code", ViolationCode.INVALID_VALUE)
        if (reasonCode != null && !CODE.matches(reasonCode)) bad("reason_code", ViolationCode.PATTERN_MISMATCH)
        if (from == null && (breakdown != null || serviceId != null || channel != null || vehicleId != null || energyParam != null || currency != null)) bad("from", ViolationCode.REQUIRED)
        if (v.isNotEmpty()) throw ProblemException(ErrorCode.BAD_REQUEST, "Mindestens ein Parameter ist ungültig.", v)

        val filter = MetricFilter(service, channel, vehicle, energy, currency)
        val period = fromDay?.let { ReportingPeriod.ofDays(reportingZone(ctx), it, toDay ?: it) }
        val definitions: List<MetricDefinition>; val values: List<MetricValue>?
        if (period != null) {
            values = analytics.metrics.evaluate(ctx.tenant, MetricQuery(period, metricId, versionNo, filter), ConfigPickupToleranceSource(config, ctx))
            definitions = values.map { it.definition }
        } else {
            values = null
            definitions = if (metricId != null) listOf(analytics.metrics.definition(ctx.tenant, metricId, versionNo)) else analytics.metrics.definitions(ctx.tenant)
        }
        val rejections = if (period != null && breakdown == "rejection_reason") analytics.metrics.rejections(ctx.tenant, period, filter, reasonCode) else null
        val funnel = if (period != null && breakdown == "funnel") analytics.metrics.funnel(ctx.tenant, period, filter) else null
        val notPerformed = if (period != null && breakdown == "not_performed") notPerformedJson(analytics.notPerformed, ctx.tenant, period, filter) else null
        return linkedMapOf("definitions" to definitions.map(::definitionJson), "values" to values?.map(::valueJson), "rejections" to rejections?.let(::rejectionsJson), "funnel" to funnel?.let(::funnelJson),
            "not_performed" to notPerformed)
    }

    /** `reporting_timezone` des Mandanten (IANA-Zone); ohne wirksamen oder lesbaren Wert Europe/Berlin. */
    private fun reportingZone(ctx: RequestContext): ZoneId {
        val item = config.effective.resolve(ctx, EffectiveRequest(parameter = "reporting_timezone")).items.firstOrNull()
        val id = item?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.value as? String
        return try { if (id != null) ZoneId.of(id) else DEFAULT_ZONE } catch (e: DateTimeException) { DEFAULT_ZONE }
    }

    private fun definitionJson(d: MetricDefinition): Map<String, Any?> = linkedMapOf(
        "metric_id" to d.metricId, "version" to d.version, "name" to d.name, "value_kind" to d.valueKind.name, "unit" to d.unit, "numerator" to d.numerator, "denominator" to d.denominator,
        "exclusions" to d.exclusions, "time_basis" to d.timeBasis, "period_basis" to d.periodBasis, "filter_dimensions" to d.filterDimensions, "executable" to d.executable,
        "not_executable_reason" to d.notExecutableReason?.name,
    )

    private fun periodJson(p: ReportingPeriod): Map<String, Any?> = linkedMapOf("from" to p.fromDay.toString(), "to" to p.toDayInclusive.toString(), "timezone" to p.zone.id, "from_instant" to p.from.toString(), "to_instant" to p.to.toString())

    private fun valueJson(m: MetricValue): Map<String, Any?> = linkedMapOf(
        "metric_id" to m.definition.metricId, "definition_version" to m.definition.version, "state" to m.state.name, "reason" to m.reason?.name, "value" to m.value, "numerator" to m.numerator,
        "denominator" to m.denominator, "coverage" to m.coverage, "unit" to m.definition.unit, "filter" to m.filter.toMap(), "period" to periodJson(m.period), "as_of" to m.asOf?.toString(),
        "source_events" to m.sourceEvents, "snapshot_id" to m.snapshotId?.toString(), "exclusions" to m.exclusions.map(::exclusionJson), "approximation" to m.approximation?.name,
    )

    private fun exclusionJson(e: MetricExclusion): Map<String, Any?> = linkedMapOf("document_id" to e.documentId.toString(), "vehicle_id" to e.vehicleId?.toString(), "reason" to e.reason.name)

    private fun rowJson(r: RejectionRow): Map<String, Any?> = linkedMapOf("code" to r.code, "group" to r.group, "metric" to r.metric, "text" to r.text, "texts" to r.texts, "primary_count" to r.primaryCount, "secondary_count" to r.secondaryCount)

    private fun rejectionsJson(r: RejectionReport): Map<String, Any?> = linkedMapOf(
        "attempts" to r.attempts, "rejections" to r.rejections, "technical_errors" to r.technicalErrors, "state" to r.state.name, "coverage" to r.coverage, "as_of" to r.asOf?.toString(),
        "source_events" to r.sourceEvents, "reason_code" to r.reasonCode, "rows" to r.rows.map(::rowJson),
    )

    /** Vier getrennte Zählgrößen (`counts`, nie als Summe) und die getrennten Endzustände (`end_states`) des Nachfragetrichters. */
    private fun funnelJson(f: FunnelReport): Map<String, Any?> = linkedMapOf(
        "period" to periodJson(f.period), "filter" to f.filter.toMap(),
        "counts" to linkedMapOf("requests" to f.requests, "offers" to f.offers, "confirmed_bookings" to f.confirmedBookings, "completed_bookings" to f.completedBookings),
        "end_states" to linkedMapOf("request_rejected" to f.requestRejected, "request_technical_error" to f.requestTechnicalError, "offer_not_booked" to f.offerNotBooked, "payment_failed" to f.paymentFailed,
            "cancelled_by_passenger" to f.cancelledByPassenger, "cancelled_by_operator" to f.cancelledByOperator, "cancelled_other" to f.cancelledOther),
        "unlinked_bookings" to f.unlinkedBookings, "state" to f.state.name, "coverage" to f.coverage, "as_of" to f.asOf?.toString(), "source_events" to f.sourceEvents,
    )

    /**
     * Auswertung nicht durchgeführter Fahrten (WP-M4-55, KPI-009) nach Ursache und Kategorie: „Ursache offen“ und Konflikte stehen sichtbar daneben (nie ausgeschlossen), die Quoten tragen Zähler und Nenner, die Fahrten
     * ihre Korrekturvermerke. Keine Fahrerzuordnung, keine Personenbezüge.
     */
    private fun notPerformedJson(api: de.taktovia.analytics.api.NotPerformedTrips, tenant: de.taktovia.platform.tenant.TenantId, period: ReportingPeriod, filter: MetricFilter): Map<String, Any?> {
        val r = api.report(tenant, period, filter)
        fun rate(x: de.taktovia.analytics.api.Rate) = linkedMapOf("numerator" to x.numerator, "denominator" to x.denominator, "value" to x.value, "reason" to x.reason?.name)
        return linkedMapOf(
            "definition_version" to r.definitionVersion, "period" to periodJson(period), "filter" to filter.toMap(), "total" to r.total,
            "by_cause" to r.byCause.map { linkedMapOf("cause" to it.cause, "category" to it.category.name, "label" to it.label, "count" to it.count) },
            "by_category" to r.byCategory.mapKeys { it.key.name }, "open_count" to r.open, "conflict_count" to r.conflict, "confirmed_pickups" to r.confirmedPickups, "confirmed_bookings" to r.confirmedBookings,
            "rates" to linkedMapOf("no_show_rate" to rate(r.noShowRate), "operator_cancellation_rate" to rate(r.operatorCancellationRate), "vehicle_outage_rate" to rate(r.vehicleOutageRate)),
            "state" to r.state.name, "coverage" to r.coverage, "as_of" to r.asOf?.toString(), "source_events" to r.sourceEvents,
            "items" to api.trips(tenant, period, filter).map { NotPerformedJson.trip(it) },
        )
    }

    private companion object {
        val METRIC_ID = Regex("^[a-z][a-z0-9_]{1,63}$")
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        const val MAX_DAYS = 366L
    }
}
