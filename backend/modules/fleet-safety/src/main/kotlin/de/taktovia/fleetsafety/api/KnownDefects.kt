package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Bekannte Altschäden anzeigen und quittieren (WP-M3-21, LH-6.21-CHK-012, LH-6.22-DMG-012; Detailverträge `docs/umsetzungsplan/vertraege/LH-6.21-CHK-012.md`, `LH-6.22-DMG-012.md`).
 *
 * **Arbeitsannahme „nutzungsrelevant“ (Pflichtenheft ohne Festlegung, nicht freigegeben):** alle offenen Schadenakten (Status nicht `CLOSED`/`DISMISSED`) des Fahrzeugs, die beim Start des
 * Prüflaufs bereits bestanden und nicht aus diesem Lauf stammen. Die Regel steht an genau einer Stelle (SQL-Funktion `inspection_known_defects`).
 */
object KnownDefectCatalog {
    /** Prüflaufarten, deren Abschluss die Quittierung aller offenen Altschäden voraussetzt (Folgeprüfung der Werkstatt nach Reparatur nicht). */
    val ACK_REQUIRED_KINDS: Set<String> = linkedSetOf("PRE_SHIFT", "VEHICLE_CHANGE")
    /** Stabile Kennwörter am Anfang von `detail` des 409. */
    const val UNACKNOWLEDGED = "KNOWN_DEFECTS_UNACKNOWLEDGED"
    const val NOT_OPEN = "KNOWN_DEFECT_NOT_OPEN"
    const val OPEN_DEFECTS_PENDING = "KNOWN_OPEN_DEFECTS"
}

/** Foto der Fahrerprojektion: nur die Kennung (kein Dateiname, keine Metadaten, nur nicht vertrauliche, freigegebene und nicht ersetzte Fotos). */
data class KnownDefectPhoto(val evidenceId: UUID, val capturedAt: Instant)

/**
 * Minimierte Fahrerprojektion eines offenen Falls: Fallnummer, Kategorie, Art, Bereich, Fotos. Bewusst **ohne** Beschreibung, Ort, Melder, Status der Bearbeitung, Klasse und ohne vertrauliche
 * Dokumente (DMG-011). [acknowledged] und [acknowledgedDefectRevision] gelten nur im Bezug zu einem Prüflauf.
 */
data class KnownDefectView(
    val defectId: UUID, val caseNumber: String, val categoryCode: String, val kind: String?, val vehicleArea: String?, val photos: List<KnownDefectPhoto>,
    val stillOpen: Boolean, val acknowledged: Boolean = false, val acknowledgedAt: Instant? = null, val acknowledgedDefectRevision: Long? = null,
)

/** Altschäden eines Prüflaufs ([inspectionId] gesetzt) bzw. die offenen Fälle eines Fahrzeugs (Hinweis vor der Neuanlage). Leere Liste = Leerzustand, keine Quittierungspflicht. */
data class KnownDefectList(val inspectionId: UUID?, val vehicleId: UUID, val defects: List<KnownDefectView>, val acknowledgementRequired: Boolean, val unacknowledgedCount: Int)

/**
 * Quittierung „gesehen/bestehend“. Die Anfrage kennt **kein** Feld (kein Verursacher, keine Schuld, kein Freitext): jedes mitgesendete Feld wird mit 422 abgewiesen und nie gespeichert.
 */
class AcknowledgeKnownDefect private constructor() {
    companion object {
        fun parse(raw: Map<String, Any?>): AcknowledgeKnownDefect {
            val v = Violations()
            v.unknown(raw, emptySet(), "")
            v.throwIfAny()
            return AcknowledgeKnownDefect()
        }
    }
}

/** Ergebnis der Quittierung: [created] ist `false`, wenn der Altschaden für diesen Prüflauf bereits quittiert war (zweites Gerät, Wiederholung): derselbe Eintrag. */
data class AcknowledgedKnownDefect(val inspectionId: UUID, val defect: KnownDefectView, val created: Boolean)

/**
 * Altschäden und Quittierung. Fahrer sehen und quittieren nur Läufe eigener Schichten, sonst 404 (kein Hinweis auf die Existenz); Disposition liest, Werkstatt liest und quittiert. Die
 * Quittierung legt nie eine Schadenakte an, ändert keine und speichert keine Verursacherangabe; sie trägt den Stand der Akte (Revision, Status) zum Quittierungszeitpunkt ein (die
 * Datenbank setzt ihn unter Zeilensperre). Genau ein Eintrag je (Prüflauf, Altschaden), auch bei gleichzeitiger Quittierung von zwei Geräten. Ein später abgeschlossener Altschaden
 * verhindert den Prüfabschluss nie.
 */
interface KnownDefects {
    /** Altschäden des Prüflaufs mit Quittierungsstand; nach dem Abschluss die quittierten samt Stand zum Quittierungszeitpunkt. */
    fun forInspection(ctx: RequestContext, inspectionId: UUID): KnownDefectList

    /**
     * Quittiert [defectId]. Akte nicht beim Fahrzeug des Laufs oder nicht Altschaden dieses Laufs: 404; Akte inzwischen abgeschlossen: 409 [KnownDefectCatalog.NOT_OPEN]; Lauf nicht mehr
     * offen: 409 `INVALID_STATE_TRANSITION`.
     */
    fun acknowledge(ctx: RequestContext, inspectionId: UUID, defectId: UUID, cmd: AcknowledgeKnownDefect): AcknowledgedKnownDefect

    /** Offene Fälle des Fahrzeugs in derselben Projektion (Hinweis vor Neuanlage). Fahrer nur mit Bezug zum Fahrzeug (Schicht), sonst 404. */
    fun forVehicle(ctx: RequestContext, vehicleId: UUID): KnownDefectList

    /**
     * Voraussetzung für den Prüfabschluss (`submitInspection`, WP-M3-14) innerhalb der laufenden Mandantentransaktion: 409 [KnownDefectCatalog.UNACKNOWLEDGED], solange ein offener Altschaden
     * unquittiert ist. Die Datenbank erzwingt dieselbe Regel zusätzlich beim Statuswechsel nach `COMPLETED` (Trigger, auch für direkte Zugriffe).
     */
    fun requireAllAcknowledged(jdbc: JdbcOperations, inspectionId: UUID)
}
