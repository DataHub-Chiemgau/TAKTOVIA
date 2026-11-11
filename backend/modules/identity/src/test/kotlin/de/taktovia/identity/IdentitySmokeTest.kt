package de.taktovia.identity

import de.taktovia.identity.api.IdentityApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentitySmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(IdentityApi::class.java.isInterface)
}
