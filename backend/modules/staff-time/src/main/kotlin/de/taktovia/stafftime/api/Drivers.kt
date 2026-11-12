package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class ContactChannel { PHONE, EMAIL, APP }
enum class DriverStatus { ACTIVE, INACTIVE }

/** Freigabestatus einer Qualifikationsrevision: wartend, freigegeben, entzogen. Nur eine freigegebene Revision innerhalb ihres Gültigkeitsintervalls gilt (WP-M3-05 Kriterium 1). */
enum class QualificationStatus { PENDING, APPROVED, REVOKED }

/** Rolle, die HR-nahe Felder sieht (Personalplanung, Rechtematrix-Datenklasse HR). Dispositionssichten (Disponent, Planer) erhalten diese Felder nie. */
const val HR_ROLE = "staff_planning"

private class Collect {
    val list = mutableListOf<Violation>()
    fun add(p: String, c: ViolationCode) { list += Violation(p, c) }
    fun check() { if (list.isNotEmpty()) throw ProblemException.invalid(*list.toTypedArray()) }
    fun unknown(raw: Map<String, Any?>, allowed: Set<String>) { raw.keys.forEach { if (it !in allowed) add("/$it", ViolationCode.UNKNOWN_FIELD) } }
    fun text(raw: Map<String, Any?>, key: String, min: Int, max: Int): String? {
        val v = raw[key] ?: run { add("/$key", ViolationCode.REQUIRED); return null }
        val s = v as? String ?: run { add("/$key", ViolationCode.TYPE_MISMATCH); return null }
        if (s.trim().length < min) { add("/$key", ViolationCode.TOO_SHORT); return null }
        if (s.length > max) { add("/$key", ViolationCode.TOO_LONG); return null }
        return s
    }
    fun enum(raw: Map<String, Any?>, key: String, values: Set<String>): String? {
        val v = raw[key] ?: run { add("/$key", ViolationCode.REQUIRED); return null }
        val s = v as? String ?: run { add("/$key", ViolationCode.TYPE_MISMATCH); return null }
        if (s !in values) { add("/$key", ViolationCode.NOT_IN_ENUM); return null }
        return s
    }
    fun instant(raw: Map<String, Any?>, key: String, required: Boolean): Instant? {
        val v = raw[key] ?: run { if (required) add("/$key", ViolationCode.REQUIRED); return null }
        val s = v as? String ?: run { add("/$key", ViolationCode.TYPE_MISMATCH); return null }
        return runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull() ?: run { add("/$key", ViolationCode.INVALID_FORMAT); null }
    }
}

