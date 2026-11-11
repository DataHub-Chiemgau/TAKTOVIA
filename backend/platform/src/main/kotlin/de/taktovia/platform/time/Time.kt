package de.taktovia.platform.time

import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Zeit-, Zeitzonen- und DST-Baustein (Kap. 05 §5.1, LH-6.1-SYS-006, WP-M1-18).
 *
 * Regel zur Speicherung: jeder Zeitpunkt ist ein [Instant] (Datenbank `timestamptz`, intern UTC); wo lokale Regeln gelten
 * (Dienst, Schicht, Betriebstag), steht daneben die IANA-Zone des Aggregats (`service.timezone`, `staff_shift.timezone`,
 * `time_zone` im Request). In der API sind Zeitpunkte RFC 3339 mit Offset. Der Offset gehört zur Eingabe: er unterscheidet die
 * beiden gleichlautenden Ortszeiten der Zeitumstellung im Herbst und wird nie geraten.
 */

/** Injizierbare Serveruhr; Tests setzen eine feste Uhr, Produktivcode nutzt [SystemTimeSource]. */
fun interface TimeSource { fun now(): Instant }

object SystemTimeSource : TimeSource { override fun now(): Instant = Instant.now() }

/** Feste, von Hand weiterstellbare Uhr für deterministische Tests. */
class FixedTimeSource(@Volatile var instant: Instant) : TimeSource {
    override fun now(): Instant = instant
    fun advance(d: Duration) { instant = instant.plus(d) }
}

/** Ergebnis einer Prüfung: entweder ein Wert oder Feldverletzungen (nie beides). */
class Checked<out T> private constructor(val value: T?, val violations: List<Violation>) {
    val ok get() = violations.isEmpty()
    fun orThrow(): T = if (violations.isEmpty()) value!! else throw ProblemException.invalid(*violations.toTypedArray())
    companion object {
        fun <T> of(value: T) = Checked(value, emptyList())
        fun <T> fail(vararg v: Violation) = Checked<T>(null, v.toList())
        fun <T> fail(v: List<Violation>) = Checked<T>(null, v)
    }
}

/** IANA-Zonen: nur `Gebiet/Ort` und `UTC`. Abkürzungen (`CET`, `EST`), feste Offsets (`+01:00`) und `Etc/…` sind keine Dienstzonen. */
object IanaZones {
    private val SHAPE = Regex("^(UTC|[A-Z][A-Za-z_-]*(/[A-Za-z0-9_+-]+)+)$")

    fun parse(text: String): ZoneId? {
        if (text.length > 64 || !SHAPE.matches(text) || text.startsWith("Etc/") || text !in ZoneId.getAvailableZoneIds()) return null
        return ZoneId.of(text)
    }

    fun check(text: String?, pointer: String): Checked<ZoneId> = when {
        text == null || text.isBlank() -> Checked.fail(Violation(pointer, ViolationCode.REQUIRED))
        else -> parse(text)?.let { Checked.of(it) } ?: Checked.fail(Violation(pointer, ViolationCode.ZONE_UNKNOWN))
    }
}

/** RFC 3339 mit Offset: `2026-10-25T02:30:00+02:00`, `…Z`. Ohne Offset gibt es keinen Zeitpunkt. */
object Rfc3339 {
    private val WITH_OFFSET = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$")
    private val WITHOUT_OFFSET = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?$")

