package de.taktovia.app.probe

import de.taktovia.platform.api.Etag
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Zählt die tatsächlichen Ausführungen der Testbefehle (die Wirkung einer Wiederholung darf nie hinzukommen). */
object ProbeCommandStats {
    val creates = AtomicInteger()
    val publishes = AtomicInteger()
}

/**
 * Nur in Tests (`taktovia.probe.idempotency=true`): Testbefehle für das Idempotenzregister (Vertrag `probe/idempotency-probe-api.yaml`).
 * Wirkung = Zeile in `public.etag_probe` plus Outbox-Eintrag in derselben Transaktion (Leitlinie 3). `publish` steht stellvertretend für
 * `publishConfig` (WP-M1-22): If-Match-geschützt, erhöht die Revision, schreibt das Ereignis zur neuen Revision.
 */
@RestController
@RequestMapping("/v1/probe/commands")
@ConditionalOnProperty("taktovia.probe.idempotency", havingValue = "true")
class IdempotencyProbeController(private val tx: TenantTransactions, private val outbox: Outbox, private val identity: ProbeIdentity) {
    private fun tenant(r: HttpServletRequest) = TenantId.parse(identity.tenant(r))

    @PostMapping
    fun create(@RequestBody body: Map<String, Any?>, r: HttpServletRequest): ResponseEntity<RevisionedBody> {
        ProbeCommandStats.creates.incrementAndGet()
        val t = tenant(r)
        (body["deadline"] as String?)?.let { if (Instant.parse(it).isBefore(Instant.now())) throw ProblemException(ErrorCode.OFFER_EXPIRED, "Frist überschritten") }
        (body["delay_ms"] as Int?)?.let { Thread.sleep(it.toLong()) }
        val id = UUID.randomUUID()
        tx.run(t) { j ->
            j.update("INSERT INTO public.etag_probe(tenant_id, id, name) VALUES (?, ?, ?)", t.value, id, body["name"] as String)
            outbox.append(j, OutboxEvent(id, 1, "probe.command.created", mapOf("name" to body["name"]), Instant.now()))
        }
        return ResponseEntity.status(HttpStatus.CREATED).location(URI("/v1/probe/commands/$id")).body(RevisionedBody(mapOf("id" to id.toString(), "name" to body["name"], "revision" to 1L), 1))
    }

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: UUID, @RequestHeader("If-Match") ifMatch: String, @RequestBody body: Map<String, Any?>, r: HttpServletRequest): RevisionedBody {
        ProbeCommandStats.publishes.incrementAndGet()
        val t = tenant(r); val expected = Etag.parseIfMatch(ifMatch)
        return tx.run(t) { j ->
            val updated = j.update("UPDATE public.etag_probe SET revision = revision + 1, value = ? WHERE tenant_id = ? AND id = ? AND revision = ?", body["reason"], t.value, id, expected)
            Etag.requireUpdated(updated) { j.queryForObject("SELECT count(*) FROM public.etag_probe WHERE id = ?", Int::class.java, id)!! > 0 }
            val revision = expected + 1
            outbox.append(j, OutboxEvent(id, revision, "probe.command.published", mapOf("reason" to body["reason"]), Instant.now()))
            RevisionedBody(mapOf("id" to id.toString(), "revision" to revision), revision)
        }
    }
}
