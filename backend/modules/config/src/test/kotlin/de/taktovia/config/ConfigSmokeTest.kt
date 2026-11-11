package de.taktovia.config

import de.taktovia.config.api.ConfigApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(ConfigApi::class.java.isInterface)
}