    fun parse(text: String?, pointer: String): Checked<OffsetDateTime> {
        if (text == null) return Checked.fail(Violation(pointer, ViolationCode.REQUIRED))
        if (WITHOUT_OFFSET.matches(text)) return Checked.fail(Violation(pointer, ViolationCode.OFFSET_REQUIRED))
        if (!WITH_OFFSET.matches(text)) return Checked.fail(Violation(pointer, ViolationCode.INVALID_FORMAT))
        return try { Checked.of(OffsetDateTime.parse(text)) } catch (_: DateTimeParseException) { Checked.fail(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
    }

    fun format(instant: Instant, offset: ZoneOffset): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(offset))
}

/** Ein eindeutiger Zeitpunkt mit der Zone, in der er lokal gelesen wird. [offset] ist der in dieser Zone zu diesem Zeitpunkt gültige UTC-Offset. */
data class ZonedMoment(val instant: Instant, val zone: ZoneId) {
    val offset: ZoneOffset get() = zone.rules.getOffset(instant)
    val local: LocalDateTime get() = LocalDateTime.ofInstant(instant, zone)
    /** RFC 3339 mit dem Offset der Zone: für die beiden Herbst-02:30 unterscheiden sich `+02:00` und `+01:00`. */
    fun toRfc3339(): String = Rfc3339.format(instant, offset)
    fun operatingDay(dayStart: LocalTime = LocalTime.MIDNIGHT): LocalDate = OperatingDay.of(instant, zone, dayStart)
}

/** Ergebnis der Auflösung einer lokalen Zeit in einer Zone. */
sealed interface LocalResolution {
    /** Genau ein Zeitpunkt. */
    data class Unique(val moment: ZonedMoment) : LocalResolution
    /** Zeitumstellung Herbst: die Ortszeit kommt zweimal vor; die Eingabe braucht einen eindeutigen Offset. */
    data class Ambiguous(val earlier: ZonedMoment, val later: ZonedMoment) : LocalResolution
    /** Zeitumstellung Frühjahr: die Ortszeit existiert nicht. */
    data object Gap : LocalResolution
}

object ZonedMoments {
    fun resolve(local: LocalDateTime, zone: ZoneId): LocalResolution {
        val offsets = zone.rules.getValidOffsets(local)
        return when (offsets.size) {
            0 -> LocalResolution.Gap
            1 -> LocalResolution.Unique(ZonedMoment(local.toInstant(offsets[0]), zone))
            else -> LocalResolution.Ambiguous(ZonedMoment(local.toInstant(offsets[0]), zone), ZonedMoment(local.toInstant(offsets[1]), zone))
        }
    }

    /** Lokale Eingabe ohne Offset: nur eindeutige Ortszeiten ergeben einen Zeitpunkt; Lücke und Mehrdeutigkeit sind Feldfehler. */
    fun fromLocal(local: LocalDateTime, zone: ZoneId, pointer: String): Checked<ZonedMoment> = when (val r = resolve(local, zone)) {
        is LocalResolution.Unique -> Checked.of(r.moment)
        is LocalResolution.Ambiguous -> Checked.fail(Violation(pointer, ViolationCode.LOCAL_TIME_AMBIGUOUS))
        LocalResolution.Gap -> Checked.fail(Violation(pointer, ViolationCode.LOCAL_TIME_NONEXISTENT))
    }

    /** Lokale Eingabe mit gewähltem Offset (Auswahl bei mehrdeutiger Ortszeit): der Offset muss zur Ortszeit passen. */
    fun fromLocal(local: LocalDateTime, zone: ZoneId, offset: ZoneOffset, pointer: String): Checked<ZonedMoment> {
        val valid = zone.rules.getValidOffsets(local)
        return when {
            valid.isEmpty() -> Checked.fail(Violation(pointer, ViolationCode.LOCAL_TIME_NONEXISTENT))
            offset !in valid -> Checked.fail(Violation(pointer, ViolationCode.ZONE_OFFSET_MISMATCH))
            else -> Checked.of(ZonedMoment(local.toInstant(offset), zone))
        }
    }

