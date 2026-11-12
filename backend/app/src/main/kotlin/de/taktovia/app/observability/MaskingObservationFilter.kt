package de.taktovia.app.observability

import de.taktovia.platform.logging.Maskierung
import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter

/**
 * Trace-Attribute (WP-M5-03/-06): vor der Weitergabe an Tracing und Metriken werden alle Schlüssel-Wert-Paare der Beobachtung durch die zentrale
 * Maskierung geführt; `http.url` und `url.full` verlieren Query und Fragment (Suchbegriffe, Kontaktdaten), `user.*`-, Such- und Kontaktschlüssel
 * werden vollständig ersetzt. Der Filter wirkt vor dem Collector-Guard (`infra/observability/otel-collector-config.yaml`), nicht stattdessen.
 */
class MaskingObservationFilter : ObservationFilter {
    override fun map(context: Observation.Context): Observation.Context {
        for (kv in context.highCardinalityKeyValues.toList()) {
            val v = if (kv.key == "http.url" || kv.key == "url.full") stripQuery(kv.value) else Maskierung.traceAttribute(kv.key, kv.value)
            if (v != kv.value) context.addHighCardinalityKeyValue(KeyValue.of(kv.key, v))
        }
        for (kv in context.lowCardinalityKeyValues.toList()) {
            val v = Maskierung.traceAttribute(kv.key, kv.value)
            if (v != kv.value) context.addLowCardinalityKeyValue(KeyValue.of(kv.key, v))
        }
        return context
    }

    private fun stripQuery(url: String): String = Maskierung.maskText(url.substringBefore('?').substringBefore('#'))
}
