package de.taktovia.analytics.api

import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Der zum Vergleichsstichtag wirksame Grenzwert `on_time_pickup_tolerance_min` mit Herkunft (WP-M4-53, LH-I.6-KPI-007). [level] ist die Ebene der Präzedenzkette (`GLOBAL`, `TENANT`, `REGION`, `SERVICE`),
 * [configRevisionId] und [configRevision] nennen die Konfigurationsrevision (bei `GLOBAL` leer). [validFrom] und [validTo] (halboffen) begrenzen, wie lange derselbe Wert für denselben Dienst gilt;
 * innerhalb dieses Intervalls darf das Ergebnis wiederverwendet werden (kein erneuter Aufruf je Buchung).
 */
data class PickupTolerance(val minutes: Int, val level: String, val configRevisionId: UUID?, val configRevision: Long?, val validFrom: Instant? = null, val validTo: Instant? = null) {
    init {
        require(minutes in 0..240) { "Grenzwert 0 bis 240 Minuten" }
        require(level in LEVELS) { "Ebene unbekannt: $level" }
        require((level == "GLOBAL") == (configRevisionId == null)) { "Konfigurationsrevision genau dann leer, wenn die Ebene GLOBAL ist" }
    }
    companion object { val LEVELS = setOf("GLOBAL", "TENANT", "REGION", "SERVICE") }
}

/**
 * Auflösung des Grenzwerts über die Konfiguration (Port, den die Anwendung aus dem Modul `config` speist; `analytics` ruft nie fremde Tabellen). [resolve] läuft in der Transaktion der Auswertung
 * (Mandantenkontext gesetzt) und löst den Wert **zum Vergleichsstichtag** [at] für den Dienst [serviceId] auf (Arbeitsannahme OP 29: Stichtag = Zusagezeitpunkt, Gebiet = Region des Dienstes);
 * ohne Dienst gilt der Mandantenrahmen. `null` heißt: nicht auflösbar (Dienst unbekannt, kein Wert): die Abholung ist dann für den Grenzwert nicht bewertbar und mindert die Abdeckung.
 */
fun interface PickupToleranceSource {
    fun resolve(j: JdbcOperations, tenant: TenantId, serviceId: UUID?, at: Instant): PickupTolerance?

    companion object {
        /** Keine Konfigurationsanbindung: nichts ist auflösbar (die Kennzahl `Abholung pünktlich` bleibt dann vorläufig mit Abdeckung 0). */
        val NONE = PickupToleranceSource { _, _, _, _ -> null }
    }
}
