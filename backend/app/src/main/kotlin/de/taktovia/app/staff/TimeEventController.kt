package de.taktovia.app.staff

import de.taktovia.app.identity.RequestContexts
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.api.Paging
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.BackfillTimeEvent
import de.taktovia.stafftime.api.DerivedTime
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventResult
import de.taktovia.stafftime.api.TimeEventSource
import de.taktovia.stafftime.api.TimeEventView
import de.taktovia.stafftime.api.WorkInterval
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import de.taktovia.platform.time.ZonedMoment
import de.taktovia.platform.time.IanaZones
import de.taktovia.platform.time.Rfc3339
import de.taktovia.stafftime.api.LocalPresentation
import de.taktovia.stafftime.api.WorkTime
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Ist-Arbeitszeit als Zeitereignisse (WP-M4-08, Modul `staff-time`). Rechte (`time-events:write|read|manage`) aus der Rechtematrix im Filter; die Person der Selbsterfassung ist der
 * Akteur, nie Teil des Bodys. Eine Wiederholung mit gleicher Ereignis-ID und gleichem Inhalt liefert 200 mit dem gespeicherten Ereignis, die Erstwirkung 201.
 */
@RestController
class TimeEventController(private val staff: StafftimeApi, private val codec: CursorCodec) {

    @GetMapping("/v1/time-events")
    fun list(
        @RequestParam(required = false) limit: String?, @RequestParam(required = false) cursor: String?, @RequestParam(name = "person_ref", required = false) person: String?,
        @RequestParam(required = false) from: String?, @RequestParam(required = false) to: String?, @RequestParam(name = "shift_id", required = false) shift: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        checkPerson(person)
        val f = instant(from, "from"); val t = instant(to, "to")
        val shiftId = shift?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/shift_id", ViolationCode.INVALID_FORMAT)) }
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "time-events|${person ?: "-"}|${f ?: "-"}|${t ?: "-"}|${shiftId ?: "-"}")
        val lim = Paging.limit(limit)
        val rows = staff.timeEvents.list(ctx, person, f, t, shiftId, lim, Paging.position(cursor, codec, scope)?.let { it.createdAt to it.id })
        return Paging.page(rows, lim, { CursorPosition(it.receivedAt, it.eventId) }, codec, scope, Instant.now()).toMap { json(it) }
    }

    @PostMapping("/v1/time-events")
    fun record(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val moment = moment(body)
        val cmd = RecordTimeEvent(
            UUID.fromString(body["event_id"] as String), kind(body["kind"] as String), moment.instant,
            OffsetDateTime.parse(body["device_recorded_at"] as String).toInstant(), TimeEventSource.valueOf(body["channel"] as String), (body["shift_id"] as String?)?.let(UUID::fromString), moment.zone,
        )
        return respond(staff.timeEvents.record(RequestContexts.require(request), cmd))
    }

    @PostMapping("/v1/time-events/backfills")
    fun backfill(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val moment = moment(body)
        val cmd = BackfillTimeEvent(
            UUID.fromString(body["event_id"] as String), body["person_ref"] as String, kind(body["kind"] as String), moment.instant,
            (body["shift_id"] as String?)?.let(UUID::fromString), body["reason"] as String, moment.zone,
        )
        return respond(staff.timeEvents.backfill(RequestContexts.require(request), cmd))
    }

    @GetMapping("/v1/time-events/intervals")
    fun intervals(@RequestParam(name = "person_ref", required = false) person: String?, @RequestParam from: String, @RequestParam to: String, request: HttpServletRequest): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        checkPerson(person)
        val f = instant(from, "from")!!; val t = instant(to, "to")!!
        val d: DerivedTime = staff.timeEvents.intervals(ctx, person, f, t)
        return linkedMapOf(
            "person_ref" to (person ?: ctx.actor.subject), "from" to f.toString(), "to" to t.toString(), "intervals" to d.intervals.map { json(it) },
            "anomalies" to d.anomalies.map { mapOf("event_id" to it.eventId.toString(), "code" to it.code.name) },
        )
    }

    /** Soll/Ist je Tag (WP-M4-09): reine Leseprojektion, Rechte wie die übrigen Leseoperationen. */
    @GetMapping("/v1/time-events/target-actual")
    fun targetActual(
        @RequestParam(name = "person_ref", required = false) person: String?, @RequestParam from: String, @RequestParam to: String, @RequestParam(required = false) timezone: String?, request: HttpServletRequest,
    ): Map<String, Any?> {
        val ctx = RequestContexts.require(request)
        checkPerson(person)
        fun date(raw: String, name: String) = runCatching { java.time.LocalDate.parse(raw) }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT))
        val zone = runCatching { java.time.ZoneId.of(timezone ?: "Europe/Berlin") }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/timezone", ViolationCode.INVALID_FORMAT))
        val days = staff.timeTargetActual.days(ctx, person, date(from, "from"), date(to, "to"), zone)
        return linkedMapOf("person_ref" to (person ?: ctx.actor.subject), "timezone" to zone.id, "days" to days.map { d ->
            linkedMapOf(
                "day" to d.day.toString(),
                "planned" to d.planned.map { linkedMapOf("shift_id" to it.shiftId.toString(), "starts_at" to it.startsAt.toString(), "ends_at" to it.endsAt.toString(), "timezone" to it.timezone.id, "planned_seconds" to it.plannedSeconds, "state" to it.state) },
                "planned_seconds" to d.plannedSeconds, "actual" to d.actual.map { json(it) }, "actual_net_seconds" to d.actualNetSeconds, "flags" to d.flags.map { it.name }, "hints" to d.hints.map(RosterJson::hint),
            )
        })
    }

    /**
     * Ereigniszeit mit Zone (WP-M4-10): fehlt `time_zone`, gilt die Plattformzone (kein Zonenname: 422). `occurred_at` trägt immer einen Offset und ist damit ein eindeutiger Zeitpunkt
     * (auch ein Stempel in UTC oder mit dem anderen Herbst-Offset). Liegt die geschriebene Ortszeit in der Frühjahrslücke der Zone, wird sie mit 422 abgelehnt, nie verschoben.
     */
    private fun moment(body: Map<String, Any?>): ZonedMoment {
        val odt = Rfc3339.parse(body["occurred_at"] as String?, "/occurred_at"); val zone = IanaZones.check((body["time_zone"] as String?) ?: WorkTime.DEFAULT_ZONE.id, "/time_zone")
        if (!odt.ok || !zone.ok) throw ProblemException.invalid(*(odt.violations + zone.violations).toTypedArray())
        val o = odt.value!!; val z = zone.value!!
        if (z.rules.getValidOffsets(o.toLocalDateTime()).isEmpty()) throw ProblemException.invalid(Violation("/occurred_at", ViolationCode.LOCAL_TIME_NONEXISTENT))
        return ZonedMoment(o.toInstant(), z)
    }

    private fun respond(r: TimeEventResult): ResponseEntity<Map<String, Any?>> = ResponseEntity.status(if (r.created) HttpStatus.CREATED else HttpStatus.OK).body(json(r.event))

    private fun kind(code: String) = requireNotNull(TimeEventKind.entries.firstOrNull { it.name == code }) { "kind ist durch das Schema geprüft" }

    private fun checkPerson(person: String?) {
        if (person != null && (person.isEmpty() || person.length > 200)) throw ProblemException.invalid(Violation("/\$query/person_ref", ViolationCode.INVALID_FORMAT))
    }

    /** Ein unkodiertes `+` im Offset kommt als Leerzeichen an und wird zurückgewandelt. */
    private fun instant(raw: String?, name: String): Instant? = raw?.let { runCatching { OffsetDateTime.parse(it.replace(' ', '+')).toInstant() }.getOrNull() ?: throw ProblemException.invalid(Violation("/\$query/$name", ViolationCode.INVALID_FORMAT)) }

    private fun json(v: TimeEventView): Map<String, Any?> = linkedMapOf(
        "event_id" to v.eventId.toString(), "person_ref" to v.personRef, "kind" to v.kind.name, "occurred_at" to v.occurredAt.toString(), "device_recorded_at" to v.deviceRecordedAt?.toString(),
        "received_at" to v.receivedAt.toString(), "source" to v.source.name, "recorded_by" to v.recordedBy, "shift_id" to v.shiftId?.toString(), "backfill_reason" to v.backfillReason,
        "time_zone" to v.timeZone.id, "occurred_at_local" to local(v.occurredLocal),
    )

    private fun local(p: LocalPresentation): Map<String, Any?> = linkedMapOf(
        "local" to p.localWithOffset(), "offset" to p.offset.id, "operating_day" to p.operatingDay.toString(), "ambiguous" to p.ambiguous,
    )

    private fun json(i: WorkInterval): Map<String, Any?> = linkedMapOf(
        "start" to i.start?.toString(), "end" to i.end?.toString(), "breaks" to i.breaks.map { mapOf("start" to it.start.toString(), "end" to it.end?.toString()) },
        "net_seconds" to i.netSeconds, "flags" to i.flags.map { it.name }, "event_ids" to i.eventIds.map { it.toString() },
        "time_zone" to i.zone.id, "start_local" to i.start?.let { local(WorkTime.present(it, i.zone)) }, "end_local" to i.end?.let { local(WorkTime.present(it, i.zone)) },
        "operating_day" to i.start?.let { WorkTime.present(it, i.zone).operatingDay.toString() },
    )
}
