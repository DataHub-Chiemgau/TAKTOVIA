package de.taktovia.stafftime

import de.taktovia.stafftime.api.StafftimeApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StafftimeSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(StafftimeApi::class.java.isInterface)
}
