package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/*
 * Anbindung des ETA-Grundmodells an die Angebotsbildung (WP-M4-66, LH-I.3-ETA-001/-002 am echten Angebot): die Fensterbildung der Disposition ruft das Grundmodell als Fachoperation auf.
 */

/**
 * Grundzeit für ein Angebot: [directSeconds] = Haltezeit am Abholstopp plus Referenzfahrzeit (früheste Ankunft ab Abholbeginn), [bufferSeconds] die Sicherheitsreserve, die nur den **späten** Rand des
 * Ankunftsfensters verlängert. Jedes Ergebnis trägt genau eine Modell-/Konfigurationsversion ([modelVersion], [configVersion]) aus einem Snapshot. Kann das Grundmodell keine regelkonforme Prognose liefern
 * ([fallbackReason] gesetzt, [directSeconds] `null`), gilt für das Fenster die Planungsannahme und das Angebot weist den Grund aus; nie eine geschätzte Modellzeit.
 */
data class OfferEta(val directSeconds: Int?, val bufferSeconds: Int, val modelVersion: Int?, val configVersion: String?, val fallbackReason: EtaFallbackReason?, val legalMinimumSeconds: Int? = null) {
    init { require((directSeconds == null) == (fallbackReason != null)) { "Grundzeit und Rückfallgrund schließen sich aus" } }
}

/**
 * Quelle der Angebotszeit: Fahrzeit von Abhol- zu Zielhaltepunkt (Revisionen) für den Dienst zum Zeitpunkt [at]. Aufrufer ist die Angebotsbildung der Disposition; **keine Fahrer-Angabe** geht ein
 * (ETA-001: dieselbe Strecke ergibt dieselbe Grundzeit, unabhängig von der zugeordneten Schicht).
 */
fun interface OfferEtaSource {
    fun ground(ctx: RequestContext, serviceId: UUID, pickupStopRevision: UUID, dropoffStopRevision: UUID, at: Instant): OfferEta
}
