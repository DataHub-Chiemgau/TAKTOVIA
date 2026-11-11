package de.taktovia.adapters.erpexport

import de.taktovia.adapters.erpexport.api.ErpexportAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ErpexportSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(ErpexportAdapter::class.java.isInterface)
}
