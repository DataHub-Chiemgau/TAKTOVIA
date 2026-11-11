package de.taktovia.farepayment

import de.taktovia.farepayment.api.FarepaymentApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FarepaymentSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(FarepaymentApi::class.java.isInterface)
}
