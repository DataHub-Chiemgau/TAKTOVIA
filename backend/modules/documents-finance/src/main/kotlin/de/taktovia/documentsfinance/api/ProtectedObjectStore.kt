package de.taktovia.documentsfinance.api

import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId

/**
 * Objektspeicher mit Schreibschutz für Belegoriginale (WP-M4-23): Objekte unter `…/originals/` dürfen nur einmal geschrieben ([putIfAbsent]) und nie überschrieben oder gelöscht werden.
 * Alle Bausteine des Moduls arbeiten ausschließlich über diesen Schutz; die Anwendung darf den Speicher nicht an anderer Stelle für Originale beschreibbar herausgeben. Zusätzlich gilt:
 * die Runtime-Rolle der Datenbank kann die Revisionen nicht ändern (Trigger, Rechte), und kein API-Pfad nimmt einen Objektschlüssel entgegen.
 */
class ProtectedObjectStore(private val delegate: TenantObjectStore) : TenantObjectStore {
    private fun isOriginal(key: ObjectKey) = "/originals/" in "/${key.name}"

    override fun put(key: ObjectKey, bytes: ByteArray, contentType: String) {
        if (isOriginal(key)) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Belegoriginale sind schreibgeschützt.")
        delegate.put(key, bytes, contentType)
    }

    override fun putIfAbsent(key: ObjectKey, bytes: ByteArray, contentType: String): Boolean = delegate.putIfAbsent(key, bytes, contentType)

    override fun get(key: ObjectKey): ByteArray? = delegate.get(key)

    override fun delete(key: ObjectKey) {
        if (isOriginal(key)) throw ProblemException(ErrorCode.IMMUTABLE_RECORD, "Belegoriginale dürfen nicht gelöscht werden.")
        delegate.delete(key)
    }

    override fun list(tenant: TenantId, namePrefix: String): List<String> = delegate.list(tenant, namePrefix)
}
