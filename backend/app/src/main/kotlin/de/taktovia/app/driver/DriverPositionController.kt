package de.taktovia.app.driver

import de.taktovia.app.identity.RequestContexts
import de.taktovia.stafftime.api.PositionBatch
import de.taktovia.stafftime.api.StafftimeApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Positionsannahme der Fahrer-App (WP-M3-34, OP-04): nur für die eigene, angetretene Schicht und ihr Fahrzeug, von einem an die Sitzung gebundenen Gerät. Rechte (`driver:positions`,
 * Rolle `driver`) aus der Rechtematrix im Filter, die Sitzungsprüfung (gesperrte Sitzung: 401) vor jedem Aufruf; Mandant und Akteur ausschließlich aus dem Kontext.
 */
@RestController
class DriverPositionController(private val staff: StafftimeApi) {

    @PostMapping("/v1/driver/positions")
    fun record(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val r = staff.positions.record(RequestContexts.require(request), PositionBatch.parse(body))
        return linkedMapOf(
            "accepted" to r.accepted, "rejected" to r.rejected.size, "received_at" to r.receivedAt.toString(),
            "rejections" to r.rejected.map { linkedMapOf("index" to it.index, "reason" to it.reason.name) },
        )
    }
}
