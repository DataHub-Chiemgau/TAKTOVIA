package de.taktovia.app.driver

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.BoardingCodeValue
import de.taktovia.stafftime.api.DevicePlatform
import de.taktovia.stafftime.api.DriverDeviceView
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventResult
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.NoShowReason
import de.taktovia.stafftime.api.RegisterDriverDevice
import de.taktovia.stafftime.api.StafftimeApi
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

class DriverDeviceResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Fahrer-App-Schnittstellen (WP-M3-24, Modul `staff-time`): Geräte-/Sitzungsbindung und Ereigniseingang. Rechte (`driver:devices`, `driver:events`) aus der Rechtematrix im Filter;
 * Mandant und Akteur ausschließlich aus dem Kontext, nie aus dem Body. Die Sitzungsprüfung (gesperrte Sitzung: 401) läuft vor jedem Aufruf im Anfragefilter.
 */
@RestController
class DriverController(private val staff: StafftimeApi) {

    @PostMapping("/v1/driver/devices")
    fun registerDevice(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<DriverDeviceResource> {
        val cmd = RegisterDriverDevice(UUID.fromString(body["device_id"] as String), DevicePlatform.valueOf(body["platform"] as String), body["app_version"] as String, body["push_token_ref"] as String?)
        val v = staff.driverDevices.register(RequestContexts.require(request), cmd)
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/v1/driver/devices/${v.deviceId}")).body(DriverDeviceResource(json(v), v.revision))
    }

    @DeleteMapping("/v1/driver/devices/{device_id}")
    fun revokeDevice(@PathVariable("device_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, request: HttpServletRequest): ResponseEntity<Void> {
        staff.driverDevices.revoke(RequestContexts.require(request), id, Etag.parseIfMatch(ifMatch))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/v1/driver/events")
    fun recordEvent(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val cmd = DriverEventCommand(
            eventId = UUID.fromString(body["event_id"] as String), deviceId = (body["device_id"] as String?)?.let(UUID::fromString), shiftId = UUID.fromString(body["shift_id"] as String),
            vehicleId = UUID.fromString(body["vehicle_id"] as String), planRevision = (body["plan_revision"] as Number).toLong(),
            type = requireNotNull(DriverEventType.parse(body["event_type"] as String)) { "event_type ist durch das Schema geprüft" },
            bookingId = (body["booking_id"] as String?)?.let(UUID::fromString), routeStopId = (body["route_stop_id"] as String?)?.let(UUID::fromString),
            occurredAt = OffsetDateTime.parse(body["occurred_at"] as String).toInstant(), deviceRecordedAt = OffsetDateTime.parse(body["device_recorded_at"] as String).toInstant(),
            offlineRecorded = body["offline_recorded"] as Boolean,
            noShowReason = (body["no_show_reason"] as String?)?.let(NoShowReason::valueOf), noShowWaitSeconds = (body["no_show_wait_seconds"] as Number?)?.toInt(),
            boardingCode = (body["boarding_code"] as String?)?.let { c -> runCatching { BoardingCodeValue(c) }.getOrElse { throw ProblemException.invalid(Violation("/boarding_code", ViolationCode.PATTERN_MISMATCH)) } },
        )
        return json(staff.driverEvents.record(RequestContexts.require(request), cmd))
    }

    private fun json(v: DriverDeviceView): Map<String, Any?> = linkedMapOf(
        "device_id" to v.deviceId.toString(), "session_id" to v.sessionId.toString(), "platform" to v.platform.name, "app_version" to v.appVersion,
        "registered_at" to v.registeredAt.toString(), "revoked_at" to v.revokedAt?.toString(), "revision" to v.revision,
    )

    private fun json(r: DriverEventResult): Map<String, Any?> = linkedMapOf(
        "event_id" to r.eventId.toString(), "state" to r.outcome.name, "received_at" to r.receivedAt.toString(), "current_plan_revision" to r.currentPlanRevision,
        "reason_code" to r.reasonCode, "original_state" to r.originalOutcome?.takeIf { r.outcome.name == "DUPLICATE" }?.name,
    )
}
