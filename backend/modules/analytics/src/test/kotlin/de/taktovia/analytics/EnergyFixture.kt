package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.Metrics
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Testhilfe des Clusters wave14-c8 (WP-M4-43): Energiekosten-Kennzahlen auf der echten Datenbank. Die Quelldaten (Beleg, Tankvorgang, Kostenereignis, Stornierung, Hinweis, Kilometerlauf) legt der Administrator
 * direkt an (die Module documents-finance und analytics sind getrennt; der Test prüft nur, was analytics aus ihren Tabellen liest), die Auswertung läuft unter der Runtime-Rolle (RLS).
 * Berichtstag ist der 1.12.2026 in Europe/Berlin, [2026-11-30T23:00Z, 2026-12-01T23:00Z); Tankzeit und Kilometerabschnitte liegen am 1.12.2026 (UTC).
 */
class EnergyFixture : AutoCloseable {
    val base = AnalyticsFixture()
    val metrics: Metrics = AnalyticsApi.createMetrics(base.tx, smallGroupThreshold = 3)
    val day: ReportingPeriod = ReportingPeriod.ofDays(ZoneId.of("Europe/Berlin"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))

    class World(val tenant: TenantId, val legalEntity: UUID)

    fun world(): World {
        val t = base.tenant(); val le = UUID.randomUUID()
        base.exec("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'Test GmbH','DE')", t.value, le)
        return World(t, le)
    }

    fun vehicle(w: World): UUID = UUID.randomUUID().also { base.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'org','{}'::jsonb)", w.tenant.value, it) }

    /**
     * Tankbeleg mit Tankvorgang. [state] `POSTED` legt zusätzlich das Ist-Kostenereignis an; [gross], [tax] und [recoverable] in Minor Units (Kostenbasis = gross - recoverable). Rückgabe: Beleg-ID.
     * [vehicle] `null` = Beleg ohne Fahrzeug, [fuelType] `null` = Kraftstoffart fehlt, [quantity] `null` = Menge fehlt.
     */
    fun fuel(
        w: World, vehicle: UUID?, fuelType: String? = "DIESEL", gross: Long = 11900, tax: Long = 1900, recoverable: Long = 1900, currency: String = "EUR", state: String = "POSTED",
        fueledAt: String = "2026-12-01T09:00:00Z", quantity: String? = "40.0", openHint: Boolean = false, bookedAt: String? = null,
    ): UUID {
        val doc = UUID.randomUUID()
        val fields = """{"amount_minor":$gross,"currency":"$currency"}"""
        base.exec("INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at,document_kind,current_fields) " +
            "VALUES (?,?,?,?,?,'image/jpeg','STORED',1,'erfasser',?::timestamptz,'FUEL_RECEIPT',?::jsonb)", w.tenant.value, doc, w.legalEntity, "receipts/originals/$doc", "a".repeat(64), fueledAt, fields)
        base.exec("INSERT INTO taktovia.fuel_transaction(tenant_id,id,document_id,vehicle_id,fuel_type,quantity,unit_price,fueled_at,document_revision,recorded_by) VALUES (?,?,?,?,?,${quantity ?: "NULL"},1.5,?::timestamptz,1,'erfasser')",
            w.tenant.value, UUID.randomUUID(), doc, vehicle, fuelType, fueledAt)
        if (openHint) base.exec("INSERT INTO taktovia.expense_hint(tenant_id,id,document_id,document_revision,rule,threshold,actual_value,basis) VALUES (?,?,?,1,'FUEL_VOLUME','100','150','{}'::jsonb)", w.tenant.value, UUID.randomUUID(), doc)
        if (state != "STORED") base.exec("UPDATE taktovia.expense_document SET state = ? WHERE tenant_id = ? AND id = ?", state, w.tenant.value, doc)
        if (state == "POSTED") event(w, doc, gross, tax, recoverable, currency, bookedAt)
        return doc
    }

