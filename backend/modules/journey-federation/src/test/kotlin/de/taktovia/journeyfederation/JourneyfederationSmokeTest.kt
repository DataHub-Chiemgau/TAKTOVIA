package de.taktovia.journeyfederation

import de.taktovia.journeyfederation.api.JourneyfederationApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JourneyfederationSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(JourneyfederationApi::class.java.isInterface)
}
