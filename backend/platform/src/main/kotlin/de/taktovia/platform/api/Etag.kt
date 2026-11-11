package de.taktovia.platform.api

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode

/** Ressource mit Revision; der Antwortfilter der Anwendung setzt daraus den starken ETag `"7"` (Kap. 05 §5.1). */
interface Revisioned { val revision: Long }

/**
 * Starker ETag und `If-Match` (Kap. 05 §5.1, WP-M1-02): fehlender Header 428 `PRECONDITION_REQUIRED`, veraltete
 * Revision 412 `REVISION_MISMATCH`, nie stilles Überschreiben. Der Vergleich geschieht atomar im bedingten UPDATE
 * (`... WHERE revision = ?`); [requireUpdated] wertet dessen Trefferzahl aus.
 */
object Etag {
    private val STRONG = Regex("^\"([0-9]{1,18})\"$")

    fun format(revision: Long): String { require(revision >= 0); return "\"$revision\"" }

    /** `If-Match` lesen: fehlt er, 428; ist er kein starker numerischer ETag (auch `*` oder schwach), 422 mit Feldverletzung. */
    fun parseIfMatch(header: String?): Long {
        if (header == null) throw ProblemException(ErrorCode.PRECONDITION_REQUIRED, "Vor einer Änderung die Ressource lesen und ihren ETag als If-Match senden.")
        val m = STRONG.matchEntire(header.trim())
            ?: throw ProblemException.invalid(Violation("/\$header/If-Match", ViolationCode.INVALID_FORMAT))
        return m.groupValues[1].toLong()
    }

    /** Bewertet ein bedingtes UPDATE: eine Zeile = Erfolg; keine Zeile = 412, wenn die Ressource existiert, sonst 404. */
    fun requireUpdated(updatedRows: Int, exists: () -> Boolean) {
        if (updatedRows == 1) return
        if (updatedRows == 0) throw if (exists()) ProblemException(ErrorCode.REVISION_MISMATCH, "Die Ressource wurde zwischenzeitlich geändert; aktuelle Revision lesen.") else ProblemException.notFound()
        throw IllegalStateException("bedingtes UPDATE traf $updatedRows Zeilen")
    }
}
