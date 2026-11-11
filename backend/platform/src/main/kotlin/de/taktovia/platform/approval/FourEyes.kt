package de.taktovia.platform.approval

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException

/**
 * Wiederverwendbarer Vier-Augen-Baustein „Prüfer ≠ Ersteller“ (WP-M1-22, LH-I.7-CFG-006). Die Konfigurationsfreigabe (M1) nutzt ihn; Schichtplan- und
 * Tariffreigabe (M3, M4) nutzen ihn für ihre eigenen Freigaben. Die Regel gilt **vor** jeder fachlichen Wirkung und wird zusätzlich in der Datenbank
 * des jeweiligen Aggregats erzwungen (bei der Konfigurationsrevision `approved_by <> created_by`), damit sie auch an der Anwendung vorbei hält.
 *
 * Verstoß ist 403 `FORBIDDEN` mit festem Text (ohne Nennung von Personen). Als Prüfer zählen nur Menschen: ein Dienstkonto kann nie die zweite Person sein.
 */
object FourEyes {
    /** Bezeichnung des Akteurs wie im Audit und in `created_by`/`approved_by` (`user:<subject>` bzw. `service:<subject>`). */
    fun refOf(ctx: RequestContext): ActorRef = ActorRef((if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)

    /** Wirft 403, wenn der Prüfer des Kontexts der Ersteller [creator] ist oder kein Mensch. Liefert die Referenz des Prüfers. */
    fun requireDistinctReviewer(ctx: RequestContext, creator: String): ActorRef {
        val reviewer = refOf(ctx)
        if (ctx.actor.type != ActorType.HUMAN) throw ProblemException(ErrorCode.FORBIDDEN, "Die Freigabe erfordert eine zweite Person.")
        if (reviewer.value == creator) throw ProblemException(ErrorCode.FORBIDDEN, "Die Freigabe erfordert eine zweite Person; der Ersteller kann nicht selbst freigeben.")
        return reviewer
    }

    /** Reine Prüfung ohne Kontext (für Aufrufer, die Ersteller und Prüfer schon als Referenz haben). */
    fun requireDistinct(creator: String, reviewer: String) {
        if (creator == reviewer) throw ProblemException(ErrorCode.FORBIDDEN, "Die Freigabe erfordert eine zweite Person; der Ersteller kann nicht selbst freigeben.")
    }
}
