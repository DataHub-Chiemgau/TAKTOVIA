package de.taktovia.farepayment.api

import de.taktovia.farepayment.internal.JdbcProviderEvents
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Signaturschlüssel eines Zahlungsdienstleisters (WP-M2-29). **Mandant und Provider folgen aus dem Schlüssel**, nie aus dem Payload: wer den Schlüssel
 * des Providers für einen Mandanten kennt, kann nur für diesen Mandanten Ereignisse einliefern. Rotation: alter und neuer Schlüssel stehen
 * nebeneinander, der alte mit [validUntil].
 */
class ProviderEventKey(val tenant: TenantId, val provider: String, secret: ByteArray, val validUntil: Instant? = null) {
    private val secret: ByteArray = secret.copyOf()
    fun secret(): ByteArray = secret.copyOf()
    override fun toString() = "ProviderEventKey(provider=$provider)"   // nie der Wert
}

/** Schlüsselquelle (Secret-Verwaltung der Umgebung, Secretreferenzen `secret://<domäne>/<name>`; hier austauschbar). Liefert alle zurzeit denkbaren Schlüssel eines Providers. */
fun interface ProviderEventKeys { fun candidates(provider: String): List<ProviderEventKey> }

enum class ProviderEventRejection { TOO_LARGE, SIGNATURE_INVALID, TIMESTAMP_OUT_OF_WINDOW, MALFORMED, PAYLOAD_CONFLICT }

sealed interface ProviderEventResult {
    /** 202: dauerhaft gespeichert (Commit erfolgt), die fachliche Wirkung ist in derselben Transaktion eingetreten. */
    data class Accepted(val eventId: String) : ProviderEventResult
    /** 200 `ALREADY_RECEIVED`: gleiche Ereignis-ID, gleicher Payload; keine zweite Wirkung. */
    data class AlreadyReceived(val eventId: String) : ProviderEventResult
    /** Abgewiesen; bei [ProviderEventRejection.PAYLOAD_CONFLICT] ist das Sicherheitsereignis geschrieben, der erste Payload bleibt maßgeblich. */
    data class Rejected(val reason: ProviderEventRejection) : ProviderEventResult
}

data class ProviderEventConfig(val maxBodyBytes: Int = 64 * 1024, val tolerance: Duration = Duration.ofSeconds(300))

/**
 * Eingang signierter PSP-Ereignisse (WP-M2-29, `POST /v1/payments/provider-events/{provider}`, Kap. 05 §5.7; Detailvertrag `psp-adapter.md`).
 * Reihenfolge: Größe, Signaturformat, Zeitfenster ±300 s, konstantzeitliche HMAC-Prüfung über den **exakten Rohbody** gegen alle Schlüssel des Providers,
 * Inhaltsprüfung, Speichern in `payment_event` (eindeutig je Mandant, Provider, Ereignis-ID) mit der Wirkung (internes Outbox-Ereignis) in **einer** Transaktion.
 */
interface ProviderEvents {
    fun receive(provider: String, signatureHeader: String?, timestampHeader: String?, rawBody: ByteArray): ProviderEventResult

    companion object {
        /** [payments]: der Zahlungsautomat (WP-M2-30); gesetzt, stellt jedes gespeicherte Ereignis in **derselben** Transaktion einen Abgleich der betroffenen Zahlung ein (Statusabfrage, nie Übergang aus dem Payload). */
        fun create(tx: TenantTransactions, keys: ProviderEventKeys, security: SecurityEventPort, outbox: Outbox, config: ProviderEventConfig = ProviderEventConfig(), clock: Clock = Clock.systemUTC(), payments: Payments? = null): ProviderEvents =
            JdbcProviderEvents(tx, keys, security, outbox, config, clock, payments)
    }
}

/** Abbildung PSP-Ereignistyp auf die interne Art (Detailvertrag `psp-adapter.md`); unbekannte, aber formal gültige Typen sind [UNMAPPED] und ohne Zahlungswirkung. */
enum class ProviderEventKind(val providerType: String?) {
    REQUIRES_ACTION("payment.requires_action"), AUTHORIZED("payment.authorized"), AUTHORIZATION_FAILED("payment.authorization_failed"),
    CAPTURE_PENDING("payment.capture_pending"), CAPTURED("payment.captured"), VOID_PENDING("payment.void_pending"), VOIDED("payment.voided"),
    REFUND_PENDING("payment.refund_pending"), PARTIALLY_REFUNDED("payment.partially_refunded"), REFUNDED("payment.refunded"),
    DISPUTE_OPENED("payment.dispute.opened"), DISPUTE_WON("payment.dispute.won"), DISPUTE_LOST("payment.dispute.lost"), UNMAPPED(null);

    companion object {
        fun of(providerType: String): ProviderEventKind = entries.firstOrNull { it.providerType == providerType } ?: UNMAPPED
    }
}

/** Typ des internen Ereignisses je gespeichertem PSP-Ereignis (Outbox, Schema `api/events/payment.provider_event_received.v1.schema.json`). */
const val PROVIDER_EVENT_RECEIVED = "payment.provider_event_received"

/** Stabile Kennung für Audit-Objektreferenzen (die Ereignis-ID des PSP ist Text und kein UUID). */
fun providerEventObjectId(provider: String, eventId: String): UUID = UUID.nameUUIDFromBytes("$provider/$eventId".toByteArray())
