package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.DocumentsfinanceApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentsfinanceSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(DocumentsfinanceApi::class.java.isInterface)
}
