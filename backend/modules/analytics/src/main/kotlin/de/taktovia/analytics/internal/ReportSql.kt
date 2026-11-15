package de.taktovia.analytics.internal

import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.ReportingPeriod
import java.sql.Timestamp

/**
 * Gemeinsame Bausteine der Berichtsfilter (WP-M4-58). Jede Abfrage nimmt Zeitraum, Zone und alle Dimensionen als **gebundene Parameter** einer Parameterzeile `p` (CTE) entgegen und vergleicht jede
 * Faktenzeile mit ihr: kein Filterwert gelangt als Text in eine SQL-Zeichenfolge, und ein Filter ohne Treffer ist die leere Menge, nie ein anderer Mandant (RLS gilt unabhängig davon in jedem Statement).
 * Reihenfolge der Parameter: von, bis, Dienst, Kanal, Betreiber, Region, Gebiet, Stunde, Zone, Dienstscope (`uuid[]`, `NULL` = alle Dienste).
 */
internal object ReportSql {
    const val P = "p AS (SELECT CAST(? AS timestamptz) AS t_from, CAST(? AS timestamptz) AS t_to, CAST(? AS uuid) AS service, CAST(? AS text) AS channel, CAST(? AS uuid) AS operator, " +
        "CAST(? AS uuid) AS region, CAST(? AS uuid) AS area, CAST(? AS integer) AS hr, CAST(? AS text) AS zone, CAST(? AS uuid[]) AS allowed)"

    /** Wie [P], zusätzlich mit Fahrzeug und Fahrzeugtyp (Abholkennzahlen). */
    const val PV = "p AS (SELECT CAST(? AS timestamptz) AS t_from, CAST(? AS timestamptz) AS t_to, CAST(? AS uuid) AS service, CAST(? AS text) AS channel, CAST(? AS uuid) AS operator, " +
        "CAST(? AS uuid) AS region, CAST(? AS uuid) AS area, CAST(? AS integer) AS hr, CAST(? AS text) AS zone, CAST(? AS uuid[]) AS allowed, CAST(? AS uuid) AS vehicle, CAST(? AS text) AS vtype)"

    /** Passt eine Zeile `f` der Versuchsfakten zu allen gesetzten Dimensionen und zur Berechtigungsgrenze? */
    const val ATTEMPT_MATCH = "(p.allowed IS NULL OR f.service_id = ANY (p.allowed)) AND (p.service IS NULL OR f.service_id = p.service) AND (p.channel IS NULL OR f.channel = p.channel) " +
        "AND (p.operator IS NULL OR f.operator_id = p.operator) AND (p.region IS NULL OR f.region_id = p.region) AND (p.area IS NULL OR f.service_area_id = p.area) " +
        "AND (p.hr IS NULL OR taktovia.report_hour(f.requested_at, p.zone) = p.hr)"

    /** Argumente in der Reihenfolge von [PV]. */
    fun argsWithVehicle(period: ReportingPeriod, f: MetricFilter): Array<Any?> = arrayOf(*args(period, f), f.vehicleId?.toString(), f.vehicleType)

    /** Argumente in der Reihenfolge von [P]; [period] ist der Berichtszeitraum **nach** Anwendung des Tagesfilters. */
    fun args(period: ReportingPeriod, f: MetricFilter): Array<Any?> = arrayOf(
        Timestamp.from(period.from), Timestamp.from(period.to), f.serviceId?.toString(), f.channel, f.operatorId?.toString(), f.regionId?.toString(), f.serviceAreaId?.toString(), f.hour, period.zone.id,
        f.allowedServices?.joinToString(",", "{", "}"),
    )
}
