package de.taktovia.app.payment

import de.taktovia.app.PaymentConfiguration
import de.taktovia.platform.secret.FileSecretStore
import de.taktovia.platform.secret.SecretResolver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Schlüsselquelle des Provider-Eingangs aus Secretreferenzen (WP-M2-29): Werte nur aus der Secret-Verwaltung, Rotation ohne Neustart, fail-closed. */
class PaymentConfigurationTest {
    private val tenant = UUID.randomUUID()
    private fun env(vararg p: Pair<String, String>) = StandardEnvironment().also { it.propertySources.addFirst(MapPropertySource("test", mapOf(*p))) }
    private fun resolverProvider(r: SecretResolver?): ObjectProvider<SecretResolver> = StaticListableBeanFactory().also { f -> if (r != null) f.addBean("r", r) }.getBeanProvider(SecretResolver::class.java)
    private fun write(dir: Path, name: String, value: String) { Files.createDirectories(dir.resolve("taktovia-test-secrets")); Files.writeString(dir.resolve("taktovia-test-secrets").resolve(name), value + "\n") }

    @Test
    fun schluessel_stammen_aus_secretreferenzen_und_rotation_wirkt_ohne_neustart(@TempDir dir: Path) {
        write(dir, "psp_current", "geheim-aktuell"); write(dir, "psp_previous", "geheim-alt")
        val until = Instant.now().plusSeconds(3600)
        val keys = PaymentConfiguration().providerEventKeys(env(
            "taktovia.payments.providers.simpsp[0].tenant" to tenant.toString(), "taktovia.payments.providers.simpsp[0].secret-ref" to "secret://taktovia-test-secrets/psp_current",
            "taktovia.payments.providers.simpsp[0].previous-secret-ref" to "secret://taktovia-test-secrets/psp_previous", "taktovia.payments.providers.simpsp[0].previous-valid-until" to until.toString(),
        ), resolverProvider(SecretResolver("taktovia-test-secrets", FileSecretStore(dir))))
        val c = keys.candidates("simpsp")
        assertEquals(2, c.size); assertTrue(c.all { it.tenant.value == tenant && it.provider == "simpsp" })
        assertArrayEquals("geheim-aktuell".toByteArray(), c[0].secret()); assertEquals(null, c[0].validUntil)
        assertArrayEquals("geheim-alt".toByteArray(), c[1].secret()); assertEquals(until, c[1].validUntil)
        assertTrue(!c[0].toString().contains("geheim"), "toString nennt nie den Wert")
        write(dir, "psp_current", "geheim-rotiert")   // Rotation: Referenz bleibt, der Wert wechselt
        assertArrayEquals("geheim-rotiert".toByteArray(), keys.candidates("simpsp")[0].secret())
        assertEquals(emptyList<Any>(), keys.candidates("anderer-provider"))
    }

    @Test
    fun ohne_secret_verwaltung_fehlendem_secret_fremder_domaene_oder_klartext_gilt_kein_schluessel(@TempDir dir: Path) {
        val base = arrayOf("taktovia.payments.providers.simpsp[0].tenant" to tenant.toString())
        val resolver = SecretResolver("taktovia-test-secrets", FileSecretStore(dir))
        fun candidates(ref: String, r: SecretResolver? = resolver) = PaymentConfiguration().providerEventKeys(env(*base, "taktovia.payments.providers.simpsp[0].secret-ref" to ref), resolverProvider(r)).candidates("simpsp")
        write(dir, "psp_current", "x")
        assertEquals(1, candidates("secret://taktovia-test-secrets/psp_current").size, "Gegenprobe")
        assertEquals(0, candidates("secret://taktovia-test-secrets/psp_current", null).size, "ohne Secret-Verwaltung")
        assertEquals(0, candidates("secret://taktovia-test-secrets/fehlt").size, "Secret fehlt")
        assertEquals(0, candidates("secret://taktovia-prod-secrets/psp_current").size, "Referenz einer fremden Umgebung wird nie aufgelöst")
        assertEquals(0, candidates("klartext-geheimnis").size, "ein Klartextwert statt einer Referenz wird nie verwendet")
        assertEquals(0, PaymentConfiguration().providerEventKeys(env("taktovia.payments.providers.simpsp[0].tenant" to "kein-uuid", "taktovia.payments.providers.simpsp[0].secret-ref" to "secret://taktovia-test-secrets/psp_current"), resolverProvider(resolver)).candidates("simpsp").size, "ungültiger Mandant")
        assertEquals(0, PaymentConfiguration().providerEventKeys(env(), resolverProvider(resolver)).candidates("simpsp").size, "nichts konfiguriert")
    }
}
