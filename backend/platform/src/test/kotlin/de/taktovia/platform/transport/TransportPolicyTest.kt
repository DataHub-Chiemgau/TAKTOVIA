package de.taktovia.platform.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** WP-M1-36 (SEC-002): Klartext-Konfiguration je Komponente wird abgewiesen, TLS-Konfiguration akzeptiert. */
class TransportPolicyTest {
    @Test fun `TLS-Konfiguration je Komponente ist zulaessig`() {
        assertEquals(emptyList<String>(), TransportPolicy.check(listOf(
            Triple(Endpoint.POSTGRES, "datasource", "jdbc:postgresql://db:5432/taktovia?sslmode=verify-full"),
            Triple(Endpoint.RABBITMQ, "broker", "amqps://mq:5671"),
            Triple(Endpoint.REDIS, "cache", "rediss://redis:6380"),
            Triple(Endpoint.OBJECT_STORE, "objectstore", "https://s3.example"),
            Triple(Endpoint.IDP, "idp", "https://idp.example/realms/x"),
        )))
    }

    @Test fun `Klartext je Komponente wird abgewiesen`() {
        for ((kind, uri) in listOf(
            Endpoint.POSTGRES to "jdbc:postgresql://db:5432/taktovia",
            Endpoint.POSTGRES to "jdbc:postgresql://db:5432/taktovia?sslmode=disable",
            Endpoint.POSTGRES to "jdbc:postgresql://db:5432/taktovia?sslmode=require",
            Endpoint.RABBITMQ to "amqp://mq:5672", Endpoint.REDIS to "redis://redis:6379",
            Endpoint.OBJECT_STORE to "http://s3.example", Endpoint.IDP to "http://idp.example", Endpoint.HTTP to "http://api.example",
        )) assertEquals(1, TransportPolicy.violations(kind, "x", uri).size, "$kind $uri")
    }

    @Test fun `Meldungen enthalten keine Zugangsdaten`() {
        val pw = "pw-geheim"
        val m = TransportPolicy.violations(Endpoint.RABBITMQ, "broker", "amqp://user:${pw}@mq:5672").single()
        assertFalse(m.contains(pw)); assertTrue(m.contains("amqps"))
    }

    @Test fun `nicht konfigurierte Endpunkte werden uebersprungen und Protokolle sind nur 1_2 und 1_3`() {
        assertEquals(emptyList<String>(), TransportPolicy.check(listOf(Triple(Endpoint.REDIS, "cache", null), Triple(Endpoint.IDP, "idp", " "))))
        assertEquals(listOf("TLSv1.3", "TLSv1.2"), TransportPolicy.ENABLED_PROTOCOLS)
    }
}