    /**
     * Validator „`desired_at` und `time_zone` beschreiben denselben lokalen Zeitpunkt“ (Kap. 05 §5.1): `desired_at` ist RFC 3339
     * mit Offset (ohne Offset: `OFFSET_REQUIRED`), `time_zone` eine bekannte IANA-Zone (`ZONE_UNKNOWN`), die Ortszeit existiert
     * in der Zone (`LOCAL_TIME_NONEXISTENT`) und der Offset ist einer ihrer gültigen Offsets zu dieser Ortszeit
     * (`ZONE_OFFSET_MISMATCH`). Alle Verletzungen werden gemeldet, nicht nur die erste.
     */
    fun parse(desiredAt: String?, timeZone: String?, instantPointer: String = "/desired_at", zonePointer: String = "/time_zone"): Checked<ZonedMoment> {
        val odt = Rfc3339.parse(desiredAt, instantPointer)
        val zone = IanaZones.check(timeZone, zonePointer)
        if (!odt.ok || !zone.ok) return Checked.fail(odt.violations + zone.violations)
        val o = odt.value!!; val z = zone.value!!
        val valid = z.rules.getValidOffsets(o.toLocalDateTime())
        return when {
            valid.isEmpty() -> Checked.fail(Violation(instantPointer, ViolationCode.LOCAL_TIME_NONEXISTENT))
            o.offset !in valid -> Checked.fail(Violation(zonePointer, ViolationCode.ZONE_OFFSET_MISMATCH))
            else -> Checked.of(ZonedMoment(o.toInstant(), z))
        }
    }
}

/**
 * Gültigkeitsintervall „links geschlossen, rechts offen“ `[from, to)`; `to == null` heißt unbegrenzt. Angrenzende Intervalle
 * `[a, b)` und `[b, c)` überschneiden sich nicht, der Zeitpunkt `b` gehört nur zum zweiten.
 */
data class ValidityInterval(val from: Instant, val to: Instant?) {
    init { require(to == null || to > from) { "Intervallende muss nach dem Beginn liegen" } }
    operator fun contains(t: Instant) = t >= from && (to == null || t < to)
    fun overlaps(o: ValidityInterval) = (to == null || o.from < to) && (o.to == null || from < o.to)
    val duration: Duration? get() = to?.let { Duration.between(from, it) }

    companion object {
        fun check(from: Instant, to: Instant?, toPointer: String = "/valid_to"): Checked<ValidityInterval> =
            if (to != null && to <= from) Checked.fail(Violation(toPointer, ViolationCode.INTERVAL_INVALID)) else Checked.of(ValidityInterval(from, to))

        /** Aus zwei RFC-3339-Texten; beide verlangen einen Offset. */
        fun parse(from: String?, to: String?, fromPointer: String = "/valid_from", toPointer: String = "/valid_to"): Checked<ValidityInterval> {
            val f = Rfc3339.parse(from, fromPointer)
            val t = if (to == null) null else Rfc3339.parse(to, toPointer)
            val bad = f.violations + (t?.violations ?: emptyList())
            if (bad.isNotEmpty()) return Checked.fail(bad)
            return check(f.value!!.toInstant(), t?.value?.toInstant(), toPointer)
        }
    }
}

/** Dauern entstehen aus absoluten Zeitpunkten, nie aus Ortszeit-Differenzen (die Zeitumstellung verschiebt Ortszeiten um eine Stunde). */
object Durations {
    fun between(a: Instant, b: Instant): Duration = Duration.between(a, b)
}

/**
 * Lokaler Betriebstag eines Zeitpunkts in der Zone des Dienstes. Planungsdefault: der Betriebstag beginnt um Mitternacht
 * Ortszeit; [dayStart] erlaubt einen späteren Beginn (z. B. 03:00, Nachtverkehr gehört zum Vortag). Der Betriebstag wird
 * zusammen mit dem Zeitpunkt angezeigt (PH-AT-018).
 */
object OperatingDay {
    fun of(instant: Instant, zone: ZoneId, dayStart: LocalTime = LocalTime.MIDNIGHT): LocalDate =
        ZonedDateTime.ofInstant(instant, zone).toLocalDateTime().minusSeconds(dayStart.toSecondOfDay().toLong()).toLocalDate()

    fun today(clock: TimeSource, zone: ZoneId, dayStart: LocalTime = LocalTime.MIDNIGHT): LocalDate = of(clock.now(), zone, dayStart)
}

/** Parser lokaler Datums-/Zeitangaben ohne Offset (`2026-10-25T02:30:00`) für UI-Eingaben. */
object LocalDateTimes {
    fun parse(text: String?, pointer: String): Checked<LocalDateTime> {
        if (text == null) return Checked.fail(Violation(pointer, ViolationCode.REQUIRED))
        return try { Checked.of(LocalDateTime.parse(text)) } catch (_: DateTimeException) { Checked.fail(Violation(pointer, ViolationCode.INVALID_FORMAT)) }
    }
}
