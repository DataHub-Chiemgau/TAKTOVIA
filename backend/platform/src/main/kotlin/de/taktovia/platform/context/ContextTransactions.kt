package de.taktovia.platform.context

import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations

/**
 * Prüft **in der Fachtransaktion**, dass die Mitgliedschaften des Kontexts jetzt noch aktiv sind (Widerruf, Ablauf, Mandant gesperrt),
 * und sperrt sie bei schreibenden Transaktionen gegen gleichzeitigen Widerruf (`FOR SHARE`). Implementiert vom Modul `identity`.
 * Wirft [ContextRevokedException], wenn der Zugriff nicht mehr besteht.
 */
interface ContextRevalidator {
    fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean)
}

/** Die Mitgliedschaft des Kontexts ist nicht mehr aktiv; die Anwendung antwortet 403. */
class ContextRevokedException : RuntimeException("Mitgliedschaft nicht mehr aktiv")

/**
 * Einziger vorgesehener Weg für Fachoperationen eines Requests: eine Transaktion im Mandanten des [RequestContext] mit Wiederprüfung der
 * Mitgliedschaft in derselben Transaktion. So ist ein Widerruf linearisiert: Keine Anfrage kann nach dem Commit eines Widerrufs noch
 * schreibend erfolgreich sein, weil sie die Mitgliedschaftszeilen mit `FOR SHARE` hält und ein Widerruf darauf wartet bzw. die
 * Anfrage den Widerruf sieht. Lesende Transaktionen prüfen ohne Sperre (PostgreSQL verbietet Zeilensperren in read-only).
 */
class ContextTransactions(private val tx: TenantTransactions, private val revalidator: ContextRevalidator) {
    fun <T> run(ctx: RequestContext, readOnly: Boolean = false, block: (JdbcOperations) -> T): T =
        tx.run(ctx.tenant, readOnly) { jdbc ->
            if (ctx.actor.type == ActorType.HUMAN) revalidator.revalidate(jdbc, ctx, lock = !readOnly)
            block(jdbc)
        }

    /**
     * Transaktion im Mandanten **ohne Personenkontext** für Betriebsaufträge (z. B. Medienprüfung und Integritätsprüfung der Schadenfotos, WP-M3-44): keine Mitgliedschaftsprüfung, weil kein Mensch
     * handelt. Nur für zeitgesteuerte Aufträge des Betriebs gedacht, nie für Anfragen; der Mandant kommt aus dem Auftrag, nie aus einer Eingabe.
     */
    fun <T> runSystem(tenant: de.taktovia.platform.tenant.TenantId, readOnly: Boolean = false, block: (JdbcOperations) -> T): T = tx.run(tenant, readOnly, block)
}
