package de.taktovia.archfixtures.bad.config

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/** Absichtlich fehlerhaft (WP-M5-07): modulinterne Rundung, Gleitkomma für Geld, Ortszeit ohne Zone. Die Selbsttests verlangen, dass jede Regel hier anschlägt. */
class ZeitGeldVerstoss {
    fun steuerMinor(netMinor: Long, rate: Double): Double = netMinor * rate                                      // Gleitkomma für Geld
    fun gerundet(v: BigDecimal): BigDecimal = v.setScale(0, RoundingMode.HALF_UP)                                  // eigene Rundung
    fun beginn(): LocalDateTime = LocalDateTime.of(2026, 10, 25, 2, 30)                                            // Ortszeit ohne Zone
}
