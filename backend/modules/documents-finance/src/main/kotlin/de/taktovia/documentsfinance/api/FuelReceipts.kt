package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Kraftstoffart eines Tankvorgangs (WP-M4-30, DOC-005). Die Einheit folgt der Art: Liter und kWh werden getrennt geführt (Kap. 03 §2.1). */
enum class FuelType(val unit: FuelUnit) { PETROL(FuelUnit.LITER), DIESEL(FuelUnit.LITER), LPG(FuelUnit.LITER), ELECTRIC(FuelUnit.KWH) }
enum class FuelUnit { LITER, KWH }

/** Feldnamen der Pflichtwerte eines Tankbelegs (Fehlerzeiger `/fuel/<feld>`). Brutto und Währung sind Belegfelder ([ExpenseFields.AMOUNT_MINOR], [ExpenseFields.CURRENCY]). */
object FuelFields {
    const val VEHICLE_ID = "vehicle_id"
    const val FUEL_TYPE = "fuel_type"
    const val QUANTITY = "quantity"
    const val UNIT_PRICE = "unit_price"
    const val ODOMETER_KM = "odometer_km"
    /** Pflichtwerte, ohne die ein Tankvorgang nicht freigabefähig ist (Kraftstoffart, Menge, Preis je Einheit, Kilometerstand, Fahrzeug; der Brutto ist Pflichtfeld des Belegs). */
    val REQUIRED = listOf(VEHICLE_ID, FUEL_TYPE, QUANTITY, UNIT_PRICE, ODOMETER_KM)
}

/**
 * Tankvorgang eines Belegs der Art [DocumentKind.FUEL_RECEIPT]. Mengen und Preise sind [BigDecimal] (nie Gleitkomma; in der API Dezimalstrings). `missing` nennt die fehlenden Pflichtwerte einzeln
 * (Namen aus [FuelFields]); `grossMinor` und `currency` spiegeln den Belegstand.
 */
data class FuelView(
    val vehicleId: UUID?, val fuelType: FuelType?, val quantity: BigDecimal?, val unit: FuelUnit?, val unitPrice: BigDecimal?, val grossMinor: Long?, val currency: String?,
    val odometerKm: Long?, val fueledAt: Instant?, val cardId: String?, val missing: List<String>,
) { val complete: Boolean get() = missing.isEmpty() }

/**
 * Erfassung der Tankwerte: gesetzt wird, was nicht `null` ist (die übrigen Werte bleiben); mindestens ein Wert. Menge und Preis: positiv, höchstens 8 Nachkommastellen und 12 Vorkommastellen,
 * Kilometerstand 0 bis 10.000.000, Karten-ID `[A-Za-z0-9._-]{1,64}`, Tankzeit höchstens einen Tag in der Zukunft.
 */
data class CaptureFuel(
    val vehicleId: UUID? = null, val fuelType: FuelType? = null, val quantity: BigDecimal? = null, val unitPrice: BigDecimal? = null, val odometerKm: Long? = null,
    val fueledAt: Instant? = null, val cardId: String? = null,
)

/**
 * Tankbeleg-Fachdaten (WP-M4-30, DOC-005): Kraftstoffart, Menge mit Einheit, Preis je Einheit, Kilometerstand, Fahrzeug und optionale Tankkarten-ID zu einem Beleg der Art `FUEL_RECEIPT`. Die Erfassung
 * erzeugt wie jede Belegänderung genau eine neue Revision (Alt- und Neuwert in `document_revision`, Eintrag `fuel`), nur im Entwurfszustand `STORED`; der Brutto ist der Belegstand und wird gespiegelt.
 * Fehlende Pflichtwerte werden einzeln in [FuelView.missing] gemeldet und blockieren die Freigabe ([ReleaseBlocker.FUEL_FIELDS_MISSING]), solange `fuel_receipt_required` gilt. Die Erfassung durch
 * den Fahrer ersetzt nie die Finanzprüfung: sie setzt weder Freigabe noch Buchung.
 */
