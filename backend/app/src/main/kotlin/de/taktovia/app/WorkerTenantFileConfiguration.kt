package de.taktovia.app

import de.taktovia.platform.outbox.TenantSource
import de.taktovia.platform.tenant.TenantId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Mandantenliste des Betriebs für die Zeitgeber (Operation-Runner, Zahlungskopplung, Warteschlangenmetriken), WP-M5-23 (OP-06/OP-08: die Runtime-Rolle darf Mandanten wegen RLS nicht
 * aufzählen). Aktiv nur mit `taktovia.workers.tenant-file`: Datei mit einer Mandanten-ID je Zeile (Kommentarzeilen mit `#`), die bei **jedem** Lauf neu gelesen wird (neue Mandanten
 * ohne Neustart). Eine fehlende oder unlesbare Datei ergibt eine leere Liste (kein Lauf), nie eine Annahme; ungültige Zeilen werden übersprungen. Ohne die Eigenschaft gibt es
 * weiterhin keine [TenantSource]-Bean und keine Zeitgeber (Betriebswerkzeug stellt sie bereit).
 */
@Configuration
@ConditionalOnProperty("taktovia.workers.tenant-file")
class WorkerTenantFileConfiguration {
    @Bean
    fun workerTenantSource(@Value("\${taktovia.workers.tenant-file}") file: String): TenantSource = tenantFileSource(file)
}

/** Mandantenliste aus einer Datei, bei jedem Aufruf neu gelesen (siehe [WorkerTenantFileConfiguration]). */
fun tenantFileSource(file: String): TenantSource = TenantSource {
    try {
        Files.readAllLines(Path.of(file)).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { runCatching { TenantId.parse(it) }.getOrNull() }
    } catch (e: NoSuchFileException) { emptyList() } catch (e: java.io.IOException) { emptyList() }
}
