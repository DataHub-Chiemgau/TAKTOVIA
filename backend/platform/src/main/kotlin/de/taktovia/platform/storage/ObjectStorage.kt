package de.taktovia.platform.storage

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Objektschlüssel mit Mandantenpräfix (WP-M1-11, Kap. 02 §4.3): im Speicher `tenants/<mandant>/<name>`. Der Name darf weder Pfadtricks (`..`, führender `/`, `//`) noch
 * Zeichen außerhalb `[A-Za-z0-9._/-]` enthalten, damit kein Name aus dem Mandantenpräfix ausbricht. Es gibt keinen Zugriff auf Objekte ohne Mandant im Schlüssel.
 */
data class ObjectKey(val tenant: TenantId, val name: String) {
    init {
        require(NAME.matches(name)) { "ungültiger Objektname" }
        require(!name.startsWith("/") && !name.contains("//") && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "ungültiger Objektname" }
    }
    /** Speicherschlüssel mit Mandantenpräfix. */
    fun path(): String = "tenants/${tenant.value}/$name"

    private companion object { val NAME = Regex("[A-Za-z0-9._/-]{1,200}") }
}

/** Objektspeicher (MinIO/S3 in Betrieb, in M1 nur die prozesslokale Umsetzung ausgeführt; der S3-Adapter folgt mit WP-M0-08-Umgebung). Alle Methoden nehmen nur [ObjectKey]. */
interface TenantObjectStore {
    fun put(key: ObjectKey, bytes: ByteArray, contentType: String)
    /** Schreibt nur, wenn das Objekt noch nicht existiert (Schreibschutz für Originale, WP-M4-21); `false`, wenn es schon da ist. Umsetzungen machen das atomar. */
    fun putIfAbsent(key: ObjectKey, bytes: ByteArray, contentType: String): Boolean {
        if (get(key) != null) return false
        put(key, bytes, contentType); return true
    }
    fun get(key: ObjectKey): ByteArray?
    fun delete(key: ObjectKey)
    /** Namen aller Objekte **dieses** Mandanten mit dem Präfix [namePrefix]. */
    fun list(tenant: TenantId, namePrefix: String = ""): List<String>
}

class InMemoryObjectStore : TenantObjectStore {
    private val data = ConcurrentHashMap<String, Pair<ByteArray, String>>()
    override fun put(key: ObjectKey, bytes: ByteArray, contentType: String) { data[key.path()] = bytes.copyOf() to contentType }
    override fun putIfAbsent(key: ObjectKey, bytes: ByteArray, contentType: String): Boolean = data.putIfAbsent(key.path(), bytes.copyOf() to contentType) == null
    override fun get(key: ObjectKey): ByteArray? = data[key.path()]?.first?.copyOf()
    override fun delete(key: ObjectKey) { data.remove(key.path()) }
    override fun list(tenant: TenantId, namePrefix: String): List<String> {
        val p = "tenants/${tenant.value}/"
        return data.keys.filter { it.startsWith(p + namePrefix) }.map { it.removePrefix(p) }.sorted()
    }
}

/** Objekt- und Zweckprüfung vor jeder Signatur: darf der Akteur das Objekt zu diesem Zweck lesen? Das Fachmodul implementiert sie. */
fun interface ObjectPurposeCheck { fun allowed(ctx: RequestContext, key: ObjectKey, purpose: String): Boolean }

/** Signierte, kurzlebige URL. */
data class SignedUrl(val url: String, val expiresAt: Instant)

/**
 * Signierte URLs für den Objektzugriff (WP-M1-11, Kap. 02 §4.3): erst **nach** Objekt- und Zweckprüfung ([ObjectPurposeCheck]) und nur für Objekte des Mandanten des Kontexts;
 * Gültigkeit höchstens [maxTtl] (Planungsdefault 60 s, nicht freigegeben). Die Signatur (HMAC-SHA256) deckt Mandant, Objektname, Zweck und Ablauf ab. [verify] weist die URL
 * eines anderen Mandanten mit einer Sitzung des Mandanten A **wie ein nicht vorhandenes Objekt** ab (404): die Signatur gilt für den Mandanten, der sie erhielt, nicht für den Aufrufer.
 */
