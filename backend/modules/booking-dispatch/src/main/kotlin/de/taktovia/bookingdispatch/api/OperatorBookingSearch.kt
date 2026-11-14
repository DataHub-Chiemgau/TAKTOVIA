package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.BookingServicesFactory
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Suchfilter der Auftragsliste (WP-M3-37, LH-6.7-OPS-002). Alle Felder sind optional, mehrere Felder wirken als **Schnittmenge**.
 *
 * - [bookingNumber]: exakte Buchungsnummer (`BK-<Jahr>-<laufende Nummer>`), Groß-/Kleinschreibung egal.
 * - [from], [to]: Zeitraum; gesucht wird im **Zusagefenster** der Buchung (Arbeitsannahme, Detailvertrag: Beginn des Abholfensters bis spätester Ankunft): eine Buchung trifft, wenn ihr Fenster
 *   den Zeitraum `[from, to)` schneidet (`from` allein: endet nicht vor `from`; `to` allein: beginnt vor `to`).
 * - [passengerRef]: Verweis auf das Fahrgastkonto (`passenger_private.id`), **exakte** Suche. Eine Suche über Name oder Kontaktdaten gibt es nicht: die Kontaktdaten liegen nur verschlüsselt
 *   (Feldschutz WP-M1-31), ein durchsuchbares Verfahren ist offen (M3 Offener Punkt 26: Blind-Index, Suchfeldumfang, berechtigte Rollen). Nur Rollen mit Recht auf Fahrgastdaten dürfen diesen Filter benutzen.
 * - [vehicleId]: Fahrzeug des Umlaufs, in dem die Buchung steht.
 * - [state]: Buchungszustand (Automat `booking`).
 * - [serviceId]: schränkt zusätzlich auf einen Dienst ein; ein Dienst außerhalb des Scopes ergibt eine leere Liste, nie einen Fehler und nie Treffer.
 */
data class BookingSearchFilter(
    val bookingNumber: String? = null, val from: Instant? = null, val to: Instant? = null, val passengerRef: UUID? = null, val vehicleId: UUID? = null, val state: String? = null,
    val serviceId: UUID? = null,
) {
    /** Stabiler Fingerabdruck der Filterwerte (SHA-256); bindet den Cursor an genau diesen Filter, ohne die Werte (Personenbezug) im Cursor oder in Logs zu führen. */
    fun fingerprint(): String {
        val raw = listOf(bookingNumber?.uppercase(), from, to, passengerRef, vehicleId, state, serviceId).joinToString("|") { it?.toString() ?: "-" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    val isEmpty: Boolean get() = bookingNumber == null && from == null && to == null && passengerRef == null && vehicleId == null && state == null && serviceId == null
}

/**
 * Hinweis an einem Auftrag (WP-M3-42): [kind] `OVERDUE` (Überfälligkeit) oder `INFO_PENDING` (Information ausstehend), [cause] der Code der Art, [affectedCount] die Zahl der betroffenen Buchungen
 * des berechtigten Dienstes. Keine Namen, keine Fahrgastdaten.
 */
data class OperatorFlagView(val id: UUID, val kind: String, val cause: String, val affectedCount: Int, val openedAt: Instant, val thresholdMin: Int?)

/**
 * Trefferzeile der Auftragsliste: nur die für die Disposition nötigen Felder (Buchungsnummer, Zustand, Zusagefenster, Fahrzeug, Personenzahl). [passengerRef] (pseudonymer Verweis, nie Name oder
 * Kontakt) nur für Rollen mit Recht auf Fahrgastdaten, [assistanceCodes] nur nach Feldfreigabe der Rolle (Disposition, Callcenter; WP-M1-31), sonst `null`.
 */
data class BookingSearchRow(
    val bookingId: UUID, val bookingNumber: String, val serviceId: UUID, val state: String, val revision: Long, val createdAt: Instant,
    val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant,
    val passengers: Int, val wheelchairSpaces: Int, val vehicleId: UUID?, val vehicleLabel: String?,
    val passengerRef: UUID?, val assistanceCodes: List<String>?, val flags: List<OperatorFlagView> = emptyList(),
)

/**
 * Auftragsliste der Leitstelle mit Suche (WP-M3-37). Gelesen werden nur Buchungen der Dienste, die der **aktuelle** Scope der Mitgliedschaften des Kontexts umfasst (frisch in jeder Abfrage, ein
 * Entzug wirkt ab dem Commit auch auf die nächste Seite einer laufenden Suche); fremder Mandant: nie vorhanden (RLS). Sortierung stabil nach `(created_at, id)`; die Seitenbildung (Cursor) liegt
 * beim Aufrufer: [search] liefert höchstens `limit` + 1 Zeilen ab [after].
 *
 * Fehler: ein Fahrgastkonto (Rolle `passenger`) oder ein Dienstkonto ohne Recht erhält 403; der Fahrgastfilter ohne Recht auf Fahrgastdaten 403; ein unbekannter Zustand 422.
 * Suchbegriffe zu Personen werden weder protokolliert noch als Metrikbezeichner benutzt.
 */
interface OperatorBookingSearch {
    fun search(ctx: RequestContext, filter: BookingSearchFilter, limit: Int, after: Pair<Instant, UUID>?): List<BookingSearchRow>

    companion object {
        /** Rollen mit Recht auf Fahrgastdaten (Verweis und Fahrgastsuche in der Auftragsliste); Arbeitsannahme, bis die Rechtematrix (OP-10/OP-34) fachlich freigegeben ist. */
        val PASSENGER_DATA_ROLES: Set<String> = setOf("dispatcher", "callcenter", "support")

        fun create(ctxTx: ContextTransactions): OperatorBookingSearch = BookingServicesFactory.operatorBookingSearch(ctxTx)
    }
}
