package de.taktovia.farepayment.api

import de.taktovia.farepayment.internal.FarepaymentServicesFactory
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Tarifklasse, solange das Angebot keine andere wählt (Arbeitsannahme dieses Plans, Detailvertrag `LH-6.10-PAY-001`). */
const val DEFAULT_TARIFF_CLASS = "STANDARD"

/** Stornoregel, auf die das Angebot verweist (Planungsdefault `std-24h` wie im Beispiel des Schemas `fare-quote-snapshot.v1`; die Stornoregel selbst liefert WP-M2-26). */
const val DEFAULT_CANCELLATION_POLICY_REF = "std-24h"

/**
 * Eingang der Quote-Bildung (WP-M2-09). [priceAt] ist der Preisstichtag (Fahrtzeitpunkt, PA-05), [expiresAt] die Gültigkeit des Angebots (die Quote gilt genau so lange wie das
 * Angebot, OP-20), [configRevisionId] die zum Angebotszeitpunkt wirksame Konfigurationsrevision, an die der Preis gebunden wird.
 */
data class QuoteRequest(
    val attemptId: UUID, val serviceId: UUID, val priceAt: Instant, val fare: FareRequest, val expiresAt: Instant, val configRevisionId: UUID,
    val tariffClass: String = DEFAULT_TARIFF_CLASS, val cancellationPolicyRef: String = DEFAULT_CANCELLATION_POLICY_REF, val routingMapVersion: String? = null,
)

/** Eine Preiskomponente der Quote (Tabelle `fare_component`, Segment 1 im Einbetreiberfall); [ruleId] ist die Regel, aus der sie stammt. */
data class QuoteComponent(val code: FareComponentCode, val amountMinor: Long, val ruleId: UUID?)

/**
 * Unveränderliches Preisangebot (`fare_quote`). [payAtPlatformMinor] ist der Betrag, der bei Bestätigung gebucht und zur Zahlung autorisiert wird; im Einbetreiberfall ist
 * `external_known_minor` 0 und es gibt keine unbekannten externen Teilstrecken (M6). [snapshot] ist das gespeicherte JSON (Schema `fare-quote-snapshot.v1`), [contentHash] SHA-256
 * über dessen kanonische Form ([QuoteHashing]).
 */
data class QuoteView(
    val id: UUID, val attemptId: UUID, val currency: String, val exponent: Int, val payAtPlatformMinor: Long, val externalKnownMinor: Long, val expiresAt: Instant,
    val tariffRevisionId: UUID, val tariffRevisionNo: Int, val configRevisionId: UUID, val components: List<QuoteComponent>, val snapshot: String, val contentHash: String,
) {
    val taxMinor: Long get() = components.filter { it.code == FareComponentCode.TAX }.sumOf { it.amountMinor }
    val netMinor: Long get() = payAtPlatformMinor - taxMinor
}

/** Ergebnis der Quote-Bildung: Quote geschrieben, kein Tarif wirksam („Kein Tarif“, nie ein geratener Preis) oder von der Engine abgelehnt. */
sealed interface QuoteOutcome {
    data class Created(val quote: QuoteView) : QuoteOutcome
    data class NoTariff(val reason: String) : QuoteOutcome
    data class Rejected(val reason: FareRejection, val detail: String) : QuoteOutcome
}

/**
 * Preisangebot als unveränderlicher `fare_quote` (WP-M2-09, LH-6.10-PAY-001).
 *  * [createIn] läuft in der **Transaktion des Aufrufers** (Angebotsbildung, WP-M2-21): Quote, `fare_component`-Zeilen und der Versuch gehören zusammen oder gar nicht.
 *  * Die Quote ist nach dem Schreiben unveränderlich (Trigger `quote_immutable`, die Runtime-Rolle hat nur SELECT und INSERT): eine später veröffentlichte Tarifrevision ändert
 *    den Betrag nie, ein neuer Preis ist eine neue Quote (Gegenereignis statt Änderung).
 *  * [requireAcceptedAmount] ist die Prüfung „akzeptierter Betrag = Quote“ für Hold und Bestätigung: abgelaufen `409 OFFER_EXPIRED`, abweichender Betrag (höher wie niedriger)
 *    `409 REQUOTE_REQUIRED`, unbekannte oder fremde Quote `404`. Sie schreibt nichts; der Aufrufer bricht vor jeder Buchung und Zahlung ab.
 */
interface FareQuotes {
    fun createIn(j: JdbcOperations, ctx: RequestContext, request: QuoteRequest): QuoteOutcome

    /** Quote lesen (RLS: fremder Mandant und unbekannte ID liefern `null`). */
    fun getIn(j: JdbcOperations, quoteId: UUID): QuoteView?

    /** Eigene lesende Transaktion; unbekannte oder fremde Quote: `404`. */
    fun get(ctx: RequestContext, quoteId: UUID): QuoteView

    /** Prüft Ablauf (`now >= expiresAt` gilt als abgelaufen) und akzeptierten Betrag in [currency]; liefert die Quote oder wirft das Problem (siehe oben). */
    fun requireAcceptedAmount(j: JdbcOperations, quoteId: UUID, acceptedMinor: Long, currency: String, now: Instant): QuoteView

    companion object {
        fun create(ctxTx: ContextTransactions, tariffs: Tariffs, clock: Clock = Clock.systemUTC()): FareQuotes = FarepaymentServicesFactory.quotes(ctxTx, tariffs, clock)
    }
}

/**
 * Kanonisierung für `fare_quote.content_hash`: das JSON wird zu einem Baum aus Objekten (Schlüssel aufsteigend nach Unicode-Codepoints sortiert), Arrays (Reihenfolge bleibt),
 * Zeichenketten, ganzen Zahlen, `true`/`false`/`null` gelesen und ohne Leerraum als UTF-8 serialisiert; Zeichenketten werden nur mit den Pflichtescapes (`"`, `\`, Steuerzeichen
 * als `\u00XX`) ausgegeben. Gleitkommazahlen kommen im Snapshot nicht vor (Geld ist `bigint`, Leitlinie 5). Hash = SHA-256, 64 Kleinbuchstaben-Hexziffern.
 * Gleiche Inhalte ergeben unabhängig von Schlüsselreihenfolge und Leerraum denselben Hash; `jsonb` aus der Datenbank darf daher gelesen und neu gehasht werden.
 */
object QuoteHashing {
    fun canonical(json: String): String = de.taktovia.farepayment.internal.CanonicalJson.canonicalize(json)
    fun hash(json: String): String = de.taktovia.farepayment.internal.CanonicalJson.sha256Hex(canonical(json))
}
