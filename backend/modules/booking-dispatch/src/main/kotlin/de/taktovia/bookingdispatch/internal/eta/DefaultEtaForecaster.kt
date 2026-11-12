package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaConfigSnapshot
import de.taktovia.bookingdispatch.api.EtaFallbackPolicy
import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaForecast
import de.taktovia.bookingdispatch.api.EtaForecastRequest
import de.taktovia.bookingdispatch.api.EtaForecaster
import de.taktovia.bookingdispatch.api.EtaLegForecast
import de.taktovia.bookingdispatch.api.EtaQuantile
import de.taktovia.bookingdispatch.api.EtaReferenceRequest
import de.taktovia.bookingdispatch.api.EtaSettings
import de.taktovia.bookingdispatch.api.EtaSpeedRules
import de.taktovia.bookingdispatch.api.EtaStopForecast
import de.taktovia.bookingdispatch.api.EtaUnavailableException
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Prognose = Referenzfahrzeit + Haltezeiten + Unsicherheitspuffer (WP-M4-62). Modellrevision und Konfiguration werden **einmal** je Prognose in einer Lesetransaktion gelesen
 * ([snapshot]); die Prognose trägt diese eine Version und mischt nie zwei Stände. Ein Abschnitt ohne Geschwindigkeitsbegrenzung in den Routendaten, fehlende Attribute oder
 * inkonsistente Routendaten ergeben einen Fallbackgrund statt eines Zeitfensters (fail-closed); ein Zeitfenster wird nur ausgegeben, wenn es für jeden Abschnitt mindestens die
 * Mindestfahrzeit bei zulässiger Geschwindigkeit enthält (Prüfroutine [EtaSpeedRules]); zu schnelle Eingabewerte verlängern auf die Mindestfahrzeit, verengen nie.
 */
