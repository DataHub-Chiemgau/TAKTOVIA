package de.taktovia.app.driver

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.Revisioned
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.AckRunCommand
import de.taktovia.stafftime.api.DriverRunStopView
import de.taktovia.stafftime.api.DriverRunView
import de.taktovia.stafftime.api.DriverShiftView
import de.taktovia.stafftime.api.EndShiftCommand
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.StartShiftCommand
import jakarta.servlet.http.HttpServletRequest
import de.taktovia.platform.api.Etag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Fahrerprojektion mit starkem ETag = Planrevision (0, solange es keinen Plan gibt). */
class DriverRunResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Dienstanmeldung und Schicht-/Fahrzeugzuordnung des Fahrers (WP-M3-25, LH-6.6-DRV-001, Modul `staff-time`): eigene Schichten, Dienstantritt, Dienstende und der Run (nur bei gültiger,
 * angetretener und freigegebener Schicht mit Aufträgen). Rechte (`driver:run`, Rolle `driver`) aus der Rechtematrix im Filter, die Sitzungsprüfung (gesperrte Sitzung: 401) vor jedem
 * Aufruf; Mandant und Akteur ausschließlich aus dem Kontext, nie aus dem Body. `GET /v1/me` (Rolle und Mandant) gehört `identity`.
 */
@RestController
class DriverShiftController(private val staff: StafftimeApi) {

