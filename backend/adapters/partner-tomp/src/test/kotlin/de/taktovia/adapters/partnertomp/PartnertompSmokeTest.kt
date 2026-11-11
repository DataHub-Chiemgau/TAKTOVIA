package de.taktovia.adapters.partnertomp

import de.taktovia.adapters.partnertomp.api.PartnertompAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartnertompSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(PartnertompAdapter::class.java.isInterface)
}
