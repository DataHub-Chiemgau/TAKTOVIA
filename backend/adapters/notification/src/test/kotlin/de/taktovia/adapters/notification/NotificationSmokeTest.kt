package de.taktovia.adapters.notification

import de.taktovia.adapters.notification.api.NotificationAdapter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationSmokeTest {
    @Test
    fun `adapter-port ist ein interface`() = assertTrue(NotificationAdapter::class.java.isInterface)
}
