package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.DefectView
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.fleetsafety.api.ReportedDefect
import de.taktovia.fleetsafety.api.SaveInspectionDraft
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.storage.ObjectKey
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen der Abweichung aus Prüfschritt (WP-M3-20), eigene Klasse je Cluster: ein Mandant mit gestartetem Prüflauf eines Fahrers (drei Pflichtschritte, STEP_1 sicherheitskritisch,
 * STEP_2 und STEP_3 nicht kritisch), Fotos über denselben Upload- und Prüfpfad wie Schadensfotos (WP-M3-44), Meldung mit Herkunft.
 */
class OriginWorld(val fx: FleetFixture) {
    val rw = ReadinessWorld(fx)
    val h = EvidenceHarness(fx)
    val tenant = rw.tenant
    val run = rw.startedRun()
    val defects = h.fleet.defects

    /** Antwort im Entwurf des Laufs (Entwurf genügt: der Schritt ist dann negativ beantwortet). */
    fun answer(step: String, status: String = "DEFECT", note: String? = "Riss sichtbar") {
        val rev = h.fleet.inspections.get(run.ctx, run.id).revision
        h.fleet.inspections.saveDraft(run.ctx, run.id, rev, SaveInspectionDraft(listOf(rw.answer(step, status, note = note))))
    }

    fun raw(step: String? = "STEP_2", inspection: UUID? = run.id, vehicle: UUID = run.vehicle, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>(
        "vehicle_id" to vehicle.toString(), "occurred_at" to Instant.now().minusSeconds(300).toString(), "category_code" to "GLASS", "description" to "Riss in der Windschutzscheibe",
        "confirm_new_despite_known" to true,
    ).also { m -> if (inspection != null) m["source_inspection_id"] = inspection.toString(); if (step != null) m["source_step_code"] = step; m.putAll(extra) }

    fun report(ctx: RequestContext = run.ctx, raw: Map<String, Any?> = raw()): ReportedDefect = defects.report(ctx, ReportDefect.parse(raw))

    /** Foto über Ticket, Quarantäne und Abschluss: freigegeben, wenn die Medienprüfung besteht (sonst bleibt es in Prüfung). */
    fun photo(seed: Int, ctx: RequestContext = run.ctx): UUID {
        val bytes = h.jpeg(seed); val t = h.ticket(ctx, bytes)
        h.store.put(ObjectKey(tenant, t.uploadKey), bytes, t.mime)
        h.complete(ctx, t)
        return t.id
    }

    fun view(id: UUID, ctx: RequestContext = run.ctx): DefectView = defects.get(ctx, id)
}
