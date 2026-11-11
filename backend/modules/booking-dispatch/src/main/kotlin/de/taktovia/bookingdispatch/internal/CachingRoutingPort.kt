package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.MatrixCell
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext

/**
 * `RoutingPort` mit zweistufigem, mandantengebundenem Cache: flüchtig (Schlüssel mit Mandant, Profil und Version) vor PostgreSQL (`route_matrix_cache`, RLS) vor dem Worker.
 * Die Kartendatenversion fragt der Port vor jedem Cachezugriff beim Worker ab; Einträge anderer Versionen werden nie gelesen und beim Stand-Wechsel gelöscht. Ein Ausfall der
 * Versionsabfrage ist ein definierter Fehler: es gibt keinen Cachezugriff ohne bekannte Version und kein Ergebnis aus alten Daten. Das Cache-Schreiben ist ein
 * Nebeneffekt nach erfolgreicher Berechnung (eigene Transaktion, nie während des Worker-Aufrufs offen).
 */
internal class CachingRoutingPort(
    private val ctxTx: ContextTransactions, private val client: HttpRoutingClient, private val db: JdbcRouteMatrixCache = JdbcRouteMatrixCache(),
    private val volatile: VolatileRouteCache = InMemoryVolatileRouteCache(),
) : RoutingPort {

    @Volatile private var lastVersion: String? = null

    private fun version(ctx: RequestContext): String {
        val v = client.mapVersion()
        if (lastVersion != null && lastVersion != v) ctxTx.run(ctx) { j -> db.invalidateOtherVersions(j, v) }
        lastVersion = v
        return v
    }

    private fun cached(ctx: RequestContext, o: String, d: String, p: String, v: String): MatrixCell? {
        volatile.get(RoutingCacheKey.redisKey(ctx.tenant.value, p, v, o, d))?.let { raw -> parse(raw, v)?.let { return it } }
        val hit = ctxTx.run(ctx, readOnly = true) { j -> db.get(j, o, d, p, v) } ?: return null
        volatile.put(RoutingCacheKey.redisKey(ctx.tenant.value, p, v, o, d), "$v|${hit.timeSeconds}|${hit.distanceMeters}")
        return hit
    }

    /** Flüchtiger Wert `<version>|<zeit>|<distanz>`: ein Wert mit anderer Version als erwartet wird verworfen. */
    private fun parse(raw: String, expected: String): MatrixCell? {
        val p = raw.split('|')
        return if (p.size == 3 && p[0] == expected) MatrixCell(p[1].toInt(), p[2].toInt()) else null
    }

    private fun store(ctx: RequestContext, o: String, d: String, p: String, v: String, cell: MatrixCell, provider: String) {
        ctxTx.run(ctx) { j -> db.put(j, ctx.tenant.value, o, d, p, v, cell, provider) }
        volatile.put(RoutingCacheKey.redisKey(ctx.tenant.value, p, v, o, d), "$v|${cell.timeSeconds}|${cell.distanceMeters}")
    }

    override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String): RouteInfo = compute(ctx, originStop, destinationStop, profile, walk = false)
    override fun walk(ctx: RequestContext, originStop: String, destinationStop: String): RouteInfo = compute(ctx, originStop, destinationStop, "walk", walk = true)

    /** Einzelrouten tragen die Geometrie und werden daher immer vom Worker berechnet; das Zeit-/Distanzergebnis landet im Cache für die Matrixabfragen. */
    private fun compute(ctx: RequestContext, o: String, d: String, p: String, walk: Boolean): RouteInfo {
        val r = client.route(ctx.tenant.value, o, d, p, walk)
        store(ctx, o, d, p, r.mapVersion, MatrixCell(r.timeSeconds, r.distanceMeters), r.provider)
        return r
    }

    override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo {
        val v = version(ctx)
        val cells = originStops.map { o -> destinationStops.map { d -> cached(ctx, o, d, profile, v) } }
        if (cells.all { row -> row.all { it != null } }) return MatrixInfo(originStops, destinationStops, cells, profile, v, "cache")
        val fresh = client.matrix(ctx.tenant.value, originStops, destinationStops, profile)
        if (fresh.mapVersion != v) throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Kartenstand wechselte während der Abfrage")   // nie gemischte Versionen
        fresh.cells.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != null) store(ctx, originStops[i], destinationStops[j], profile, v, c, fresh.provider) } }
        return fresh
    }
}
