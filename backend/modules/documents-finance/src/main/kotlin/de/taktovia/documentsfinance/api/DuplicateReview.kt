package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Wie der Treffer zustande kam: identische Datei (`HASH`, SHA-256 je Mandant) oder Übereinstimmung der Fachdaten (`FACTS`: Händler, Datum, Währung, Betrag, Belegnummer bzw. Karten-/Kassentransaktion). */
enum class DuplicateMatch { HASH, FACTS }

/** `SAFE` = sicherer Treffer (wird sofort als weitere Evidenz desselben Vorgangs verknüpft), `POSSIBLE` = unsicherer Treffer (Prüfung durch einen Menschen). */
enum class DuplicateCertainty { SAFE, POSSIBLE }

/** `OFFEN` -> `GLEICHE_EVIDENZ` | `EIGENER_VORGANG` (einmalig; ein sicherer Treffer entsteht bereits als `GLEICHE_EVIDENZ`). */
enum class DuplicateState { OFFEN, GLEICHE_EVIDENZ, EIGENER_VORGANG }

/** Entscheidung eines Menschen zu einem offenen Treffer. */
enum class DuplicateDecision(val state: DuplicateState) { GLEICHE_EVIDENZ(DuplicateState.GLEICHE_EVIDENZ), EIGENER_VORGANG(DuplicateState.EIGENER_VORGANG) }

/** Aus Sicht des betrachteten Belegs: `NEWER` = er ist der neuere Beleg des Paares (er wird Evidenz oder blockiert), `OLDER` = er ist der ältere (Gegenüber). */
enum class DuplicateRole { NEWER, OLDER }

/** Eckdaten des anderen Belegs für die Gegenüberstellung (nur für die Finanzrolle sichtbar). */
data class DuplicateFacts(val merchant: String?, val documentDate: String?, val currency: String?, val amountMinor: Long?, val documentNumber: String?, val channel: SourceChannel, val receivedAt: Instant)

/**
 * Treffer der Dublettenerkennung (WP-M4-32). `basis` nennt Trefferregel, Punkte und die abweichenden Felder; `score` 0 bis 100 gegen `receipt_duplicate_threshold`. Ein Treffer löscht oder verschmilzt nie einen
 * Beleg. `otherDocumentId`, `basis` und `other` sieht nur die Finanzrolle.
 */
data class DuplicateCandidateView(
    val id: UUID, val role: DuplicateRole, val otherDocumentId: UUID?, val match: DuplicateMatch, val certainty: DuplicateCertainty, val score: Int, val basis: Map<String, Any?>?, val state: DuplicateState,
    val detectedAt: Instant, val decidedBy: String?, val decidedAt: Instant?, val reason: String?, val other: DuplicateFacts?,
)

/**
 * Dublettenerkennung und Evidenzverknüpfung (WP-M4-32, DOC-009). Belege desselben Mandanten werden anhand von Dateihash, Händler, Datum, Währung, Betrag, Belegnummer bzw. Karten-/Kassentransaktion verglichen
 * (nie über Mandantengrenzen; ein Hashvergleich gibt keine Auskunft über Belege anderer Mandanten). Je Paar entsteht ein Treffer am **neueren** Beleg:
 *  - **sicher** (identischer Hash, oder alle Fachdaten einschließlich Belegnummer bzw. Kartentransaktion gleich): sofort `GLEICHE_EVIDENZ`; der neuere Beleg ist weitere Evidenz desselben Vorgangs und nie ein
 *    eigener Kostenfall ([ReleaseBlocker.DUPLICATE_EVIDENCE], Freigabe 409 `DUPLICATE_EVIDENCE_LINKED`); mit dem Posting des ersten Belegs entsteht die Evidenzverknüpfung zum Kostenereignis (`evidence_link`);
 *  - **unsicher** (Punktzahl ab `receipt_duplicate_threshold`): `OFFEN`; die Freigabe ist mit 409 `DUPLICATE_REVIEW_REQUIRED` gesperrt, bis ein Mensch `GLEICHE_EVIDENZ` oder `EIGENER_VORGANG` entscheidet.
 * Die Erkennung läuft nach der Prüfung des Originals, nach jeder Revision, nach Erfassung der Tankwerte und im Freigabeschritt (idempotent, ein Treffer je Paar).
 */
interface DuplicateReview {
    /** Prüflauf (idempotent; parallele Läufe erzeugen je Paar genau einen Treffer). Erfasser und Finanzrolle; sonst 404. */
    fun check(ctx: RequestContext, documentId: UUID): List<DuplicateCandidateView>

    /** Treffer, an denen der Beleg beteiligt ist (liest nur). */
    fun list(ctx: RequestContext, documentId: UUID): List<DuplicateCandidateView>

    /**
     * Entscheidung zu einem offenen Treffer (`expectedRevision` = `If-Match` des Belegs aus dem Pfad, veraltet 412). Recht: Scope `expenses:review`, Finanzrolle und Mensch, sonst 403; Begründung 3 bis 1000 Zeichen.
     * `GLEICHE_EVIDENZ`: der neuere Beleg wird weitere Evidenz des Vorgangs des älteren (Verknüpfung zum Ereignis, sobald es besteht); `EIGENER_VORGANG`: der neuere Beleg bleibt ein eigener Kostenfall. Die gleiche
     * Entscheidung wiederholt ist ein No-op, eine andere Entscheidung auf einen entschiedenen Treffer 409. Fremde Mandanten und unbekannte Treffer: 404.
     */
    fun decide(ctx: RequestContext, documentId: UUID, candidateId: UUID, expectedRevision: Long, decision: DuplicateDecision, reason: String): ExpenseView
}