/** Anlegen eines Fahrers: Verknüpfung zur Mitgliedschaft (Pflicht, endgültig) und Stammdaten; neue Fahrer sind aktiv. Der Mandant steht nie im Body. */
data class DriverCreate(val membershipId: UUID, val displayName: String, val contactChannel: ContactChannel, val contactValue: String, val status: DriverStatus = DriverStatus.ACTIVE) {
    companion object {
        fun parse(raw: Map<String, Any?>): DriverCreate {
            val v = Collect()
            v.unknown(raw, setOf("membership_id", "display_name", "contact_channel", "contact_value"))
            val membership = (raw["membership_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { v.add("/membership_id", if (raw["membership_id"] == null) ViolationCode.REQUIRED else ViolationCode.INVALID_FORMAT); null }
            val name = v.text(raw, "display_name", 1, 200)
            val channel = v.enum(raw, "contact_channel", ContactChannel.entries.map { it.name }.toSet())
            val value = v.text(raw, "contact_value", 1, 200)
            v.check()
            return DriverCreate(membership!!, name!!, ContactChannel.valueOf(channel!!), value!!)
        }
    }
}

/** Ändern der Stammdaten; die Mitgliedschaft ist nach der Zuordnung nicht änderbar (unbekanntes Feld). */
data class DriverUpdate(val displayName: String, val contactChannel: ContactChannel, val contactValue: String, val status: DriverStatus) {
    companion object {
        fun parse(raw: Map<String, Any?>): DriverUpdate {
            val v = Collect()
            v.unknown(raw, setOf("display_name", "contact_channel", "contact_value", "active"))
            val name = v.text(raw, "display_name", 1, 200)
            val channel = v.enum(raw, "contact_channel", ContactChannel.entries.map { it.name }.toSet())
            val value = v.text(raw, "contact_value", 1, 200)
            val active = raw["active"] as? Boolean ?: run { v.add("/active", if (raw["active"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            v.check()
            return DriverUpdate(name!!, ContactChannel.valueOf(channel!!), value!!, if (active!!) DriverStatus.ACTIVE else DriverStatus.INACTIVE)
        }
    }
}

/** HR-nahe Felder: nur für [HR_ROLE] sichtbar. */
data class DriverHrFields(val displayName: String, val contactChannel: ContactChannel, val contactValue: String)

/**
 * Fahrer, wie ihn der Aufrufer sehen darf: [hr] ist `null` ohne HR-Berechtigung (Dispositionssicht). [qualificationSeq] ist die fortlaufende Qualifikationsnummer des Fahrers
 * (Bezug von `resource_release.qualification_revision`), [revision] die Revision (ETag) von Stamm und Qualifikationen.
 */
data class DriverView(
    val id: UUID, val membershipId: UUID?, val status: DriverStatus, val qualificationSeq: Long, val revision: Long, val createdAt: Instant, val updatedAt: Instant, val hr: DriverHrFields?,
)

/** Eine Qualifikationsrevision. [evidenceRef] (interner Nachweisverweis) ist HR-nah und nur für [HR_ROLE] gefüllt. */
data class QualificationView(
    val id: UUID, val code: String, val revision: Long, val status: QualificationStatus, val validFrom: Instant, val validTo: Instant?, val approvedBy: String?, val approvedAt: Instant?,
    val driverSeq: Long?, val evidenceRef: String?, val createdAt: Instant,
    /** Wirksame Revision der Qualifikation (höchste nicht wartende Revision). */
    val effective: Boolean,
    /** Gilt zum angefragten Zeitpunkt: wirksam, freigegeben und im Gültigkeitsintervall. */
    val validAt: Boolean,
)

/** Neue Qualifikationsrevision. [status] APPROVED gibt frei (Freigeber und Zeitpunkt werden aus dem Kontext gesetzt), REVOKED entzieht, PENDING legt wartend an. */
data class QualificationInput(val code: String, val validFrom: Instant, val validTo: Instant?, val evidenceRef: String?, val status: QualificationStatus) {
    companion object {
        val CODE = Regex("^[A-Z][A-Z0-9_]{1,39}$")
        fun parse(raw: Map<String, Any?>): QualificationInput {
            val v = Collect()
            v.unknown(raw, setOf("qualification_code", "valid_from", "valid_to", "evidence_ref", "decision"))
            val code = (raw["qualification_code"] as? String)?.also { if (!CODE.matches(it)) v.add("/qualification_code", ViolationCode.PATTERN_MISMATCH) }
                ?: run { v.add("/qualification_code", if (raw["qualification_code"] == null) ViolationCode.REQUIRED else ViolationCode.TYPE_MISMATCH); null }
            val from = v.instant(raw, "valid_from", true)
            val to = v.instant(raw, "valid_to", false)
            if (from != null && to != null && !to.isAfter(from)) v.add("/valid_to", ViolationCode.INTERVAL_INVALID)
            val evidence = if (raw["evidence_ref"] == null) null else v.text(raw, "evidence_ref", 1, 200)
            val status = v.enum(raw, "decision", QualificationStatus.entries.map { it.name }.toSet())
            v.check()
            return QualificationInput(code!!, from!!, to, evidence, QualificationStatus.valueOf(status!!))
        }
    }
}

/** Eine zum Zeitpunkt gültige Qualifikation (Lesefunktion für Einsatzfreigabe und Prüfung). */
data class ValidQualification(val code: String, val revision: Long, val validFrom: Instant, val validTo: Instant?)

/**
 * Fahrerstamm und Qualifikationsrevisionen (WP-M3-05, Modul `staff-time`, OP-07). Ein Fahrer gehört genau einer aktiven Mitgliedschaft mit der Rolle `driver` des Mandanten (409, wenn
 * schon verknüpft oder nicht passend); fremde Fahrer sind 404. Jede Änderung (Stamm oder Qualifikation) hat genau ein Audit und erhöht die Revision des Fahrers (If-Match, 412).
 * Qualifikationen sind append-only: eine Änderung ist eine neue Revision, die alte bleibt lesbar. Die Revision wird unter der Fahrerzeilensperre vergeben und die fortlaufende Nummer
 * des Fahrers erhöht; gleichzeitige Änderungen ergeben nie doppelte oder fehlende Nummern.
 */
interface Drivers {
    fun create(ctx: RequestContext, input: DriverCreate): DriverView
    fun get(ctx: RequestContext, driverId: UUID): DriverView
    fun list(ctx: RequestContext, status: DriverStatus?, limit: Int, after: Pair<Instant, UUID>?): List<DriverView>
    fun update(ctx: RequestContext, driverId: UUID, expectedRevision: Long, input: DriverUpdate): DriverView

    /** Qualifikationsrevisionen des Fahrers (alle, neueste zuerst je Qualifikation) mit Kennzeichen „wirksam“ und „gilt zum Zeitpunkt [at]“ (Standard: jetzt); [onlyValid] liefert nur die geltenden. */
    fun qualifications(ctx: RequestContext, driverId: UUID, at: Instant?, onlyValid: Boolean): List<QualificationView>

    /** Legt eine neue Revision an (Freigabe, Entzug oder wartend); liefert sie zusammen mit dem Fahrer nach der Änderung. */
    fun addQualification(ctx: RequestContext, driverId: UUID, expectedRevision: Long, input: QualificationInput): Pair<QualificationView, DriverView>

    /** Gültige Qualifikationen zum Zeitpunkt [at] innerhalb der laufenden Mandantentransaktion des Aufrufers (nicht freigegeben, entzogen oder außerhalb des Intervalls: nicht vorhanden). */
    fun validQualifications(jdbc: JdbcOperations, driverId: UUID, at: Instant): List<ValidQualification>

    /** Fortlaufende Qualifikationsnummer des Fahrers (0 ohne Änderung seit M3). */
    fun qualificationRevision(jdbc: JdbcOperations, driverId: UUID): Long
}
