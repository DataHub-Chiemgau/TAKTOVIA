package de.taktovia.adapters.timetablegtfs

import de.taktovia.adapters.timetablegtfs.api.TimetablegtfsAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimetablegtfsSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(TimetablegtfsAdapter::class.java.isInterface)
}
