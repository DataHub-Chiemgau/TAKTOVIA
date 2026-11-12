package de.taktovia.app.probe

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Nur in Tests (WP-M5-06): Fehlerpfade mit Kontakt- und Zahlungsdaten in Nachricht und Ausnahmeobjekt. Erfundene Testwerte. */
@RestController
@RequestMapping("/v1/probe/diagnose")
@ConditionalOnProperty("taktovia.probe.enabled", havingValue = "true")
class DiagnoseProbeController {
    private val log = LoggerFactory.getLogger(javaClass)
    private data class Passagier(val name: String, val email: String, val iban: String, val karte: String)

    @GetMapping("/validierung")
    fun validierung(): Nothing {
        log.warn("Validierung abgelehnt für erika.mustermann@example.test Telefon +49 176 12345678")
        throw ProblemException(ErrorCode.VALIDATION_FAILED, "Feld contact.email 'erika.mustermann@example.test' ist ungültig")
    }

    @GetMapping("/ausnahme")
    fun ausnahme(): Nothing {
        val p = Passagier("Erika Mustermann", "erika.mustermann@example.test", "DE89370400440532013000", "4111 1111 1111 1111")
        throw IllegalStateException("Zahlung fehlgeschlagen: $p, Adresse Musterstraße 12, 10115 Berlin")
    }
}

/** Zeigt die Trace-ID des aktuellen Spans (WP-M5-03). */
@RestController
@RequestMapping("/v1/probe/telemetrie")
@ConditionalOnProperty("taktovia.probe.enabled", havingValue = "true")
class TelemetryProbeController(private val tracer: io.micrometer.tracing.Tracer) {
    @GetMapping("/trace")
    fun trace(): Map<String, String?> = mapOf("trace_id" to tracer.currentSpan()?.context()?.traceId())
}
