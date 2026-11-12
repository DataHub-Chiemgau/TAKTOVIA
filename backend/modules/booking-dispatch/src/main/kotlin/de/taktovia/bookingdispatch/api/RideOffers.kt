package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.RideOfferServicesFactory
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.QuoteView
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.ServicegeoApi
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Art des Zeitwunsches (Kap. 05 §5.9): Abfahrt- oder Ankunftswunsch. */
enum class RideTimeKind { DEPARTURE, ARRIVAL }

/**
 * Sofort- oder Vorausbuchung (Arbeitsannahme, im OpenAPI-Detailvertrag bestätigt): **Sofort** ist ein Abfahrtswunsch „jetzt“ (Zeitwunsch höchstens [RideOfferParameters.IMMEDIATE_TOLERANCE_MIN]
 * Minuten von der Serverzeit entfernt), **Voraus** jeder andere Wunsch in der Zukunft. Der Mindestvorlauf `min_booking_lead_min` gilt nur für Voraus.
 */
enum class RideBookingKind { IMMEDIATE, ADVANCE }

/** Ergebnis einer Anfrage: Zeitfenster geliefert, fachlich abgelehnt oder technisch gescheitert (nie als Ablehnung gezählt). */
enum class AttemptOutcome { OFFERED, REJECTED, TECHNICAL_ERROR }

/** Start oder Ziel: Koordinate (Pflicht) und optional ein fester Haltepunkt. Die Koordinate wird nie gespeichert (nur die grobe Gebietskennung, OP-13). */
data class RidePlace(val longitude: Double, val latitude: Double, val stopId: UUID? = null) {
    fun point(): GeoPoint = GeoPoint(longitude, latitude)
}

/**
 * Anfrage „Fahrt suchen“ (`POST /v1/ride-offers`). [desiredAt] und [timeZone] sind die rohen Eingabetexte: das Modul prüft sie nach Kap. 05 §5.1 (RFC 3339 mit Offset, bekannte
 * IANA-Zone, Ortszeit existiert, Offset passt zur Zone). [idempotencyKey] ist der Wert des Headers `Idempotency-Key`: gleicher Schlüssel und gleicher Inhalt ist ein technischer
 * Retry (kein neuer Versuch), gleicher Schlüssel mit anderem Inhalt ein Konflikt.
 */
data class RideOfferCommand(
    val serviceId: UUID, val origin: RidePlace, val destination: RidePlace, val timeKind: RideTimeKind, val desiredAt: String?, val timeZone: String?,
    val passengers: Int, val idempotencyKey: String, val wheelchairSpaces: Int = 0, val equipment: Set<EquipmentCode> = emptySet(),
    val assistanceCodes: Set<AssistanceCode> = emptySet(), val channel: String = "WEB",
) {
    /**
     * Bedarf als Ressourcenvektor wie [BookingDemand.of]: Personen schließen die Rollstuhlplätze ein, Sitzbedarf = Personen minus Rollstuhlplätze (`m2-detailvertraege.md` §8); `ASSISTANCE_ANIMAL`
     * verlangt zusätzlich das Merkmal `ASSISTANCE_DOG_SPACE`. Der Bedarf des Angebots ist damit derselbe Vektor, den der Hold hält und die Bestätigung als Bedarf der Buchung erwartet.
     */
    fun demand(): ResourceVector = ResourceVector(
        seats = maxOf(0, passengers - wheelchairSpaces), wheelchair = wheelchairSpaces,
        equipment = equipment + (if (AssistanceCode.ASSISTANCE_ANIMAL in assistanceCodes) setOf(EquipmentCode.ASSISTANCE_DOG_SPACE) else emptySet()),
    )

    /** Rollstuhlnutzung: Rollstuhlplätze oder der Unterstützungscode `WHEELCHAIR_SPACE`. */
    fun needsWheelchair(): Boolean = wheelchairSpaces > 0 || AssistanceCode.WHEELCHAIR_SPACE in assistanceCodes
}

/** Zeitfenster `[earliestAt, latestAt]` (Kap. 05 §5.9 `TimeWindow`), nichtleer: `earliestAt <= latestAt`. */
data class RideTimeWindow(val earliestAt: Instant, val latestAt: Instant) {
    init { require(!latestAt.isBefore(earliestAt)) { "Zeitfenster: spätester Rand vor frühestem" } }
}

/**
 * Angebotenes Zeitfenster (noch ohne Preis und ohne Kapazitätszusage; Preis und Angebotsobjekt folgen mit WP-M2-09/-21). [arrivalGraceMin] ist die Ankunftstoleranz
 * (`arrival_grace_min`) über dem späten Rand des Ankunftsfensters; [directSeconds] die zugrunde gelegte Direktfahrzeit.
 */
data class RideOfferProposal(
    val serviceId: UUID, val bookingKind: RideBookingKind, val timeKind: RideTimeKind, val pickupWindow: RideTimeWindow, val arrivalWindow: RideTimeWindow,
    val arrivalGraceMin: Int, val directSeconds: Int,
    /** Das ausgestellte Angebotsobjekt (WP-M2-21) mit Preisstand; `null` nur, wenn die Angebotsbildung ohne Preisbildung betrieben wird (Tests der Anfrageerfassung, WP-M2-11). */
    val offer: IssuedOffer? = null,
)

