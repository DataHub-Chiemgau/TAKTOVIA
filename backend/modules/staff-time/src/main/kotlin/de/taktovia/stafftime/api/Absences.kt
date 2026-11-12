package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/** Neutrale Art der Abwesenheit (WP-M4-06): bewusst ohne Krankheit, Diagnose oder Nachweis (Krankheitsabwesenheit TIM-010 folgt in M7). */
enum class AbsenceKind { LEAVE, TRAINING, OTHER }

enum class AbsenceState { APPROVED, WITHDRAWN }

/** Eingabe „genehmigte Abwesenheit erfassen“; [to] ist exklusiv. Der Mandant folgt aus dem Kontext. */
data class CreateAbsence(val personRef: String, val from: Instant, val to: Instant, val kind: AbsenceKind)

/** Vollständige Sicht der Personalrolle (mit Art). */
data class AbsenceView(
    val id: UUID, val personRef: String, val from: Instant, val to: Instant, val kind: AbsenceKind, val state: AbsenceState,
    val createdBy: String, val createdAt: Instant, val revision: Long,
)

/**
 * Verfügbarkeitsrestriktion (Planer- und Dispositionssicht, Konfliktprüfung): ausschließlich Person und Zeitraum, „nicht verfügbar“. Art, Zustand und Urheber sind hier
 * bewusst nicht enthalten (Kap. 06 §6.8); [createdAt] dient nur der stabilen Sortierung und Cursorposition.
 */
data class AvailabilityRestriction(val id: UUID, val personRef: String, val from: Instant, val to: Instant, val createdAt: Instant)

/** Zeitraum einer Nichtverfügbarkeit (halboffen `[from, to)`). */
data class UnavailableInterval(val from: Instant, val to: Instant)

/**
 * Abwesenheit als Verfügbarkeitsrestriktion (WP-M4-06, Minimalmodell ohne Antragsworkflow). Anlegen und Zurücknehmen nur mit dem Funktionsscope `absences:write`
 * (Personalrolle), Detail mit Art nur mit `absences:detail`, die Restriktionssicht mit `absences:read`; fremde Mandanten sind nie sichtbar (404). Jede Anlage und
 * Rücknahme steht im `audit_event` (ohne Person und Art), Rücknahme ist endgültig und trägt die Revision (`If-Match`).
 */
interface Absences {
    fun create(ctx: RequestContext, cmd: CreateAbsence): AbsenceView

    /** Nimmt zurück; `expectedRevision` ist der `If-Match`-Wert (veraltet: 412); wiederholt mit der Revision der Rücknahme liefert denselben Stand. */
    fun withdraw(ctx: RequestContext, absenceId: UUID, expectedRevision: Long): AbsenceView

    fun get(ctx: RequestContext, absenceId: UUID): AbsenceView

    /** Genehmigte, nicht zurückgenommene Abwesenheiten, die das Fenster `[from, to)` überlappen; sortiert nach `(created_at, id)`, mit `limit + 1` Zeilen für die Folgeseite. */
    fun restrictions(ctx: RequestContext, personRef: String?, from: Instant?, to: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<AvailabilityRestriction>

    /**
     * Eigensicht (WP-M4-20, UI-13): die eigenen genehmigten, nicht zurückgenommenen Abwesenheitszeiträume der Person des Kontexts, neutral wie [restrictions] (nie Art). Braucht nur
     * `time-events:read`; fremde Personen gibt es hier nicht (die Person ist immer der Akteur, nie Eingabe).
     */
    fun own(ctx: RequestContext, from: Instant?, to: Instant?, limit: Int, after: Pair<Instant, UUID>?): List<AvailabilityRestriction>

    /** Für die Konfliktprüfung (WP-M4-07): Nichtverfügbarkeit einer Person im Fenster in der **laufenden** Transaktion des Aufrufers (Mandantenkontext gesetzt). */
    fun restrictionsIn(jdbc: JdbcOperations, personRef: String, from: Instant, to: Instant): List<UnavailableInterval>
}
