package de.taktovia.fleetsafety.internal

/**
 * Ausstattungsabhängige Prüfpunkte (WP-M3-12, LH-6.21-CHK-007): ein Prüfelement mit `equipment_condition = 'RAMP_OR_LIFT'` gehört nur zu Prüfläufen von Fahrzeugen, deren Ausstattung
 * (`vehicle.equipment` und `capacity_config.equipment`) Rampe oder Lift enthält. Die Bedingung wird immer aus dem Fahrzeugstamm abgeleitet, nie aus einem Clientwert. SQL-Fragment für den Alias `v` der Fahrzeugzeile
 * (als `const val`, damit Anfragen weiter ohne Textbausteine zur Laufzeit gebaut werden).
 */
internal const val SQL_VEHICLE_HAS_RAMP_OR_LIFT =
    "(v.equipment && ARRAY['RAMP', 'LIFT']::text[] OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(v.capacity_config -> 'equipment') = 'array' THEN v.capacity_config -> 'equipment' ELSE '[]'::jsonb END) AS eq(x) WHERE eq.x IN ('RAMP', 'LIFT')))"

/** Ausstattungsbedingungen der Prüfelemente (Katalog; weitere Bedingungen mit Entscheidung OP-14). */
internal object EquipmentSteps {
    const val RAMP_OR_LIFT = "RAMP_OR_LIFT"
    val CONDITIONS = setOf(RAMP_OR_LIFT)
    val DIMENSIONS = setOf("wheelchair")

    /** Wendet die Bedingung eines Prüfelements auf das Fahrzeug an; [rampOrLift] ist das Ergebnis von [SQL_VEHICLE_HAS_RAMP_OR_LIFT]. */
    fun applies(condition: String?, rampOrLift: Boolean): Boolean = when (condition) {
        null -> true
        RAMP_OR_LIFT -> rampOrLift
        else -> false
    }
}