/** Angebotsobjekt in der Antwort der Anfrage: ID, Ablauf, unveränderlicher Preisstand, Haltepunkte (Revision) und Planbindung. Es verspricht keine Kapazität. */
data class IssuedOffer(
    val id: UUID, val expiresAt: Instant, val quote: QuoteView, val cancellationPolicyRef: String, val pickupStop: OfferStop?, val dropoffStop: OfferStop?,
    val runRevision: Long?, val revision: Long,
)

/** Normierter Ablehnungsgrund (WP-M2-13): genau ein Primärgrund, weitere Ursachen als Sekundärgründe. */
data class RideRejection(val primary: RejectionCode, val secondary: List<RejectionCode> = emptyList())

/**
 * Antwort der Anfrage: [attemptId] ist der `travel_attempt`; [retry] zeigt, dass ein technischer Retry das gespeicherte Ergebnis des ersten Versuchs wiederholt. Entweder liefert
 * [offers] genau ein Zeitfenster oder die Liste ist leer und [rejection] nennt den Grund.
 */
data class RideOfferResult(val attemptId: UUID, val retry: Boolean, val outcome: AttemptOutcome, val offers: List<RideOfferProposal>, val rejection: RideRejection?, val bookingKind: RideBookingKind?)

/**
 * Wirksame Dienstparameter der Anfrage (`/v1/config/effective`, Katalog J.1). Fehlt ein Wert in der Konfiguration, gelten die **Planungsdefaults** aus [Companion] (nicht
 * fachlich freigegeben, im Detailvertrag zu bestätigen); der Wert und seine Herkunft werden nie stillschweigend erfunden: [defaulted] nennt die Parameter, die den Default nutzen.
 */
data class RideOfferParameters(
    val minBookingLeadMin: Int, val maxBookingHorizonDays: Int, val pickupWindowMin: Int, val arrivalGraceMin: Int, val stopWalkMaxM: Int, val defaulted: Set<String> = emptySet(),
    /** Konfigurationsrevision, aus der die Parameter stammen (Preisbindung, `fare_quote.config_revision_id`); `null` = nur Planungsdefaults. */
    val configRevisionId: UUID? = null,
) {
    companion object {
        const val IMMEDIATE_TOLERANCE_MIN = 5L
        val DEFAULTS = RideOfferParameters(minBookingLeadMin = 0, maxBookingHorizonDays = 30, pickupWindowMin = 15, arrivalGraceMin = 5, stopWalkMaxM = 400)
        val NAMES = listOf("min_booking_lead_min", "max_booking_horizon_days", "pickup_window_min", "arrival_grace_min", "stop_walk_max_m")
    }
}

/** Direktfahrzeit zwischen Start und Ziel in Sekunden für die Fensterbildung (Routingadapter folgt mit der Tarif-/Angebotsbildung). */
fun interface DirectTravelTime {
    fun seconds(origin: GeoPoint, destination: GeoPoint): Int
}

/**
 * Planungsannahme für die Fensterbildung ohne Routing-Worker: Großkreisentfernung mal [detourFactor] bei [speedKmh] (Luftlinie ist nie die Fahrzeit der späteren Zusage; das
 * verbindliche Fenster entsteht erst mit der Einfügeheuristik, WP-M2-17 bis -19). Mindestens [minSeconds].
 */
class HaversineTravelTime(private val speedKmh: Double = 30.0, private val detourFactor: Double = 1.3, private val minSeconds: Int = 120) : DirectTravelTime {
    override fun seconds(origin: GeoPoint, destination: GeoPoint): Int {
        val r = 6_371_000.0
        val p1 = Math.toRadians(origin.latitude); val p2 = Math.toRadians(destination.latitude)
        val dp = p2 - p1; val dl = Math.toRadians(destination.longitude - origin.longitude)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val meters = 2 * r * atan2(sqrt(a), sqrt(1 - a)) * detourFactor
        return max(minSeconds, (meters / (speedKmh / 3.6)).toInt())
    }
}

/**
 * Eingang der Kandidatensuche nach bestandener Vorprüfung (WP-M2-12). Das Tor ([CandidateGate]) ist der Übergabepunkt zur Fahrzeugsuche; eine abgelehnte Anfrage erreicht es nie.
 */
data class CandidateSearchRequest(val serviceId: UUID, val demand: ResourceVector, val pickupStopId: UUID?, val pickupWindow: RideTimeWindow, val arrivalWindow: RideTimeWindow)

/** Ergebnis der Kandidatensuche: gefunden oder nicht erfüllbar mit den festgestellten Gründen (Kapazität, Personal, Ausstattung). */
sealed interface CandidateGateResult {
    data object Reached : CandidateGateResult
    /** Kandidat gefunden und an eine Planrevision gebunden (WP-M2-21): das Angebot hält später genau gegen diesen Umlauf, diese Revision und dieses Abschnittsintervall. */
    data class Assigned(val assignment: OfferAssignment) : CandidateGateResult
    data class NoCandidate(val causes: List<RejectionCode>) : CandidateGateResult
}

