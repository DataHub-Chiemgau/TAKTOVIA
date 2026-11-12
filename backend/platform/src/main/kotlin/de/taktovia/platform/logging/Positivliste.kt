package de.taktovia.platform.logging

/**
 * Positivliste protokollierbarer Felder je Modul (WP-M5-06): ein Feld, das nicht auf der Liste steht, wird nicht protokolliert.
 * Neue Felder werden bewusst hier ergänzt (Review), nicht ad hoc im Modul. Der Wert eines zugelassenen Felds läuft zusätzlich durch
 * [Maskierung.maskText]. Die Liste `ALLGEMEIN` gilt für alle Module.
 */
object Positivliste {
    val ALLGEMEIN = setOf("correlation_id", "tenant_id", "tenant_ref", "request_id", "trace_id", "span_id", "event", "event_type", "aggregate_type",
        "aggregate_id", "operation_id", "status", "result", "code", "attempts", "count", "duration_ms", "queue", "component", "reason_code",
        "revision", "http_method", "route_class", "http_status", "actor_ref", "error_class")

    /** Modulspezifische Ergänzungen (technische Bezeichner, keine Personen-, Kontakt- oder Zahlungsdaten). */
    private val MODULE: Map<String, Set<String>> = mapOf(
        "booking" to setOf("booking_id", "offer_id", "hold_id", "segment_id"),
        "payment" to setOf("payment_intent_id", "psp_reference", "payment_state", "amount_minor", "currency"),
        "routing" to setOf("solver_ms", "queue_wait_ms", "timeout", "plan_id", "vehicle_id"),
        "documents" to setOf("document_id", "scan_result", "ocr_state", "size_bytes", "media_type"),
        "notification" to setOf("notification_id", "channel", "template"),
        "gateway" to setOf("bff_route", "client_kind"),
        "audit" to setOf("audit_event_id", "action", "object_type", "object_id"),
        "security" to setOf("security_event_id", "incident_id", "rule"),
    )

    private val SPERR = Regex("(?i)(?<![a-z])(pass(word|wd)?|secret|token|authorization|api[_-]?key|credential|cookie|signature|private[_-]?key|otp|pin|cvc|cvv|bearer|session|iban|card|pan|e-?mail|phone|mobil|tel(efon)?|first[_-]?name|last[_-]?name|full[_-]?name|vorname|nachname|address|adresse|street|strasse|postal|birth|geburt|boarding|body|query|search|passenger_name|fahrgast_name)s?(?![a-z])")

    fun zugelassen(modul: String): Set<String> = ALLGEMEIN + (MODULE[modul] ?: emptySet())

    fun istSperrschluessel(key: String): Boolean = SPERR.containsMatchIn(key)

    /** Behält nur Felder der Positivliste von [modul]; Werte maskiert. Sperrschlüssel auf der Liste gibt es nicht (Test erzwingt es). */
    fun filtere(modul: String, felder: Map<String, Any?>): Map<String, String> {
        val ok = zugelassen(modul)
        return felder.filterKeys { it in ok }.mapValues { (_, v) -> Maskierung.maskText(v?.toString()).take(256) }
    }

    /** Für Tests und Prüfskript: alle Listen als Menge. */
    fun alleModule(): Map<String, Set<String>> = MODULE.mapValues { ALLGEMEIN + it.value }
}
