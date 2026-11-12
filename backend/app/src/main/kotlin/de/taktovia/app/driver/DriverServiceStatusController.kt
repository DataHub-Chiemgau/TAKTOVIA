package de.taktovia.app.driver

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.ServiceStatus
import de.taktovia.stafftime.api.ServiceStatusReport
import de.taktovia.stafftime.api.ServiceStatusView
import de.taktovia.stafftime.api.StafftimeApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Fahrzeug- und Dienststatus des Fahrers (WP-M3-31, LH-6.6-DRV-007, Modul `staff-time`): Statusmeldung der eigenen, angetretenen Schicht und Abruf von Status und Historie. Rechte (`driver:events`,
 * `driver:run`, Rolle `driver`) aus der Rechtematrix im Filter; Mandant und Akteur ausschließlich aus dem Kontext. `NOT_READY` ist nie ein Meldeziel (Anfangszustand).
 */
@RestController
class DriverServiceStatusController(private val staff: StafftimeApi) {

    @PostMapping("/v1/driver/shifts/{shift_id}/service-status")
    fun report(@PathVariable("shift_id") shiftId: UUID, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val target = ServiceStatus.parse(body["target_status"] as String)?.takeIf { it != ServiceStatus.NOT_READY } ?: throw ProblemException.invalid(Violation("/target_status", ViolationCode.NOT_IN_ENUM))
        val cmd = ServiceStatusReport(
            eventId = UUID.fromString(body["event_id"] as String), shiftId = shiftId, vehicleId = (body["vehicle_id"] as String?)?.let(UUID::fromString), deviceId = (body["device_id"] as String?)?.let(UUID::fromString),
            status = target, occurredAt = OffsetDateTime.parse(body["occurred_at"] as String).toInstant(), deviceRecordedAt = OffsetDateTime.parse(body["device_recorded_at"] as String).toInstant(),
            offlineRecorded = body["offline_recorded"] as Boolean,
        )
        val r = staff.serviceStatus.report(RequestContexts.require(request), cmd)
        return linkedMapOf("state" to r.outcome.name, "reason_code" to r.reasonCode, "received_at" to r.receivedAt.toString(), "status" to json(r.status))
    }

    @GetMapping("/v1/driver/shifts/{shift_id}/service-status")
    fun current(@PathVariable("shift_id") shiftId: UUID, request: HttpServletRequest): Map<String, Any?> = json(staff.serviceStatus.current(RequestContexts.require(request), shiftId))

    private fun json(v: ServiceStatusView): Map<String, Any?> = linkedMapOf(
        "shift_id" to v.shiftId.toString(), "vehicle_id" to v.vehicleId.toString(), "status" to v.status.name, "dispatchable" to v.status.dispatchable, "revision" to v.revision, "changed_at" to v.changedAt?.toString(),
        "history" to v.history.map { h ->
            linkedMapOf("from" to h.from?.name, "to" to h.to.name, "trigger" to h.trigger.name, "occurred_at" to h.occurredAt?.toString(), "recorded_at" to h.recordedAt.toString(), "offline_recorded" to h.offlineRecorded, "revision" to h.revision)
        },
    )
}
