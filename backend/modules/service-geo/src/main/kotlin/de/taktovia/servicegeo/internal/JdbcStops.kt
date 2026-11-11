package de.taktovia.servicegeo.internal

import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.DbErrorMapper
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.CreateStop
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.ReviseStop
import de.taktovia.servicegeo.api.StopFilter
import de.taktovia.servicegeo.api.StopView
import de.taktovia.servicegeo.api.Stops
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val COLS = "s.stop_id, s.id, s.revision, s.name, ST_X(s.point) AS lon, ST_Y(s.point) AS lat, s.accessibility::text AS accessibility, s.traffic_products, s.active, " +
    "s.approved_by, s.valid_from, s.valid_to, s.created_at"
private const val EFFECTIVE = "s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to)"

/**
 * Haltepunktrevisionen (WP-M2-05). Schreibende Operationen sperren die Revisionskette (`FOR UPDATE`), prüfen Eingaben und erwartete Revision, schließen eine offene
 * Vorgängerrevision und legen die neue Zeile in **einer** Transaktion an; ein gleichzeitiger Leser sieht daher entweder die alte wirksame Revision oder die neue, nie eine halbe.
 * Überlappung, Revisionsnummer, Wertebereich und Verkehrsprodukt sichert zusätzlich die Datenbank.
 */