interface FuelReceipts {
    /**
     * Setzt die Tankwerte (`expectedRevision` = `If-Match`, veraltet 412). Beleg einer anderen Art: 422; fremdes oder unbekanntes Fahrzeug: 404 ohne Existenzhinweis; ein freigegebener oder gebuchter
     * Beleg: 409 `IMMUTABLE_RECORD`. Gleiche Werte erzeugen keine neue Revision. Der Prüflauf der Tankplausibilität (WP-M4-31) läuft in derselben Transaktion.
     */
    fun capture(ctx: RequestContext, documentId: UUID, expectedRevision: Long, cmd: CaptureFuel): ExpenseView

    /** Tankwerte eines Belegs der Art `FUEL_RECEIPT` (Erfasser und Finanzrolle; sonst 404); `null` für Belege anderer Art. */
    fun get(ctx: RequestContext, documentId: UUID): FuelView?
}

/** Zuordnung einer Tankkarten-ID zu einem Fahrzeug mit Gültigkeitsfenster (`fuel_card_mapping`); je Karte überlappen sich Fenster nie (eindeutig pro Gültigkeitsfenster). `validTo` = `null`: unbefristet. */
data class FuelCardMapping(val cardId: String, val vehicleId: UUID, val validFrom: java.time.LocalDate, val validTo: java.time.LocalDate? = null)

/**
 * Schwellen und Vergleichsbasen der Tankplausibilität (WP-M4-31). `null`/leer = nicht konfiguriert: die jeweilige Regel ist dann nicht prüfbar und wird als Vermerk ausgewiesen, nie als bestanden gewertet.
 * `priceReference` = Vergleichspreis je Kraftstoffart (Planungsannahme: konfigurierte Vergleichsbasis, solange es keine historischen Preisvergleichsdaten gibt, Offener Punkt 16).
 */
data class FuelParameters(
    val maxLiters: java.math.BigDecimal? = null, val priceAlertPct: java.math.BigDecimal? = null, val useAlertPct: java.math.BigDecimal? = null,
    val priceReference: Map<FuelType, java.math.BigDecimal> = emptyMap(), val cardMapping: List<FuelCardMapping> = emptyList(),
)

/** Fachparameter des Moduls je Mandant (Planungsstand, D-06). Ohne gespeicherte Werte gelten die Vorgaben. */
data class FinancePolicyView(
    /** `fuel_receipt_required`: true (Vorgabe) = Tankbeleg erst mit vollständigen Tankwerten freigabefähig; false = unvollständige Tankwerte blockieren nicht (Offener Punkt 39, Annahme). */
    val fuelReceiptRequired: Boolean = true, val revision: Long = 0,
    val fuel: FuelParameters = FuelParameters(),
)

/** `fuel` ersetzt den gesamten Satz der Tankparameter (nicht Angegebenes wird nicht konfiguriert); `null` lässt ihn unverändert. */
data class UpdateFinancePolicy(val fuelReceiptRequired: Boolean? = null, val fuel: FuelParameters? = null)

/** Schwellen je Fahrzeug (`fuel_max_liters`: an die Tankgröße gebunden; `fuel_use_alert_pct`: je Fahrzeugtyp); `null` = der Wert des Mandanten gilt. */
data class FuelVehiclePolicy(val vehicleId: UUID, val maxLiters: java.math.BigDecimal?, val useAlertPct: java.math.BigDecimal?, val revision: Long)

/** Fachparameter des Moduls (nur Finanzrolle, Scope `receipts:write`; Audit `finance.policy.changed`). Nicht über HTTP erreichbar (Pflege durch die Verwaltung). */
interface FinancePolicies {
    fun get(ctx: RequestContext): FinancePolicyView
    fun update(ctx: RequestContext, cmd: UpdateFinancePolicy): FinancePolicyView

    /** Setzt die Schwellen eines Fahrzeugs (fremde und unbekannte Fahrzeuge: 404). */
    fun putVehicle(ctx: RequestContext, vehicleId: UUID, maxLiters: java.math.BigDecimal?, useAlertPct: java.math.BigDecimal?): FuelVehiclePolicy
}
