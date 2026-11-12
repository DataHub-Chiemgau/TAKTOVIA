package de.taktovia.platform.storage

import de.taktovia.platform.tenant.TenantId
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Objektspeicher auf dem Dateisystem (WP-M4-21) für lokale Umgebung und Tests, solange kein S3/MinIO-Adapter angebunden ist (WP-M0-08, Docker in der Entwicklungsumgebung nicht
 * ausgeführt). Jedes Objekt liegt unter `<Wurzel>/tenants/<Mandant>/<Name>`; [ObjectKey] verhindert Pfadausbrüche, zusätzlich wird der aufgelöste Pfad gegen die Wurzel
 * des Mandanten geprüft. Schreiben geschieht über eine Temporärdatei und einen atomaren Verschiebevorgang; [putIfAbsent] überschreibt nie (Schreibschutz für Originale).
 * Der Inhaltstyp wird nicht gespeichert (der Fachbaustein führt ihn in der Datenbank).
 */
class FileObjectStore(root: Path) : TenantObjectStore {
    private val root: Path = root.toAbsolutePath().normalize().also { Files.createDirectories(it) }

    private fun target(key: ObjectKey): Path = resolve(key.tenant, key.name)

    private fun tenantRoot(tenant: TenantId): Path = root.resolve("tenants").resolve(tenant.value.toString()).normalize()

    private fun resolve(tenant: TenantId, name: String): Path {
        val base = tenantRoot(tenant)
        val p = base.resolve(name).normalize()
        require(p.startsWith(base) && p != base) { "Objektpfad außerhalb des Mandantenbereichs" }
        return p
    }

    override fun put(key: ObjectKey, bytes: ByteArray, contentType: String) {
        val p = target(key)
        Files.createDirectories(p.parent)
        val tmp = Files.createTempFile(p.parent, ".tmp-", ".part")
        try { Files.write(tmp, bytes); Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) } finally { Files.deleteIfExists(tmp) }
    }

    override fun putIfAbsent(key: ObjectKey, bytes: ByteArray, contentType: String): Boolean {
        val p = target(key)
        Files.createDirectories(p.parent)
        val tmp = Files.createTempFile(p.parent, ".tmp-", ".part")
        return try {
            Files.write(tmp, bytes)
            // Hartlink statt Verschieben: scheitert atomar, wenn das Ziel schon existiert, und das Ziel erscheint nie halb geschrieben
            try { Files.createLink(p, tmp); true } catch (_: FileAlreadyExistsException) { false }
        } finally { Files.deleteIfExists(tmp) }
    }

    override fun get(key: ObjectKey): ByteArray? = try { Files.readAllBytes(target(key)) } catch (_: java.nio.file.NoSuchFileException) { null }

    override fun delete(key: ObjectKey) { try { Files.deleteIfExists(target(key)) } catch (_: IOException) { /* nicht vorhanden oder gerade entfernt */ } }

    override fun list(tenant: TenantId, namePrefix: String): List<String> {
        val base = tenantRoot(tenant)
        if (!Files.isDirectory(base)) return emptyList()
        return Files.walk(base).use { s ->
            s.filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".tmp-") }.map { base.relativize(it).toString().replace('\\', '/') }
                .filter { it.startsWith(namePrefix) }.sorted().toList()
        }
    }

}
