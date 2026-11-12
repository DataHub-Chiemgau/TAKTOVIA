package de.taktovia.fleetsafety

import de.taktovia.fleetsafety.api.AcknowledgeKnownDefect
import de.taktovia.fleetsafety.api.DefectState
import de.taktovia.fleetsafety.api.DefectTransition
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.ReportDefect
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen der Altschaden-Quittierung (WP-M3-21), eigene Klasse: Mandant mit Fahrzeug, Fahrer mit angetretener Schicht und gestartetem Prüflauf, Altschäden (Akten), Abschluss des
 * Prüflaufs unter Administration (der Abschluss `submitInspection` folgt mit WP-M3-14; der Trigger der Datenbank ist die Sperre, die der Test hier prüft).
 */
class KnownDamageWorld(val fx: FleetFixture, val fleet: FleetsafetyApi = fx.fleet) {
    val iw = InspectionWorld(fx)
    val tenant = iw.tenant
    val pkwTemplate = iw.published("PKW")
    val vehicle: UUID = iw.vehicle("PKW")

    fun ctx(subject: UUID, vararg roles: String) = iw.ctx(subject, *roles)
    val dispatcher: RequestContext = ctx(UUID.randomUUID(), "dispatcher")
    val workshop: RequestContext = ctx(UUID.randomUUID(), "workshop")

    /** Offene Akte (Meldung durch die Disposition; die Fahrer-Neuanlage ist der Gegenstand eines eigenen Tests). */
    fun defect(vehicle: UUID = this.vehicle, category: String = "BODY", description: String = "Kratzer an der Beifahrertür"): UUID =
        fleet.defects.report(dispatcher, ReportDefect.parse(linkedMapOf("vehicle_id" to vehicle.toString(), "occurred_at" to Instant.now().minusSeconds(3600).toString(),
            "category_code" to category, "description" to description))).defect.id

    class Driver(val subject: UUID, val ctx: RequestContext, val shift: UUID)

    fun driver(vehicle: UUID = this.vehicle): Driver = UUID.randomUUID().let { s -> Driver(s, ctx(s, "driver"), iw.shift(s, vehicle)) }

    /** Prüflauf des Fahrers; **nach** den Altschäden anlegen (Altschaden = beim Start des Laufs vorhanden). */
    fun startRun(d: Driver, vehicle: UUID = this.vehicle, kind: String = "PRE_SHIFT"): UUID = fleet.inspections.start(d.ctx,
        de.taktovia.fleetsafety.api.StartInspection.parse(linkedMapOf("shift_id" to d.shift.toString(), "vehicle_id" to vehicle.toString(), "kind" to kind, "client_started_at" to Instant.now().toString()))).inspection.id

    fun ack(d: Driver, run: UUID, defect: UUID) = fleet.knownDefects.acknowledge(d.ctx, run, defect, AcknowledgeKnownDefect.parse(emptyMap()))

    fun dismiss(defect: UUID, rev: Long = 1) = fleet.defects.transition(workshop, defect, rev, DefectTransition.parse(linkedMapOf("to" to DefectState.DISMISSED.name, "reason" to "Fehlmeldung")))

    /** Abschluss des Laufs (wie `submitInspection` ihn setzt): wirft die SQLException des Wächters, solange offene Altschäden unquittiert sind. */
    fun complete(run: UUID) = fx.exec("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now() WHERE id=?", run)

    fun status(run: UUID) = fx.strings("SELECT status FROM taktovia.inspection_run WHERE id=?", run).single()
    fun defectCount() = fx.count("SELECT count(*) FROM taktovia.defect_case WHERE tenant_id=?", tenant.value)
    fun ackCount(run: UUID, defect: UUID) = fx.count("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE inspection_id=? AND defect_id=?", run, defect)
}
