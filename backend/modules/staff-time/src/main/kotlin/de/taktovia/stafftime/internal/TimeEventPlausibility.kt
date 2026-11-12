package de.taktovia.stafftime.internal

import de.taktovia.stafftime.api.TimeConflict
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventOrigin
import de.taktovia.stafftime.api.WorkState
import java.time.Duration
import java.time.Instant

/**
 * Konfliktregeln für Stempel (WP-M4-12, Detailvertrag LH-6.24-TIM-003). Das Pflichtenheft beziffert keine Toleranzen; die Werte sind **Planungsdefaults** (Offener Punkt des Vertrags):
 * - `CLOCK_SKEW`: Ereigniszeit oder Gerätezeit liegt mehr als [TOLERANCE] nach der Serverzeit (Zukunft); bei Offline-Stempeln zusätzlich Ereigniszeit älter als [MAX_OFFLINE_AGE]
 *   oder Gerätezeit und Ereigniszeit weichen um mehr als [TOLERANCE] voneinander ab (der App-Stempel trägt beide aus derselben Geräteuhr).
 * - `INVALID_ORDER` (nur offline; online ist Doppel-Kommen eine 409-Abweisung): der Stempel passt nicht in die Ereignisfolge der Person (Kommen bei laufender Arbeitszeit, Pause oder Gehen
 *   ohne passenden Zustand) oder das zeitlich nächste vorhandene Ereignis passt nach ihm nicht mehr (überlappende Intervalle).
 * Die Markierung ändert den Stempel nie; Klärung erst über den Korrekturantrag (TIM-007, M7).
 */
internal object TimeEventPlausibility {
    val TOLERANCE: Duration = Duration.ofMinutes(5)
    val MAX_OFFLINE_AGE: Duration = Duration.ofDays(7)

    fun assess(now: Instant, origin: TimeEventOrigin, kind: TimeEventKind, occurredAt: Instant, deviceAt: Instant, prev: TimeEventKind?, next: TimeEventKind?): TimeConflict? {
        val horizon = now.plus(TOLERANCE)
        if (occurredAt.isAfter(horizon) || deviceAt.isAfter(horizon)) return TimeConflict.CLOCK_SKEW
        if (origin == TimeEventOrigin.OFFLINE) {
            if (occurredAt.isBefore(now.minus(MAX_OFFLINE_AGE))) return TimeConflict.CLOCK_SKEW
            if (Duration.between(occurredAt, deviceAt).abs() > TOLERANCE) return TimeConflict.CLOCK_SKEW
            val after = WorkState.of(prev).after(kind) ?: return TimeConflict.INVALID_ORDER
            if (next != null && after.after(next) == null) return TimeConflict.INVALID_ORDER
        }
        return null
    }
}
