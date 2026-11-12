package de.taktovia.app.geo

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.Rfc3339
import java.time.Instant

/** Antwort mit Revision (der Antwortfilter setzt daraus den ETag). */
class GeoResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/** Gemeinsame Eingabehilfen der Geo-Controller (WP-M2-03 bis -06). */
internal object GeoWeb {
    /** RFC-3339-Zeitpunkt mit Offset aus dem Body (`pointer` = JSON-Pointer des Felds); Fehler als 422 mit Feldverweis. */
    fun instant(text: Any?, pointer: String): Instant {
        val c = Rfc3339.parse(text as? String, pointer)
        if (!c.ok) throw ProblemException.invalid(*c.violations.toTypedArray())
        return c.value!!.toInstant()
    }

    fun optionalInstant(text: Any?, pointer: String): Instant? = if (text == null) null else instant(text, pointer)

    fun string(body: Map<String, Any?>, field: String): String =
        body[field] as? String ?: throw ProblemException.invalid(Violation("/$field", if (body[field] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH))

    fun queryInstant(text: String?, name: String): Instant? {
        if (text == null) return null
        val c = Rfc3339.parse(text, "/\$query/$name")
        if (!c.ok) throw ProblemException.invalid(*c.violations.toTypedArray())
        return c.value!!.toInstant()
    }
}
