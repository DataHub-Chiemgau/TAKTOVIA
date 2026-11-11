package de.taktovia.platform.promise

import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import tools.jackson.databind.JsonNode
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.util.UUID

/** Zulässige Änderungsverfahren (vorläufiger Katalog, im Pflichtenheft nicht abschließend festgelegt: OP-29/OP-01). */
enum class ChangeProcedure { OPERATIONAL_CHANGE, CUSTOMER_REQUEST, CORRECTION }

/**
 * JSON-Schema des Zusage-Snapshots in der Version 1.0 (`docs/umsetzungsplan/vertraege/schemas/promise-snapshot.v1.schema.json`; ein Test hält beide
 * synchron). Welche Felder „Kernkonditionen“ sind und welche Änderungen eine Fahrgastinformation verlangen, ist im Pflichtenheft nicht abschließend
 * festgelegt (OP-01/OP-29): hier gilt die Testregel „Änderung eines als fahrgastrelevant markierten Feldes löst eine Information aus“; der Einsatz
 * an der Buchung (`booking.promise_snapshot`) braucht die Festlegung des Auftraggebers.
 */
object PromiseSnapshotSchema {
    const val CURRENT = "1.0"
    val KNOWN_VERSIONS = setOf(CURRENT)

    enum class Kind { TIMESTAMP, MONEY_MINOR, CURRENCY, TEXT, RESOURCE_VECTOR }
    data class Field(val name: String, val kind: Kind, val required: Boolean, val passengerRelevant: Boolean, val maxLength: Int = 64)

    val FIELDS_V1: List<Field> = listOf(
        Field("pickup_earliest", Kind.TIMESTAMP, true, true), Field("pickup_latest", Kind.TIMESTAMP, true, true),
        Field("arrival_earliest", Kind.TIMESTAMP, true, true), Field("arrival_latest", Kind.TIMESTAMP, true, true),
        Field("gross_minor", Kind.MONEY_MINOR, true, true), Field("currency", Kind.CURRENCY, true, true),
        Field("cancellation_policy_ref", Kind.TEXT, true, true, 64),
        Field("vehicle_class", Kind.TEXT, true, false, 32),
        Field("internal_note", Kind.TEXT, false, false, 200),
        // WP-M2-14: Ressourcenvektor der Buchung (Sitz, Rollstuhlplatz, Ausstattung) ist nach der Bestätigung Teil der Zusage; Änderungen betreffen den Fahrgast.
        Field("resource_vector", Kind.RESOURCE_VECTOR, false, true),
    )

    fun fields(version: String): List<Field> = when (version) { "1.0" -> FIELDS_V1; else -> throw unknownVersion() }
    fun passengerRelevant(version: String): Set<String> = fields(version).filter { it.passengerRelevant }.map { it.name }.toSet()

    fun unknownVersion() = ProblemException.invalid(Violation("/schema_version", ViolationCode.NOT_IN_ENUM))

    /** Strukturprüfung gegen die Felddefinition; die Pointer beginnen unter `/content`. */
    fun validate(version: String, content: JsonNode): List<Violation> {
        val defs = fields(version)
        if (!content.isObject) return listOf(Violation("/content", ViolationCode.TYPE_MISMATCH))
        val out = mutableListOf<Violation>()
        content.propertyNames().filter { n -> defs.none { it.name == n } }.forEach { out += Violation("/content/$it", ViolationCode.UNKNOWN_FIELD) }
        for (f in defs) {
            val v = content.get(f.name)
            if (v == null || v.isNull) { if (f.required) out += Violation("/content/${f.name}", ViolationCode.REQUIRED); continue }
            val p = "/content/${f.name}"
            when (f.kind) {
                Kind.TIMESTAMP -> if (!v.isString) out += Violation(p, ViolationCode.TYPE_MISMATCH)
                    else if (!RFC3339.matches(v.asString()) || !parses(v.asString())) out += Violation(p, ViolationCode.INVALID_FORMAT)
                Kind.MONEY_MINOR -> if (!v.isIntegralNumber) out += Violation(p, ViolationCode.MONEY_NOT_INTEGER)
                    else if (v.asLong() < 0) out += Violation(p, ViolationCode.MONEY_NEGATIVE)
                    else if (v.asLong() > MAX_SAFE) out += Violation(p, ViolationCode.MONEY_OUT_OF_RANGE)
                Kind.CURRENCY -> if (!v.isString || !Regex("[A-Z]{3}").matches(v.asString())) out += Violation(p, ViolationCode.PATTERN_MISMATCH)
                Kind.RESOURCE_VECTOR -> out += resourceVector(p, v)
                Kind.TEXT -> if (!v.isString) out += Violation(p, ViolationCode.TYPE_MISMATCH)
                    else if (v.asString().isEmpty()) out += Violation(p, ViolationCode.TOO_SHORT)
                    else if (v.asString().length > f.maxLength) out += Violation(p, ViolationCode.TOO_LONG)
            }
        }
        return out
    }

