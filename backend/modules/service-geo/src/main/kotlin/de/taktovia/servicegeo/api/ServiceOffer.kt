package de.taktovia.servicegeo.api

import de.taktovia.config.api.RevisionValidator
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Angebotsmodell eines Dienstes (WP-M1-25, LH-6.1-SYS-003). Technische Werte entsprechen dem Enum `traffic_type` der Dienstkonfiguration (`service_config`, Planungsannahme PA-01).
 * Das Modell ist Bestandteil der Dienstrevision (`values_json.service_config.traffic_type`), keine Spalte von `service` (OP-28).
 */
enum class OfferModel(val label: String) {
    AREA_ON_DEMAND("Flächenbedarfsverkehr"), LINE_ON_DEMAND("linienbasierter Bedarfsverkehr"), STATION_FEEDER("Bahnhofszubringer");
    companion object { fun of(code: Any?): OfferModel? = entries.firstOrNull { it.name == code } }
}

/** Regeln eines Modells für die fünf modellabhängigen Parameter der Dienstrevision: Pflicht, unzulässig, sonst optional. Planungsannahme, nicht fachlich freigegeben (OP-28). */
data class ModelRules(val model: OfferModel, val required: Set<String>, val forbidden: Set<String>, val optional: Set<String>)

/** Regeltabelle der Modelle (Details und Begründung: `docs/umsetzungsplan/vertraege/angebotsmodelle.md`). */
object OfferModelRules {
    /** Die modellabhängigen Parameter, die der Validator je Modell prüft. */
    val PARAMETERS: List<String> = listOf("pooling_allowed", "max_detour_pct", "stop_walk_max_m", "advance_arrival_buffer_min")

    private fun rules(model: OfferModel, required: Set<String>, forbidden: Set<String>) = ModelRules(model, required, forbidden, PARAMETERS.toSet() - required - forbidden)

    val ALL: Map<OfferModel, ModelRules> = listOf(
        rules(OfferModel.AREA_ON_DEMAND, required = setOf("max_detour_pct"), forbidden = setOf("advance_arrival_buffer_min")),
        rules(OfferModel.LINE_ON_DEMAND, required = setOf("stop_walk_max_m"), forbidden = setOf("max_detour_pct", "advance_arrival_buffer_min")),
        rules(OfferModel.STATION_FEEDER, required = setOf("advance_arrival_buffer_min"), forbidden = emptySet()),
    ).associateBy { it.model }

    fun of(model: OfferModel): ModelRules = ALL.getValue(model)
}

/** Wirksame Dienstkonfiguration zum Zeitpunkt [asOf]: Modell und Regeln aus der veröffentlichten Revision, die fünf modellabhängigen Werte und die Herkunft. [model] `null` = keine publizierte Dienstkonfiguration. */
data class ServiceConfigurationView(
    val serviceId: UUID, val asOf: Instant, val model: OfferModel?, val rules: ModelRules?, val parameters: Map<String, Any?>, val revisionId: UUID?, val effectiveFrom: Instant?,
)

interface ServiceConfigurations {
    /**
     * Wirksame Dienstkonfiguration über den Konfigurations-Port (`ConfigApi.effective`, Präzedenzkette inkl. Mandant/Region): 404 für unbekannte oder fremde Dienste und für
     * Dienste außerhalb des Dienstscopes lesender Rollen. [at] `null` = jetzt.
     */
    fun effective(ctx: RequestContext, serviceId: UUID, at: Instant? = null): ServiceConfigurationView

    /** Regeltabelle aller Modelle (für Oberfläche und Dokumentation). */
    fun modelRules(): List<ModelRules> = OfferModelRules.ALL.values.toList()

    /** Fachliche Prüfregel für Dienstrevisionen: als Bean `RevisionValidator` im Konfigurationsmodul registrieren (läuft bei Validierung und Publikation, WP-M1-22). */
    val revisionValidator: RevisionValidator
}
