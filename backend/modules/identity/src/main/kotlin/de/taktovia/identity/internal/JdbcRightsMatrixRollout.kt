package de.taktovia.identity.internal

import de.taktovia.identity.api.RightsMatrix
import de.taktovia.identity.api.RightsMatrixRollout
import de.taktovia.identity.api.RolloutResult
import de.taktovia.platform.tenant.TenantTransactions

/**
 * Protokolliert das Ausrollen der Rechtematrix (IAM-004 AK 4) in der globalen, nur anhängbaren Tabelle `rights_matrix_rollout`. Mehrere Instanzen
 * starten gleichzeitig: eine Advisory-Sperre je Transaktion serialisiert, sodass genau eine Zeile je Matrixänderung entsteht.
 */
internal class JdbcRightsMatrixRollout(private val tx: TenantTransactions, private val matrix: RightsMatrix) : RightsMatrixRollout {
    override fun record(rolledOutBy: String): RolloutResult = tx.runGlobal { j ->
        j.execute("SELECT pg_advisory_xact_lock(hashtext('taktovia.rights_matrix_rollout'))") { }
        val last = j.query("SELECT matrix_sha256 FROM taktovia.rights_matrix_rollout ORDER BY id DESC LIMIT 1", { rs, _ -> rs.getString(1).trim() }).firstOrNull()
        if (last == matrix.sha256) RolloutResult(false, matrix.version, matrix.sha256, last)
        else {
            j.update(
                "INSERT INTO taktovia.rights_matrix_rollout(matrix_version, matrix_sha256, previous_sha256, role_count, operation_count, rolled_out_by) VALUES (?,?,?,?,?,?)",
                matrix.version, matrix.sha256, last, matrix.roles.size, matrix.operations.size, rolledOutBy,
            )
            RolloutResult(true, matrix.version, matrix.sha256, last)
        }
    }
}
