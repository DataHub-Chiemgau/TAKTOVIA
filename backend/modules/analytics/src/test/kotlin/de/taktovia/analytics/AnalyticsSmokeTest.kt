package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsSmokeTest {
    @Test
    fun `modul-api ist ein interface`() = assertTrue(AnalyticsApi::class.java.isInterface)
}
