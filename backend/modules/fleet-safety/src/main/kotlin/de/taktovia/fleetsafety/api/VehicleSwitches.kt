package de.taktovia.fleetsafety.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/** Begründungskatalog des Fahrzeugwechsels; `OTHER` verlangt einen Hinweistext (Planungsannahme, die Begründung ist im Lastenheft nicht katalogisiert). */
enum class SwitchReason { DEFECT, BREAKDOWN, SERVICE_NEED, CAPACITY, OTHER }

/** Wer den Wechsel ausgelöst hat: der Fahrer der Schicht oder die Leitstelle (Planungsannahme zu OP-08, beide dürfen). */
enum class SwitchActorRole { DRIVER, DISPATCH }

/**
 * Wechsel auf das Ersatzfahrzeug [newVehicleId] in der Schicht [shiftId] (WP-M3-22). [fromVehicleId] ist das Fahrzeug, das der Auslöser als aktuell sieht (optimistische Bedingung wie `If-Match`):
 * fährt die Schicht inzwischen ein anderes, ist der Wechsel 409, auch wenn ein paralleler Wechsel schon abgeschlossen ist. [note] ist ein begrenzter Hinweistext ohne Personenangaben (1 bis 500 Zeichen); er steht nur in der
 * Wechselhistorie und im Audit, nie im Ereignis.
 */
data class SwitchVehicle(val shiftId: UUID, val fromVehicleId: UUID, val newVehicleId: UUID, val reason: SwitchReason, val note: String? = null) {
    val trimmedNote: String? = note?.trim()?.ifEmpty { null }

    init {
        val violations = buildList {
            if (note != null && trimmedNote == null) add(Violation("/note", ViolationCode.REQUIRED))
            if (trimmedNote != null && trimmedNote.length > 500) add(Violation("/note", ViolationCode.TOO_LONG))
            if (reason == SwitchReason.OTHER && trimmedNote == null && none { it.pointer == "/note" }) add(Violation("/note", ViolationCode.REQUIRED))
        }
        if (violations.isNotEmpty()) throw ProblemException.invalid(*violations.toTypedArray())
    }

    companion object {
        private val ALLOWED = setOf("from_vehicle_id", "new_vehicle_id", "reason_code", "note")

        /** Strikte Prüfung des JSON-Körpers (`shiftId` kommt aus dem Pfad, nie aus dem Körper). */
        fun parse(shiftId: UUID, body: Map<String, Any?>): SwitchVehicle {
            val v = mutableListOf<Violation>()
            body.keys.filter { it !in ALLOWED }.forEach { v += Violation("/$it", ViolationCode.UNKNOWN_FIELD) }
            fun uuid(key: String): UUID? = (body[key] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }.also { if (it == null) v += Violation("/$key", if (body[key] == null) ViolationCode.REQUIRED else ViolationCode.INVALID_FORMAT) }
            val from = uuid("from_vehicle_id"); val vehicle = uuid("new_vehicle_id")
            val reason = (body["reason_code"] as? String)?.let { c -> SwitchReason.entries.firstOrNull { it.name == c } }
            if (reason == null) v += Violation("/reason_code", if (body["reason_code"] == null) ViolationCode.REQUIRED else ViolationCode.NOT_IN_ENUM)
            val note = body["note"]
            if (note != null && note !is String) v += Violation("/note", ViolationCode.TYPE_MISMATCH)
            if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
            return SwitchVehicle(shiftId, from!!, vehicle!!, reason!!, note as String?)
        }
    }
}

/** Eine Zeile der Wechselhistorie; [revokedReleases] = im selben Commit widerrufene gültige Einsatzfreigaben der Schicht. */
data class VehicleSwitchView(
    val id: UUID, val shiftId: UUID, val fromVehicleId: UUID, val toVehicleId: UUID, val reason: SwitchReason, val note: String?, val actorRole: SwitchActorRole,
    val revokedReleases: Int, val createdAt: Instant,
)

/**
 * Schreibende Seite der Schichtzuweisung (Besitzer: `staff-time`), vom Fahrzeugwechsel in der **laufenden** Mandantentransaktion gerufen. Die Anwendung verdrahtet sie mit
 * `de.taktovia.stafftime.api.Shifts.changeVehicle`; ohne Verdrahtung antwortet der Wechsel ehrlich 503 und ändert nichts. Das Modul schreibt `staff_shift` nie selbst.
 */
fun interface ShiftVehiclePort {
    fun change(jdbc: JdbcOperations, ctx: RequestContext, shiftId: UUID, newVehicleId: UUID)
}

/**
 * Fahrzeugwechsel in laufender Schicht (WP-M3-22, LH-6.21-CHK-015, Planungsannahme zu OP-08: der Wechsel ändert das Fahrzeug **derselben** Schicht; er läuft über die Schichtzuweisung und eine
 * unveränderliche Wechselhistorie, nicht über eine neue Schicht).
 *
 * Auslöser: der Fahrer der Schicht (Rolle `driver`, nur eigene Schicht, sonst 404) oder die Leitstelle (`dispatcher`, `tenant_admin`); jede andere Rolle: 403, fremder Mandant: 404.
 * Alles in **einer** Transaktion, Sperrreihenfolge Fahrzeuge (nach Kennung), Schicht, Fahrer: (1) die Schicht muss geplant, veröffentlicht oder angetreten und nicht abgelaufen sein;
 * (2) Ersatzfahrzeug aktiv, ohne aktive Sperre (409), der Fahrer mit gültiger Qualifikation und, wo für den Fahrzeugtyp hinterlegt, mit der Qualifikationsart des Typs (422, neutral);
 * (3) jede gültige Einsatzfreigabe der Schicht wird widerrufen (Grund `VEHICLE_CHANGED`), das Ersatzfahrzeug erhält **keine** Freigabe: sie entsteht erst durch einen eigenen, nach dem Wechsel
 * angelegten und bestandenen Prüflauf dieses Fahrzeugs (Prüfung des alten Fahrzeugs trägt nie, auch nicht bei Rückwechsel); (4) Wechselzeile, Audit (Akteur, Begründung, altes und neues Fahrzeug)
 * und Ereignis `fleet.vehicle_switched` ohne Freitext. Zwei gleichzeitige Wechsel derselben Schicht: genau einer gewinnt, der andere 409. Die Prüfhistorie beider Fahrzeuge bleibt unverändert.
 * Offene Stopps des Umlaufs gehen **nicht** automatisch über (WP-M3-40); das Ersatzfahrzeug liefert bis zur Freigabe keine Aufträge aus.
 */
interface VehicleSwitches {
    fun switchVehicle(ctx: RequestContext, cmd: SwitchVehicle): VehicleSwitchView

    /** Wechselhistorie der Schicht, neueste zuerst; Fahrer nur eigener Schichten, Leitstelle alle des Mandanten. */
    fun history(ctx: RequestContext, shiftId: UUID): List<VehicleSwitchView>
}