    /** Struktur des Ressourcenvektors (Schema `resource-vector.v1`); die Merkmalskataloge prüft `booking-dispatch` (`ResourceVector.parse`). */
    private fun resourceVector(p: String, v: JsonNode): List<Violation> {
        if (!v.isObject) return listOf(Violation(p, ViolationCode.TYPE_MISMATCH))
        val out = mutableListOf<Violation>()
        v.propertyNames().filter { it !in setOf("schema_version", "seats", "wheelchair", "equipment") }.forEach { out += Violation("$p/$it", ViolationCode.UNKNOWN_FIELD) }
        for (dim in listOf("seats", "wheelchair")) {
            val n = v.get(dim) ?: continue
            if (!n.isIntegralNumber) out += Violation("$p/$dim", ViolationCode.TYPE_MISMATCH) else if (n.asLong() < 0) out += Violation("$p/$dim", ViolationCode.BELOW_MINIMUM)
            else if (n.asLong() > 99) out += Violation("$p/$dim", ViolationCode.ABOVE_MAXIMUM)
        }
        v.get("equipment")?.let { e -> if (!e.isArray) out += Violation("$p/equipment", ViolationCode.TYPE_MISMATCH) }
        return out
    }

    private const val MAX_SAFE = 9_007_199_254_740_991L
    private val RFC3339 = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})""")
    private fun parses(s: String) = try { OffsetDateTime.parse(s); true } catch (e: DateTimeException) { false }
}

/**
 * Gebundener Snapshot einer verbindlichen Zusage: Inhalt (Kernkonditionen), Version des JSON-Schemas, SHA-256 über die kanonische JSON-Darstellung
 * des Inhalts, Revision und die referenzierte Konfigurations-/Tarifrevision. Unveränderlich; eine Änderung ist eine neue Revision (WP-M1-27).
 */
class BoundSnapshot private constructor(
    val promiseId: UUID, val revision: Long, val schemaVersion: String, val content: JsonNode, val contentHash: String, val configRevisionId: UUID,
) {
    init { require(revision >= 1) }

    companion object {
        fun hashOf(content: JsonNode): String = EventEnvelope.sha256(content)

        /** Bindet [content]: Version bekannt, Inhalt schemakonform, Hash wird berechnet. */
        fun bind(promiseId: UUID, revision: Long, schemaVersion: String, content: JsonNode, configRevisionId: UUID): BoundSnapshot {
            if (schemaVersion !in PromiseSnapshotSchema.KNOWN_VERSIONS) throw PromiseSnapshotSchema.unknownVersion()
            val v = PromiseSnapshotSchema.validate(schemaVersion, content)
            if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
            return BoundSnapshot(promiseId, revision, schemaVersion, content, hashOf(content), configRevisionId)
        }

        /** Übernimmt einen Snapshot samt mitgelieferter Prüfsumme (z. B. aus einer Preisauskunft): stimmt der Hash nicht, wird er abgewiesen. */
        fun adopt(promiseId: UUID, revision: Long, schemaVersion: String, content: JsonNode, claimedHash: String, configRevisionId: UUID): BoundSnapshot {
            val s = bind(promiseId, revision, schemaVersion, content, configRevisionId)
            if (s.contentHash != claimedHash) throw ProblemException.invalid(Violation("/content_hash", ViolationCode.INVALID_VALUE))
            return s
        }

        /** Aus der Datenbank gelesen: Hash und Version werden erneut geprüft; eine Abweichung ist Datenverfälschung und kein Eingabefehler. */
        internal fun fromStore(promiseId: UUID, revision: Long, schemaVersion: String, content: JsonNode, storedHash: String, configRevisionId: UUID): BoundSnapshot {
            check(schemaVersion in PromiseSnapshotSchema.KNOWN_VERSIONS) { "Snapshot $promiseId/$revision hat unbekannte Schema-Version" }
            check(hashOf(content) == storedHash) { "Snapshot $promiseId/$revision: Prüfsumme stimmt nicht mit dem Inhalt überein" }
            return BoundSnapshot(promiseId, revision, schemaVersion, content, storedHash, configRevisionId)
        }
    }
}