class SignedUrls(secret: ByteArray, private val clock: Clock = Clock.systemUTC(), val maxTtl: Duration = DEFAULT_MAX_TTL, private val base: String = "/v1/objects") {
    private val key = SecretKeySpec(secret.also { require(it.size >= 32) { "Signaturschlüssel zu kurz" } }, "HmacSHA256")

    fun sign(ctx: RequestContext, key: ObjectKey, purpose: String, check: ObjectPurposeCheck, ttl: Duration = maxTtl): SignedUrl {
        require(PURPOSE.matches(purpose)) { "ungültiger Zweck" }
        require(!ttl.isNegative && !ttl.isZero && ttl <= maxTtl) { "Gültigkeit über dem Höchstwert von ${maxTtl.seconds} s" }
        // Fremdes Objekt und fehlendes Recht sind für den Aufrufer nicht zu unterscheiden.
        if (key.tenant != ctx.tenant || !check.allowed(ctx, key, purpose)) throw ProblemException.notFound()
        val exp = clock.instant().plus(ttl)
        val q = "exp=${exp.epochSecond}&purpose=$purpose"
        val sig = mac(key.tenant, key.name, purpose, exp.epochSecond)
        return SignedUrl("$base/${key.tenant.value}/${URLEncoder.encode(key.name, StandardCharsets.UTF_8).replace("%2F", "/")}?$q&sig=$sig", exp)
    }

    /** Prüft Signatur, Ablauf und Mandantenbezug und liefert den Objektschlüssel; sonst 404 (nicht unterscheidbar von „nicht vorhanden“). */
    fun verify(ctx: RequestContext, url: String): Pair<ObjectKey, String> {
        val notFound = ProblemException(ErrorCode.NOT_FOUND)
        val parsed = parse(url) ?: throw notFound
        if (parsed.tenant != ctx.tenant) throw notFound
        val expected = mac(parsed.tenant, parsed.name, parsed.purpose, parsed.exp)
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), parsed.sig.toByteArray())) throw notFound
        if (!Instant.ofEpochSecond(parsed.exp).isAfter(clock.instant())) throw notFound
        return ObjectKey(parsed.tenant, parsed.name) to parsed.purpose
    }

    private class Parsed(val tenant: TenantId, val name: String, val purpose: String, val exp: Long, val sig: String)

    private fun parse(url: String): Parsed? = try {
        val (path, query) = url.substringBefore('#').split('?', limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val rest = path.removePrefix("$base/")
        val tenant = TenantId(UUID.fromString(rest.substringBefore('/')))
        val name = URLDecoder.decode(rest.substringAfter('/'), StandardCharsets.UTF_8)
        val params = query.split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        ObjectKey(tenant, name)   // prüft den Namen
        Parsed(tenant, name, params.getValue("purpose").also { require(PURPOSE.matches(it)) }, params.getValue("exp").toLong(), params.getValue("sig"))
    } catch (e: Exception) { null }

    private fun mac(tenant: TenantId, name: String, purpose: String, exp: Long): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(key)
        return m.doFinal("${tenant.value}\n$name\n$purpose\n$exp".toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Planungsdefault Kap. 02 §4.3 (nicht fachlich freigegeben). */
        val DEFAULT_MAX_TTL: Duration = Duration.ofSeconds(60)
        private val PURPOSE = Regex("[a-z][a-z0-9_]{1,40}")
        /** Name des Sicherheitsereignisses bei Fremdzugriff über eine URL (der Aufrufer meldet es über den Sicherheitsereignis-Port, ohne Inhalte von B). */
        const val SECURITY_ACTION = AuditActions.SECURITY_TENANT_MISMATCH
    }
}
