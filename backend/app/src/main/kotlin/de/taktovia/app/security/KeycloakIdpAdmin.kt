package de.taktovia.app.security

import de.taktovia.identity.api.IdpAdmin
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * IdP-Administration über die Keycloak-Admin-REST-API (WP-M1-10, LH-6.2-IAM-009) mit eigenem, eng berechtigtem Dienstkonto
 * (`taktovia-idp-admin`, Client Credentials; Rollen nur `view-users`, `manage-users` im Realm, keine Realm-Verwaltung). Das Secret kommt aus
 * der Secret-Verwaltung bzw. der Umgebung, nie aus dem Repository ([clientSecret] wird je Tokenabruf gelesen, Rotation ohne Neustart).
 *
 * Operationen: alle Sitzungen eines Kontos beenden (`POST users/{id}/logout`), eine Sitzung beenden (`DELETE sessions/{id}`), Konto
 * sperren/entsperren (`PUT users/{id}` mit `enabled`), Kontakt prüfen (`GET users/{id}`: `emailVerified`) und Wiederherstellung anstoßen
 * (`PUT users/{id}/execute-actions-email` mit `UPDATE_PASSWORD`, geht nur an den im IdP geführten Kontakt). Jeder Fehler ist [IdpAdmin.Unavailable].
 * Gegen einen echten Keycloak **nicht ausgeführt** (kein Docker); die HTTP-Verträge prüft `KeycloakIdpAdminTest` gegen einen lokalen Ersatzserver.
 */
class KeycloakIdpAdmin(
    private val baseUrl: String, private val realm: String, private val issuer: String, private val clientId: String,
    private val clientSecret: () -> String, private val clock: Clock = Clock.systemUTC(),
    private val timeout: Duration = Duration.ofSeconds(5), private val recoveryLifespan: Duration = Duration.ofHours(1),
) : IdpAdmin {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = JsonMapper()
    private val admin = baseUrl.trimEnd('/') + "/admin/realms/" + realm
    @Volatile private var token: Pair<String, Long>? = null

    override fun endAllSessions(issuer: String, subject: String) { sameIssuer(issuer); send("POST", "/users/${uuid(subject)}/logout", null, ok = setOf(204, 404)) }

    override fun endSession(issuer: String, idpSessionId: String) {
        sameIssuer(issuer)
        require(SID.matches(idpSessionId)) { "ungültige Sitzungskennung" }
        send("DELETE", "/sessions/$idpSessionId", null, ok = setOf(204, 404))   // 404: Sitzung im IdP schon beendet
    }

    override fun setAccountEnabled(issuer: String, subject: String, enabled: Boolean) {
        sameIssuer(issuer); send("PUT", "/users/${uuid(subject)}", json.writeValueAsString(mapOf("enabled" to enabled)), ok = setOf(204))
    }

    override fun contactVerified(issuer: String, subject: String): Boolean {
        sameIssuer(issuer)
        val user: JsonNode = json.readTree(send("GET", "/users/${uuid(subject)}", null, ok = setOf(200)))
        return user["emailVerified"]?.asBoolean() == true && !user["email"]?.asString().isNullOrBlank()
    }

    override fun startRecovery(issuer: String, subject: String) {
        sameIssuer(issuer)
        send("PUT", "/users/${uuid(subject)}/execute-actions-email?lifespan=${recoveryLifespan.seconds}", json.writeValueAsString(listOf("UPDATE_PASSWORD")), ok = setOf(204))
    }

    private fun sameIssuer(i: String) { if (i != issuer) throw IdpAdmin.Unavailable("Issuer gehört nicht zu diesem IdP") }
    private fun uuid(s: String): String = runCatching { UUID.fromString(s).toString() }.getOrElse { throw IdpAdmin.Unavailable("Subject ist keine Kennung des IdP") }

    private fun send(method: String, path: String, body: String?, ok: Set<Int>): String {
        try {
            val req = HttpRequest.newBuilder(URI.create(admin + path)).timeout(timeout)
                .header("Authorization", "Bearer " + accessToken()).header("Accept", "application/json")
                .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                .also { if (body != null) it.header("Content-Type", "application/json") }.build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 401) token = null
            if (res.statusCode() !in ok) throw IdpAdmin.Unavailable("IdP-Administration antwortete mit Status ${res.statusCode()}")
            return res.body()
        } catch (e: IdpAdmin.Unavailable) { throw e } catch (e: Exception) {
            log.warn("IdP-Administration {} {} fehlgeschlagen ({})", method, path.substringBefore('?').replace(Regex("[0-9a-fA-F-]{36}"), "{id}"), e.javaClass.simpleName)
            throw IdpAdmin.Unavailable("IdP-Administration nicht erreichbar", e)
        }
    }

    /** Dienstkonto-Token (Client Credentials), bis kurz vor Ablauf wiederverwendet. */
    private fun accessToken(): String {
        val cached = token
        val now = clock.instant().epochSecond
        if (cached != null && cached.second > now + 10) return cached.first
        val form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, Charsets.UTF_8) + "&client_secret=" + URLEncoder.encode(clientSecret(), Charsets.UTF_8)
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/realms/$realm/protocol/openid-connect/token")).timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IdpAdmin.Unavailable("Dienstkonto-Anmeldung am IdP abgelehnt (Status ${res.statusCode()})")
        val node = json.readTree(res.body())
        val t = node["access_token"]?.asString() ?: throw IdpAdmin.Unavailable("IdP lieferte kein Token")
        token = t to now + (node["expires_in"]?.asLong() ?: 60)
        return t
    }

    private companion object { val SID = Regex("[A-Za-z0-9._-]{8,128}") }
}
