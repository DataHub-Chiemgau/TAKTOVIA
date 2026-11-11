package de.taktovia.adapters.psp

import de.taktovia.adapters.psp.api.PspAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PspSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(PspAdapter::class.java.isInterface)
}
