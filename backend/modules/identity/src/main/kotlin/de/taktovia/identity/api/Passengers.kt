package de.taktovia.identity.api

import de.taktovia.identity.internal.PassengerServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Fahrgastkonto, Profil und Einwilligung (WP-M1-28, -29, -30; LH-6.2-IAM-001, -006, -011). Detailverträge: `docs/umsetzungsplan/vertraege/`
 * `LH-6.2-IAM-001.md`, `LH-6.2-IAM-006.md`, `LH-6.2-IAM-011.md` und `fahrgastkonto.md`. Der Fahrgast ist Mitglied (Rolle `passenger`) im Mandanten;
 * sein Konto im IdP (`issuer`, `subject`) ist an die Zeile in `passenger_private` gebunden. Kontaktdaten liegen nur verschlüsselt (Feldschutz).
 */
enum class ContactChannel { EMAIL, PHONE }
enum class ChannelStatus { UNVERIFIED, VERIFIED }

/** Fachliche Parameter mit Vorschlagswerten (OP-23: im Pflichtenheft nicht beziffert; Beschluss vor Produktivschaltung). */
data class PassengerSettings(
    val codeLength: Int = 6,
    val codeTtl: Duration = Duration.ofMinutes(10),
    /** Falsche Codeeingaben je Verifikationsaufgabe, danach gesperrt. */
    val maxAttempts: Int = 5,
    /** Neue Verifikationsaufgaben je Fahrgast und Kanal in der letzten Stunde. */
    val maxStartsPerHour: Int = 5,
    /** Sperrzeit nach Überschreiten des Versuchslimits. */
    val lockout: Duration = Duration.ofMinutes(15),
    /** `retention_until` eines neuen Kontos (D-11 offen, Planungswert). */
    val retention: Duration = Duration.ofDays(1095),
    /** Zulässige Sprachen (im Pflichtenheft nicht aufgelistet, Planungswert). */
    val locales: Set<String> = setOf("de-DE", "en-GB"),
    /** Aktuelle Textversion der Marketingeinwilligung (Herkunft und Pflege nicht festgelegt). */
    val consentTextVersion: String = "marketing-2026-1",
) {
    init {
        require(codeLength in 6..10 && maxAttempts in 1..10 && maxStartsPerHour in 1..20 && !codeTtl.isNegative && !codeTtl.isZero)
    }
}

/** HMAC-Schlüssel für Kontakt-Token und Codes (aus der Secret-Verwaltung, mindestens 32 Byte). */
class ContactKey(secret: ByteArray) {
    internal val bytes: ByteArray = secret.copyOf()
    init { require(bytes.size >= 32) { "Schlüssel für Kontakt-Token muss mindestens 32 Byte lang sein" } }
    override fun toString() = "ContactKey"
}

/** Versandweg des Verifikationscodes. Der Code darf nie protokolliert werden. In M1: Test-Adapter; produktiver SMS-Kanal mit M2 (`notification`). */
fun interface VerificationSender {
    /** Wirft [DeliveryUnavailableException], wenn kein Versand möglich ist. */
    fun send(channel: ContactChannel, destination: String, code: String)
}
class DeliveryUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Test-SMS-Adapter und Test-E-Mail-Ablage (M1, Entwicklung und Tests): hält die gesendeten Codes im Speicher, nirgends sonst. Kein produktiver Versand.
 */
class InMemoryVerificationSender : VerificationSender {
    data class Sent(val channel: ContactChannel, val destination: String, val code: String) { override fun toString() = "Sent(channel=$channel)" }
    private val sent = java.util.concurrent.CopyOnWriteArrayList<Sent>()
    override fun send(channel: ContactChannel, destination: String, code: String) { sent += Sent(channel, destination, code) }
    fun all(): List<Sent> = sent.toList()
    fun last(channel: ContactChannel, destination: String): String? = sent.lastOrNull { it.channel == channel && it.destination == destination }?.code
    fun clear() = sent.clear()
}

