package de.taktovia.analytics.api

import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Die zum Vergleichsstichtag wirksamen Werte der Ankunftstoleranz (WP-M4-54, LH-I.6-KPI-008): [tolerance] ist `on_time_arrival_tolerance_min` (Kennzahlgrenzwert), [grace] ist `arrival_grace_min`
 * (Ankunftstoleranz der Zusage); beide mit Herkunft ([PickupTolerance] dient als Träger für Minuten, Ebene und Konfigurationsrevision). Arbeitsannahme zum offenen Verhältnis beider Parameter:
 * wirksam ist der größere Wert ([effectiveMinutes]); eine Ankunft innerhalb der in der Zusage gewährten Karenz gilt nie als verspätet.
 */
data class ArrivalTolerance(val tolerance: PickupTolerance, val grace: PickupTolerance) {
    val effectiveMinutes: Int get() = maxOf(tolerance.minutes, grace.minutes)
}

/**
 * Auflösung der Ankunftstoleranz über die Konfiguration (Port, den die Anwendung aus dem Modul `config` speist). [resolve] läuft in der Transaktion der Auswertung und löst die Werte **zum Vergleichsstichtag**
 * [at] für den Dienst [serviceId] auf; `null` heißt: nicht auflösbar (die Ankunft ist dann für die Pünktlichkeit nicht bewertbar und mindert die Abdeckung).
 */
fun interface ArrivalToleranceSource {
    fun resolve(j: JdbcOperations, tenant: TenantId, serviceId: UUID?, at: Instant): ArrivalTolerance?

    companion object {
        /** Keine Konfigurationsanbindung: nichts ist auflösbar (`Ankunft pünktlich` bleibt dann vorläufig mit Abdeckung 0). */
        val NONE = ArrivalToleranceSource { _, _, _, _ -> null }
    }
}
