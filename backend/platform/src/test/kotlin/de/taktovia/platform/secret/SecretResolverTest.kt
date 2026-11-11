package de.taktovia.platform.secret

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** WP-M1-36 (SEC-003): Konfiguration nur über Secret-Referenzen, getrennte Domänen je Umgebung, Werte nie im Klartext ausgegeben. */
class SecretResolverTest {
    @TempDir lateinit var dir: Path

    private fun put(domain: String, name: String, value: String) {
        Files.createDirectories(dir.resolve(domain)); Files.writeString(dir.resolve(domain).resolve(name), value + "\n")
    }

    @Test fun `Secret wird aus der Verwaltung der eigenen Domaene aufgeloest`() {
        put("taktovia-staging-secrets", "rabbitmq_credential", "geheim-1")
        val r = SecretResolver("taktovia-staging-secrets", FileSecretStore(dir))
        assertEquals("geheim-1", r.resolve("secret://taktovia-staging-secrets/rabbitmq_credential").reveal())
    }

    @Test fun `Secret einer anderen Umgebung wird nie aufgeloest`() {
        put("taktovia-staging-secrets", "redis_credential", "x"); put("taktovia-development-secrets", "redis_credential", "y")
        val dev = SecretResolver("taktovia-development-secrets", FileSecretStore(dir))
        assertThrows<SecretDomainMismatchException> { dev.resolve("secret://taktovia-staging-secrets/redis_credential") }
        assertEquals("y", dev.resolve("secret://taktovia-development-secrets/redis_credential").reveal())
    }

    @Test fun `fehlendes Secret und Klartext statt Referenz werden abgewiesen ohne den Wert auszugeben`() {
        val r = SecretResolver("taktovia-staging-secrets", FileSecretStore(dir))
        assertThrows<SecretNotFoundException> { r.resolve("secret://taktovia-staging-secrets/fehlt") }
        val klartext = "Sup3rGeheimesPasswort"
        val e = assertThrows<IllegalArgumentException> { SecretRef.requireRef("spring.datasource.password", klartext) }
        assertFalse(e.message!!.contains(klartext)); assertTrue(e.message!!.contains("spring.datasource.password"))
        assertFalse(SecretRef.isRef("../../etc/passwd")); assertFalse(SecretRef.isRef("secret://../x/y"))
    }

    @Test fun `SecretValue gibt den Inhalt weder in toString noch im Konfigurationsdump aus`() {
        val v = SecretValue("topsecret-123")
        assertFalse(v.toString().contains("topsecret")); assertFalse("$v".contains("123"))
        assertEquals("secret://taktovia-staging-secrets/db_runtime_credential", SecretRef("taktovia-staging-secrets", "db_runtime_credential").toString())
    }

    @Test fun `Rotation wird ohne Neustart gelesen`() {
        put("taktovia-staging-secrets", "adapter_client_secrets", "alt")
        val r = SecretResolver("taktovia-staging-secrets", FileSecretStore(dir))
        val ref = SecretRef("taktovia-staging-secrets", "adapter_client_secrets")
        assertEquals("alt", r.resolve(ref).reveal())
        put("taktovia-staging-secrets", "adapter_client_secrets", "neu")
        assertEquals("neu", r.resolve(ref).reveal())
    }
}
