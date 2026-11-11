package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookingdispatchSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(BookingdispatchApi::class.java.isInterface)
}
