package de.taktovia.bookingdispatch.etafixture

import de.taktovia.platform.readports.DriverReadPort
import java.util.UUID

/** Absichtlich verletzende Fixture (nur Test): ein ETA-Baustein mit Zugriff auf Fahrerprofile und Fahrer-Kennung. Ohne sie wäre nicht belegt, dass die Architekturregeln anschlagen. */
class EtaDriverAwareModel(private val drivers: DriverReadPort) {
    val driverId: UUID? = null
    fun referenceFor(driverId: UUID): Int = drivers.hashCode() + driverId.hashCode()
}