/** Wiederherstellung anstoßen (E-Mail über den IdP, Telefon über den SMS-Kanal); wird nur für einen **verifizierten** Kanal aufgerufen. */
fun interface RecoveryDispatch {
    fun dispatch(issuer: String, subject: String, channel: ContactChannel, destination: String)
}

/** Der Aufrufer einer Registrierung: das geprüfte Konto des Tokens (noch ohne Mitgliedschaft). */
class RegistrationCaller(val issuer: String, val subject: String, val correlationId: UUID) { override fun toString() = "RegistrationCaller" }

data class ContactInput(val channel: ContactChannel, val value: String) { override fun toString() = "ContactInput(channel=$channel)" }

/** Minimierte Sicht auf ein Fahrgastkonto (Betreiberliste und Ressourcenkopf): keine Kontaktdaten, kein Unterstützungsbedarf. */
data class PassengerSummary(val id: UUID, val status: String, val locale: String, val createdAt: Instant, val revision: Long)

data class VerificationInfo(val channel: ContactChannel, val expiresAt: Instant)
data class Registered(val passenger: PassengerSummary, val verification: VerificationInfo)

/** Antwort der Wiederherstellungsanfrage: für bekannte, unbekannte und unverifizierte Kontakte identisch (keine Kontoenumeration). */
object RecoveryAcknowledged { override fun toString() = "RecoveryAcknowledged" }

data class ChannelView(val value: String?, val status: ChannelStatus, val verifiedAt: Instant?)
data class FavoriteView(val label: String, val stopId: UUID)
data class ProfileView(
    val id: UUID, val status: String, val locale: String, val createdAt: Instant, val revision: Long,
    val email: ChannelView?, val phone: ChannelView?,
    val favorites: List<FavoriteView>, val preferredStops: List<UUID>, val notificationChannels: List<String>,
)

/** Änderung des Profils: nur gesetzte Bereiche ändern sich; Listen ersetzen den jeweiligen Bereich vollständig. */
class ProfilePatch(
    val locale: String? = null, val email: String? = null, val phone: String? = null,
    val favorites: List<FavoriteView>? = null, val preferredStops: List<UUID>? = null, val notificationChannels: List<String>? = null,
) {
    val isEmpty get() = locale == null && email == null && phone == null && favorites == null && preferredStops == null && notificationChannels == null
    override fun toString() = "ProfilePatch"
}

interface PassengerAccounts {
    /**
     * Legt das Fahrgastkonto des geprüften Kontos im Mandanten an (Konto, Kontaktkanal `UNVERIFIED`, Mitgliedschaft `passenger`, Verifikationsaufgabe,
     * Audit in einer Transaktion), schreibt danach den verschlüsselten Kontakt und versendet den Code. Fehler (alle [de.taktovia.platform.problem.ProblemException]):
     * 422 ungültiger Kontakt/Sprache, 409 Konto oder Kontakt bereits vergeben (nicht unterscheidbar), 403 Mandant nicht aktiv.
     */
    fun register(caller: RegistrationCaller, tenant: TenantId, locale: String, contact: ContactInput): Registered

    /** Startet eine neue Verifikation für den Kanal des eigenen Kontos (ersetzt eine offene). 404 ohne Konto/Kanal, 409 bei geändertem Kontakt, 429 über dem Limit. */
    fun startVerification(ctx: RequestContext, channel: ContactChannel): VerificationInfo

    /** Löst den Code ein: 422 falsch/abgelaufen/verbraucht, 429 nach zu vielen Fehlversuchen (mit Sicherheitsereignis). Ein Code wirkt höchstens einmal. */
    fun confirmVerification(ctx: RequestContext, channel: ContactChannel, code: String): ChannelView

    /** Wiederherstellung: nur über einen verifizierten Kanal; die Antwort ist immer dieselbe. Ohne Anmeldung nutzbar (Port für den IdP-Ablauf). */
    fun requestRecovery(tenant: TenantId, channel: ContactChannel, value: String, correlationId: UUID): RecoveryAcknowledged

    /** Eigenes Konto zum Mandanten des Kontexts (Objektbindung): `null`, wenn das Konto des Akteurs kein Fahrgastkonto hat. */
    fun own(ctx: RequestContext): PassengerSummary?

