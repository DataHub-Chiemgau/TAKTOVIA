package de.taktovia.archfixtures.bad.isolation

import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantId

/** Verstoß: Objektspeicher mit Zugriff über rohen Pfad (ohne Mandantenpräfix). */
class BadObjectStore : TenantObjectStore {
    override fun put(key: ObjectKey, bytes: ByteArray, contentType: String) = Unit
    override fun get(key: ObjectKey): ByteArray? = null
    override fun delete(key: ObjectKey) = Unit
    override fun list(tenant: TenantId, namePrefix: String): List<String> = emptyList()
    fun get(path: String): ByteArray? = null
}