/**
 * Tor zur Kandidatensuche (WP-M2-12/-13). Eine technische Störung (z. B. Routing-Timeout) wird als Ausnahme gemeldet: der Versuch wird mit Ergebnis `TECHNICAL_ERROR`
 * erfasst und **nicht** als fachliche Ablehnung gezählt. Die Standardumsetzung reicht alle zulässigen Anfragen durch.
 */
fun interface CandidateGate {
    fun check(ctx: RequestContext, request: CandidateSearchRequest): CandidateGateResult

    companion object {
        val PASS_THROUGH = CandidateGate { _, _ -> CandidateGateResult.Reached }

        /** Tor über den Fahrzeugkandidatenfilter (WP-M2-15): Kategorien der Filter-Ablehnung werden auf Katalogcodes abgebildet. Ohne Abholhaltepunkt keine Prüfung möglich: Reached. */
        fun overVehicleCandidates(candidates: VehicleCandidates): CandidateGate = de.taktovia.bookingdispatch.internal.VehicleCandidateGate(candidates)
    }
}

/**
 * Anfrageerfassung (WP-M2-11, LH-6.4-BKG-001), Zulässigkeitsprüfung (WP-M2-12, OPT-001) und standardisierte Ablehnung (WP-M2-13, OPT-011) von `POST /v1/ride-offers`.
 *
 * Ablauf: (1) formale Prüfung (422: Personenzahl, Zeitzone, Zeitwunsch nach Kap. 05 §5.1 inkl. DST-Lücke und Doppelstunde ohne Offset, Schlüssel); (2) Wiederholungserkennung über
 * `Idempotency-Key` + Inhalts-Fingerprint; (3) Zeitfenster aus `pickup_window_min`/`arrival_grace_min`, Prüfung von Vorlauf und Horizont; (4) Zulässigkeit; (5) Kandidatensuche
 * über das Tor; (6) `travel_attempt` und Ereignis `booking.attempt_recorded` in **einer** Transaktion. Fachliche Ablehnungen sind **kein** Fehler: leere Angebotsliste plus Grund.
 */
interface RideOfferRequests {
    fun request(ctx: RequestContext, cmd: RideOfferCommand): RideOfferResult

    companion object {
        fun create(
            ctxTx: ContextTransactions, outbox: Outbox, geo: ServicegeoApi, config: EffectiveConfig, clock: Clock = Clock.systemUTC(),
            travelTime: DirectTravelTime = HaversineTravelTime(), gate: CandidateGate = CandidateGate.PASS_THROUGH,
            /** Preisbildung (WP-M2-09): gesetzt = jedes Angebot erhält einen unveränderlichen `fare_quote` und ein Angebotsobjekt (WP-M2-21); `null` = nur Zeitfenster (Anfrageerfassung allein). */
            quotes: FareQuotes? = null, offerPolicy: OfferPolicy = OfferPolicy(), distance: DirectDistance = HaversineDistance(),
        ): RideOfferRequests = RideOfferServicesFactory.create(ctxTx, outbox, geo, config, clock, travelTime, gate, quotes, offerPolicy, distance)
    }
}


/**
 * Regel für Gültigkeit und Tarif der Angebote (Planungsannahmen, kein Katalogparameter: M2 OP-20): [validity] ab Ausstellung (Standard 300 s, `m2-detailvertraege.md` §6), Tarifklasse und
 * Stornoregel-Verweis des Preisstands. Die Uhrenreserve gegenüber dem Client liegt beim Client; serverseitig gilt `now >= expires_at` ohne Toleranz als abgelaufen.
 */
data class OfferPolicy(
    val validity: java.time.Duration = java.time.Duration.ofSeconds(300), val tariffClass: String = de.taktovia.farepayment.api.DEFAULT_TARIFF_CLASS,
    val cancellationPolicyRef: String = de.taktovia.farepayment.api.DEFAULT_CANCELLATION_POLICY_REF,
) { init { require(!validity.isNegative && !validity.isZero) { "Angebotsgültigkeit muss positiv sein" } } }

/** Tarifliche Strecke in Metern für die Bepreisung (Routingadapter folgt mit der Einfügeheuristik; bis dahin Planungsschätzung wie [DirectTravelTime]). */
fun interface DirectDistance {
    fun meters(origin: GeoPoint, destination: GeoPoint): Long
}

/** Großkreisentfernung mal [detourFactor] (Planungsannahme, nicht die Zusage; die tatsächliche Strecke liefert später das Routing). */
class HaversineDistance(private val detourFactor: Double = 1.3) : DirectDistance {
    override fun meters(origin: GeoPoint, destination: GeoPoint): Long {
        val r = 6_371_000.0
        val p1 = Math.toRadians(origin.latitude); val p2 = Math.toRadians(destination.latitude)
        val dp = p2 - p1; val dl = Math.toRadians(destination.longitude - origin.longitude)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return max(1L, (2 * r * atan2(sqrt(a), sqrt(1 - a)) * detourFactor).toLong())
    }
}