    /** Betreibersicht, minimiert (Kap. 01 §1.7), sortiert nach (`created_at`, `id`), höchstens [limit]+1 Zeilen. */
    fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<PassengerSummary>
}

interface PassengerProfiles {
    /** Eigenes Profil (404, wenn das Konto des Akteurs kein Fahrgastkonto hat). */
    fun get(ctx: RequestContext): ProfileView

    /** Ändert das eigene Profil mit `If-Match`-Revision (412 bei veralteter Revision, 422 bei ungültigen Werten, 409 bei vergebenem Kontakt). */
    fun update(ctx: RequestContext, expectedRevision: Long, patch: ProfilePatch): ProfileView

    /** Eigene Auswahl des Unterstützungsbedarfs ersetzen (nur Katalogcodes, kein Freitext); verschlüsselt über den Feldschutz. */
    fun replaceSupportNeeds(ctx: RequestContext, codes: List<String>)

    /** Eigene Auswahl des Unterstützungsbedarfs lesen. */
    fun readSupportNeeds(ctx: RequestContext): List<String>
}

/** Fest vorgegebene Zweckklassen der Nachrichtenzustellung. */
enum class ConsentPurpose { OPERATIONAL_SAFETY, MARKETING }

data class ConsentState(val purpose: ConsentPurpose, val granted: Boolean, val changeable: Boolean, val recordedAt: Instant?, val textVersion: String?)
data class ConsentRevisionView(val id: UUID, val purpose: ConsentPurpose, val sequence: Long, val granted: Boolean, val recordedAt: Instant, val textVersion: String)
data class PrivacySettings(val currentTextVersion: String, val consents: List<ConsentState>, val history: List<ConsentRevisionView>)

interface ConsentService {
    /** Aktueller Stand je Zweck und die bisherigen Revisionen (neueste zuerst, höchstens 50). */
    fun settings(ctx: RequestContext): PrivacySettings

    /** Legt eine neue Revision an (Einwilligung oder Widerruf); ändert nie eine bestehende. 422 bei nicht änderbarem Zweck oder veralteter Textversion. */
    fun record(ctx: RequestContext, purpose: ConsentPurpose, granted: Boolean, textVersion: String): ConsentRevisionView
}

/**
 * Port für den Benachrichtigungsdienst (WP-M2-32): darf an den Fahrgast zum Zweck zugestellt werden? `OPERATIONAL_SAFETY` ist immer erlaubt,
 * `MARKETING` nur bei jüngster Revision mit `granted=true` (ohne Revision: nicht erlaubt). Entscheidung aus dem festgeschriebenen Stand der Datenbank.
 */
fun interface DeliveryPolicy {
    fun isDeliveryAllowed(tenant: TenantId, passengerId: UUID, purpose: ConsentPurpose): Boolean
}

/** Öffentliche Ports des Fahrgastkontos (eigene Fabrik, damit [IdentityApi] unverändert bleibt). */
interface PassengerApi {
    val accounts: PassengerAccounts
    val profiles: PassengerProfiles
    val consents: ConsentService
    val delivery: DeliveryPolicy

    companion object {
        /**
         * [fieldTx] ist die Transaktionsklammer des Modulzugangs `taktovia_fields` (einziger Zugriff auf die Ciphertext-Spalten), [store] der Feldschutzbaustein.
         * [ctxTx] wird aus [tx] und dem Revalidator der Identität gebildet.
         */
        fun create(
            identity: IdentityApi, tx: TenantTransactions, fieldTx: TenantTransactions, store: ProtectedColumnStore, audit: AuditPort, security: SecurityEventPort,
            outbox: Outbox, key: ContactKey, sender: VerificationSender, recovery: RecoveryDispatch, settings: PassengerSettings = PassengerSettings(),
            clock: Clock = Clock.systemUTC(),
        ): PassengerApi = PassengerServicesFactory.create(identity, tx, fieldTx, store, audit, security, outbox, key, sender, recovery, settings, clock)
    }
}
