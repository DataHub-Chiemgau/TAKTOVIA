package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Sperr- und Freigabekern (WP-M3-08, Modul `fleet-safety`; Zustandsautomaten `einsatzfreigabe.md` und `fahrzeug_sperre.md`). Es gibt genau eine serverseitige Stelle, die über die
 * Einsatzfähigkeit eines Fahrzeugs entscheidet. Sperren und Freigaben wirken synchron im selben Commit und sind gegen konkurrierende Disposition abgesichert.
 *
 * Sperrreihenfolge (m3-detailvertraege.md §5): jede Änderung sperrt zuerst die Fahrzeugzeile (`FOR UPDATE`), der Hold-Commit der Disposition liest sie danach unter `FOR SHARE`
 * (nach dem Umlauf). Dadurch entsteht in keiner Serialisierung eine Zusage auf ein gesperrtes Fahrzeug (PH-AT-020).
 */
enum class BlockSource { OPERATIONS, SAFETY }

/** Sperrgrundkatalog (Planungsannahme OP-01): welche Gründe eine Quelle setzen darf, bestimmt [source]. */
enum class BlockReason(val source: Set<BlockSource>) {
    OPERATIONAL_DISRUPTION(setOf(BlockSource.OPERATIONS)),
    MANUAL(setOf(BlockSource.OPERATIONS)),
    DEFECT_CRITICAL(setOf(BlockSource.SAFETY)),
    INSPECTION_FAILED(setOf(BlockSource.SAFETY)),
    OTHER(setOf(BlockSource.OPERATIONS, BlockSource.SAFETY));
}

enum class BlockStatus { ACTIVE, LIFTED }

/** Eine Sperre. [reasonRef] ist der Quellbezug (z. B. Schadenakte, Prüflauf); gleicher (Fahrzeug, Quelle, Grund, Bezug) ist höchstens einmal aktiv. */
data class VehicleBlockView(
    val id: UUID, val vehicleId: UUID, val source: BlockSource, val reason: BlockReason, val reasonRef: String?, val status: BlockStatus,
    val setBy: String, val setAt: Instant, val liftedBy: String?, val liftedAt: Instant?, val releaseDecisionId: UUID?, val revision: Long,
)

data class SetBlock(val vehicleId: UUID, val source: BlockSource, val reason: BlockReason, val reasonRef: String? = null) {
    init {
        require(source in reason.source) { "Grund $reason ist für die Quelle $source nicht zulässig" }
        require(reasonRef == null || reasonRef.length in 1..120) { "reasonRef 1 bis 120 Zeichen" }
    }
}

/**
 * Ergebnis des Setzens: [created] = `false` bei Wiederholung derselben Sperre (kein zweiter Grund, kein Revisionssprung). [safetyRevision] ist der Stand nach dem Aufruf,
 * [revokedReleaseIds] die im selben Commit widerrufenen Einsatzfreigaben.
 */
data class BlockOutcome(val block: VehicleBlockView, val created: Boolean, val safetyRevision: Long, val revokedReleaseIds: List<UUID>)

/** Ergebnis des Aufhebens: [stillBlocked] = es bestehen weitere aktive Sperrgründe (sie bleiben unabhängig wirksam). */
data class LiftOutcome(val block: VehicleBlockView, val safetyRevision: Long, val stillBlocked: Boolean)

enum class ReleaseState { ISSUED, REVOKED, EXPIRED }

enum class ReleaseRevokeReason { BLOCK_SET, VEHICLE_CHANGED, QUALIFICATION_REVOKED, MANUAL }

/** Einsatzfreigabe für genau (Fahrer der Schicht, Fahrzeug, Schicht); [state] wird aus `revoked_at` und `valid_until` abgeleitet, nie gespeichert. */
data class ResourceReleaseView(
    val id: UUID, val vehicleId: UUID, val shiftId: UUID, val inspectionId: UUID, val safetyRevision: Long, val qualificationRevision: Long, val validUntil: Instant,
    val issuedAt: Instant, val issuedBy: String?, val revokedAt: Instant?, val revokedBy: String?, val revokeReason: ReleaseRevokeReason?, val state: ReleaseState,
)

data class IssueRelease(val vehicleId: UUID, val shiftId: UUID, val inspectionId: UUID)

/** Fehlende Voraussetzung der Einsatzfreigabe (Codes wie `MissingRequirement` im Vertrag `api/fleet-safety.yaml`); keine Diagnosetexte. */
enum class ReleaseBlocker { NO_INSPECTION, INSPECTION_OPEN, INSPECTION_EXPIRED, DEFECT_BLOCK, OPERATIONAL_BLOCK, QUALIFICATION_MISSING, VEHICLE_CHANGED, RELEASE_REVOKED }

sealed interface ReleaseOutcome {
    /** [replayed] = die Freigabe für diesen Prüflauf bestand schon (Wiederholung, keine zweite Freigabe, kein zweiter Revisionsstand). */
    data class Issued(val release: ResourceReleaseView, val replayed: Boolean) : ReleaseOutcome
    data class Refused(val missing: List<ReleaseBlocker>) : ReleaseOutcome
}

