package de.taktovia.farepayment.api

import de.taktovia.farepayment.internal.FarepaymentServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Lebenslauf einer Tarifrevision (Detailvertrag `zustaende/tarifrevision.md`): `ENTWURF -> GEPRUEFT -> VEROEFFENTLICHT`, danach unveränderlich. */
enum class TariffState { ENTWURF, GEPRUEFT, VEROEFFENTLICHT }

/** Regeltypen der Tarifengine (WP-M2-08): Festpreis, Zonenpreis (je gerichtetem Zonenpaar), Grundpreis plus Streckenanteil. */
enum class TariffRuleType { FESTPREIS, ZONENPREIS, GRUNDPREIS_STRECKE }

/** Wer wird bepreist: je Person (Standard) oder je Buchung (Personenzahl ohne Einfluss). */
enum class PersonMode { PER_PERSON, PER_BOOKING }

/** Steuermodus der Regel: Beträge enthalten die Steuer (`GROSS`, Standard) oder sie kommt obendrauf (`NET`). */
enum class TaxMode { GROSS, NET }

/**
 * Eine Preisregel einer Revision. Sätze ([ratePerKm]: kleinste Einheit je Kilometer, [taxRate]: 0 bis < 1) sind Dezimalzahlen mit höchstens acht Nachkommastellen
 * und werden als Text übertragen (nie als Gleitkomma). [zoneFrom]/[zoneTo] sind Kennungen von Bediengebieten (Gebietsschlüssel, WP-M2-04/-06); ein Fremdschlüssel ist
 * nicht möglich (der Schlüssel ist in `service_area` nicht eindeutig).
 */
data class TariffRuleView(
    val id: UUID, val ruleNo: Int, val ruleType: String, val personMode: String, val amountMinor: Long, val zoneFrom: UUID?, val zoneTo: UUID?,
    val ratePerKm: String?, val bookingFeeMinor: Long, val taxRate: String, val taxMode: String, val taxRuleRevision: String,
)

/**
 * Tarifrevision eines Dienstes und einer Tarifklasse mit Gültigkeit `validFrom <= t < validTo` (links geschlossen, rechts offen; `validTo == null` = offen).
 * [revision] ist die **Ressourcenrevision** für `ETag`/`If-Match` (zählt jede Änderung), [revisionNo] die fachliche Nummer der Tarifrevision je Dienst und Klasse.
 */
data class TariffRevisionView(
    val id: UUID, val serviceId: UUID, val tariffClass: String, val revisionNo: Int, val state: TariffState, val validFrom: Instant, val validTo: Instant?,
    val currency: String, val reason: String, val revision: Long, val createdBy: String, val createdAt: Instant,
    val reviewedBy: String?, val reviewedAt: Instant?, val publishedBy: String?, val publishedAt: Instant?, val rules: List<TariffRuleView>,
)

/** Eingabe einer Regel in der JSON-Form des HTTP-Rands (ungeprüfte Werte; jede Verletzung wird als 422 mit Feldverweis `/rules/<n>/…` gemeldet). */
data class TariffRuleInput(
    val ruleType: Any?, val personMode: Any? = null, val amountMinor: Any?, val zoneFrom: Any? = null, val zoneTo: Any? = null, val ratePerKm: Any? = null,
    val bookingFeeMinor: Any? = null, val taxRate: Any? = null, val taxMode: Any? = null, val taxRuleRevision: Any?,
)

/** Neuer Entwurf. Pflichtangaben (Dienst, Tarifklasse, Beginn der Gültigkeit, Währung, Grund, mindestens eine Regel) fehlen als `null` und ergeben 422 mit Feldverweis. */
data class CreateTariffRevision(
    val serviceId: UUID?, val tariffClass: String?, val validFrom: Instant?, val validTo: Instant?, val currency: String?, val reason: String?, val rules: List<TariffRuleInput>?,
)

