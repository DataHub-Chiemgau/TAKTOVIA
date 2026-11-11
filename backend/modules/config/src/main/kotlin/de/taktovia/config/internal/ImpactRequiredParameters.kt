package de.taktovia.config.internal

/**
 * Vorläufige Liste der impact-pflichtigen Parameter (WP-M1-23, OP-19: nicht freigegeben). Das Kennzeichen steht nicht in der unveränderlichen
 * Schema-Version 1; die nächste Schema-Version führt es als `x-impact-required` im JSON-Schema, dann entfällt diese Liste. Kriterium: Eine Änderung
 * kann bereits bestätigte Buchungen, Angebote oder Schichten berühren (Kap. 06 §6.8).
 */
internal object ImpactRequiredParameters {
    private val HINTS = mapOf(
        "pickup_window_min" to "Bei Änderung bereits bestätigte Buchungen prüfen",
        "max_wait_min" to "Bei Änderung bereits bestätigte Buchungen und Zusagen prüfen",
        "min_booking_lead_min" to "Bei Änderung bereits angebotene Fahrten prüfen",
        "max_booking_horizon_days" to "Bei Verkürzung bereits bestätigte zukünftige Buchungen prüfen",
        "protected_stop_horizon_min" to "Bei Änderung bereits geplante Haltepunkte und Schichten prüfen",
        "min_capacity_reserve" to "Bei Änderung bereits zugesagte Kapazität prüfen",
        "service_config" to "Bei Änderung von Bediengebiet, Betriebszeiten oder Verkehrstyp bestätigte Buchungen und Schichten prüfen",
    )

    fun hint(parameter: String): String? = HINTS[parameter]

    /** Oberster Parametername eines JSON-Pointers (`/service_config/traffic_type` → `service_config`). */
    fun parameterOf(pointer: String): String = pointer.removePrefix("/").substringBefore('/').replace("~1", "/").replace("~0", "~")

    fun isImpactRequired(pointer: String): Boolean = hint(parameterOf(pointer)) != null
}
