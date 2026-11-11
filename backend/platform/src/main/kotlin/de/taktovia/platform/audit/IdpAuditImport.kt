package de.taktovia.platform.audit

import de.taktovia.platform.tenant.PlatformTenants
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Anmeldeereignis des IdP (Keycloak-Ereignisschnittstelle) in der für das Audit zulässigen Form. Es trägt bewusst **keine** Felder für
 * Passwort, Code, IP-Adresse oder andere Details; [details] enthält nur die Whitelist aus [KeycloakEvents]. Die Textform nennt nur Id und Typ.
 */
class IdpEvent(
    val eventId: String, val type: String, val issuer: String, val userId: String?, val loginName: String?, val clientId: String?,
    val error: String?, val occurredAt: Instant, val details: Map<String, String> = emptyMap(),
) {
    override fun toString() = "IdpEvent(id=$eventId, type=$type)"
}

/** Ordnet ein IdP-Konto den Mandanten mit aktiver Mitgliedschaft zu (Implementierung im Identitätsbaustein); leer = kein Mandant bekannt. */
fun interface IdpTenantResolver {
    fun tenantsOf(issuer: String, subject: String): List<TenantId>
}

/** [inserted] neu geschriebene Zeilen (0 bei doppelter Lieferung), [tenants] die Mandanten, in deren Audit das Ereignis gehört. */
data class IdpImportResult(val inserted: Int, val tenants: List<TenantId>)

/**
 * Übernahme der Anmeldeereignisse des IdP in `audit_event` (WP-M1-12, OP-14; Logins finden nicht im Backend statt). Je Mandant mit aktiver
 * Mitgliedschaft des Kontos ein Eintrag, ohne bekanntes Konto oder Mitgliedschaft im reservierten Systemmandanten. Die Zeilen-Id ist aus
 * Aussteller und Ereignis-ID abgeleitet, daher erzeugt eine doppelte (auch parallele) Lieferung keinen zweiten Eintrag. Die Anmeldekennung
 * erscheint nur als pseudonymer Verweis `login_ref` (gekürzter SHA-256), nie im Klartext; Passwort und Code sind nie Teil des Eintrags.
 */
class IdpAuditImporter(private val tx: TenantTransactions, private val audit: AuditPort, private val resolver: IdpTenantResolver) {

    fun import(e: IdpEvent): IdpImportResult {
        val action = actionOf(e) ?: return IdpImportResult(0, emptyList())
        val ref = loginRef(e)
        val subject = e.userId?.takeIf { ID.matches(it) }
        val actor = ActorRef(if (subject != null) "user:$subject" else "user:unknown-$ref")
        val obj = if (subject != null) ObjectRef("user", subject) else ObjectRef("login", ref)
        val changes = linkedMapOf<String, Any?>(
            "result" to if (action == AuditActions.SECURITY_LOGIN_FAILED || action == AuditActions.SECURITY_MFA_FAILED) "failure" else "success",
            "idp_event_type" to e.type, "login_ref" to ref,
        )
        e.clientId?.takeIf { CLIENT.matches(it) }?.let { changes["client_id"] = it }
        e.error?.let { changes["error"] = if (CODE.matches(it)) it else "other" }
        if (action == AuditActions.SECURITY_MFA_FAILED) changes["second_factor"] = true
        val rowId = UUID.nameUUIDFromBytes("idp-event|${e.issuer}|${e.eventId}".toByteArray(StandardCharsets.UTF_8))
        val entry = AuditEntry(actor, action, obj, UUID.nameUUIDFromBytes("idp-correlation|${e.issuer}|${e.eventId}".toByteArray(StandardCharsets.UTF_8)), changes, e.occurredAt)

        val tenants = (subject?.let { resolver.tenantsOf(e.issuer, it) } ?: emptyList()).ifEmpty { listOf(PlatformTenants.SYSTEM) }
        val inserted = tenants.count { t -> tx.run(t) { j -> audit.recordOnce(j, rowId, entry) } }
        return IdpImportResult(inserted, tenants)
    }

    private fun actionOf(e: IdpEvent): String? = when (e.type) {
        "LOGIN" -> AuditActions.SECURITY_LOGIN_SUCCEEDED
        "LOGOUT" -> AuditActions.SECURITY_LOGOUT
        "LOGIN_ERROR" -> if (isSecondFactor(e)) AuditActions.SECURITY_MFA_FAILED else AuditActions.SECURITY_LOGIN_FAILED
        else -> null
    }

    /** Annahme zur Keycloak-Ereignisform (nicht gegen einen Dienst geprüft): Fehler am OTP-Schritt tragen `otp` in Fehlercode, Credentialtyp oder Authenticator. */
    private fun isSecondFactor(e: IdpEvent) =
        e.error?.contains("otp", true) == true || e.error?.contains("totp", true) == true ||
            e.details["credential_type"].equals("otp", true) || e.details["authenticator"]?.contains("otp", true) == true

    private fun loginRef(e: IdpEvent): String {
        val basis = (e.loginName ?: e.userId ?: "unbekannt").trim().lowercase()
        return HexHash.sha256(basis).take(16)
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9._:-]{1,120}")
        val CLIENT = Regex("[A-Za-z0-9._:-]{1,100}")
        val CODE = Regex("[a-z0-9_.-]{1,64}")
    }
}

internal object HexHash {
    fun sha256(s: String): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)))
}

/**
 * Lesen der Ereignisform des Keycloak-Ereignislisteners (JSON: `id`, `time` in ms, `type`, `clientId`, `userId`, `error`, `details`). Nur die
 * Whitelist wird übernommen; `ipAddress`, `password`, `code` und alle weiteren Details werden verworfen. Nicht auditpflichtige Typen ergeben `null`.
 * Nicht ausgeführt gegen einen echten Keycloak (kein Dienst in dieser Umgebung); der Transportweg (Listener, Webhook) ist offen (OP-14).
 */
object KeycloakEvents {
    private val mapper = JsonMapper.builder().build()
    private val TYPES = setOf("LOGIN", "LOGIN_ERROR", "LOGOUT")

    fun parse(json: String, issuer: String): IdpEvent? {
        val n = mapper.readTree(json)
        val type = n.path("type").asString("")
        if (type !in TYPES) return null
        val id = n.path("id").asString("").ifBlank { throw IllegalArgumentException("Ereignis-ID fehlt (Dedup nicht möglich)") }
        val d = n.path("details")
        val keep = listOf("credential_type", "authenticator").mapNotNull { k -> d.path(k).asString("").takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        return IdpEvent(
            id, type, issuer, n.path("userId").asString("").ifEmpty { null }, d.path("username").asString("").ifEmpty { null },
            n.path("clientId").asString("").ifEmpty { null }, n.path("error").asString("").ifEmpty { null }, Instant.ofEpochMilli(n.path("time").asLong(0)), keep,
        )
    }
}
