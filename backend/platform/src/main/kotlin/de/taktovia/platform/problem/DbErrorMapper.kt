package de.taktovia.platform.problem

import java.sql.SQLException

/**
 * Bildet Datenbankfehler auf Problem-Codes ab (Kap. 02 §4.2, WP-M1-02). Die Meldung der Datenbank gelangt nie
 * zur API: weder Constraint- noch Tabellen- noch Spaltennamen, nur der feste Text des Katalogs.
 */
object DbErrorMapper {
    fun map(e: Throwable): ProblemException {
        val sql = generateSequence(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            ?: return ProblemException(ErrorCode.INTERNAL_ERROR, cause = e)
        val state = sql.sqlState ?: ""
        return when {
            // Übergangsregel der Datenbank (V1170, Trigger enforce_state_transition): derselbe Fehler wie beim serverseitigen Baustein
            state == "23514" && sql.message?.contains("state transition not allowed") == true ->
                ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Der Zustandsübergang ist nicht erlaubt.", cause = e)
            // CHECK über die Wertemenge einer Zustandsspalte (harden_state_column): ein unzulässiger Wert ist ein Eingabefehler, kein unveränderlicher Datensatz
            state == "23514" && sql.message?.contains("_state_valid") == true -> ProblemException(ErrorCode.VALIDATION_FAILED, "Ein Wert ist ungültig.", cause = e)
            state == "23514" -> ProblemException(ErrorCode.IMMUTABLE_RECORD, "Der Datensatz ist unveränderlich; eine Korrektur wird als neuer Datensatz angelegt.", cause = e)
            state == "23505" -> ProblemException(ErrorCode.CONFLICT, "Der Datensatz existiert bereits.", cause = e)
            state == "23503" -> ProblemException(ErrorCode.VALIDATION_FAILED, "Ein referenzierter Datensatz existiert nicht.", cause = e)
            state == "23502" || state == "22023" || state == "22P02" || state == "22001" || state == "22003" || state == "22007" || state == "22008" ->
                ProblemException(ErrorCode.VALIDATION_FAILED, "Ein Wert ist ungültig.", cause = e)
            // 42501: unzureichende Rechte bzw. Verstoß gegen eine RLS-Policy (WITH CHECK): nie preisgeben, welches Objekt betroffen ist.
            state == "42501" -> ProblemException(ErrorCode.FORBIDDEN, cause = e)
            state == "40001" || state == "40P01" -> ProblemException(ErrorCode.CONFLICT, "Gleichzeitige Änderung; Anfrage erneut senden.", cause = e)
            state.startsWith("08") || state.startsWith("53") || state.startsWith("57") ->
                ProblemException(ErrorCode.SERVICE_UNAVAILABLE, headers = mapOf("Retry-After" to "5"), cause = e)
            else -> ProblemException(ErrorCode.INTERNAL_ERROR, cause = e)
        }
    }
}
