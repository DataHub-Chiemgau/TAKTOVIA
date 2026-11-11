package de.taktovia.app.probe

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.web.RequestIdentityResolver
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Paging
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Nur in Tests (`taktovia.probe.enabled=true`): Mandant und Akteur aus Testheadern. Kein Muster für Produktivcode. */
@Component
@ConditionalOnProperty("taktovia.probe.enabled", havingValue = "true")
class ProbeIdentity : RequestIdentityResolver {
    override fun tenant(request: HttpServletRequest) = request.getHeader("X-Test-Tenant") ?: "-"
    override fun actor(request: HttpServletRequest) = request.getHeader("X-Test-Actor") ?: request.remoteAddr
}

class RevisionedBody(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Prüf-Endpunkte gegen die echte Datenbank (Tabelle `public.etag_probe` mit RLS), Vertrag in `probe/probe-api.yaml`. */
@RestController
@RequestMapping("/v1/probe")
@ConditionalOnProperty("taktovia.probe.enabled", havingValue = "true")
class ProbeController(private val tx: TenantTransactions, private val codec: CursorCodec, private val identity: ProbeIdentity) {
    private fun tenant(r: HttpServletRequest) = TenantId.parse(identity.tenant(r))

    private fun row(rs: ResultSet) = mapOf("id" to rs.getObject("id", UUID::class.java).toString(), "name" to rs.getString("name"), "value" to rs.getString("value"),
        "revision" to rs.getLong("revision"), "created_at" to rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString())

    @PostMapping("/items")
    fun create(@RequestBody body: Map<String, Any?>, r: HttpServletRequest): ResponseEntity<RevisionedBody> {
        val t = tenant(r); val id = UUID.randomUUID()
        val item = tx.run(t) { j ->
            j.query("INSERT INTO public.etag_probe(tenant_id, id, name) VALUES (?, ?, ?) RETURNING *", { rs, _ -> row(rs) }, t.value, id, body["name"] as String).single()
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(RevisionedBody(item, item["revision"] as Long))
    }

    @GetMapping("/items")
    fun list(@RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, r: HttpServletRequest): Any {
        val t = tenant(r); val scope = CursorScope(t.toString(), identity.actor(r), "probe-items")
        val lim = Paging.limit(limit); val pos = Paging.position(cursor, codec, scope)
        val rows = tx.run(t, readOnly = true) { j ->
            if (pos == null) j.query("SELECT * FROM public.etag_probe ORDER BY created_at, id LIMIT ?", { rs, _ -> row(rs) }, lim + 1)
            else j.query("SELECT * FROM public.etag_probe WHERE (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?", { rs, _ -> row(rs) },
                OffsetDateTime.ofInstant(pos.createdAt, ZoneOffset.UTC), pos.id, lim + 1)
        }
        return Paging.page(rows, lim, { CursorPosition(Instant.parse(it["created_at"] as String), UUID.fromString(it["id"] as String)) }, codec, scope, Instant.now())
            .toMap { it }
    }

    @GetMapping("/items/{id}")
    fun get(@PathVariable id: UUID, r: HttpServletRequest): RevisionedBody {
        val t = tenant(r)
        val item = tx.run(t, readOnly = true) { j -> j.query("SELECT * FROM public.etag_probe WHERE id = ?", { rs, _ -> row(rs) }, id).firstOrNull() } ?: throw ProblemException.notFound()
        return RevisionedBody(item, item["revision"] as Long)
    }

    @PutMapping("/items/{id}")
    fun update(@PathVariable id: UUID, @RequestHeader("If-Match") ifMatch: String, @RequestBody body: Map<String, Any?>, r: HttpServletRequest): RevisionedBody {
        val t = tenant(r); val expected = Etag.parseIfMatch(ifMatch)
        return tx.run(t) { j ->
            val updated = j.update("UPDATE public.etag_probe SET value = ?, revision = revision + 1 WHERE tenant_id = ? AND id = ? AND revision = ?", body["value"], t.value, id, expected)
            Etag.requireUpdated(updated) { j.queryForObject("SELECT count(*) FROM public.etag_probe WHERE id = ?", Int::class.java, id)!! > 0 }
            val item = j.query("SELECT * FROM public.etag_probe WHERE id = ?", { rs, _ -> row(rs) }, id).single()
            RevisionedBody(item, item["revision"] as Long)
        }
    }

    @GetMapping("/admin")
    fun admin(@RequestHeader("X-Test-Scopes", required = false) scopes: String?): Map<String, Any> {
        if (scopes?.split(' ')?.contains("probe:admin") != true) throw ProblemException(ErrorCode.FORBIDDEN)
        return mapOf("ok" to true)
    }

    @GetMapping("/boom")
    fun boom(): Nothing = throw IllegalStateException("SECRET-GEHEIM select * from audit_event at de.taktovia.internal.Foo")

    /**
     * Provoziert SQLSTATE 23514 an einer echten Tabelle: der Trigger `tenant_id_immutable` (V3) verweigert das Umhängen
     * einer Zeile auf einen anderen Mandanten. (`reject_mutation()` auf `audit_event` erreicht die Runtime-Rolle nicht,
     * ihr fehlt dort schon das UPDATE-Recht; dieser Pfad ist in `DbErrorMapperDatabaseTest` mit dem Administrator belegt.)
     */
    @PostMapping("/immutable")
    fun immutable(r: HttpServletRequest): Map<String, Any> {
        val t = tenant(r)
        tx.run(t) { j ->
            val id = UUID.randomUUID()
            j.update("INSERT INTO taktovia.legal_entity(tenant_id, id, name, country) VALUES (?, ?, 'probe', 'DE')", t.value, id)
            j.update("UPDATE taktovia.legal_entity SET tenant_id = ? WHERE tenant_id = ? AND id = ?", UUID.randomUUID(), t.value, id)
        }
        return mapOf("unreachable" to true)
    }

    /** Antwort mit einem Feld, das der Vertrag (`additionalProperties:false`) nicht kennt: der Vertragslauf muss das erkennen. */
    @GetMapping("/leaky")
    fun leaky(@RequestParam(required = false) fail: String?): Map<String, Any> = if (fail != null) throw ProblemException(ErrorCode.FORBIDDEN) else mapOf("id" to UUID.randomUUID().toString(), "geheim" to "darf nicht in der Antwort stehen")

    @PostMapping("/free")
    fun free(@RequestBody body: Map<String, Any?>): Map<String, Any?> = body
}
