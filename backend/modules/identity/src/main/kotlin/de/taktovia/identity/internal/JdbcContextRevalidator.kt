package de.taktovia.identity.internal

import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations

/**
 * Wiederprüfung in der Fachtransaktion: alle Mitgliedschaften des Kontexts müssen jetzt noch aktiv sein. Schreibend mit `FOR SHARE`:
 * Ein gleichzeitiger Widerruf (UPDATE) wartet auf die laufende Anfrage, eine später beginnende Anfrage wartet auf den Widerruf und sieht
 * ihn danach. Damit ist „keine erfolgreiche schreibende Anfrage nach dem Commit eines Widerrufs“ gewährleistet.
 */
internal class JdbcContextRevalidator : ContextRevalidator {
    override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) {
        val ids = ctx.membershipIds
        if (ids.isEmpty()) throw ContextRevokedException()
        val literal = ids.joinToString(",", "{", "}")   // nur UUIDs, nie Fremdtext
        val active = jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT 1 FROM taktovia.membership m JOIN taktovia.tenant t ON t.id = m.tenant_id " +
                "WHERE m.id = ANY(CAST(? AS uuid[])) AND t.status = 'ACTIVE' AND $ACTIVE_MEMBERSHIP ${if (lock) "FOR SHARE OF m" else ""}) x",
            Int::class.java, literal,
        )
        if (active != ids.size) throw ContextRevokedException()
    }
}

/** Akteur-Referenz für Audit: Menschen `user:<subject>`, Dienstkonten `service:<subject>`; nie Namen oder Token. */
internal fun auditActor(ctx: RequestContext): de.taktovia.platform.audit.ActorRef =
    de.taktovia.platform.audit.ActorRef((if (ctx.actor.type == de.taktovia.platform.context.ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject)