    private fun event(w: World, doc: UUID, gross: Long, tax: Long, recoverable: Long, currency: String, bookedAt: String?) {
        base.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor," +
            "tax_rule_revision,occurred_at,booked_at,document_id) VALUES (?,?,?,'EXPENSE',?,'EXPENSE_COST','ACTUAL','FUEL',?,?,?,?,?,'test-1','2026-12-01T00:00:00Z',${if (bookedAt == null) "clock_timestamp()" else "?::timestamptz"},?)",
            *listOfNotNull<Any>(w.tenant.value, UUID.randomUUID(), w.legalEntity, doc.toString(), currency, gross - tax, tax, gross, recoverable, bookedAt, doc).toTypedArray())
    }

    /** Stornierung eines gebuchten Belegs: Gegenereignis mit umgekehrten Vorzeichen (vollständig). */
    fun reverse(w: World, doc: UUID) {
        base.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor," +
            "tax_rule_revision,occurred_at,booked_at,document_id,reverses_id) SELECT tenant_id,?,legal_entity_id,source_system,source_ref || ':storno','EXPENSE_REVERSAL',scenario,category,currency,-net_minor,-tax_minor," +
            "-gross_minor,-recoverable_tax_minor,tax_rule_revision,occurred_at,clock_timestamp(),document_id,id FROM taktovia.economic_event WHERE tenant_id = ? AND document_id = ? AND reverses_id IS NULL",
            UUID.randomUUID(), w.tenant.value, doc)
    }

    /** Stellt einen noch nicht freigegebenen Beleg als freigegeben und gebucht (Zustand und Kostenereignis in einer Transaktion nicht nötig: die Auswertung liest beides in einem Statement). */
    fun approve(w: World, doc: UUID, gross: Long = 11900, tax: Long = 1900, recoverable: Long = 1900, currency: String = "EUR") {
        base.exec("UPDATE taktovia.expense_document SET state = 'POSTED' WHERE tenant_id = ? AND id = ?", w.tenant.value, doc)
        event(w, doc, gross, tax, recoverable, currency, null)
    }

    /** Klassifikationslauf eines Fahrzeugs am UTC-Tag 1.12.2026: Abschnitte als (Kategorie, von, bis, Meter); Summe = Gesamtstrecke (ein Statement: die Summeninvariante wird am Ende geprüft). */
    fun km(w: World, vehicle: UUID, segments: List<Seg>, eventsComplete: Boolean = true, dataAsOf: String = "2020-01-01T00:00:00Z") {
        val run = UUID.randomUUID(); val total = segments.sumOf { it.meters }
        val values = segments.joinToString(",") { "('${it.category}', '${it.from}'::timestamptz, '${it.to}'::timestamptz, ${it.meters})" }
        base.exec("WITH r AS (INSERT INTO taktovia.vehicle_km_run(tenant_id,id,vehicle_id,service_day,input_sha256,data_as_of,rule_version,position_count,events_complete,total_km_thousandths) " +
            "VALUES (?,?,?,'2026-12-01',?,?::timestamptz,1,10,?,?) RETURNING tenant_id, id, vehicle_id, service_day) " +
            "INSERT INTO taktovia.vehicle_km_segment(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,category,km_thousandths,source,rule_version) " +
            "SELECT r.tenant_id, gen_random_uuid(), r.id, r.vehicle_id, r.service_day, v.f, v.t, v.c, v.m, 'POSITION', 1 FROM r CROSS JOIN (VALUES $values) AS v(c, f, t, m)",
            w.tenant.value, run, vehicle, UUID.randomUUID().toString().replace("-", "").repeat(2), dataAsOf, eventsComplete, total)
    }

    class Seg(val category: String, val from: String, val to: String, val meters: Long)

    /** Referenzfahrzeug: 120 km Fahrgast (1 h), 40 km leer (0,5 h), 20 km Überführung (0,5 h), 5 km Werkstatt (0,25 h) = 185 km, 2 Betriebsstunden. */
    fun referenceKm(w: World, vehicle: UUID, eventsComplete: Boolean = true) = km(w, vehicle, listOf(
        Seg("PASSENGER", "2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z", 120_000), Seg("EMPTY", "2026-12-01T07:00:00Z", "2026-12-01T07:30:00Z", 40_000),
        Seg("TRANSFER", "2026-12-01T07:30:00Z", "2026-12-01T08:00:00Z", 20_000), Seg("WORKSHOP", "2026-12-01T08:00:00Z", "2026-12-01T08:15:00Z", 5_000)), eventsComplete)

    /** Elektrofahrzeug: 50 km Fahrgast in einer Stunde. */
    fun electricKm(w: World, vehicle: UUID) = km(w, vehicle, listOf(Seg("PASSENGER", "2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z", 50_000)))

    fun now(): Instant = Instant.now()
    override fun close() = base.close()
}
