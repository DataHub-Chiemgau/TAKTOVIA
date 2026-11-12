package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.FareComponentCode
import de.taktovia.farepayment.api.FareEngine
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.FareResult
import de.taktovia.farepayment.api.QuoteComponent
import de.taktovia.farepayment.api.QuoteHashing
import de.taktovia.farepayment.api.QuoteOutcome
import de.taktovia.farepayment.api.QuoteRequest
import de.taktovia.farepayment.api.QuoteView
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Quote-Bildung und Preisbindung (WP-M2-09). Es wird nur eingefügt (die Runtime-Rolle hat auf `fare_quote` und `fare_component` kein UPDATE/DELETE, der Trigger
 * `quote_immutable` verbietet beides auch dem Administrator). Die wirksame Tarifrevision wird zum Preisstichtag gelesen; veröffentlichte Revisionen und ihre Regeln sind
 * unveränderlich, die Quote trägt daher vollständig die alte oder die neue Revision und bleibt nach einer späteren Veröffentlichung unberührt.
 */
internal class JdbcFareQuotes(private val ctxTx: ContextTransactions, private val tariffs: JdbcTariffs, @Suppress("unused") private val clock: Clock) : FareQuotes {
    private val json = JsonMapper.builder().build()

    override fun createIn(j: JdbcOperations, ctx: RequestContext, request: QuoteRequest): QuoteOutcome {
        val revision = tariffs.effectiveIn(j, request.serviceId, request.tariffClass, request.priceAt)
            ?: return QuoteOutcome.NoTariff("Kein Tarif zum Preisstichtag wirksam.")
        val breakdown = when (val r = FareEngine.price(revision, request.fare)) {
            is FareResult.Priced -> r.breakdown
            is FareResult.Rejected -> return QuoteOutcome.Rejected(r.reason, r.detail)
        }
        val currency = breakdown.currency
        val exponent = j.queryForObject("SELECT exponent FROM taktovia.currency WHERE code = ?", Int::class.java, currency)!!
        val lines = breakdown.components.map { c -> linkedMapOf<String, Any?>("code" to c.code.name, "amount_minor" to c.amountMinor) }
        val snapshotMap = linkedMapOf<String, Any?>(
            "schema_version" to "1.0", "tariff_revision" to revision.revisionNo, "currency" to currency,
        )
        request.fare.distanceMeters?.let { snapshotMap["distance_m"] = it }
        request.routingMapVersion?.let { snapshotMap["routing_map_version"] = it }
        snapshotMap["lines"] = lines
        snapshotMap["gross_minor"] = breakdown.grossMinor
        snapshotMap["cancellation_policy_ref"] = request.cancellationPolicyRef
        val snapshot = json.writeValueAsString(snapshotMap)
        val hash = QuoteHashing.hash(snapshot)
        val id = UUID.randomUUID()
        // der gelieferte Snapshot ist die gespeicherte Form (jsonb): spätere Lesezugriffe liefern denselben Text; der Hash gilt für die kanonische Form
        val stored = j.queryForObject(
            "INSERT INTO taktovia.fare_quote(tenant_id, id, attempt_id, currency, pay_at_platform_minor, external_known_minor, external_unknown_leg_ids, expires_at, tariff_revision_id, " +
                "config_revision_id, snapshot, content_hash) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 0, '{}', ?, ?, ?, CAST(? AS jsonb), ?) RETURNING snapshot::text",
            String::class.java, id, request.attemptId, currency, breakdown.grossMinor, Timestamp.from(request.expiresAt), revision.id, request.configRevisionId, snapshot, hash,
        )!!
        breakdown.components.forEachIndexed { i, c ->
            j.update(
                "INSERT INTO taktovia.fare_component(tenant_id, id, quote_id, segment_no, component_no, code, amount_minor, currency, rule_id) VALUES (taktovia.current_tenant(), ?, ?, 1, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, i + 1, c.code.name, c.amountMinor, currency, c.ruleId,
            )
        }
        return QuoteOutcome.Created(
            QuoteView(id, request.attemptId, currency, exponent, breakdown.grossMinor, 0, request.expiresAt, revision.id, revision.revisionNo, request.configRevisionId,
                breakdown.components.map { QuoteComponent(it.code, it.amountMinor, it.ruleId) }, stored, hash),
        )
    }

    override fun getIn(j: JdbcOperations, quoteId: UUID): QuoteView? {
        val head = j.query(
            "SELECT q.id, q.attempt_id, q.currency, c.exponent, q.pay_at_platform_minor, q.external_known_minor, q.expires_at, q.tariff_revision_id, r.revision_no, q.config_revision_id, q.snapshot::text, q.content_hash " +
                "FROM taktovia.fare_quote q JOIN taktovia.currency c ON c.code = q.currency JOIN taktovia.tariff_revision r ON r.tenant_id = q.tenant_id AND r.id = q.tariff_revision_id WHERE q.id = ?",
            { rs, _ ->
                QuoteView(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3).trim(), rs.getInt(4), rs.getLong(5), rs.getLong(6), rs.getTimestamp(7).toInstant(),
                    rs.getObject(8, UUID::class.java), rs.getInt(9), rs.getObject(10, UUID::class.java), emptyList(), rs.getString(11), rs.getString(12).trim())
            }, quoteId,
        ).singleOrNull() ?: return null
        val components = j.query(
            "SELECT code, amount_minor, rule_id FROM taktovia.fare_component WHERE quote_id = ? ORDER BY segment_no, component_no",
            { rs, _ -> QuoteComponent(FareComponentCode.valueOf(rs.getString(1)), rs.getLong(2), rs.getObject(3, UUID::class.java)) }, quoteId,
        )
        return head.copy(components = components)
    }

    override fun get(ctx: RequestContext, quoteId: UUID): QuoteView = ctxTx.run(ctx, readOnly = true) { j -> getIn(j, quoteId) } ?: throw ProblemException.notFound()

    override fun requireAcceptedAmount(j: JdbcOperations, quoteId: UUID, acceptedMinor: Long, currency: String, now: Instant): QuoteView {
        val q = getIn(j, quoteId) ?: throw ProblemException.notFound()
        if (!now.isBefore(q.expiresAt)) throw ProblemException(ErrorCode.OFFER_EXPIRED, "Das Angebot ist abgelaufen; bitte neu suchen.")
        if (currency != q.currency || acceptedMinor != q.payAtPlatformMinor) {
            throw ProblemException(ErrorCode.REQUOTE_REQUIRED, "Der akzeptierte Betrag weicht vom angebotenen Preis ab; bitte den Preis erneut bestätigen.")
        }
        return q
    }
}
