package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
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
        if (breakdown != null && breakdown != "rejection_reason") bad("breakdown", ViolationCode.NOT_IN_ENUM)
        if (reasonCode != null && breakdown == null) bad("reason_code", ViolationCode.INVALID_VALUE)
        if (reasonCode != null && !CODE.matches(reasonCode)) bad("reason_code", ViolationCode.PATTERN_MISMATCH)
        if (from == null && (breakdown != null || serviceId != null || channel != null)) bad("from", ViolationCode.REQUIRED)
        if (v.isNotEmpty()) throw ProblemException(ErrorCode.BAD_REQUEST, "Mindestens ein Parameter ist ungültig.", v)

        val filter = MetricFilter(service, channel)
        val period = fromDay?.let { ReportingPeriod.ofDays(reportingZone(ctx), it, toDay ?: it) }
        val definitions: List<MetricDefinition>; val values: List<MetricValue>?
        if (period != null) {
            values = analytics.metrics.evaluate(ctx.tenant, MetricQuery(period, metricId, versionNo, filter))
            definitions = values.map { it.definition }
        } else {
            values = null
            definitions = if (metricId != null) listOf(analytics.metrics.definition(ctx.tenant, metricId, versionNo)) else analytics.metrics.definitions(ctx.tenant)
        }
        val rejections = if (period != null && breakdown != null) analytics.metrics.rejections(ctx.tenant, period, filter, reasonCode) else null
        return linkedMapOf("definitions" to definitions.map(::definitionJson), "values" to values?.map(::valueJson), "rejections" to rejections?.let(::rejectionsJson))
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
        "source_events" to m.sourceEvents, "snapshot_id" to m.snapshotId?.toString(),
    )

    private fun rowJson(r: RejectionRow): Map<String, Any?> = linkedMapOf("code" to r.code, "group" to r.group, "metric" to r.metric, "text" to r.text, "texts" to r.texts, "primary_count" to r.primaryCount, "secondary_count" to r.secondaryCount)

    private fun rejectionsJson(r: RejectionReport): Map<String, Any?> = linkedMapOf(
        "attempts" to r.attempts, "rejections" to r.rejections, "technical_errors" to r.technicalErrors, "state" to r.state.name, "coverage" to r.coverage, "as_of" to r.asOf?.toString(),
        "source_events" to r.sourceEvents, "reason_code" to r.reasonCode, "rows" to r.rows.map(::rowJson),
    )

    private companion object {
        val METRIC_ID = Regex("^[a-z][a-z0-9_]{1,63}$")
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        const val MAX_DAYS = 366L
    }
}
