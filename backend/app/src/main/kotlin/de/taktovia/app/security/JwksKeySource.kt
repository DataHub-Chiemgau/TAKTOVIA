package de.taktovia.app.security

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Die zuletzt geladenen Schlüssel sind zu alt und der IdP ist nicht erreichbar: keine Prüfung möglich (503, kein Durchwinken). */
class KeysUnavailableException(message: String) : RuntimeException(message)

/** Öffentliche Schlüssel des IdP, gefunden über die `kid` aus dem Token-Header. */
fun interface JwksKeySource {
    /** Der Schlüssel oder `null`, wenn der IdP keinen Schlüssel dieser `kid` veröffentlicht. @throws KeysUnavailableException */
    fun key(kid: String): JWK?
}

/**
 * Lädt das JWKS des Issuers per HTTP(S) und hält es kurz im Speicher (Rotation, WP-M1-04):
 *  - Ein geladener Satz gilt `cacheSeconds`; danach wird neu geladen. Ein vom IdP zurückgezogener Schlüssel verschwindet damit spätestens
 *    nach dieser Zeit (und sofort, sobald eine Anfrage mit unbekannter `kid` ein Neuladen auslöst).
 *  - Unbekannte `kid` löst höchstens alle `minRefreshSeconds` ein Neuladen aus (neuer Schlüssel ohne Wartezeit, aber kein Lastangriff).
 *  - Ist der IdP nicht erreichbar, gelten die zuletzt geladenen Schlüssel höchstens `maxStaleSeconds`; danach [KeysUnavailableException].
 *  - Nebenläufige Anfragen laden gemeinsam (ein Abruf je Ablauf). Private Schlüsselteile und unpassende Schlüsselarten werden verworfen.
 */
class HttpJwksKeySource(
    private val uri: URI,
    private val cacheSeconds: Long,
    private val minRefreshSeconds: Long,
    private val maxStaleSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(),
) : JwksKeySource {
    private class Snapshot(val keys: Map<String, JWK>, val loadedAt: Instant)

    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var lastAttempt: Instant = Instant.EPOCH

    override fun key(kid: String): JWK? {
        val observed = snapshot
        val now = clock.instant()
        if (observed != null && Duration.between(observed.loadedAt, now).toMillis() < cacheSeconds * 1000) {
            observed.keys[kid]?.let { return it }
            // unbekannte kid: ein neuer Schlüssel könnte veröffentlicht worden sein
            if (Duration.between(lastAttempt, now).toMillis() < minRefreshSeconds * 1000) return null
            return (refresh(observed) ?: observed).keys[kid]
        }
        val fresh = refresh(observed)
        if (fresh != null) return fresh.keys[kid]
        // Laden gescheitert: nur innerhalb der Karenzzeit auf die alten Schlüssel zurückgreifen
        val stale = snapshot
        if (stale != null && Duration.between(stale.loadedAt, clock.instant()).toMillis() <= maxStaleSeconds * 1000) return stale.keys[kid]
        throw KeysUnavailableException("JWKS nicht erreichbar und keine gültigen Schlüssel im Speicher")
    }

    private fun refresh(observed: Snapshot?): Snapshot? = synchronized(lock) {
        val current = snapshot
        if (current !== observed) return current  // ein anderer Aufruf war schneller
        lastAttempt = clock.instant()
        try {
            val loaded = fetch()
            Snapshot(loaded, clock.instant()).also { snapshot = it }
        } catch (e: Exception) {
            log.warn("JWKS konnte nicht geladen werden ({})", e.javaClass.simpleName)
            null
        }
    }

    private fun fetch(): Map<String, JWK> {
        val resp = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("Accept", "application/json").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
        check(resp.statusCode() == 200) { "JWKS-Antwort ${resp.statusCode()}" }
        check(resp.body().size <= 262_144) { "JWKS zu groß" }
        val keys = JWKSet.parse(String(resp.body(), Charsets.UTF_8)).keys
        return keys.mapNotNull { k ->
            val usable = k.keyID != null && (k.keyUse == null || k.keyUse == KeyUse.SIGNATURE) && !k.isPrivate && when (k) {
                is RSAKey -> k.size() >= 2048
                is ECKey -> k.curve == com.nimbusds.jose.jwk.Curve.P_256
                else -> false
            }
            if (usable) k.keyID to k else { log.warn("JWKS-Schlüssel verworfen (kid={}, Art={})", k.keyID, k.keyType); null }
        }.toMap()
    }
}