    /**
     * Bestätigung der Planrevision (WP-M3-27, DRV-004): `If-Match` trägt die bestätigte Revision (veraltet: 412 mit aktuellem ETag), je Fahrer und Revision genau ein Eintrag (auch von zwei
     * Geräten), fremde Schicht 404. Antwort ist die aktualisierte Fahrerprojektion mit ETag = Planrevision.
     */
    @PostMapping("/v1/driver/shifts/{shift_id}/run/acknowledgements")
    fun acknowledge(@PathVariable("shift_id") id: UUID, @RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<DriverRunResource> {
        val expected = Etag.parseIfMatch(ifMatch)
        val revision = (body["plan_revision"] as? Number)?.toLong() ?: throw ProblemException.invalid(Violation("/plan_revision", ViolationCode.INVALID_FORMAT))
        val at = (body["acknowledged_at"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: throw ProblemException.invalid(Violation("/acknowledged_at", ViolationCode.INVALID_FORMAT))
        val device = (body["device_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/device_id", ViolationCode.INVALID_FORMAT)) }
        val r = staff.planAcks.acknowledge(RequestContexts.require(request), id, expected, AckRunCommand(revision, at, device))
        return ResponseEntity.ok().body(DriverRunResource(json(r.run), r.run.planRevision ?: 0L))
    }

    @GetMapping("/v1/driver/shifts")
    fun mine(request: HttpServletRequest): Map<String, Any?> =
        linkedMapOf("items" to staff.driverShifts.mine(RequestContexts.require(request)).map { json(it) }, "as_of" to Instant.now().toString())

    @PostMapping("/v1/driver/shift-starts")
    fun start(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val r = staff.driverShifts.start(RequestContexts.require(request), StartShiftCommand(uuid(body, "shift_id"), uuid(body, "vehicle_id"), uuid(body, "device_id")))
        return linkedMapOf("shift" to json(r.shift), "already_started" to r.alreadyStarted)
    }

    @PostMapping("/v1/driver/shift-ends")
    fun end(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> =
        json(staff.driverShifts.end(RequestContexts.require(request), EndShiftCommand(uuid(body, "shift_id"), uuid(body, "device_id"))))

    /**
     * Fahrerprojektion mit starkem ETag = Planrevision und bedingtem Abruf: `If-None-Match` mit dem aktuellen ETag liefert 304 ohne Inhalt, **solange sich nur der Plan-Stand gemerkt
     * haben könnte**: bei gültiger, freigegebener Schicht mit Stopps. Eine Schicht ohne Aufträge (Leerzustand, Schichtende, Entzug, fehlende Freigabe) antwortet immer 200, damit der
     * Client nie einen alten Plan behält, wenn der Dienst endete (WP-M3-26, WP-M3-32). Solange eine bestätigungspflichtige Änderung offen ist, antwortet der Abruf ebenfalls immer 200 (WP-M3-27): der Client behält die Pflicht nie stillschweigend.
     */
    @GetMapping("/v1/driver/shifts/{shift_id}/run")
    fun run(@PathVariable("shift_id") id: UUID, @RequestHeader("If-None-Match", required = false) ifNoneMatch: String?, request: HttpServletRequest): ResponseEntity<DriverRunResource> {
        val r = staff.driverShifts.run(RequestContexts.require(request), id)
        val revision = r.planRevision ?: 0L
        val etag = Etag.format(revision)
        if (ifNoneMatch != null && ifNoneMatch.trim() == etag && r.shiftValid && r.releaseState == de.taktovia.stafftime.api.RunReleaseState.RELEASED && r.stops.isNotEmpty() && !r.acknowledgementRequired)
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build()
        return ResponseEntity.ok().body(DriverRunResource(json(r), revision))
    }

    /** Fahrgastliste des Check-ins (WP-M3-30): nur Buchungskürzel und Personenzahl, nie Kontakt-, Zahlungs- oder Codedaten. */
    @GetMapping("/v1/driver/shifts/{shift_id}/passengers")
    fun passengers(@PathVariable("shift_id") id: UUID, request: HttpServletRequest): Map<String, Any?> {
        val v = staff.driverShifts.expectedPassengers(RequestContexts.require(request), id)
        return linkedMapOf(
            "shift_id" to v.shiftId.toString(), "plan_revision" to v.planRevision, "current_route_stop_id" to v.currentStopId?.toString(),
            "passengers" to v.entries.map { linkedMapOf("route_stop_id" to it.routeStopId.toString(), "next_stop" to it.next, "booking_id" to it.passenger.bookingId.toString(), "booking_ref" to it.passenger.bookingRef, "persons" to it.passenger.persons) },
        )
    }

    private fun uuid(body: Map<String, Any?>, key: String): UUID =
        (body[key] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw ProblemException.invalid(Violation("/$key", ViolationCode.INVALID_FORMAT))

    private fun json(s: DriverShiftView): Map<String, Any?> = linkedMapOf(
        "shift_id" to s.shiftId.toString(), "vehicle_id" to s.vehicleId.toString(), "vehicle_label" to s.vehicleLabel, "starts_at" to s.startsAt.toString(), "ends_at" to s.endsAt.toString(),
        "timezone" to s.timezone, "state" to s.state, "startable_from" to s.startableFrom.toString(), "started_at" to s.startedAt?.toString(), "ended_at" to s.endedAt?.toString(), "valid" to s.valid,
    )

    private fun json(r: DriverRunView): Map<String, Any?> = linkedMapOf(
        "shift_id" to r.shiftId.toString(), "vehicle_id" to r.vehicleId.toString(), "shift_state" to r.shiftState, "shift_valid" to r.shiftValid, "release_state" to r.releaseState.name,
        "missing_requirements" to r.missingRequirements.map { linkedMapOf("code" to it) }, "plan_revision" to r.planRevision, "plan_as_of" to r.planAsOf.toString(),
        "stops" to r.stops.map { json(it) }, "acknowledged_revision" to r.acknowledgedRevision, "revision_state" to r.revisionState.name, "acknowledgement_required" to r.acknowledgementRequired,
        "base_revision" to r.baseRevision,
        "removed_stops" to r.removedStops.map { linkedMapOf("stop_name" to it.stopName, "kind" to it.kind, "planned_at" to it.plannedAt.toString(), "booking_ids" to it.bookingIds.map { id -> id.toString() }) },
    )

    private fun json(s: DriverRunStopView): Map<String, Any?> = linkedMapOf<String, Any?>(
        "route_stop_id" to s.routeStopId.toString(), "sequence" to s.sequence, "kind" to s.kind, "planned_at" to s.plannedAt.toString(), "forecast_at" to s.forecastAt?.toString(), "stop_name" to s.stopName,
        "position" to linkedMapOf("latitude" to s.latitude, "longitude" to s.longitude), "passenger_count" to s.passengerCount, "booking_ids" to s.bookingIds.map { it.toString() }, "change" to s.change,
        "previous_planned_at" to s.previousPlannedAt?.toString(), "support_hints" to s.supportHints, "stop_revision_id" to s.stopRevisionId?.toString(), "boarding_count" to s.boardingCount, "alighting_count" to s.alightingCount,
        "resource_need" to s.resourceNeed?.let { linkedMapOf("seats" to it.seats, "wheelchair" to it.wheelchair, "equipment" to it.equipment) },
    ).also { m ->
        s.assistance?.let { m["assistance"] = linkedMapOf("wheelchair_required" to it.wheelchairRequired, "boarding_assist" to it.boardingAssist, "alighting_assist" to it.alightingAssist) }
    }
}
