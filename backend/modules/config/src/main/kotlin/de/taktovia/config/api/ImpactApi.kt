package de.taktovia.config.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Wirkungsanalyse vor Publikation (WP-M1-23, LH-I.7-CFG-007). Fachmodule melden über [ImpactProvider], welche bestehenden Objekte (Buchungen,
 * Schichten, Angebote) von einer Revision betroffen wären; der Dienst speichert das Ergebnis als unveränderlichen [ImpactReportView], der an genau
 * einen Revisionsstand gebunden ist. Die Publikation (WP-M1-22) ruft vor dem Übergang nach `PUBLISHED` [ImpactAnalysis.confirmForPublication] auf.
 *
 * Datenschutz: Berichte enthalten nur Objektverweise (Art und ID) und Anzahlen, nie Personendaten betroffener Fahrgäste oder Beschäftigter.
 */
interface ImpactAnalysis {
    /**
     * Führt die Konfliktprüfung für den Revisionsstand mit der Zeilenversion [expectedRowVersion] (`If-Match`) aus und speichert den Bericht.
     * Voraussetzung: Funktionsscope `config:write` im Kontext (sonst 403). Veralteter Stand 412, unbekannte oder fremde Revision 404, bereits
     * publizierte Revision 409 `IMMUTABLE_RECORD`. Fällt ein Provider aus, entsteht **kein** Bericht (503): „keine Konflikte“ darf nie aus einem Fehler folgen.
     */
    fun analyze(ctx: RequestContext, revisionId: UUID, expectedRowVersion: Long): ImpactReportView

    /** Bericht über seine ID; Bericht eines anderen Mandanten oder unbekannte ID ist 404. Erfordert `config:read`. */
    fun get(ctx: RequestContext, reportId: UUID): ImpactReportView

    /**
     * Publikationssperre: prüft **innerhalb der Transaktion der Publikation** [j] (Mandantenkontext gesetzt), dass [reportId] zu genau der unveränderten
     * Revision [revisionId] gehört. Fehlender Bericht 422 (`/impact_report_id` REQUIRED), unbekannter oder fremder Bericht 422 (INVALID_VALUE),
     * Bericht einer anderen Revision, geänderte Werte (Hash) oder seit der Analyse neu betroffene Objekte 409 `CONFLICT`. Die Prüfung sperrt die
     * Revisionszeile (`FOR SHARE`), so dass sich der Revisionsstand bis zum Ende der Transaktion nicht mehr ändert. Bei Erfolg steht die Bindung im
     * Audit (`config.impact.bound`: Bericht-ID, Hash, Schweregrad); die Publikation nennt dieselbe Bericht-ID in ihrem eigenen Audit.
     */
    fun confirmForPublication(j: JdbcOperations, ctx: RequestContext, revisionId: UUID, reportId: UUID?): ImpactReportRef
}

/** Port je Fachmodul: Revision → betroffene Objekte mit Schweregrad. Implementierungen kommen mit M2 (Bediengebiete, Buchungen) und M4 (Schichten). */
interface ImpactProvider {
    /** Stabiler Name des Anbieters (nur für Fehlermeldungen und Logs). */
    val name: String

    /**
     * Ermittelt die von [subject] betroffenen bestehenden Objekte. [j] ist die Transaktion des Aufrufs mit gesetztem Mandantenkontext (nur Lesen
     * eigener Tabellen). Ein Anbieter, den die Revision nicht berührt, liefert eine leere Liste. Verweise dürfen keine Personendaten enthalten.
     */
    fun analyze(j: JdbcOperations, ctx: RequestContext, subject: ImpactSubject): List<ImpactFinding>
}

/**
 * Die zu prüfende Revision mit ihrem Diff gegen den Vorgänger ([ConfigRevisionView.changeLog]). [impactRelevantChanges] sind die Änderungen an
 * als „wichtig/impact-pflichtig“ gekennzeichneten Parametern (siehe [ParameterDefinition.impactRequired]).
 */
class ImpactSubject(val revision: ConfigRevisionView, val impactRelevantChanges: List<ConfigChange>)

/** Schweregrad eines Befunds, aufsteigend. `BLOCKING`: bestätigte Zusagen oder Schichten wären berührt. */
enum class ImpactSeverity { NONE, INFO, WARNING, BLOCKING }

/** Verweis auf ein betroffenes Objekt: Art (z. B. `BOOKING`, `SHIFT`) und ID; nie Personendaten. */
data class ImpactObjectRef(val kind: String, val id: UUID)

/** Befund eines Anbieters: [count] betroffene Objekte der Art [kind], davon bis zu 100 namentlich in [refs]. */
data class ImpactFinding(val kind: String, val severity: ImpactSeverity, val count: Long, val refs: List<ImpactObjectRef> = emptyList())

/** Ein Befund im gespeicherten Bericht (nach Zusammenfassung gleicher Art und Schwere). */
data class ImpactAffected(val kind: String, val severity: ImpactSeverity, val count: Long, val refs: List<ImpactObjectRef>)

/** Gespeicherter, unveränderlicher Wirkungsbericht. [valuesHash] bindet ihn an den geprüften Revisionsstand. */
data class ImpactReportView(
    val id: UUID,
    val revisionId: UUID,
    val revisionRowVersion: Long,
    val valuesHash: String,
    val schemaVersion: String,
    val highestSeverity: ImpactSeverity,
    val conflictCount: Long,
    val affected: List<ImpactAffected>,
    val createdBy: String,
    val createdAt: Instant,
)

/** Bestätigte Bindung für die Publikation (Bericht-ID und Hash gehören in deren Audit). */
data class ImpactReportRef(val id: UUID, val revisionId: UUID, val valuesHash: String, val highestSeverity: ImpactSeverity, val conflictCount: Long)