/** Warum ein Fahrzeug für Schicht und Zeitpunkt nicht disponierbar ist ([DispatchVerdict.Refused]). */
enum class DispatchRefusal {
    UNKNOWN_VEHICLE, VEHICLE_INACTIVE, VEHICLE_BLOCKED, STALE_SAFETY_REVISION, NO_VALID_SHIFT, NO_RELEASE, RELEASE_REVOKED, RELEASE_EXPIRED, RELEASE_STALE, QUALIFICATION_MISSING,
}

sealed interface DispatchVerdict {
    data class Dispatchable(val releaseId: UUID, val safetyRevision: Long, val validUntil: Instant) : DispatchVerdict
    data class Refused(val reasons: List<DispatchRefusal>) : DispatchVerdict
}

/**
 * Regeln der Einsatzfreigabe (Planungsannahmen OP-33 und `inspection_release_max_hours`): `valid_until` ist das Schichtende, höchstens [maxValidity] nach Erteilung;
 * nach einer Pause innerhalb derselben Schicht ist keine Neuprüfung nötig, solange `safety_revision` unverändert ist.
 */
data class ReleasePolicy(val maxValidity: Duration = Duration.ofHours(16)) {
    init { require(!maxValidity.isNegative && !maxValidity.isZero) { "maxValidity > 0" } }
}

/**
 * Sperren, Freigeben und die synchrone Dispositionsprüfung.
 *
 * - **Sperre setzen** ([setBlock]): Quelle `OPERATIONS` durch Disposition oder Betriebsleitung, Quelle `SAFETY` ausschließlich durch den Server (Dienstkonto; fachliche Auslöser
 *   sind kritischer Mangel, sicherheitskritischer Schaden). Im selben Commit: Sperrzeile, `vehicle.active_block`, `safety_revision + 1`, Widerruf **jeder** gültigen Einsatzfreigabe
 *   des Fahrzeugs, Audit und Outbox. Dieselbe Sperre aus derselben Quelle ist idempotent. Mehrere Gründe sind unabhängig.
 * - **Aufheben**: `OPERATIONS` durch Disposition ([liftOperationalBlock]); `SAFETY` nur mit einer Wiederfreigabeentscheidung ([liftSafetyBlock], WP-M3-51). Das Aufheben eines
 *   Grundes hebt andere Gründe nicht auf; es erteilt keine Freigabe (die verlangt eine neue Prüfung).
 * - **Freigabe erteilen** ([issueRelease]): nur, wenn im selben Commit gültige Schicht (angetreten, nicht beendet), abgeschlossene bestandene Prüfung für genau (Fahrer, Fahrzeug,
 *   Schicht), gültige Qualifikation des Fahrers und keine aktive Sperre vorliegen. Sonst [ReleaseOutcome.Refused] mit den fehlenden Voraussetzungen.
 * - **[assertDispatchable]**: die synchrone Prüfung für die Disposition im selben Lock wie der Hold-Commit (Fahrzeugzeile `FOR SHARE`). Fahrzeug aktiv, keine Sperre, Schicht
 *   gültig, Freigabe nicht widerrufen/abgelaufen und gegen die aktuelle `safety_revision` ausgestellt, Qualifikation gültig, und, falls übergeben, die vom Aufrufer gelesene
 *   `safety_revision` noch aktuell.
 */
interface Readiness {
    fun setBlock(ctx: RequestContext, cmd: SetBlock): BlockOutcome

    /** Hebt eine Sperre der Quelle `OPERATIONS` auf; eine `SAFETY`-Sperre bleibt bestehen (409 `INVALID_STATE_TRANSITION`). */
    fun liftOperationalBlock(ctx: RequestContext, vehicleId: UUID, blockId: UUID): LiftOutcome

    /** Hebt eine `SAFETY`-Sperre mit einer Wiederfreigabeentscheidung `RELEASED` des Fahrzeugs auf (Werkstatt, Betriebsleitung); ohne passende Entscheidung 409. */
    fun liftSafetyBlock(ctx: RequestContext, vehicleId: UUID, blockId: UUID, releaseDecisionId: UUID): LiftOutcome

    fun activeBlocks(ctx: RequestContext, vehicleId: UUID): List<VehicleBlockView>

    fun issueRelease(ctx: RequestContext, cmd: IssueRelease): ReleaseOutcome

    fun revokeRelease(ctx: RequestContext, releaseId: UUID, reason: ReleaseRevokeReason): ResourceReleaseView

    fun release(ctx: RequestContext, releaseId: UUID): ResourceReleaseView

    /** Prüfung im Kontext der Anfrage (eigene lesende Transaktion; ohne Sperre, nur für Anzeige und Tests). */
    fun assertDispatchable(ctx: RequestContext, vehicleId: UUID, shiftId: UUID, expectedSafetyRevision: Long?, at: Instant): DispatchVerdict

    /**
     * Prüfung innerhalb der **laufenden Mandantentransaktion** der Disposition (Hold-Commit): sperrt die Fahrzeugzeile `FOR SHARE` (nach dem Umlauf, Sperrreihenfolge laut
     * Detailvertrag), damit eine gleichzeitige Sperre oder Freigabeänderung sich vorher abschließt und die Prüfung einen konsistenten Stand sieht.
     */
    fun assertDispatchable(jdbc: JdbcOperations, vehicleId: UUID, shiftId: UUID, expectedSafetyRevision: Long?, at: Instant): DispatchVerdict
}
