package de.taktovia.servicegeo

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.CreateService
import org.junit.jupiter.api.Assertions.assertThrows
import java.util.UUID

/** Testhilfen des Clusters Dienste/Geo (WP-M2-04 bis -06) über [StructureFixture]; eigene Namen, keine Duplikate fremder Fixtures. */
internal object GeoTestSupport {
    val GEO_SCOPES = setOf("geo:read", "geo:write", "services:read", "services:write")

    fun ctx(fx: StructureFixture, w: StructureFixture.World): RequestContext = fx.context(w.tenant, scopes = GEO_SCOPES)
    fun service(fx: StructureFixture, w: StructureFixture.World, ctx: RequestContext): UUID = fx.geo.structure.createService(ctx, CreateService(w.operator, "Rufbus", "Europe/Berlin")).id

    /** GeoJSON-MultiPolygon eines Quadrats (Länge/Breite) als Map, wie es der HTTP-Rand liefert. */
    fun square(x0: Double, y0: Double, size: Double = 0.1): Map<String, Any?> {
        val x1 = x0 + size; val y1 = y0 + size
        return mapOf("type" to "MultiPolygon", "coordinates" to listOf(listOf(listOf(listOf(x0, y0), listOf(x1, y0), listOf(x1, y1), listOf(x0, y1), listOf(x0, y0)))))
    }

    /** Schleife (Acht): geschlossen, aber selbstüberschneidend. */
    val BOWTIE: Map<String, Any?> = mapOf("type" to "MultiPolygon", "coordinates" to listOf(listOf(listOf(listOf(0.0, 0.0), listOf(1.0, 1.0), listOf(1.0, 0.0), listOf(0.0, 1.0), listOf(0.0, 0.0)))))

    fun problem(block: () -> Unit): ProblemException = assertThrows(ProblemException::class.java) { block() }
}
