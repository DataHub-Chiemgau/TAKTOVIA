package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.FleetsafetyApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FleetsafetySmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(FleetsafetyApi::class.java.isInterface)
}
