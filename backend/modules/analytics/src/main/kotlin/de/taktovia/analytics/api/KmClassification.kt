package de.taktovia.analytics.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Kategorien der Fahrzeugkilometer (WP-M4-42, FCO-005). [UNASSIGNED] ist der Rest zur Gesamtstrecke und wird nie auf die anderen Kategorien verteilt. */
enum class KmCategory { PASSENGER, EMPTY, TRANSFER, WORKSHOP, UNASSIGNED }

/** Ergebnis eines Klassifikationslaufs; [created] `false`, wenn der Eingangszustand schon klassifiziert war (kein zweiter Lauf, nichts verändert). */
data class KmRunView(
    val runId: UUID, val vehicleId: UUID, val serviceDay: LocalDate, val dataAsOf: Instant, val ruleVersion: Int, val positionCount: Int, val eventsComplete: Boolean,
    val totalKmThousandths: Long, val byCategory: Map<KmCategory, Long>, val created: Boolean,
)

/** Vollständigkeit der Auswertung: [INCOMPLETE], solange die Fahrerereignisse eines Laufs unvollständig sind (Revisionslücke, ohne Ausstieg, Ausstieg ohne Einstieg). */
data class KmDay(val serviceDay: LocalDate, val vehicleId: UUID, val runId: UUID, val dataAsOf: Instant, val totalKmThousandths: Long, val byCategory: Map<KmCategory, Long>, val eventsComplete: Boolean)

/** Kilometer je Kategorie im Zeitraum; [byCategory] enthält immer alle fünf Kategorien, die Summe ist [totalKmThousandths] (Tausendstel km = Meter). */
data class KmSummary(
    val definitionVersion: Int, val from: LocalDate, val to: LocalDate, val vehicleId: UUID?, val asOf: Instant?, val completeness: Completeness,
    val totalKmThousandths: Long, val byCategory: Map<KmCategory, Long>, val days: List<KmDay>,
)

/**
 * Klassifikation der Fahrzeugkilometer je Fahrzeug und Betriebstag (WP-M4-42, `LH-I.2-FCO-005`). Die Gesamtstrecke ist der Positionsverlauf (Quelle `POSITION`), jeder Abschnitt zwischen zwei
 * aufeinanderfolgenden Positionen wird genau einer Kategorie zugeordnet (Regelversion [RULE_VERSION], siehe Vertrag). Was keine Fahrtenplan- oder Ereignisdeckung hat oder über eine Lücke in den
 * Positionsdaten läuft, ist [KmCategory.UNASSIGNED]. Ergebnisse sind append-only: ein geänderter Eingangszustand (nachträglich eintreffende Positionen, neue Planrevision, neue Fahrerereignisse)
 * erzeugt einen neuen Lauf mit neuem Datenstand, ein früherer Lauf wird nie überschrieben.
 */
interface KmClassification {
    /** Klassifiziert [vehicleId] am Betriebstag [serviceDay] (UTC-Tag) im Mandantenkontext des Workers. Unbekanntes oder fremdes Fahrzeug: `ProblemException.notFound()`. */
    fun classify(tenant: TenantId, vehicleId: UUID, serviceDay: LocalDate): KmRunView

    /** Klassifikationsjob eines Betriebstags: alle Fahrzeuge des Mandanten mit Positionen an diesem Tag, je Fahrzeug eine eigene Transaktion; liefert die Läufe (neu oder unverändert). */
    fun classifyDay(tenant: TenantId, serviceDay: LocalDate): List<KmRunView>

    /** Kilometer je Kategorie aus dem jeweils jüngsten Lauf je Fahrzeug und Tag. Recht `costs:read`; ungültiger Zeitraum (von nach bis, mehr als 366 Tage): 422; fremdes Fahrzeug: 404. */
    fun summary(ctx: RequestContext, vehicleId: UUID?, from: LocalDate, to: LocalDate): KmSummary

    companion object {
        const val RULE_VERSION = 1
        const val SCOPE_READ = "costs:read"
        const val MAX_DAYS = 366L
        /** Größter Abstand zweier Positionen in Sekunden, über den eine Strecke noch als durchgehend erfasst gilt (Planungsannahme, OP-22; nicht im Parameterkatalog). */
        const val MAX_GAP_SECONDS = 300L
    }
}