internal class JdbcStops(
    private val ctxTx: ContextTransactions, private val audit: AuditPort, private val outbox: Outbox, private val clock: Clock,
) : Stops {
    private val json = JsonMapper.builder().build()

    override fun create(ctx: RequestContext, cmd: CreateStop): StopView {
        requireWrite(ctx)
        val checked = validate(cmd.name, cmd.point, cmd.accessibility, cmd.trafficProducts, cmd.validFrom, cmd.validTo)
        return ctxTx.run(ctx) { j -> doCreate(j, ctx, cmd, checked) }
    }

    @AuditRequired
    private fun doCreate(j: JdbcOperations, ctx: RequestContext, cmd: CreateStop, c: Checked): StopView {
        val stopId = cmd.stopId ?: UUID.randomUUID()
        if (cmd.stopId != null && j.queryForObject("SELECT count(*) FROM taktovia.stop_revision WHERE stop_id = ?", Int::class.java, stopId) != 0)
            throw ProblemException(ErrorCode.CONFLICT, "Der Haltepunkt existiert bereits; neue Revisionen werden mit PUT angelegt.")
        val id = insert(j, ctx, stopId, 1, cmd.name, c, cmd.active, cmd.validFrom, cmd.validTo)
        val v = readRow(j, id)
        record(j, ctx, v, "created")
        emit(j, ctx, v)
        return v
    }

    override fun revise(ctx: RequestContext, stopId: UUID, expectedRevision: Long, cmd: ReviseStop): StopView {
        requireWrite(ctx)
        val checked = validate(cmd.name, cmd.point, cmd.accessibility, cmd.trafficProducts, cmd.validFrom, cmd.validTo)
        return ctxTx.run(ctx) { j -> doRevise(j, ctx, stopId, expectedRevision, cmd, checked) }
    }

    @AuditRequired
    private fun doRevise(j: JdbcOperations, ctx: RequestContext, stopId: UUID, expected: Long, cmd: ReviseStop, c: Checked): StopView {
        // Kette sperren (RLS: fremde und unbekannte Haltepunkte liefern keine Zeile, 404), dann den Stand frisch lesen
        if (j.query("SELECT s.id FROM taktovia.stop_revision s WHERE s.stop_id = ? FOR UPDATE OF s", { rs, _ -> rs.getObject(1, UUID::class.java) }, stopId).isEmpty()) throw ProblemException.notFound()
        val head = head(j, stopId) ?: throw ProblemException.notFound()
        if (head.revision != expected) throw GeoSupport.revisionMismatch()
        if (head.validTo == null && head.validFrom.isBefore(cmd.validFrom))
            j.update("UPDATE taktovia.stop_revision SET valid_to = ? WHERE id = ? AND valid_to IS NULL", Timestamp.from(cmd.validFrom), head.revisionId)
        val id = insert(j, ctx, stopId, expected + 1, cmd.name, c, cmd.active, cmd.validFrom, cmd.validTo)
        val v = readRow(j, id)
        record(j, ctx, v, if (!v.active && head.active) "deactivated" else "revised")
        emit(j, ctx, v)
        return v
    }

    override fun get(ctx: RequestContext, stopId: UUID, revision: Long?): StopView = ctxTx.run(ctx, readOnly = true) { j ->
        val v = if (revision == null) head(j, stopId) else j.query("SELECT $COLS FROM taktovia.stop_revision s WHERE s.stop_id = ? AND s.revision = ?", MAPPER, stopId, revision).singleOrNull()
        // Fahrgäste sehen nur aktive, jetzt wirksame Haltepunkte: alles andere ist für sie nicht vorhanden
        if (v != null && !canManage(ctx) && !(v.active && v.validFrom <= clock.instant() && (v.validTo == null || clock.instant() < v.validTo))) null else v
    } ?: throw ProblemException.notFound()

    override fun list(ctx: RequestContext, filter: StopFilter, limit: Int, after: Pair<Instant, UUID>?): List<StopView> = ctxTx.run(ctx, readOnly = true) { j ->
        val manage = canManage(ctx)
        val at = filter.at ?: clock.instant()
        // „buchbar“-Sicht: mit Dienst, mit Zeitpunkt oder für alle ohne Stammdatenrecht; Stammdatenpfleger sehen sonst die jüngste Revision je Haltepunkt
        val effectiveMode = filter.serviceId != null || filter.at != null || !manage
        val active: Boolean? = if (manage) filter.active else true
        val product: String? = if (filter.serviceId != null) (effectiveProduct(j, filter.serviceId, at) ?: return@run emptyList()) else null
        val near = filter.near; val radius = if (near != null) (filter.nearRadiusMeters ?: 1000) else null
        j.query("SELECT $COLS FROM taktovia.stop_revision s WHERE " +
            "((CAST(? AS boolean) AND s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to)) OR " +
            "(NOT CAST(? AS boolean) AND s.revision = (SELECT max(h.revision) FROM taktovia.stop_revision h WHERE h.tenant_id = s.tenant_id AND h.stop_id = s.stop_id))) " +
            "AND (CAST(? AS boolean) IS NULL OR s.active = CAST(? AS boolean)) AND (CAST(? AS text) IS NULL OR CAST(? AS text) = ANY(s.traffic_products)) " +
            "AND (CAST(? AS float8) IS NULL OR ST_DWithin(CAST(s.point AS geography), CAST(ST_SetSRID(ST_MakePoint(CAST(? AS float8), CAST(? AS float8)), 4326) AS geography), CAST(? AS float8))) " +
            "AND (s.created_at, s.id) > (?, ?) ORDER BY s.created_at, s.id LIMIT ?",
            MAPPER, effectiveMode, Timestamp.from(at), Timestamp.from(at), effectiveMode, active, active, product, product,
            near?.longitude, near?.longitude, near?.latitude, radius?.toDouble(), Timestamp.from(after?.first ?: Instant.EPOCH), after?.second ?: UUID(0, 0), limit + 1)
    }

    override fun bookable(ctx: RequestContext, serviceId: UUID, stopId: UUID, at: Instant): StopView? = ctxTx.run(ctx, readOnly = true) { j ->
        val product = effectiveProduct(j, serviceId, at) ?: return@run null
        j.query("SELECT $COLS FROM taktovia.stop_revision s WHERE s.stop_id = ? AND s.active AND $EFFECTIVE AND ? = ANY(s.traffic_products) ORDER BY s.revision DESC LIMIT 1",
            MAPPER, stopId, Timestamp.from(at), Timestamp.from(at), product).singleOrNull()
    }

    // ---- intern ------------------------------------------------------------------------------------------------------------------------

    private fun canManage(ctx: RequestContext) = ctx.hasScope("geo:write")
    private fun requireWrite(ctx: RequestContext) { if (!canManage(ctx)) throw ProblemException(ErrorCode.FORBIDDEN) }

    /** Verkehrsprodukt der zum Zeitpunkt wirksamen Dienstversion; `null` ohne wirksame Version (kein Angebot, WP-M2-03). */
    private fun effectiveProduct(j: JdbcOperations, serviceId: UUID, at: Instant): String? =
        j.query("SELECT r.traffic_product FROM taktovia.service_revision r WHERE r.service_id = ? AND r.valid_from <= ? AND (r.valid_to IS NULL OR ? < r.valid_to) ORDER BY r.revision DESC LIMIT 1",
            { rs, _ -> rs.getString(1) }, serviceId, Timestamp.from(at), Timestamp.from(at)).singleOrNull()

    private class Checked(val point: GeoPoint, val accessibilityJson: String, val products: List<String>)

    private fun validate(name: String, point: Any?, accessibility: Any?, products: Any?, from: Instant, to: Instant?): Checked {
        val v = mutableListOf<Violation>()
        if (name.isEmpty()) v += Violation("/name", ViolationCode.TOO_SHORT) else if (name.length > 200) v += Violation("/name", ViolationCode.TOO_LONG)
        if (to != null && !to.isAfter(from)) v += Violation("/valid_to", ViolationCode.INTERVAL_INVALID)
        var geo: GeoPoint? = null
        val pm = point as? Map<*, *>
        if (pm == null) v += Violation("/point", if (point == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH)
        else {
            val lat = (pm["latitude"] as? Number)?.toDouble(); val lon = (pm["longitude"] as? Number)?.toDouble()
            if (lat == null || !lat.isFinite()) v += Violation("/point/latitude", if (pm["latitude"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH)
            else if (lat < -90.0 || lat > 90.0) v += Violation("/point/latitude", if (lat < -90.0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM, "Breite außerhalb -90 bis 90 (WGS84)")
            if (lon == null || !lon.isFinite()) v += Violation("/point/longitude", if (pm["longitude"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH)
            else if (lon < -180.0 || lon > 180.0) v += Violation("/point/longitude", if (lon < -180.0) ViolationCode.BELOW_MINIMUM else ViolationCode.ABOVE_MAXIMUM, "Länge außerhalb -180 bis 180 (WGS84)")
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) geo = GeoPoint(lon, lat)
        }
        v += StopAccessibility.check(accessibility)
        val list = (products as? List<*>)
        if (list == null) v += Violation("/traffic_products", if (products == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH)
        else {
            if (list.isEmpty()) v += Violation("/traffic_products", ViolationCode.TOO_FEW_ITEMS, "Mindestens ein zulässiges Verkehrsprodukt ist nötig")
            if (list.size > 10) v += Violation("/traffic_products", ViolationCode.TOO_MANY_ITEMS)
            list.forEachIndexed { i, p -> if (p !is String || !PRODUCT.matches(p)) v += Violation("/traffic_products/$i", ViolationCode.PATTERN_MISMATCH) }
            if (list.toSet().size != list.size) v += Violation("/traffic_products", ViolationCode.NOT_UNIQUE)
        }
        GeoSupport.throwIf(v)
        return Checked(geo!!, json.writeValueAsString(accessibility), list!!.map { it as String })
    }

    private fun insert(j: JdbcOperations, ctx: RequestContext, stopId: UUID, revision: Long, name: String, c: Checked, active: Boolean, from: Instant, to: Instant?): UUID {
        val id = UUID.randomUUID(); val actor = GeoSupport.actor(ctx).value
        try {
            j.update("INSERT INTO taktovia.stop_revision (tenant_id, id, stop_id, revision, point, name, accessibility, approved_by, valid_from, valid_to, active, traffic_products, created_by) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, CAST(? AS jsonb), ?, ?, ?, ?, CAST(? AS text[]), ?)",
                id, stopId, revision, c.point.longitude, c.point.latitude, name, c.accessibilityJson, actor, Timestamp.from(from), to?.let { Timestamp.from(it) }, active,
                c.products.joinToString(",", "{", "}"), actor)   // nur geprüfte Kennungen (A–Z, 0–9, _)
        } catch (_: DuplicateKeyException) { throw GeoSupport.revisionMismatch() }
        catch (e: DataAccessException) { throw DbErrorMapper.map(e) }   // Exclusion Constraint: überlappende Wirksamkeit derselben Revisionskette, 409
        return id
    }

    private fun head(j: JdbcOperations, stopId: UUID): StopView? =
        j.query("SELECT $COLS FROM taktovia.stop_revision s WHERE s.stop_id = ? ORDER BY s.revision DESC LIMIT 1", MAPPER, stopId).singleOrNull()

    private fun readRow(j: JdbcOperations, id: UUID): StopView = j.query("SELECT $COLS FROM taktovia.stop_revision s WHERE s.id = ?", MAPPER, id).single()

    private fun record(j: JdbcOperations, ctx: RequestContext, v: StopView, change: String) {
        audit.record(j, GeoSupport.entry(ctx, AuditActions.GEO_STOP_CHANGED, "stop", v.stopId, v.revision, change, linkedMapOf(
            "revision_id" to v.revisionId.toString(), "active" to v.active, "traffic_products" to v.trafficProducts, "approved_by" to v.approvedBy,
            "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString())))
    }

    private fun emit(j: JdbcOperations, ctx: RequestContext, v: StopView) {
        outbox.append(j, OutboxEvent(v.stopId, v.revision, if (v.active) "geo.stop_revision_effective" else "geo.stop_revision_deactivated", linkedMapOf(
            "stop_id" to v.stopId.toString(), "revision_id" to v.revisionId.toString(), "revision" to v.revision, "active" to v.active, "traffic_products" to v.trafficProducts,
            "valid_from" to v.validFrom.toString(), "valid_to" to v.validTo?.toString()), clock.instant(), correlationId = ctx.correlationId))
    }

    private companion object {
        val PRODUCT = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        private val mapper = JsonMapper.builder().build()
        @Suppress("UNCHECKED_CAST")
        val MAPPER = RowMapper { rs, _ ->
            StopView(rs.getObject("stop_id", UUID::class.java), rs.getObject("id", UUID::class.java), rs.getLong("revision"), rs.getString("name"),
                GeoPoint(rs.getDouble("lon"), rs.getDouble("lat")), mapper.readValue(rs.getString("accessibility"), Map::class.java) as Map<String, Any?>,
                (rs.getArray("traffic_products").array as Array<*>).map { it as String }, rs.getBoolean("active"), rs.getString("approved_by"),
                rs.getTimestamp("valid_from").toInstant(), rs.getTimestamp("valid_to")?.toInstant(), rs.getTimestamp("created_at").toInstant())
        }
    }
}
