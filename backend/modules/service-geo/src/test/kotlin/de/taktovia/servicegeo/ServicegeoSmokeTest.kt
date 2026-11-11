package de.taktovia.servicegeo

import de.taktovia.servicegeo.api.ServicegeoApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServicegeoSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(ServicegeoApi::class.java.isInterface)
}
