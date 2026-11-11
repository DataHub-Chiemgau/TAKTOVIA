package de.taktovia.platform.tenant

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

/** Typisierte Mandanten-ID. `tenant_id IS NULL` gibt es nicht (Kap. 04 §4.1). */
@JvmInline
value class TenantId(val value: UUID) {
    override fun toString() = value.toString()
    companion object { fun parse(s: String) = TenantId(UUID.fromString(s)) }
}

/** Reservierte Plattformmandanten (WP-M1-12): der Systemmandant trägt Ereignisse ohne Mandantenkontext, ist `SUSPENDED` und nie Fachmandant. Der Plattformbetrieb legt ihn an (`python db/db.py provision-system-tenant`); die Runtime-Rolle kann keine Mandanten anlegen. */
object PlatformTenants {
    val SYSTEM = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
}

/**
 * Führt Fachoperationen in genau einer Transaktion mit genau einem Mandantenkontext aus (Kap. 04 §4.6, WP-M1-01).
 *
 * Der Kontext wird mit `set_config('app.tenant_id', ?, true)` gesetzt, also transaktionslokal: nach COMMIT oder
 * ROLLBACK wirkt kein Kontext fort, auch nicht bei Poolwiederverwendung. Sessionweite Kontexte sind verboten
 * (siehe `SessionContextRuleTest`). Verbindung und Parameter werden nie zusammengesetzt, der Wert ist gebunden.
 *
 * Die Datenbankverbindung muss die eingeschränkte Runtime-Rolle sein; das prüft `RuntimeRoleGuard` beim Start.
 */
class TenantTransactions(dataSource: DataSource) {
    private val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val jdbc = JdbcTemplate(dataSource)

    /** Eine Transaktion für [tenant]. Der Block darf nur die übergebene [JdbcOperations] verwenden. */
    fun <T> run(tenant: TenantId, readOnly: Boolean = false, block: (JdbcOperations) -> T): T {
        val t = TransactionTemplate(tx.transactionManager!!).also { it.isReadOnly = readOnly }
        // Der Block darf null liefern (z. B. „nicht gefunden“); `execute` meldet dann null, das ist kein Fehler.
        @Suppress("UNCHECKED_CAST")
        return t.execute {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String::class.java, tenant.toString())
            block(jdbc)
        } as T
    }

    /**
     * Transaktion **ohne** Mandanten- oder Subjektkontext für globale Plattformtabellen ohne `tenant_id` (z. B. Ausrollprotokoll der
     * Rechtematrix, WP-M1-07). Mandantenlokale Tabellen bleiben dabei durch RLS unsichtbar. Nicht für Fachoperationen verwenden.
     */
    fun <T> runGlobal(block: (JdbcOperations) -> T): T {
        @Suppress("UNCHECKED_CAST")
        return TransactionTemplate(tx.transactionManager!!).execute { block(jdbc) } as T
    }

    /**
     * Lookup vor gesetztem Tenantkontext (WP-M1-06, OP-06): setzt **transaktionslokal** `app.subject_key` auf das geprüfte Subject
     * (`issuer`, `subject` aus dem verifizierten Token). Nur damit liefert `membership_subject_index` Zeilen, und zwar ausschließlich die
     * dieses Subjects. Es gibt keinen Tenantkontext; alle anderen Tabellen bleiben unsichtbar. Nur für den Identitätsbaustein gedacht.
     */
    fun <T> runForSubject(issuer: String, subject: String, block: (JdbcOperations) -> T): T {
        val t = TransactionTemplate(tx.transactionManager!!).also { it.isReadOnly = true }
        @Suppress("UNCHECKED_CAST")
        return t.execute {
            jdbc.queryForObject("SELECT set_config('app.subject_key', ?, true)", String::class.java, issuer + "\n" + subject)
            block(jdbc)
        } as T
    }

    /**
     * Worker-/Batchvariante: iteriert begrenzt über die übergebenen Mandanten, je Mandant eine eigene Transaktion mit
     * neuem Kontext. Ein Fehler bei einem Mandanten bricht dessen Transaktion ab und lässt keinen Kontext zurück;
     * die Fehler werden gesammelt zurückgegeben, die übrigen Mandanten laufen weiter.
     */
    fun <T> forEachTenant(tenants: Collection<TenantId>, block: (TenantId, JdbcOperations) -> T): Map<TenantId, Result<T>> {
        require(tenants.size <= MAX_TENANTS_PER_BATCH) { "Batch über ${tenants.size} Mandanten überschreitet das Limit $MAX_TENANTS_PER_BATCH" }
        return tenants.distinct().associateWith { t -> runCatching { run(t) { j -> block(t, j) } } }
    }

    companion object { const val MAX_TENANTS_PER_BATCH = 1_000 }
}
