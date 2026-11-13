package de.taktovia.platform.carddata

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fundstelle ohne Wert: Ort der Eingabe (`query:<name>`, `header:<name>`, `body:<json-pointer>`); unsichere Namen sind durch `*` ersetzt. */
data class CardDataFinding(val location: String)

/** Eingabe einer Anfrage zur Prüfung; alle Teile optional. [body] ist der geparste JSON-Body. */
class CardDataInput(val query: String? = null, val headers: Map<String, List<String>> = emptyMap(), val body: JsonNode? = null, val rawBody: String? = null)

/**
 * Eingangsfilter gegen rohe Kartendaten (WP-M1-37): prüft Query, Header und Body auf kartennummernähnliche Muster ([CardData]). Ein Treffer
 * bedeutet Abweisen der Anfrage (4xx durch den Aufrufer); der Wert wird nie in Fundstelle, Antwort, Log oder Audit übernommen.
 */
object CardDataFilter {
    private val SAFE_NAME = Regex("[A-Za-z0-9_.\\-]{1,64}")
    private const val MAX_DEPTH = 64

    fun screen(input: CardDataInput): List<CardDataFinding> {
        val out = LinkedHashSet<String>()
        input.query?.takeIf { it.isNotEmpty() }?.let { q ->
            for (pair in q.split('&')) {
                val kv = pair.split('=', limit = 2)
                val name = decode(kv[0]); val value = decode(kv.getOrElse(1) { "" })
                if (CardData.contains(name) || CardData.contains(value) || CardData.contains(pair)) out += "query:" + safe(name)
            }
        }
        for ((name, values) in input.headers) if (values.any { CardData.contains(it) } || CardData.contains(name)) out += "header:" + safe(name)
        if (input.body != null) walk(input.body, "", 0, out)
        else if (input.rawBody != null && CardData.contains(input.rawBody)) out += "body:/"
        return out.map { CardDataFinding(it) }
    }

    private fun walk(n: JsonNode, pointer: String, depth: Int, out: MutableSet<String>) {
        val here = "body:" + pointer.ifEmpty { "/" }
        when {
            depth > MAX_DEPTH -> out += here   // unzulässig tief verschachtelt: nicht prüfbar, also nicht zulassen
            n.isString -> if (CardData.contains(n.asString())) out += here
            // Gleitkommazahlen (Koordinaten, Messwerte mit 15 bis 17 gültigen Stellen) sind keine Kartennummern: ohne diese Ausnahme wies der Filter ~10 % der Positionsmeldungen
            // mit einem Breitengrad 51..55 und 16 Stellen fälschlich ab (Luhn + Mastercard-Präfix). Ganzzahlen bleiben geprüft.
            n.isNumber -> if (!n.isFloatingPointNumber && CardData.contains(n.asString())) out += here
            n.isObject -> for (key in n.propertyNames()) {
                if (CardData.contains(key)) out += here
                walk(n.get(key), pointer + "/" + safe(key), depth + 1, out)
            }
            n.isArray -> n.values().forEachIndexed { i, c -> walk(c, "$pointer/$i", depth + 1, out) }
        }
    }

    private fun safe(name: String) = if (SAFE_NAME.matches(name) && !CardData.contains(name)) name else "*"

    private fun decode(s: String) = try { URLDecoder.decode(s, Charsets.UTF_8) } catch (_: IllegalArgumentException) { s }

    /**
     * Ereignis-Payloads und andere strukturierte Werte (Map/Liste/Zahl/Text): wirft [IllegalArgumentException] ohne den Wert zu nennen, wenn
     * irgendwo ein kartennummernähnliches Muster steckt. Aufruf aus `Outbox.append`.
     */
    fun requireNoCardData(value: Any?, what: String) {
        require(!containsCardData(value, 0)) { "$what enthält kartennummernähnliche Daten (SEC-008)" }
    }

    private fun containsCardData(v: Any?, depth: Int): Boolean = when {
        depth > MAX_DEPTH -> true
        v == null || v is Boolean -> false
        v is Map<*, *> -> v.entries.any { CardData.contains(it.key.toString()) || containsCardData(it.value, depth + 1) }
        v is Iterable<*> -> v.any { containsCardData(it, depth + 1) }
        v is Array<*> -> v.any { containsCardData(it, depth + 1) }
        else -> CardData.contains(v.toString())
    }
}

/**
 * Meldet abgewiesene Eingaben **asynchron** (damit der Abweisungspfad nicht blockiert): strukturierte Logzeile und, mit Mandantenkontext, ein
 * Sicherheitsereignis `security.input.card_data_rejected` im Audit. Enthalten sind ausschließlich Fundstellen und `correlation_id`, nie ein
 * Teil der Eingabe. [close] wartet auf ausstehende Meldungen (Tests: erst danach Logs und Audit auswerten).
 */
class CardDataIncidents(private val tx: TenantTransactions?, private val security: SecurityEventPort?, threads: Int = 2) : AutoCloseable {
    private val log = LoggerFactory.getLogger("taktovia.security")
    private val executor: ExecutorService = Executors.newFixedThreadPool(threads) { r -> Thread(r, "card-data-incidents").also { it.isDaemon = true } }

    fun report(tenant: TenantId?, correlationId: UUID, findings: List<CardDataFinding>) {
        val locations = findings.map { it.location }
        executor.execute {
            try {
                log.warn("{\"event\":\"card_data_rejected\",\"correlation_id\":\"{}\",\"locations\":\"{}\"}", correlationId, locations.joinToString(","))
                if (tenant != null && tx != null && security != null) {
                    tx.run(tenant) { jdbc ->
                        security.report(jdbc, SecurityEvent(ActorRef.system("card-data-filter"), AuditActions.SECURITY_CARD_DATA_REJECTED,
                            ObjectRef("request", correlationId), correlationId, mapOf("locations" to locations)))
                    }
                }
            } catch (e: RuntimeException) {
                log.error("{\"event\":\"card_data_incident_not_recorded\",\"error\":\"{}\"}", e.javaClass.simpleName)
            }
        }
    }

    override fun close() { executor.shutdown(); executor.awaitTermination(30, TimeUnit.SECONDS) }
}
