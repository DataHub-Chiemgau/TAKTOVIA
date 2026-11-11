package de.taktovia.communicationsupport

import de.taktovia.communicationsupport.api.CommunicationsupportApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommunicationsupportSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(CommunicationsupportApi::class.java.isInterface)
}
