package de.taktovia.app.contract

import de.taktovia.app.payment.PspEventEnv
import de.taktovia.app.payment.PspTestKeys
import de.taktovia.platform.webhook.WebhookSignature
import java.time.Instant

/** Vertragsfälle des Zahlungsmoduls (WP-M2-29): `receiveProviderEvent` ohne Bearer-Token, nur mit PSP-Signatur, gegen die Anwendung mit eingeschaltetem Ressourcenserver. */
internal object PaymentContractCases {
    private val secret = java.util.UUID.randomUUID().toString().toByteArray()   // je Lauf zufällig, nie ein Wert im Repository

    private fun request(provider: String, body: String, key: ByteArray, skew: Long = 0): ContractRequest {
        val ts = Instant.now().plusSeconds(skew)
        return ContractRequest("POST", "/v1/payments/provider-events/$provider", body,
            mapOf("X-Provider-Signature" to "t=${ts.epochSecond},v1=${WebhookSignature.hmacHex(key, ts, body.toByteArray())}", "X-Provider-Timestamp" to ts.epochSecond.toString()))
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("receiveProviderEvent",
            202, { val p = PspEventEnv.provider(); PspTestKeys.register(PspEventEnv.tenant(), p, secret); request(p, PspEventEnv.body(), secret) },
            401, { val p = PspEventEnv.provider(); PspTestKeys.register(PspEventEnv.tenant(), p, secret); request(p, PspEventEnv.body(), "falscher-schluessel-0123456789abcd".toByteArray()) },
            server = { PspEventEnv.server }),
    )
}
