package de.taktovia.platform.problem

import java.util.UUID

/**
 * Fehlercode-Katalog der API (Kap. 05 §5.1/§5.4, WP-M1-02). `code` und `type` sind stabil und ändern sich nie
 * ohne neue Major-Version. Die Codes stehen zusätzlich als Schema `ErrorCode` in `api/common.yaml`; ein Test hält
 * beide synchron. Codes späterer Meilensteine (M2: OFFER_EXPIRED, HOLD_EXPIRED, REQUOTE_REQUIRED, CAPACITY_UNAVAILABLE,
 * COOPERATION_NOT_ALLOWED, PROVIDER_UNAVAILABLE) sind hier bereits festgelegt, damit der Katalog nicht auseinanderläuft.
 */
enum class ErrorCode(val status: Int, val title: String) {
    BAD_REQUEST(400, "Ungültige Anfrage"),
    INVALID_CURSOR(400, "Ungültiger Cursor"),
    UNAUTHENTICATED(401, "Nicht angemeldet"),
    FORBIDDEN(403, "Nicht berechtigt"),
    COOPERATION_NOT_ALLOWED(403, "Kooperation nicht erlaubt"),
    NOT_FOUND(404, "Nicht gefunden"),
    METHOD_NOT_ALLOWED(405, "Methode nicht erlaubt"),
    NOT_ACCEPTABLE(406, "Darstellung nicht verfügbar"),
    CONFLICT(409, "Konflikt"),
    IDEMPOTENCY_CONFLICT(409, "Idempotenzkonflikt"),
    OPERATION_IN_PROGRESS(409, "Vorgang in Bearbeitung"),
    OFFER_EXPIRED(409, "Angebot abgelaufen"),
    HOLD_EXPIRED(409, "Reservierung abgelaufen"),
    REQUOTE_REQUIRED(409, "Neue Preisauskunft erforderlich"),
    CAPACITY_UNAVAILABLE(409, "Kapazität nicht verfügbar"),
    IMMUTABLE_RECORD(409, "Datensatz unveränderlich"),
    REVISION_MISMATCH(412, "Veraltete Revision"),
    PAYLOAD_TOO_LARGE(413, "Anfrage zu groß"),
    UNSUPPORTED_MEDIA_TYPE(415, "Medientyp nicht unterstützt"),
    VALIDATION_FAILED(422, "Eingabe ungültig"),
    PRECONDITION_REQUIRED(428, "Revision erforderlich"),
    RATE_LIMITED(429, "Zu viele Anfragen"),
    INTERNAL_ERROR(500, "Interner Fehler"),
    SERVICE_UNAVAILABLE(503, "Dienst nicht verfügbar"),
    PROVIDER_UNAVAILABLE(503, "Anbieter nicht erreichbar");

    /** Stabile Typ-URI nach Muster `https://api.taktovia.example/problems/<code-kebab>`. */
    val type: String get() = TYPE_BASE + name.lowercase().replace('_', '-')

    companion object {
        const val TYPE_BASE = "https://api.taktovia.example/problems/"
        fun of(code: String): ErrorCode? = entries.firstOrNull { it.name == code }
    }
}

/**
 * Katalog der Feldverletzungscodes (`violations[].code`). Der Client erkennt Feld und Fehlerart aus `pointer` und
 * `code`, ohne `message` auszuwerten. Standen die Codes in `api/common.yaml` als Schema `ViolationCode`.
 */
enum class ViolationCode(val message: String) {
    REQUIRED("Pflichtfeld fehlt"),
    UNKNOWN_FIELD("Unbekanntes Feld"),
    TYPE_MISMATCH("Falscher Datentyp"),
    TOO_SHORT("Wert zu kurz"),
    TOO_LONG("Wert zu lang"),
    BELOW_MINIMUM("Wert unterschreitet das Minimum"),
    ABOVE_MAXIMUM("Wert überschreitet das Maximum"),
    PATTERN_MISMATCH("Wert entspricht nicht dem erwarteten Muster"),
    INVALID_FORMAT("Ungültiges Format"),
    NOT_IN_ENUM("Wert nicht zulässig"),
    TOO_FEW_ITEMS("Zu wenige Elemente"),
    TOO_MANY_ITEMS("Zu viele Elemente"),
    NOT_UNIQUE("Elemente nicht eindeutig"),
    INVALID_VALUE("Wert ungültig"),
    NESTING_TOO_DEEP("Verschachtelung zu tief"),
    // Geld (Kap. 05 §5.1)
    MONEY_NOT_INTEGER("Betrag muss eine ganze Zahl in kleinster Einheit sein"),
    MONEY_OUT_OF_RANGE("Betrag außerhalb des sicher darstellbaren Bereichs"),
    MONEY_NEGATIVE("Negativer Betrag nur im Typ SignedMoney"),
    CURRENCY_UNKNOWN("Unbekannte Währung"),
    EXPONENT_MISMATCH("Exponent passt nicht zur Währung"),
    // Zeit (SYS-006)
    OFFSET_REQUIRED("Zeitpunkt ohne UTC-Offset"),
    LOCAL_TIME_NONEXISTENT("Ortszeit existiert wegen der Zeitumstellung nicht"),
    LOCAL_TIME_AMBIGUOUS("Ortszeit ist mehrdeutig, eindeutigen Offset angeben"),
    ZONE_UNKNOWN("Unbekannte Zeitzone"),
    ZONE_OFFSET_MISMATCH("Offset passt nicht zur Zeitzone"),
    INTERVAL_INVALID("Intervallende liegt nicht nach dem Beginn"),
    // Eingabehärtung (SEC-009)
    TARGET_NOT_ALLOWED("Ziel-URL nicht zulässig"),
}

/** Eine Feldverletzung: `pointer` ist ein JSON-Pointer (RFC 6901); Nicht-Body-Orte tragen `/$query/…` bzw. `/$header/…`. */
data class Violation(val pointer: String, val code: ViolationCode, val message: String = code.message)

/** Fachlicher oder technischer API-Fehler; wird vom globalen Fehlerabbildner zu `application/problem+json`. */
class ProblemException(
    val code: ErrorCode,
    val detail: String? = null,
    val violations: List<Violation> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(code.name, cause) {
    companion object {
        fun invalid(vararg v: Violation) = ProblemException(ErrorCode.VALIDATION_FAILED, "Mindestens ein Feld ist ungültig.", v.toList())
        fun notFound() = ProblemException(ErrorCode.NOT_FOUND)
    }
}

/** Serialisierbare Form des Schemas `Problem` (`api/common.yaml`). */
data class Problem(
    val code: ErrorCode,
    val correlationId: UUID,
    val detail: String? = null,
    val violations: List<Violation> = emptyList(),
) {
    /** Feldreihenfolge und -namen wie im Schema; `detail` höchstens 2000 Zeichen, `message` höchstens 256. */
    fun toMap(): Map<String, Any?> = buildMap {
        put("type", code.type); put("title", code.title); put("status", code.status); put("code", code.name)
        detail?.let { put("detail", it.take(2000)) }
        put("correlation_id", correlationId.toString())
        if (violations.isNotEmpty()) put("violations", violations.map {
            mapOf("pointer" to it.pointer.take(256), "code" to it.code.name, "message" to it.message.take(256))
        })
    }
}
