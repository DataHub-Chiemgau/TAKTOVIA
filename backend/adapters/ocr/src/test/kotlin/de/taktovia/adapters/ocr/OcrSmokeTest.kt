package de.taktovia.adapters.ocr

import de.taktovia.adapters.ocr.api.OcrAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OcrSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(OcrAdapter::class.java.isInterface)
}
