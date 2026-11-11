package de.taktovia.platform.statemachine

import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.TestDatabase
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Testaufbau für Zustandsautomaten gegen die echte Datenbank. Operation und Mandant sind die echten Tabellen (`operation.status`, `tenant.status`);
 * Buchung und Zahlung werden an Testaggregaten in einem eigenen Schema nachgewiesen (die echten Tabellen kommen mit M2), die Testtabellen tragen
 * dieselbe Härtung (`harden_state_column`) wie die echten. Das Schema liegt außerhalb von `taktovia`, damit die Strukturtests des Kernschemas unberührt bleiben.
 */
class StateMachineTestSupport(val schema: String) : AutoCloseable {
    val runtime: HikariDataSource = TestDatabase.runtime(poolSize = 12)
    val tx: TenantTransactions = TenantTransactions(runtime)
    val adminDs: javax.sql.DataSource = TestDatabase.admin()
    val admin: JdbcTemplate = JdbcTemplate(adminDs)
    /** Für Zustände, die die Runtime-Rolle nicht ändern darf (Mandantenstatus: Plattformverwaltung). */
    val adminTx: TenantTransactions = TenantTransactions(adminDs)
    val transitioner: StateTransitioner = StateTransitioner()

    val booking: StateMachine<BookingState> = StateMachines.BOOKING.onTable("$schema.booking")
    val payment: StateMachine<PaymentState> = StateMachines.PAYMENT.onTable("$schema.payment_intent")

    init {
        TestDatabase.require()
        admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        admin.execute("CREATE SCHEMA $schema")
        for ((tbl, machine) in listOf("booking" to "booking", "payment_intent" to "payment")) {
            admin.execute("CREATE TABLE $schema.$tbl (tenant_id uuid NOT NULL REFERENCES taktovia.tenant(id), id uuid NOT NULL, revision bigint NOT NULL DEFAULT 1, state text NOT NULL, PRIMARY KEY (tenant_id, id))")
            admin.execute("ALTER TABLE $schema.$tbl ENABLE ROW LEVEL SECURITY")
            admin.execute("ALTER TABLE $schema.$tbl FORCE ROW LEVEL SECURITY")
            admin.execute("CREATE POLICY tenant_isolation ON $schema.$tbl USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())")
            admin.execute("SELECT taktovia.harden_state_column('$schema.$tbl', 'state', '$machine')")
        }
        admin.execute("GRANT USAGE ON SCHEMA $schema TO taktovia_runtime")
        admin.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA $schema TO taktovia_runtime")
    }

    fun newTenant(status: String = "ACTIVE"): TenantId = TenantId(UUID.randomUUID()).also {
        admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,?)", it.value, "sm-$it", status)
    }

    /** Legt ein Aggregat im Zustand [state] an und liefert seine ID (bei Mandant: die Mandanten-ID). */
    fun seed(machine: StateMachine<*>, tenant: TenantId, state: String): UUID {
        if (machine.name == "tenant") return tenant.value   // der Mandant selbst ist das Aggregat; sein Zustand wird beim Anlegen gesetzt
        val id = UUID.randomUUID()
        if (machine.name == "operation") {
            // Operation ohne Journey-Kette (Fremdschlüssel für diese Sitzung aus); CHECK und Übergangsregeln bleiben wirksam.
            adminDs.connection.use { c ->
                c.autoCommit = false
                c.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
                c.prepareStatement("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (?,?,?,'probe',?,now())").use {
                    it.setObject(1, tenant.value); it.setObject(2, id); it.setObject(3, UUID.randomUUID()); it.setString(4, state); it.executeUpdate()
                }
                c.commit()
            }
        } else admin.update("INSERT INTO ${machine.table} (tenant_id, id, state) VALUES (?,?,?)", tenant.value, id, state)
        return id
    }

    fun txFor(machine: StateMachine<*>) = if (machine.name == "tenant") adminTx else tx

    fun state(machine: StateMachine<*>, t: TenantId, id: UUID): Pair<String, Long> = txFor(machine).run(t, readOnly = true) { j ->
        val rev = machine.revisionColumn ?: "0"
        val tenantAndId = if (machine.tenantColumn == "id") "id = taktovia.current_tenant()" else "id = '$id'"
        j.query("SELECT ${machine.stateColumn}, $rev FROM ${machine.table} WHERE $tenantAndId", { rs, _ -> rs.getString(1) to rs.getLong(2) }).single()
    }

    fun events(t: TenantId, id: UUID, type: String): List<Map<String, Any?>> = tx.run(t, readOnly = true) { j: JdbcOperations ->
        j.queryForList("SELECT aggregate_revision, payload::text AS payload FROM taktovia.outbox WHERE aggregate_id = ? AND event_type = ? ORDER BY aggregate_revision", id, type)
    }

    override fun close() { admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE"); runtime.close() }
}