/** Ersetzt den Inhalt eines Entwurfs (Dienst, Tarifklasse und Währung sind unveränderlich). */
data class UpdateTariffRevision(val validFrom: Instant?, val validTo: Instant?, val reason: String?, val rules: List<TariffRuleInput>?)

/**
 * Tarifrevisionen mit Preisregeln und Gültigkeit (WP-M2-07, LH-6.10-PAY-003; Detailvertrag `LH-6.10-PAY-003.md`).
 *  * Pflichtangaben je Revision: Dienst, Tarifklasse, Gültigkeitszeitraum; ohne sie 422 mit Feldverweis, keine Freigabe.
 *  * Änderungen verlangen die erwartete Ressourcenrevision (veraltet: `REVISION_MISMATCH`, 412; im HTTP-Rand fehlendes `If-Match` 428).
 *  * Vier-Augen: wer die Revision angelegt hat, kann sie weder prüfen noch veröffentlichen (403); die Regel steht zusätzlich als CHECK in der Datenbank.
 *  * Nicht überlappende aktive Tarifversion je Dienst und Tarifklasse (Kap. 04 §4.5): eine überlappende Veröffentlichung wird abgewiesen (409), bei zwei parallelen
 *    gewinnt genau eine (Exclusion Constraint). Eine veröffentlichte Revision ist unveränderlich (409 `IMMUTABLE_RECORD`), Korrektur nur als neue Revision.
 *  * Fremder Mandant oder unbekannter Dienst/Revision: 404 (RLS); ohne Funktionsscope 403; jede Änderung schreibt genau einen Auditeintrag in derselben Transaktion.
 */
interface Tariffs {
    fun create(ctx: RequestContext, cmd: CreateTariffRevision): TariffRevisionView
    /** Entwurf ändern; [expectedRevision] = zuletzt gelesene Ressourcenrevision. Nicht mehr im Entwurf: 409 `IMMUTABLE_RECORD`. */
    fun update(ctx: RequestContext, id: UUID, expectedRevision: Long, cmd: UpdateTariffRevision): TariffRevisionView
    /** `ENTWURF -> GEPRUEFT` durch eine zweite Person (nicht der Ersteller). */
    fun review(ctx: RequestContext, id: UUID, expectedRevision: Long): TariffRevisionView
    /** `GEPRUEFT -> VEROEFFENTLICHT` durch eine zweite Person; überlappende aktive Revision: 409. Schreibt das Ereignis `tariff.revision_published`. */
    fun publish(ctx: RequestContext, id: UUID, expectedRevision: Long): TariffRevisionView
    fun get(ctx: RequestContext, id: UUID): TariffRevisionView
    /** Revisionen, optional eines Dienstes und/oder Zustands; stabil sortiert nach `(createdAt, id)`. */
    fun list(ctx: RequestContext, serviceId: UUID?, state: TariffState?, limit: Int, after: Pair<Instant, UUID>?): List<TariffRevisionView>
    /**
     * Zum Preisstichtag [at] wirksame **veröffentlichte** Revision des Dienstes und der Tarifklasse (PA-05: Preisstichtag = Fahrtzeitpunkt) samt allen Regeln oder `null`
     * („kein Tarif“): abgelaufene (`at >= validTo`) und noch nicht gültige (`at < validFrom`) Revisionen werden nie geliefert. Revision und Regeln sind unveränderlich;
     * eine parallele Veröffentlichung liefert daher vollständig die alte oder die neue Revision, nie eine Mischung.
     */
    fun effective(ctx: RequestContext, serviceId: UUID, tariffClass: String, at: Instant): TariffRevisionView?

    companion object {
        fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox = Outbox(), clock: Clock = Clock.systemUTC(), authorizer: ObjectAuthorizer = ServiceScopeAuthorizer): Tariffs =
            FarepaymentServicesFactory.tariffs(ctxTx, audit, outbox, clock, authorizer)
    }
}

/** Ereignistyp der Veröffentlichung (Schema `api/events/tariff.revision_published.v1.schema.json`). */
const val TARIFF_REVISION_PUBLISHED_EVENT = "tariff.revision_published"
