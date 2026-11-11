package de.taktovia.platform.fieldprotection

import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID

/**
 * Geschützte Ciphertext-Spalte. Bezeichner sind eine feste Liste (siehe [ProtectedColumns]) und werden nie aus Eingaben gebildet.
 * Eigentümer der Tabelle bleibt das Fachmodul (`db/table_owners.d`); der Feldschutzbaustein ist der einzige Zugriffsweg auf die Ciphertext-Spalten
 * (Modulzugang `taktovia_fields`, Kap. 04 §4.6).
 */
class ProtectedColumn(val field: FieldCode, val table: String, val column: String) {
    init {
        require(IDENT.matches(table) && IDENT.matches(column)) { "ungültiger Bezeichner" }
    }
    private companion object { val IDENT = Regex("[a-z][a-z0-9_]{0,62}") }
}

object ProtectedColumns {
    val SUPPORT_NEEDS = ProtectedColumn(ProtectedFields.SUPPORT_NEEDS, "passenger_private", "support_ciphertext")
    val CONTACT = ProtectedColumn(ProtectedFields.CONTACT, "passenger_private", "contact_ciphertext")
}

/**
 * Lesen und Schreiben der verschlüsselten Spalte über die Verbindung des Modulzugangs (nie über die allgemeine Runtime-Rolle: dort scheitert
 * der Zugriff in der Datenbank). Der Aufrufer liefert die [JdbcOperations] einer `TenantTransactions.run` des Modulzugangs; RLS begrenzt auf den Mandanten.
 */
class ProtectedColumnStore(private val cipher: EnvelopeCipher) {
    sealed interface Read {
        /** Die Zeile existiert im Mandanten nicht (auch bei fremdem Mandanten: RLS macht beides ununterscheidbar). */
        data object Missing : Read
        /** Die Zeile existiert, die Spalte ist leer. */
        data object Empty : Read
        class Value(val plaintext: ByteArray) : Read { override fun toString() = "Value(***)" }
    }

    fun read(jdbc: JdbcOperations, tenant: UUID, col: ProtectedColumn, rowId: UUID): Read {
        val rows = jdbc.query("SELECT ${col.column} FROM taktovia.${col.table} WHERE tenant_id = taktovia.current_tenant() AND id = ?", { rs, _ -> rs.getBytes(1) }, rowId) // sql-lint: ok Tabelle/Spalte stammen aus der festen Aufzaehlung ProtectedColumn
        if (rows.isEmpty()) return Read.Missing
        val blob = rows.single() ?: return Read.Empty
        return Read.Value(cipher.decrypt(blob, EnvelopeCipher.context(tenant, col, rowId)))
    }

    /** Verschlüsselt und speichert; `false`, wenn die Zeile im Mandanten nicht existiert. */
    fun write(jdbc: JdbcOperations, tenant: UUID, col: ProtectedColumn, rowId: UUID, plaintext: ByteArray): Boolean =
        jdbc.update("UPDATE taktovia.${col.table} SET ${col.column} = ? WHERE tenant_id = taktovia.current_tenant() AND id = ?", // sql-lint: ok Tabelle/Spalte stammen aus der festen Aufzaehlung ProtectedColumn
            cipher.encrypt(plaintext, EnvelopeCipher.context(tenant, col, rowId)), rowId) == 1
}
