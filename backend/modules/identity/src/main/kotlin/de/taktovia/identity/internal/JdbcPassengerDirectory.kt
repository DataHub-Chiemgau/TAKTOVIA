package de.taktovia.identity.internal

import de.taktovia.identity.api.ChannelStatus
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.PassengerDirectory
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.fieldprotection.ProtectedColumns
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.util.UUID

/**
 * Verzeichnis für den Nachrichtenversand (WP-M2-32). Der Kanalstatus kommt aus `passenger_contact` (Runtime-Rolle, RLS), der Kontakt aus der verschlüsselten Spalte
 * über den Modulzugang; ein Kontakt, dessen Token nicht (mehr) zum verifizierten Kanal passt, wird nicht ausgegeben (Kontaktänderung setzt den Kanal auf `UNVERIFIED`).
 */
internal class JdbcPassengerDirectory(
    private val tx: TenantTransactions, private val fieldTx: TenantTransactions, private val store: ProtectedColumnStore,
) : PassengerDirectory {

    override fun verifiedContact(tenant: TenantId, passengerId: UUID, channel: ContactChannel): String? {
        val status = tx.run(tenant, readOnly = true) { j ->
            j.query("SELECT status FROM taktovia.passenger_contact WHERE passenger_id = ? AND channel = ?", { rs, _ -> ChannelStatus.valueOf(rs.getString(1)) }, passengerId, channel.name).firstOrNull()
        }
        if (status != ChannelStatus.VERIFIED) return null
        val doc = fieldTx.run(tenant, readOnly = true) { fj -> store.read(fj, tenant.value, ProtectedColumns.CONTACT, passengerId) }
        val (email, phone) = ContactDocument.decode((doc as? ProtectedColumnStore.Read.Value)?.plaintext)
        return if (channel == ContactChannel.EMAIL) email else phone
    }

    override fun locale(tenant: TenantId, passengerId: UUID): String? = tx.run(tenant, readOnly = true) { j ->
        j.query("SELECT locale FROM taktovia.passenger_private WHERE id = ?", { rs, _ -> rs.getString(1) }, passengerId).firstOrNull()
    }
}
