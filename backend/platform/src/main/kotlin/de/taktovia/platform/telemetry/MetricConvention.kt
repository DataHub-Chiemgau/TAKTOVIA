package de.taktovia.platform.telemetry

import de.taktovia.platform.logging.Maskierung
import de.taktovia.platform.logging.Positivliste
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Namens- und Labelkonvention der Telemetrie (WP-M5-03, Kap. 01 §1.1, Kap. 05 §5.1), Beschreibung: docs/betrieb/telemetrie.md.
 *
 * - Eigene Metriken heißen `taktovia_<komponente>_<größe>[_<einheit>]` (Kleinbuchstaben, Ziffern, Unterstrich); Einheiten `_seconds`, `_total`, `_bytes`, `_ratio`.
 * - Labelschlüssel nur aus [ALLOWED_LABELS]; Werte nur technische Bezeichner (`[A-Za-z0-9_.:/-]`, höchstens 64 Zeichen), `tenant_id` nur als UUID.
 * - Verboten als Labelschlüssel und -wert: Personen, Kontakte (Name, E-Mail, Telefon), Ortssuchen und Suchbegriffe, Adressen, Zahlungsdetails, Token.
 */
object MetricConvention {
    val NAME = Regex("taktovia_[a-z][a-z0-9_]{2,100}")
    val ALLOWED_LABELS = setOf("component", "route_class", "method", "status_class", "result", "kind", "queue", "outcome", "error_class",
        "tenant_id", "worker", "event_type", "consumer", "state", "operation", "reason", "phase")
    private val SAFE_VALUE = Regex("[A-Za-z0-9_.:/-]{0,64}")
    private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** `null` = zulässig, sonst die Verletzung (ohne den Wert zu nennen). */
    fun violation(name: String, tags: Map<String, String>): String? {
        val own = name.startsWith("taktovia_")
        if (own && !NAME.matches(name)) return "Metrikname verletzt die Namenskonvention"
        for ((k, v) in tags) {
            if (Positivliste.istSperrschluessel(k)) return "Labelschlüssel '$k' ist verboten (Personen-, Kontakt-, Such- oder Zahlungsbezug)"
            if (own && k !in ALLOWED_LABELS) return "Labelschlüssel '$k' steht nicht in der Positivliste"
            if (own && !SAFE_VALUE.matches(v)) return "Labelwert zu '$k' enthält unzulässige Zeichen oder ist zu lang"
            if (k == "tenant_id" && !UUID_RE.matches(v)) return "tenant_id nur als UUID"
            if (Maskierung.klartextKlassen(v).isNotEmpty()) return "Labelwert zu '$k' enthält Kontakt-, Zahlungs- oder Tokenmuster"
        }
        return null
    }

    fun tenantLabel(tenant: UUID): String = tenant.toString()
}

/**
 * Micrometer-Filter: Zähler, Uhren und Anzeiger, die die Konvention verletzen, werden nicht angelegt (kein Klartext in der Betriebsplattform).
 * Die Verletzungen bleiben für Test und Prüfskript abrufbar ([violations]); sie nennen Metrik und Regel, nie den Wert.
 */
class LabelPolicyFilter : MeterFilter {
    private val found = ConcurrentLinkedQueue<String>()
    private val count = AtomicLong()

    fun violations(): List<String> = found.toList()
    fun denied(): Long = count.get()

    override fun accept(id: Meter.Id): io.micrometer.core.instrument.config.MeterFilterReply {
        val v = runCatching { MetricConvention.violation(id.name, id.tags.associate { it.key to it.value }) }.getOrNull() ?: return io.micrometer.core.instrument.config.MeterFilterReply.NEUTRAL
        count.incrementAndGet()
        if (found.size < 200) found.add("${id.name}: $v")
        return io.micrometer.core.instrument.config.MeterFilterReply.DENY
    }
}
