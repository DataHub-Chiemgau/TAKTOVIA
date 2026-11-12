package de.taktovia.fleetsafety.internal

import de.taktovia.fleetsafety.api.EvidenceConfidentiality
import de.taktovia.fleetsafety.api.EvidenceDetail
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations

/**
 * Rollenmatrix der Vertraulichkeit in der Schadenakte (WP-M3-50, LH-6.22-DMG-011; Planungsannahme, nicht freigegeben):
 * - Dokument [EvidenceConfidentiality.STANDARD]: jede Rolle mit Zugriff auf die Akte (Melder, Disposition, Werkstatt, Betriebsleitung).
 * - Dokument [EvidenceConfidentiality.CONFIDENTIAL]: Werkstatt und Betriebsleitung (Prüfer, [DefectRoles.REVIEWERS]) sowie der Urheber, solange er noch eine meldeberechtigte Rolle hat.
 *   Für alle anderen ist es nicht vorhanden: 404, kein Zähler, keine Metadaten.
 * Wer die Akte selbst nicht sehen darf (Fahrer anderer Fahrzeuge oder Schichten, anderer Mandant), erreicht auch kein Dokument.
 * Beim Lesen und Herunterladen werden die Rollen **aktuell aus den Mitgliedschaftszeilen** des Kontexts bestimmt ([currentRoles]); ein committeter Entzug wirkt sofort, ein gleichzeitiger
 * Entzug wird abgewartet (`FOR SHARE`), wie bei der Dienstscope-Prüfung der Plattform.
 */
internal object DefectConfidentiality {
    /** Rollen mit Zugriff auf alle vertraulichen Dokumente einer Akte. */
    val PRIVILEGED: Set<String> = DefectRoles.REVIEWERS

    fun privileged(roles: Set<String>): Boolean = roles.any { it in PRIVILEGED }

    fun actorRef(ctx: RequestContext): String = (if (ctx.actor.type == ActorType.SERVICE) "service:" else "user:") + ctx.actor.subject

    /** Entscheidung nach Rollen und Urheber; [uploadedBy]/[actorRef] im Format `user:<subject>`. */
    fun mayRead(roles: Set<String>, actorRef: String, uploadedBy: String, confidentiality: String): Boolean {
        if (confidentiality != EvidenceConfidentiality.CONFIDENTIAL) return true
        if (roles.none { it in DefectRoles.REPORTERS || it in PRIVILEGED }) return false
        return privileged(roles) || uploadedBy == actorRef
    }

    /** Sichtbarkeit in Listen und Projektionen nach der Momentaufnahme der Rollen im Kontext (die Transaktion prüft die Mitgliedschaft zusätzlich). */
    fun visibleTo(ctx: RequestContext, e: EvidenceDetail): Boolean = mayRead(ctx.roles, actorRef(ctx), e.createdBy, e.confidentiality)

    /**
     * Rollen, die der Akteur **jetzt** noch hat: die Rollen des Kontexts, soweit eine seiner Mitgliedschaften noch aktiv ist (nicht widerrufen, gültig). Ohne Mitgliedschaften im Kontext
     * (Dienstkonto, Test ohne Mitgliedschaft) gilt die Momentaufnahme des Kontexts. Mit [lock] werden die Zeilen gegen gleichzeitigen Widerruf gesperrt; nur in schreibenden Transaktionen.
     */
    fun currentRoles(j: JdbcOperations, ctx: RequestContext, lock: Boolean): Set<String> {
        if (ctx.actor.type != ActorType.HUMAN || ctx.membershipIds.isEmpty()) return ctx.roles
        val ids = ctx.membershipIds.joinToString(",", "{", "}")   // nur UUIDs, nie Fremdtext
        val rows = j.query(if (lock) ACTIVE_ROLES_LOCKED else ACTIVE_ROLES, { rs, _ -> rs.getString(1) }, ids)
        return ctx.roles intersect rows.toSet()
    }

    private const val ACTIVE_ROLES = "SELECT m.role_code FROM taktovia.membership m WHERE m.id = ANY(CAST(? AS uuid[])) AND m.revoked_at IS NULL AND m.valid_from <= clock_timestamp() " +
        "AND (m.valid_to IS NULL OR m.valid_to > clock_timestamp())"
    private const val ACTIVE_ROLES_LOCKED = ACTIVE_ROLES + " FOR SHARE"
}
