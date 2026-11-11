package de.taktovia.adapters.partnerosdm

import de.taktovia.adapters.partnerosdm.api.PartnerosdmAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartnerosdmSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(PartnerosdmAdapter::class.java.isInterface)
}