internal class DefaultEtaForecaster(
    private val ctxTx: ContextTransactions, private val config: EffectiveConfig, private val reference: DefaultEtaReferenceModel, private val revisions: JdbcEtaModelRevisions,
) : EtaForecaster {

    override fun snapshot(ctx: RequestContext, serviceId: UUID?, vehicleId: UUID?, at: Instant): EtaConfigSnapshot = ctxTx.run(ctx, readOnly = true) { j ->
        val model = revisions.current(j) ?: throw EtaUnavailableException(EtaFallbackReason.NO_APPROVED_MODEL)
        val floor = j.queryForObject("SELECT minimum FROM taktovia.policy_system_floor() WHERE parameter = 'stop_dwell_sec'", BigDecimal::class.java)?.toInt() ?: 1
        val view = config.resolveInTransaction(j, ctx, EffectiveRequest(parameters = NAMES, serviceId = serviceId, vehicleId = vehicleId, at = at))
        val items = view.items.associateBy { it.parameter }
        val sources = sortedMapOf<String, String>()

        fun src(name: String, fallback: String) = items[name]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.let { it.revisionId?.let { id -> "revision:$id" } ?: "catalog-default" } ?: fallback
        fun int(name: String, fallback: Int): Int = items[name]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.value?.let { (it as Number).toInt() } ?: fallback

        val dwell = maxOf(int("stop_dwell_sec", model.defaultStopDwellSec), floor)
        sources["stop_dwell_sec"] = src("stop_dwell_sec", "model") + if (dwell == floor) "+floor" else ""
        val wheelchair = int("wheelchair_boarding_sec", model.defaultWheelchairBoardingSec); sources["wheelchair_boarding_sec"] = src("wheelchair_boarding_sec", "model")
        val quantile = (items["eta_model_quantile"]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.value as? String)?.let { EtaQuantile.of(it) } ?: EtaQuantile.P85
        sources["eta_model_quantile"] = src("eta_model_quantile", "planning-default")
        val tolerance = int("eta_safe_speed_tolerance", 0); sources["eta_safe_speed_tolerance"] = src("eta_safe_speed_tolerance", "planning-default")
        val maxAge = int("eta_max_data_age_sec", DEFAULT_MAX_DATA_AGE_SEC); sources["eta_max_data_age_sec"] = src("eta_max_data_age_sec", "planning-default")
        val policy = (items["eta_fallback_policy"]?.takeIf { it.status == EffectiveStatus.EFFECTIVE }?.value as? String)?.let { runCatching { EtaFallbackPolicy.valueOf(it) }.getOrNull() } ?: EtaFallbackPolicy.PLAN_TIME
        sources["eta_fallback_policy"] = src("eta_fallback_policy", "planning-default")

        val digest = MessageDigest.getInstance("SHA-256").digest((sources.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\nmodel=${model.id}").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        EtaConfigSnapshot(model, EtaSettings(dwell, wheelchair, quantile, tolerance, maxAge, policy), "m${model.version}-c$digest", sources, at)
    }

    override fun forecast(ctx: RequestContext, request: EtaForecastRequest): EtaForecast {
        val snap = try { snapshot(ctx, request.serviceId, request.vehicleId, request.startAt) } catch (e: EtaUnavailableException) { return fallback(null, e.reason, request.roadDataVersion) }
        return forecast(ctx, request, snap)
    }

    override fun forecast(ctx: RequestContext, request: EtaForecastRequest, snapshot: EtaConfigSnapshot): EtaForecast {
        val model = snapshot.model; val s = snapshot.settings
        val pct = model.bufferPctByQuantile.getValue(s.quantile); val pct90 = model.bufferPctByQuantile.getValue(EtaQuantile.P90)
        var pinned = request.roadDataVersion
        val legs = ArrayList<EtaLegForecast>()
        for (i in 0 until request.stops.size - 1) {
            val ref = reference.compute(ctx, model, EtaReferenceRequest(request.stops[i].stopKey, request.stops[i + 1].stopKey, request.vehicleProfile, request.startAt, pinned))
            ref.fallbackReason?.let { return fallback(snapshot, it, pinned ?: ref.roadDataVersion) }
            if (pinned == null) pinned = ref.roadDataVersion   // ab dem ersten Abschnitt gilt für alle weiteren genau diese Straßendatenversion
            val segments = ref.segments ?: return fallback(snapshot, EtaFallbackReason.SPEED_LIMIT_DATA_UNAVAILABLE, pinned)
            if (segments.any { (it.speedLimitKmh ?: 0) <= 0 }) return fallback(snapshot, EtaFallbackReason.SPEED_LIMIT_MISSING, pinned)
            if (segments.sumOf { it.lengthMeters.toLong() } != ref.distanceMeters!!.toLong()) return fallback(snapshot, EtaFallbackReason.ROUTE_DATA_INCONSISTENT, pinned)
            val legalMin = EtaSpeedRules.legalMinimumSeconds(segments)!!
            val r = maxOf(ref.timeSeconds!!, legalMin)
            val buffer = maxOf(model.minBufferSec, ceilPct(r, pct)); val uncertainty = maxOf(model.minBufferSec, ceilPct(r, pct90))
            // Prüfroutine: das zugeteilte Fenster darf für keinen Abschnitt eine Überschreitung verlangen. Die Toleranz (s.safeSpeedToleranceKmh) geht nie ein.
            if (EtaSpeedRules.violations(segments, r).isNotEmpty()) return fallback(snapshot, EtaFallbackReason.ROUTE_DATA_INCONSISTENT, pinned)
            legs += EtaLegForecast(i, r, legalMin, buffer, uncertainty, ref.distanceMeters)
        }
        var arrE = request.startAt; var arrL = request.startAt
        val stops = ArrayList<EtaStopForecast>()
        var dwellTotal = 0
        request.stops.forEachIndexed { i, st ->
            val wb = if (st.wheelchairHandling) s.wheelchairBoardingSec else 0
            val dwell = s.stopDwellSec + wb
            dwellTotal += dwell
            val depE = arrE.plusSeconds(dwell.toLong()); val depL = arrL.plusSeconds(dwell.toLong())
            stops += EtaStopForecast(i, st.stopKey, arrE, arrL, dwell, wb, depE, depL)
            if (i < legs.size) { arrE = depE.plusSeconds(legs[i].referenceSeconds.toLong()); arrL = depL.plusSeconds((legs[i].referenceSeconds + legs[i].bufferSeconds).toLong()) }
        }
        return EtaForecast(
            stops, legs, legs.sumOf { it.referenceSeconds }, dwellTotal, legs.sumOf { it.bufferSeconds }, legs.sumOf { it.uncertaintySeconds }, s.quantile, model.version, snapshot.configVersion,
            pinned, s, null,
        )
    }

    private fun ceilPct(seconds: Int, pct: BigDecimal): Int = BigDecimal(seconds).multiply(pct).divide(HUNDRED, 0, RoundingMode.CEILING).toInt()

    private fun fallback(snapshot: EtaConfigSnapshot?, reason: EtaFallbackReason, roadDataVersion: String?) =
        EtaForecast(emptyList(), emptyList(), 0, 0, 0, 0, snapshot?.settings?.quantile, snapshot?.model?.version, snapshot?.configVersion, roadDataVersion, snapshot?.settings, reason)

    private companion object {
        val NAMES = setOf("stop_dwell_sec", "wheelchair_boarding_sec", "eta_model_quantile", "eta_safe_speed_tolerance", "eta_max_data_age_sec", "eta_fallback_policy")
        val HUNDRED = BigDecimal(100)
        const val DEFAULT_MAX_DATA_AGE_SEC = 120
    }
}
