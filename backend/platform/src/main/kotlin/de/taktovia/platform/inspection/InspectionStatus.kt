package de.taktovia.platform.inspection

/**
 * Prüfstatus je (Fahrzeug, Schicht) in Fahrer-App und Leitstelle (WP-M3-23, LH-6.21-CHK-016). Reine Projektion des Serverzustands, nie eigener Zustand und nie eine Rechtequelle.
 * Die Ableitung steht in [InspectionStatusDerivation] (Detailvertrag `vertraege/m3-pruefstatus.md`); beide Oberflächen zeigen genau diese sechs Werte.
 */
enum class InspectionStatus {
    /** Keine gültige Prüfung der Schicht (fehlt, abgelaufen, Freigabe widerrufen oder Sicherheitsrevision überholt). */
    PENDING,

    /** Ein Prüflauf der Schicht ist offen. */
    IN_PROGRESS,

    /** Freigabe gültig, keine entfallene Ressourcendimension. */
    PASSED,

    /** Freigabe gültig, aber mindestens eine Ressourcendimension entfällt (`PASSED_RESTRICTED`, OP-17). */
    RESTRICTED,

    /** Aktive Sicherheits- oder Dienstsperre bzw. Prüfergebnis `BLOCKED`. */
    BLOCKED,

    /** Prüfergebnis `CLARIFICATION_REQUIRED` (technisch ungeklärt, OP-16): keine Freigabe, Klärung steht aus. */
    UNCLEAR,
}

/**
 * Serverseitig gelesene Fakten einer (Fahrzeug, Schicht)-Kombination: [blocked] aktive Fahrzeug-/Dienstsperre oder inaktives Fahrzeug, [inspectionOpen] offener Prüflauf (nicht die Prüfung vor Fahrgastaufnahme),
 * [released] gültige, nicht widerrufene Freigabe mit aktueller `safety_revision`, [restricted] Freigabe mit entfallener Ressourcendimension, [latestOutcome] Ergebnis des zuletzt gestarteten, abgeschlossenen Laufs.
 */
data class InspectionStatusFacts(val blocked: Boolean, val inspectionOpen: Boolean, val released: Boolean, val restricted: Boolean, val latestOutcome: String?)

object InspectionStatusDerivation {
    /**
     * Ableitungstabelle (erste zutreffende Zeile gilt): Sperre -> `BLOCKED`; offener Lauf -> `IN_PROGRESS`; gültige Freigabe -> `RESTRICTED` bei entfallener Dimension, sonst `PASSED`;
     * Ergebnis `CLARIFICATION_REQUIRED` -> `UNCLEAR`; Ergebnis `BLOCKED` -> `BLOCKED`; sonst `PENDING`.
     */
    fun derive(f: InspectionStatusFacts): InspectionStatus = when {
        f.blocked -> InspectionStatus.BLOCKED
        f.inspectionOpen -> InspectionStatus.IN_PROGRESS
        f.released -> if (f.restricted) InspectionStatus.RESTRICTED else InspectionStatus.PASSED
        f.latestOutcome == "CLARIFICATION_REQUIRED" -> InspectionStatus.UNCLEAR
        f.latestOutcome == "BLOCKED" -> InspectionStatus.BLOCKED
        else -> InspectionStatus.PENDING
    }
}
