package de.taktovia.app.isolation

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * WP-M1-11 / LH-6.1-SYS-001, LH-I.4-MUL-007: Cross-Tenant-Scan über **alle gebauten M1-Endpunkte** der OpenAPI-Verträge (echte Anwendung, Runtime-Rolle, echte Datenbank).
 * Mandant A kennt Objekt-IDs von Mandant B und ruft jeden Endpunkt mit Pfadparameter (Detail, Änderung, Löschung) damit auf: die Antwort muss gleichartig zu der für
 * eine nicht vorhandene ID sein (404, gleicher Problemtyp, Code, Titel und Detail, ohne Fremdwerte). Listenendpunkte zeigen nie Objekte von B, und B bleibt danach unverändert.
 * Vollständigkeit: jeder gebaute Endpunkt mit Pfadparameter und jeder Listenweg muss in diesem Scan stehen, sonst schlägt der Test fehl (neue Endpunkte kommen so in den Scan).
 * Abgrenzung: Such-, Medien-, Beleg-, Export- und Finanzendpunkte entstehen mit M2/M4 und werden dann in denselben Scan aufgenommen (PH-AT-001 vollständig erst dann).
 */
class CrossTenantIsolationTest {
    private val env = IdentityEnv
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val pending: Set<String> = Files.readAllLines(repo.resolve("api/contract-pending.txt")).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
    private fun idem() = mapOf("Idempotency-Key" to "iso-" + UUID.randomUUID())

    private class Side(val tenant: TenantId, val admin: UUID, val adminToken: String, val callcenter: UUID, val ccToken: String, val passenger: UUID, val passengerToken: String,
        val planner: UUID, val plannerToken: String, val dispatcher: UUID, val dispatcherToken: String, val driver: UUID, val driverToken: String, val finance: UUID, val financeToken: String)

    private fun side(): Side {
        val t = env.tenant("Iso-" + UUID.randomUUID()); val a = UUID.randomUUID(); val c = UUID.randomUUID(); val p = UUID.randomUUID()
        env.grant(t, a, "tenant_admin"); env.grant(t, c, "callcenter"); env.grant(t, p, "passenger")
        val pl = UUID.randomUUID(); val di = UUID.randomUUID(); val dr = UUID.randomUUID()
        env.grant(t, pl, "staff_planning"); env.grant(t, di, "dispatcher"); env.grant(t, dr, "driver")
        val fi = UUID.randomUUID(); env.grant(t, fi, "finance")
        return Side(t, a, env.token(a), c, env.token(c), p, env.token(p), pl, env.token(pl), di, env.token(di), dr, env.token(dr), fi, env.token(fi))
    }

    private class Ids(val map: Map<String, String>, val subject: String)

    /** Legt in B über die API je einen Bestand an (Region, Depot, Rechtsträger, Organisation, Dienst, Kostenstelle, Revision, Wirkungsbericht, Webhook, Fahrgast, Operation, Sitzung). */
    private fun populate(b: Side): Ids {
        fun create(path: String, body: String, token: String = b.adminToken): Resp = env.post(path, body, token, extra = idem()).also { check(it.status == 201) { "$path: ${it.text}" } }
        val region = create("/v1/regions", """{"name":"Region B"}""").json["id"].asString()
        val depot = create("/v1/depots", """{"region_id":"$region","name":"Hof B"}""").json["id"].asString()
        val le = create("/v1/legal-entities", """{"name":"Rechtstraeger B","country":"DE"}""").json["id"].asString()
        val org = create("/v1/organizations", """{"legal_entity_id":"$le","name":"Betreiber B","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""").json["id"].asString()
        val service = create("/v1/services", """{"operator_id":"$org","name":"Rufbus B","timezone":"Europe/Berlin"}""").json["id"].asString()
        // Geodaten (WP-M2-03 bis -06): Bediengebiet und Haltepunkt in B
        val area = create("/v1/service-areas", """{"service_id":"$service","area":{"type":"MultiPolygon","coordinates":[[[[13.7,51.0],[13.8,51.0],[13.8,51.1],[13.7,51.1],[13.7,51.0]]]]},"valid_from":"2026-01-01T00:00:00Z","valid_to":null}""").json["id"].asString()
        val stop = create("/v1/stops", """{"name":"Halt B","point":{"latitude":51.05,"longitude":13.74},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":["AREA_ON_DEMAND"],"valid_from":"2026-01-01T00:00:00Z"}""").json["stop_id"].asString()
        // Tarifrevision (WP-M2-07) in B
        val tariff = create("/v1/tariffs", """{"service_id":"$service","tariff_class":"STANDARD","valid_from":"2026-01-01T00:00:00Z","valid_to":null,"currency":"EUR","reason":"Tarif B","rules":[{"rule_type":"FESTPREIS","amount_minor":450,"tax_rate":"0.07","tax_rule_revision":"DE-2026-1"}]}""").json["id"].asString()
        val cost = create("/v1/cost-centers", """{"code":"KST-B","name":"Kostenstelle B","legal_entity_id":"$le"}""").json["id"].asString()
        val rev = create("/v1/config/revisions", """{"scope_kind":"TENANT","scope_id":"${b.tenant}","schema_version":"1","values":{"max_wait_min":20},"effective_from":"2030-01-01T00:00:00Z","reason":"Bestand B"}""").json["id"].asString()
        val report = env.server.send("POST", "/v1/config/revisions/$rev/impact", null, env.headers(b.adminToken, extra = idem() + ("If-Match" to "\"1\""))).json["id"].asString()
        val hook = create("/v1/webhook-subscriptions", """{"url":"https://93.184.216.34/hook","event_types":["membership.granted"]}""").json["id"].asString()
        // Fahrgast registriert sich selbst (Registrierungstoken ohne Mitgliedschaft, wie PassengerContractCases)
        val enrol = env.idp.sign(env.idp.humanClaims(sub = UUID.randomUUID(), roles = listOf("passenger"), acr = "loa1", tenants = listOf(b.tenant.value), azp = "passenger-web").also { it["sid"] = "sid-" + UUID.randomUUID() })
        val passenger = create("/v1/passengers", """{"contact":{"channel":"EMAIL","value":"b.${UUID.randomUUID().toString().take(8)}@example.org"},"locale":"de-DE"}""", enrol).json["id"].asString()
        val operation = env.tx.run(b.tenant) { OperationService().enqueue(it, "TEST_COMMAND", Instant.now().plusSeconds(3600)) }.toString()
        val membership = env.get("/v1/memberships", b.adminToken).json["items"].values().first { it["subject"].asString() == b.passenger.toString() }["id"].asString()
        env.get("/v1/me", b.passengerToken)
        val session = env.get("/v1/me", b.passengerToken).json["session_id"].asString()
        // Fahrerfreigabe (Eigentümer B, Empfänger C), Fahrergerät und Nachricht in B
        val recipientTenant = env.tenant("Iso-Empf-" + UUID.randomUUID())
        val driverRef = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,'Erika Muster','PHONE','+491701234567')", b.tenant.value, driverRef)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,evidence_ref) VALUES (?,?,?,'PBEF',1,now()-interval '1 year','akte-1')", b.tenant.value, UUID.randomUUID(), driverRef)
        val release = create("/v1/driver-releases", """{"driver_id":"$driverRef","recipient_tenant_id":"${recipientTenant.value}","service_ids":["${UUID.randomUUID()}"],"valid_from":"2026-01-01T00:00:00Z","valid_to":"2099-01-01T00:00:00Z"}""", b.plannerToken).json["id"].asString()
        val retentionRule = create("/v1/retention-rules", """{"data_category":"LK_PII","retention_days":30,"action":"ANONYMIZE"}""").json["id"].asString()
        // Fahrzeugstamm in B (WP-M3-03)
        val vehicleMaster = create("/v1/operator/vehicle-master", """{"vehicle_label":"ISO-B","vehicle_type":"PKW","operator_organization_id":"$org","capacity":{"seats":4,"wheelchair":1},"equipment":[],"active":true}""", b.dispatcherToken).json["id"].asString()
        // Dienstzuordnung des Fahrzeugpools in B (WP-M3-06): Dienst, Gebiet und Zuordnung am Fahrzeug von B
        val poolCfg = UUID.randomUUID(); val poolService = UUID.randomUUID(); val assignment = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1',CAST('{}' AS jsonb),'2026-01-01T00:00:00Z','user:t','Test')", b.tenant.value, poolCfg, poolService)
        env.exec("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,CAST(? AS uuid),'Dienst','Europe/Berlin',?,'user:t')", b.tenant.value, poolService, org, poolCfg)
        env.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,CAST(? AS uuid),'2026-01-01T00:00:00Z','system:test')", b.tenant.value, assignment, poolService, vehicleMaster)
        // Fahrerstamm in B (WP-M3-05): Fahrer mit Mitgliedschaft der Rolle driver
        val driverMaster = create("/v1/drivers", """{"membership_id":"${env.grant(b.tenant, UUID.randomUUID(), "driver")}","display_name":"Erika B","contact_channel":"PHONE","contact_value":"+49170000"}""", b.plannerToken).json["id"].asString()
        // WP-M3-07: Schichtzuweisung in B (Disposition von B weist Fahrer und Fahrzeug aus B zu)
        val shift = create("/v1/shifts", """{"driver_id":"$driverMaster","vehicle_id":"$vehicleMaster","starts_at":"2026-12-01T06:00:00Z","ends_at":"2026-12-01T14:00:00Z","timezone":"Europe/Berlin"}""", b.dispatcherToken).json["id"].asString()
        // WP-M3-10: Checklistenvorlage in B (Werkstatt von B legt an)
        val checklist = create("/v1/operator/checklist-templates", """{"name":"Iso-B","vehicle_class":"PKW","service_mode":"ONDEMAND","steps":[{"step_code":"BRAKES","title":"Bremsen","input_kind":"OK_DEFECT_NA","mandatory":true}]}""", b.adminToken).json["id"].asString()
        // WP-M3-13: Prüflauf in B (Direktanlage mit der Entwurfsvorlage von B; der Startpfad prüft InspectionApiTest)
        val inspection = UUID.randomUUID().toString()
        env.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (?,?,?,?,?,?,'PRE_SHIFT',now(),'system:test')",
            b.tenant.value, UUID.fromString(inspection), UUID.fromString(shift), UUID.fromString(vehicleMaster), UUID.fromString(driverMaster), UUID.fromString(checklist))
        // Schadenakte mit freigegebenem Foto in B (WP-M3-43/-44): Ticket, Direktupload in die Quarantäne, Abschluss mit Medienprüfung, Meldung mit Foto
        val photo = de.taktovia.app.fleet.DefectEvidenceApiTest.jpeg(777)
        val ticket = env.post("/v1/defect-evidence-uploads", de.taktovia.app.fleet.DefectEvidenceApiTest.ticketBody(photo), b.dispatcherToken, null, idem())
        check(ticket.status == 201) { "defect-evidence-uploads: ${ticket.text}" }
        val uploadId = ticket.json["id"].asString()
        env.server.ctx.getBean(de.taktovia.platform.storage.TenantObjectStore::class.java).put(de.taktovia.platform.storage.ObjectKey(b.tenant, ticket.json["upload_key"].asString()), photo, "image/jpeg")
        check(env.post("/v1/defect-evidence-uploads/$uploadId/complete", "{}", b.dispatcherToken, null, idem() + ("If-Match" to "\"1\"")).status == 200)
        val defect = create("/v1/defects", """{"vehicle_id":"$vehicleMaster","occurred_at":"${Instant.now().minusSeconds(600)}","category_code":"BODY","description":"Kratzer B","evidence_ids":["$uploadId"]}""", b.dispatcherToken).json["id"].asString()
        val deviceId = UUID.randomUUID().toString()
        create("/v1/driver/devices", """{"device_id":"$deviceId","platform":"ANDROID","app_version":"1.0.0"}""", b.driverToken)
        // WP-M4-06: Abwesenheit in B (Personalrolle)
        val absence = create("/v1/absences", """{"person_ref":"${b.planner}","from":"2027-03-01T00:00:00Z","to":"2027-03-06T00:00:00Z","kind":"LEAVE"}""", b.plannerToken).json["id"].asString()
        // WP-M4-03: Schicht in B (Planer von B)
        env.exec("INSERT INTO taktovia.roster_depot_grant(tenant_id,id,subject,scope_kind,depot_id,granted_by) VALUES (?,?,?,'DEPOT',CAST(? AS uuid),'user:test-admin')", b.tenant.value, UUID.randomUUID(), b.planner.toString().lowercase(), depot)
        val roster = create("/v1/rosters", """{"depot_id":"$depot","week_start":"2026-11-30"}""", b.plannerToken).json["id"].asString()
        val rosterShift = create("/v1/rosters/shifts", """{"roster_id":"$roster","starts_local":"2026-12-01T22:00:00","ends_local":"2026-12-02T06:00:00","timezone":"Europe/Berlin","operator_id":"$org","depot_id":"$depot","function":"Fahrdienst","staffing_need":2}""", b.plannerToken).json["id"].asString()
        // WP-M4-08: Zeitereignis in B (Fahrer von B stempelt)
        val timeEvent = UUID.randomUUID().toString()
        create("/v1/time-events", """{"event_id":"$timeEvent","kind":"WORK_START","occurred_at":"2026-12-01T06:00:00Z","device_recorded_at":"2026-12-01T06:00:00Z","channel":"APP","shift_id":null}""", b.driverToken)
        // WP-M4-21: Beleg-Original in B (Finanzrolle von B legt Ticket an, lädt in die Quarantäne und schließt ab)
        val jpeg = ByteArray(512) { (it * 3).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val hash = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(jpeg))
        val ticketJson = create("/v1/expenses/receipts", """{"legal_entity_id":"$le","purpose":"RECEIPT","mime":"image/jpeg","size_bytes":${jpeg.size},"sha256":"$hash"}""", b.financeToken).json
        env.server.ctx.getBean(de.taktovia.platform.storage.TenantObjectStore::class.java).put(de.taktovia.platform.storage.ObjectKey(b.tenant, ticketJson["upload_key"].asString()), jpeg, "image/jpeg")
        val receipt = env.post("/v1/expenses/receipts/${ticketJson["ticket_id"].asString()}/complete", "", b.financeToken, extra = idem() + ("If-Match" to "\"1\"")).also { check(it.status == 201) { it.text } }.json["document_id"].asString()
        // WP-M4-35/-36: Fiskalperiode und gebuchtes Kostenereignis in B (per SQL; der Fremdzugriff aus A prüft nur, dass nichts sichtbar oder änderbar ist)
        val fiscalPeriod = UUID.randomUUID().toString(); val costEvent = UUID.randomUUID().toString(); val costJournal = UUID.randomUUID().toString()
        env.exec("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (?,CAST(? AS uuid),CAST(? AS uuid),DATE '2040-01-01',DATE '2041-01-01')", b.tenant.value, fiscalPeriod, le)
        env.exec("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at) " +
            "VALUES (?,CAST(? AS uuid),CAST(? AS uuid),'EXPENSE',?,'EXPENSE_COST','ACTUAL','FUEL','EUR',10000,0,10000,0,'T',DATE '2040-03-01',now())", b.tenant.value, costEvent, le, costEvent)
        env.exec("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (?,CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),'EUR',CAST(? AS uuid),DATE '2040-03-01','DRAFT')", b.tenant.value, costJournal, costEvent, le, fiscalPeriod)
        env.exec("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (?,gen_random_uuid(),CAST(? AS uuid),'6600','D',10000),(?,gen_random_uuid(),CAST(? AS uuid),'1600','C',10000)", b.tenant.value, costJournal, b.tenant.value, costJournal)
        env.exec("UPDATE taktovia.journal SET state='POSTED', posted_by='user:test' WHERE id = CAST(? AS uuid)", costJournal)
        val ledgerOperation = UUID.randomUUID().toString(); val ledgerExport = UUID.randomUUID().toString()
        env.exec("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (?,CAST(? AS uuid),NULL,'LEDGER_EXPORT','QUEUED',now() + interval '1 day')", b.tenant.value, ledgerOperation)
        env.exec("INSERT INTO taktovia.ledger_export(tenant_id,id,legal_entity_id,target_system,period_from,period_to,operation_id,requested_by) VALUES (?,CAST(? AS uuid),CAST(? AS uuid),'NEUTRAL',DATE '2040-01-01',DATE '2041-01-01',CAST(? AS uuid),'user:test')",
            b.tenant.value, ledgerExport, le, ledgerOperation)
        // Alarm in B (WP-M3-52): über den Port der Alarm-Grundlage in der Transaktion von B; der Fremdzugriff aus A darf ihn weder sehen noch quittieren
        val alert = env.tx.run(b.tenant) { j -> env.server.ctx.getBean(de.taktovia.platform.alert.BusinessAlertPort::class.java).raise(j, de.taktovia.platform.alert.BusinessAlertRequest("OVERDUE", sourceEventId = UUID.randomUUID())).alertId }.toString()
        val recipient = UUID.randomUUID()
        // Angebot in B (WP-M2-21/-22): Versuch, Preisstand und Angebot per SQL (der Fremdzugriff aus A prüft nur, dass nichts sichtbar oder änderbar ist)
        val offerAttempt = UUID.randomUUID(); val offerQuote = UUID.randomUUID(); val rideOffer = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (?,?,now(),'WEB')", b.tenant.value, offerAttempt)
        env.exec("INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash) " +
            "SELECT s.tenant_id,?,?,'EUR',900,0,'{}',now()+interval '5 minutes',CAST(? AS uuid),s.config_revision_id,CAST('{}' AS jsonb),? FROM taktovia.service s WHERE s.id = CAST(? AS uuid)", offerQuote, offerAttempt, tariff, "e".repeat(64), service)
        env.exec("INSERT INTO taktovia.ride_offer(tenant_id,id,attempt_id,quote_id,service_id,actor_ref,booking_kind,time_kind,pickup_earliest,pickup_latest,arrival_earliest,arrival_latest,arrival_grace_min,direct_seconds,expires_at,created_at,cancellation_policy_ref,demand) " +
            "VALUES (?,?,?,?,CAST(? AS uuid),?,'ADVANCE','DEPARTURE',now()+interval '1 day',now()+interval '1 day 15 minutes',now()+interval '1 day 10 minutes',now()+interval '1 day 25 minutes',5,600,now()+interval '5 minutes',now(),'std-24h',CAST('{\"seats\":2}' AS jsonb))",
            b.tenant.value, rideOffer, offerAttempt, offerQuote, service, "user:${b.passenger}")
        // Buchung in B (WP-M2-23 bis -26): Zeile per SQL ohne Fremdschlüsselprüfung (replica-Rolle, nur Testaufbau); der Fremdzugriff aus A prüft nur, dass nichts sichtbar oder änderbar ist
        val booking = UUID.randomUUID().toString()
        env.exec("SET session_replication_role = replica; INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision,booking_no,created_by) VALUES (?,CAST(? AS uuid),?,CAST(? AS uuid),?,'CONFIRMING',CAST(? AS jsonb),1,'BK-2026-000001','user:test')",
            b.tenant.value, booking, UUID.randomUUID(), service, "fed-$booking", """{"pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:15:00Z","arrival_earliest":"2026-12-01T06:10:00Z","arrival_latest":"2026-12-01T06:30:00Z","gross_minor":900,"currency":"EUR","cancellation_policy_ref":"std-24h","vehicle_class":"STANDARD"}""")
        val notification = env.tx.run(b.tenant) { j ->
            j.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (taktovia.current_tenant(),?,?,now() + interval '1 year')", recipient, "tok-$recipient")
            env.server.ctx.getBean(CommunicationsupportApi::class.java).notifications
                .send(j, NotificationRequest(UUID.randomUUID(), recipient, NotificationChannel.EMAIL, "booking.confirmed", null, mapOf("booking_reference" to "BK-1"))).notificationId
        }.toString()
        return Ids(mapOf(
            "release_id" to release, "device_id" to deviceId, "rule_id" to retentionRule, "vehicle_id" to vehicleMaster, "driver_id" to driverMaster, "notification_id" to notification,
            "area_id" to area, "stop_id" to stop, "tariff_id" to tariff, "ride_offer_id" to rideOffer.toString(), "booking_id" to booking,
            "release_id" to release, "device_id" to deviceId, "rule_id" to retentionRule, "vehicle_id" to vehicleMaster, "assignment_id" to assignment.toString(), "driver_id" to driverMaster, "notification_id" to notification,
            "release_id" to release, "device_id" to deviceId, "rule_id" to retentionRule, "vehicle_id" to vehicleMaster, "defect_id" to defect, "upload_id" to uploadId, "evidence_id" to uploadId,
            "tenant_id" to b.tenant.toString(), "object_name" to "defect-evidence-$uploadId", "driver_id" to driverMaster, "notification_id" to notification,
            "area_id" to area, "stop_id" to stop,
            "area_id" to area, "stop_id" to stop, "checklist_revision_id" to checklist, "step_code" to "BRAKES", "inspection_id" to inspection,
            "region_id" to region, "depot_id" to depot, "legal_entity_id" to le, "organization_id" to org, "service_id" to service, "cost_center_id" to cost,
            "revision_id" to rev, "impact_report_id" to report, "subscription_id" to hook, "passenger_id" to passenger, "operation_id" to operation,
            "membership_id" to membership, "session_id" to session, "absence_id" to absence, "shift_id" to shift, "roster_shift_id" to rosterShift, "roster_id" to roster, "receipt_ticket_id" to ticketJson["ticket_id"].asString(), "document_id" to receipt, "time_event_id" to timeEvent,
            "membership_id" to membership, "session_id" to session, "absence_id" to absence, "receipt_ticket_id" to ticketJson["ticket_id"].asString(), "document_id" to receipt, "expense_id" to receipt, "time_event_id" to timeEvent,
"alert_id" to alert, "hint_id" to UUID.randomUUID().toString(), "candidate_id" to UUID.randomUUID().toString(), "period_id" to fiscalPeriod, "cost_event_id" to costEvent, "export_id" to ledgerExport,
        ), subject = b.passenger.toString())
    }

    /** Anfrage je Endpunkt mit Pfadparameter: Pfadvorlage, Body, Rolle des Aufrufers aus Mandant A. */
    private class Probe(val method: String, val path: String, val body: String? = null, val ifMatch: Boolean = false, val cc: Boolean = false, val purpose: Boolean = false, val role: String? = null)

    private fun probes(a: Side): Map<String, Probe> {
        val aRegion = env.post("/v1/regions", """{"name":"Region A"}""", a.adminToken, extra = idem()).json["id"].asString()
        val aLe = env.post("/v1/legal-entities", """{"name":"Rechtstraeger A","country":"DE"}""", a.adminToken, extra = idem()).json["id"].asString()
        val aOp = env.post("/v1/organizations", """{"legal_entity_id":"$aLe","name":"Betreiber A","federation_ref":"f-${UUID.randomUUID()}","roles":["TRANSPORT_COMPANY"]}""", a.adminToken, extra = idem()).json["id"].asString()
        return mapOf(
            "publishConfig" to Probe("POST", "/v1/config/revisions/{revision_id}/publish", """{"effective_from":"2031-01-01T00:00:00Z","reason":"Fremdversuch","impact_report_id":"${UUID.randomUUID()}"}""", true),
            "revokeMembership" to Probe("DELETE", "/v1/memberships/{membership_id}", null, true),
            "updateLegalEntity" to Probe("PUT", "/v1/legal-entities/{legal_entity_id}", """{"name":"Gekapert","country":"DE"}""", true),
            "updateRegion" to Probe("PUT", "/v1/regions/{region_id}", """{"name":"Gekapert"}""", true),
            "updateDepot" to Probe("PUT", "/v1/depots/{depot_id}", """{"region_id":"$aRegion","name":"Gekapert"}""", true),
            "updateCostCenter" to Probe("PUT", "/v1/cost-centers/{cost_center_id}", """{"name":"Gekapert"}""", true),
            "revokeSession" to Probe("DELETE", "/v1/sessions/{session_id}", null, true),
            "getOrganization" to Probe("GET", "/v1/organizations/{organization_id}"),
            "updateOrganization" to Probe("PUT", "/v1/organizations/{organization_id}", """{"legal_entity_id":"$aLe","name":"Gekapert","roles":["CLIENT"]}""", true),
            "getOperation" to Probe("GET", "/v1/operations/{operation_id}"),
            "getPassengerSupportNeeds" to Probe("GET", "/v1/passengers/{passenger_id}/support-needs", cc = true, purpose = true),
            "getPassenger" to Probe("GET", "/v1/passengers/{passenger_id}", cc = true),
            "getConfigRevision" to Probe("GET", "/v1/config/revisions/{revision_id}"),
            "updateConfigRevision" to Probe("PATCH", "/v1/config/revisions/{revision_id}", """{"reason":"Gekapert"}""", true),
            "validateConfigRevision" to Probe("POST", "/v1/config/revisions/{revision_id}/validate", null, true),
            "approveConfigRevision" to Probe("POST", "/v1/config/revisions/{revision_id}/approve", null, true),
            "rollbackConfigRevision" to Probe("POST", "/v1/config/revisions/{revision_id}/rollback", """{"effective_from":"2031-01-01T00:00:00Z","reason":"Fremdversuch"}""", true),
            "requestConfigImpact" to Probe("POST", "/v1/config/revisions/{revision_id}/impact", null, true),
            "getConfigImpactReport" to Probe("GET", "/v1/config/impact-reports/{impact_report_id}"),
            "getServiceConfiguration" to Probe("GET", "/v1/services/{service_id}/configuration"),
            // Dienste und Geodaten (WP-M2-03 bis -06)
            "getServiceOperatingHours" to Probe("GET", "/v1/services/{service_id}/operating-hours"),
            "putServiceOperatingHours" to Probe("PUT", "/v1/services/{service_id}/operating-hours", """{"traffic_product":"AREA_ON_DEMAND","weekly_hours":[{"weekdays":["MON"],"from":"06:00","to":"22:00"}],"exceptions":[],"valid_from":"2026-01-01T00:00:00Z","valid_to":null,"reason":"Fremdversuch"}""", true),
            "listServiceOperatingHoursRevisions" to Probe("GET", "/v1/services/{service_id}/operating-hours/revisions"),
            "getServiceAvailability" to Probe("GET", "/v1/services/{service_id}/availability"),
            "getServiceRelationRule" to Probe("GET", "/v1/services/{service_id}/relation-rules"),
            "putServiceRelationRule" to Probe("PUT", "/v1/services/{service_id}/relation-rules", """{"destination_stop_ids":[],"valid_from":"2026-01-01T00:00:00Z","valid_to":null,"reason":"Fremdversuch"}""", true),
            "checkServiceRelation" to Probe("GET", "/v1/services/{service_id}/relation-check?origin_latitude=51.05&origin_longitude=13.05&destination_latitude=51.05&destination_longitude=14.05"),
            "getServiceArea" to Probe("GET", "/v1/service-areas/{area_id}"),
            "updateServiceArea" to Probe("PUT", "/v1/service-areas/{area_id}", """{"area":{"type":"MultiPolygon","coordinates":[[[[13.7,51.0],[13.8,51.0],[13.8,51.1],[13.7,51.1],[13.7,51.0]]]]},"valid_from":"2027-01-01T00:00:00Z","valid_to":null}""", true),
            // Tarifrevisionen (WP-M2-07)
            "getTariffRevision" to Probe("GET", "/v1/tariffs/{tariff_id}"),
            "updateTariffRevision" to Probe("PUT", "/v1/tariffs/{tariff_id}", """{"valid_from":"2027-01-01T00:00:00Z","valid_to":null,"reason":"Fremdversuch","rules":[{"rule_type":"FESTPREIS","amount_minor":1,"tax_rule_revision":"x"}]}""", true),
            "reviewTariffRevision" to Probe("POST", "/v1/tariffs/{tariff_id}/review", null, true),
            "publishTariffRevision" to Probe("POST", "/v1/tariffs/{tariff_id}/publish", null, true),
            "getEffectiveTariff" to Probe("GET", "/v1/services/{service_id}/tariff?at=2026-06-01T00:00:00Z"),
            // Angebot, Hold und Freigabe (WP-M2-21/-22)
            "getRideOffer" to Probe("GET", "/v1/ride-offers/{ride_offer_id}", role = "passenger"),
            "holdRideOffer" to Probe("POST", "/v1/ride-offers/{ride_offer_id}/hold", """{"max_gross":{"amount_minor":900,"currency":"EUR","exponent":2}}""", true, role = "passenger"),
            "releaseHold" to Probe("POST", "/v1/ride-offers/{ride_offer_id}/hold/release", null, true, role = "passenger"),
            // Buchung, Historie und Storno (WP-M2-23 bis -26)
            "getBooking" to Probe("GET", "/v1/bookings/{booking_id}", role = "passenger"),
            "listBookingHistory" to Probe("GET", "/v1/bookings/{booking_id}/history", role = "passenger"),
            "cancelBooking" to Probe("POST", "/v1/bookings/{booking_id}/cancel", """{"reason":"PASSENGER_REQUEST"}""", true, role = "passenger"),
            "startBookingPayment" to Probe("POST", "/v1/bookings/{booking_id}/payment", """{"payment_method_ref":"pm_ok","amount":{"amount_minor":950,"currency":"EUR","exponent":2}}""", true, role = "passenger"),
            "returnBookingPayment" to Probe("POST", "/v1/bookings/{booking_id}/payment/return", null, true, role = "passenger"),
            "getStop" to Probe("GET", "/v1/stops/{stop_id}"),
            "updateStop" to Probe("PUT", "/v1/stops/{stop_id}", """{"name":"Gekapert","point":{"latitude":51.05,"longitude":13.74},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"UNVERIFIED"},"traffic_products":["AREA_ON_DEMAND"],"active":false,"valid_from":"2027-01-01T00:00:00Z"}""", true),
            "updateService" to Probe("PUT", "/v1/services/{service_id}", """{"name":"Gekapert","timezone":"Europe/Berlin"}""", true),
            "revokeMySession" to Probe("DELETE", "/v1/me/sessions/{session_id}", null, true),
            "unlockAccount" to Probe("DELETE", "/v1/account-locks/{subject}", null, true),
            "getWebhookSubscription" to Probe("GET", "/v1/webhook-subscriptions/{subscription_id}"),
            "deactivateWebhookSubscription" to Probe("POST", "/v1/webhook-subscriptions/{subscription_id}/deactivate", null, true),
            "revokeDriverRelease" to Probe("DELETE", "/v1/driver-releases/{release_id}", null, true, role = "planner"),
            "getDriverReleaseProjection" to Probe("GET", "/v1/driver-releases/{release_id}/projection?service_id=${UUID.randomUUID()}", role = "dispatcher"),
            "revokeDriverDevice" to Probe("DELETE", "/v1/driver/devices/{device_id}", null, true, role = "driver"),
            "getNotification" to Probe("GET", "/v1/notifications/{notification_id}"),
            "updateRetentionRule" to Probe("PUT", "/v1/retention-rules/{rule_id}", """{"retention_days":10,"action":"DELETE"}""", true),
            "approveRetentionRule" to Probe("POST", "/v1/retention-rules/{rule_id}/approvals", "{}", true),
            "getDriver" to Probe("GET", "/v1/drivers/{driver_id}", role = "planner"),
            "updateDriver" to Probe("PUT", "/v1/drivers/{driver_id}", """{"display_name":"Gekapert","contact_channel":"APP","contact_value":"x","active":false}""", true, role = "planner"),
            "listDriverQualifications" to Probe("GET", "/v1/drivers/{driver_id}/qualifications", role = "planner"),
            "createDriverQualification" to Probe("POST", "/v1/drivers/{driver_id}/qualifications", """{"qualification_code":"GEKAPERT","valid_from":"2026-01-01T00:00:00Z","decision":"APPROVED"}""", true, role = "planner"),
            "getVehicleMaster" to Probe("GET", "/v1/operator/vehicle-master/{vehicle_id}", role = "dispatcher"),
            "updateVehicleMaster" to Probe("PUT", "/v1/operator/vehicle-master/{vehicle_id}", """{"vehicle_label":"Gekapert","vehicle_type":"PKW","operator_organization_id":"$aOp","capacity":{"seats":1,"wheelchair":0},"equipment":[],"active":false}""", true, role = "dispatcher"),
            "getChecklistTemplateRevision" to Probe("GET", "/v1/operator/checklist-templates/{checklist_revision_id}"),
            "updateChecklistTemplateDraft" to Probe("PUT", "/v1/operator/checklist-templates/{checklist_revision_id}", """{"name":"Gekapert","vehicle_class":"PKW","service_mode":"ONDEMAND","steps":[{"step_code":"BRAKES","title":"Gekapert","input_kind":"OK_DEFECT_NA","mandatory":true}]}""", true),
            "createChecklistTemplateRevision" to Probe("POST", "/v1/operator/checklist-templates/{checklist_revision_id}/revisions", null, true),
            "publishChecklistTemplateRevision" to Probe("POST", "/v1/operator/checklist-templates/{checklist_revision_id}/publish", null, true),
            "putChecklistStepExampleImage" to Probe("PUT", "/v1/operator/checklist-templates/{checklist_revision_id}/steps/{step_code}/example-image", """{"content_type":"image/png","data_base64":"iVBORw0KGgoBAgM="}""", true),
            "getChecklistStepExampleImage" to Probe("GET", "/v1/operator/checklist-templates/{checklist_revision_id}/steps/{step_code}/example-image", role = "dispatcher"),
            "getVehicleCapacity" to Probe("GET", "/v1/operator/vehicle-master/{vehicle_id}/capacity", role = "dispatcher"),
            "getInspection" to Probe("GET", "/v1/inspections/{inspection_id}", role = "dispatcher"),
            "saveInspectionDraft" to Probe("PUT", "/v1/inspections/{inspection_id}", """{"answers":[]}""", true, role = "driver"),
            "submitInspection" to Probe("POST", "/v1/inspections/{inspection_id}/submit", """{"answers":[{"step_code":"S1","answer":"OK","answered_at":"2026-11-12T06:00:00Z"}],"client_submitted_at":"2026-11-12T06:00:00Z"}""", true, role = "driver"),
            "createInspectionAmendment" to Probe("POST", "/v1/inspections/{inspection_id}/amendments", """{"step_code":"S1","kind":"CORRECTION","new_answer":"OK","reason":"x"}""", false, role = "workshop"),
            "justifyInspectionAnswer" to Probe("POST", "/v1/inspections/{inspection_id}/answers/{step_code}/justification", """{"number_value":1,"reason":"x"}""", true, role = "dispatcher"),
            "listKnownDefects" to Probe("GET", "/v1/inspections/{inspection_id}/known-defects", role = "dispatcher"),
            "acknowledgeKnownDefect" to Probe("POST", "/v1/inspections/{inspection_id}/known-defects/{defect_id}/acknowledge", "{}", false, role = "driver"),
            "listVehicleKnownDefects" to Probe("GET", "/v1/vehicles/{vehicle_id}/known-defects", role = "dispatcher"),
            "getDefect" to Probe("GET", "/v1/defects/{defect_id}", role = "dispatcher"),
            "transitionDefect" to Probe("POST", "/v1/defects/{defect_id}/transitions", """{"to":"REVIEWED"}""", true, role = "dispatcher"),
            "listDefectStatusHistory" to Probe("GET", "/v1/defects/{defect_id}/status-history", role = "dispatcher"),
            "amendDefect" to Probe("POST", "/v1/defects/{defect_id}/amendments", """{"change_reason":"Fremdversuch","description":"gekapert"}""", false, role = "dispatcher"),
            "listDefectHistory" to Probe("GET", "/v1/defects/{defect_id}/history"),   // Prüfer: Betriebsleitung (Standardtoken)
            "getDefectAsOf" to Probe("GET", "/v1/defects/{defect_id}/as-of?at=2099-01-01T00:00:00Z"),
            "getDefectEvidenceUpload" to Probe("GET", "/v1/defect-evidence-uploads/{upload_id}", role = "dispatcher"),
            "completeDefectEvidenceUpload" to Probe("POST", "/v1/defect-evidence-uploads/{upload_id}/complete", "{}", true, role = "dispatcher"),
            "listDefectEvidence" to Probe("GET", "/v1/defects/{defect_id}/evidence", role = "dispatcher"),
            "getDefectEvidenceDownload" to Probe("GET", "/v1/defects/{defect_id}/evidence/{evidence_id}/download", role = "dispatcher"),
            "readSignedObject" to Probe("GET", "/v1/objects/{tenant_id}/{object_name}?exp=4102444800&purpose=defect_evidence&sig=${"0".repeat(64)}", role = "dispatcher"),
            "updateVehicleCapacity" to Probe("PATCH", "/v1/operator/vehicle-master/{vehicle_id}/capacity", """{"restrictions":[]}""", true, role = "dispatcher"),
            "listVehicleServiceAssignments" to Probe("GET", "/v1/operator/vehicles/{vehicle_id}/service-assignments", role = "dispatcher"),
            "createVehicleServiceAssignment" to Probe("POST", "/v1/operator/vehicles/{vehicle_id}/service-assignments", """{"service_id":"${UUID.randomUUID()}","valid_from":"2031-01-01T00:00:00Z"}""", false, role = "dispatcher"),
            "endVehicleServiceAssignment" to Probe("POST", "/v1/operator/vehicles/{vehicle_id}/service-assignments/{assignment_id}/end", """{"valid_to":"2099-01-01T00:00:00Z"}""", true, role = "dispatcher"),
            "getReceiptDocument" to Probe("GET", "/v1/expenses/receipts/{document_id}", role = "finance"),
            "completeReceiptUpload" to Probe("POST", "/v1/expenses/receipts/{receipt_ticket_id}/complete", null, true, role = "finance"),
            "getDriverRun" to Probe("GET", "/v1/driver/shifts/{shift_id}/run", role = "driver"),
            "listExpectedPassengers" to Probe("GET", "/v1/driver/shifts/{shift_id}/passengers", role = "driver"),
            // WP-M3-27 / WP-M3-52 (Cluster wave12-c5)
            "acknowledgeDriverRunRevision" to Probe("POST", "/v1/driver/shifts/{shift_id}/run/acknowledgements", """{"plan_revision":1,"acknowledged_at":"2026-11-05T09:00:00Z"}""", true, role = "driver"),
            "getOperatorAlert" to Probe("GET", "/v1/operator/alerts/{alert_id}", role = "dispatcher"),
            "acknowledgeOperatorAlert" to Probe("POST", "/v1/operator/alerts/{alert_id}/acknowledgements", """{"cause":"HANDLED"}""", true, role = "dispatcher"),
            "getStaffShift" to Probe("GET", "/v1/shifts/{shift_id}", role = "dispatcher"),
            "updateStaffShift" to Probe("PUT", "/v1/shifts/{shift_id}", """{"driver_id":"${UUID.randomUUID()}","vehicle_id":"${UUID.randomUUID()}","starts_at":"2026-12-01T06:00:00Z","ends_at":"2026-12-01T14:00:00Z","timezone":"Europe/Berlin","withdrawn":true}""", true, role = "dispatcher"),
            // Belege: Aggregat, Revisionen, Original, Extraktion (WP-M4-22 bis -27)
            "getExpense" to Probe("GET", "/v1/expenses/{expense_id}", role = "finance"),
            "correctExpense" to Probe("PATCH", "/v1/expenses/{expense_id}", """{"reason":"Fremdversuch","fields":{"amount_minor":1}}""", true, role = "finance"),
            "listExpenseRevisions" to Probe("GET", "/v1/expenses/{expense_id}/revisions", role = "finance"),
            "getExpenseOriginal" to Probe("GET", "/v1/expenses/{expense_id}/original", role = "finance"),
            "decideExpenseDuplicate" to Probe("POST", "/v1/expenses/{expense_id}/duplicates/{candidate_id}/decision", """{"decision":"EIGENER_VORGANG","reason":"Fremdversuch"}""", true, role = "finance"),
            "submitExpense" to Probe("POST", "/v1/expenses/{expense_id}/submit", null, true, role = "finance"),
            "reviewExpense" to Probe("POST", "/v1/expenses/{expense_id}/review", """{"decision":"APPROVED","reason":"Fremdversuch"}""", true, role = "finance"),
            "approveExpense" to Probe("POST", "/v1/expenses/{expense_id}/approve", """{"reason":"Fremdversuch","validated_document_revision":1}""", true, role = "finance"),
            "rejectExpense" to Probe("POST", "/v1/expenses/{expense_id}/reject", """{"reason":"Fremdversuch"}""", true, role = "finance"),
            // Aufteilung, Fiskalperiode, Gegenereignis (WP-M4-35, -36)
            "allocateCost" to Probe("POST", "/v1/cost-events/{cost_event_id}/allocations", """{"allocation_policy_revision":"fremd","reason":"Fremdversuch","lines":[{"cost_object_type":"COST_CENTER","cost_object_id":"${UUID.randomUUID()}","amount":{"amount_minor":10000,"currency":"EUR","exponent":2}}]}""", true, role = "finance"),
            "closeFiscalPeriod" to Probe("POST", "/v1/fiscal-periods/{period_id}/close", null, false, role = "finance"),
            "createCostEventReversal" to Probe("POST", "/v1/cost-events/{cost_event_id}/reversals", """{"reason":"Fremdversuch"}""", false, role = "finance"),
            // ERP-Export (WP-M4-37)
            "getLedgerExport" to Probe("GET", "/v1/ledger/exports/{export_id}", role = "finance"),
            "listLedgerExportLines" to Probe("GET", "/v1/ledger/exports/{export_id}/lines", role = "finance"),
            "getLedgerExportDownload" to Probe("GET", "/v1/ledger/exports/{export_id}/download", role = "finance"),
            "readLedgerExportContent" to Probe("GET", "/v1/ledger/exports/{export_id}/content?exp=4102444800&sig=${"0".repeat(64)}", role = "finance"),
            "confirmLedgerExport" to Probe("POST", "/v1/ledger/exports/{export_id}/confirmation", """{"entries":[{"journal_id":"${UUID.randomUUID()}","import_ref":"ERP-1"}]}""", false, role = "finance"),
            "acknowledgeExpenseHint" to Probe("POST", "/v1/expenses/{expense_id}/hints/{hint_id}/acknowledgement", """{"reason":"Fremdversuch"}""", true, role = "finance"),
            "requestReceiptExtraction" to Probe("POST", "/v1/expenses/receipts/{document_id}/extract", null, true, role = "finance"),
            "getReceiptExtraction" to Probe("GET", "/v1/expenses/receipts/{document_id}/extraction", role = "finance"),
            // Belegerfassung per Kamera (WP-M4-25): bearbeitete Ansicht
            "attachReceiptEditedView" to Probe("PUT", "/v1/expenses/receipts/{document_id}/edited-view", """{"mime":"image/jpeg","content_base64":"/9j/4AAQ"}""", true, role = "finance"),
            "getReceiptEditedView" to Probe("GET", "/v1/expenses/receipts/{document_id}/edited-view", role = "finance"),
            "getAbsence" to Probe("GET", "/v1/absences/{absence_id}", role = "planner"),
            "withdrawAbsence" to Probe("POST", "/v1/absences/{absence_id}/withdraw", null, true, role = "planner"),
            "getRoster" to Probe("GET", "/v1/rosters/{roster_id}", role = "planner"),
            "openRosterDraft" to Probe("POST", "/v1/rosters/{roster_id}/draft", null, true, role = "planner"),
            // WP-M4-14/-15: Veröffentlichung, Revisionen und Historie (der Planer hat weder `rosters:publish` noch `rosters:audit`: beide Wege sind gleichartig abgewiesen; die echte Mandantentrennung weist RosterPublishApiTest nach)
            "publishRoster" to Probe("POST", "/v1/rosters/{roster_id}/publish", """{"effective_from":"2026-12-01T00:00:00Z","reason":"Freigabe","impact_report_id":"00000000-0000-4000-8000-0000000000c9"}""", true, role = "planner"),
            "listRosterRevisions" to Probe("GET", "/v1/rosters/{roster_id}/revisions", role = "planner"),
            "getRosterRevision" to Probe("GET", "/v1/rosters/{roster_id}/revisions/1", role = "planner"),
            "listRosterShiftHistory" to Probe("GET", "/v1/rosters/{roster_id}/shifts/{roster_shift_id}/history", role = "planner"),
            "getRosterShift" to Probe("GET", "/v1/rosters/shifts/{roster_shift_id}?roster_id={roster_id}", role = "planner"),
            "updateRosterShift" to Probe("PUT", "/v1/rosters/shifts/{roster_shift_id}", """{"roster_id":"00000000-0000-4000-8000-0000000000c1","starts_local":"2026-12-01T22:00:00","ends_local":"2026-12-02T06:00:00","timezone":"Europe/Berlin","operator_id":"00000000-0000-4000-8000-0000000000c2","depot_id":"00000000-0000-4000-8000-0000000000c3","function":"Fahrdienst","staffing_need":2}""", true, role = "planner"),
        ).also { check(aOp.isNotEmpty()) }
    }

    /** Listenwege und mandantenbezogene Leseendpunkte ohne Pfadparameter: (Pfad, Rolle). Reine Eigen-Ressourcen (`/me`, Profil) und der globale Katalog der Angebotsmodelle kennen keine fremden Objekte. */
    private val listPaths = mapOf(
        "listMemberships" to "/v1/memberships", "listLegalEntities" to "/v1/legal-entities", "listRegions" to "/v1/regions", "listDepots" to "/v1/depots",
        "listCostCenters" to "/v1/cost-centers", "listOrganizations" to "/v1/organizations", "listServices" to "/v1/services", "listConfigRevisions" to "/v1/config/revisions",
        "listVehicleMasters" to "/v1/operator/vehicle-master", "listDrivers" to "/v1/drivers", "listStaffShifts" to "/v1/shifts", "listOperatorVehicles" to "/v1/operator/vehicles", "getEffectiveConfig" to "/v1/config/effective", "listServiceAreas" to "/v1/service-areas", "listStops" to "/v1/stops", "listPassengers" to "/v1/passengers", "listNotifications" to "/v1/notifications", "listRosterShifts" to "/v1/rosters/shifts?roster_id=00000000-0000-4000-8000-0000000000c1", "listRosters" to "/v1/rosters", "listSessions" to "/v1/sessions", "listAbsences" to "/v1/absences", "listTimeEvents" to "/v1/time-events", "listTimeIntervals" to "/v1/time-events/intervals?from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z", "listTimeTargetActual" to "/v1/time-events/target-actual?from=2026-12-01&to=2026-12-02", "listWebhookSubscriptions" to "/v1/webhook-subscriptions", "listVehicleRoles" to "/v1/vehicle-roles", "listAuditEvents" to "/v1/audit-events?purpose=security_review",
        "listOperatorAlerts" to "/v1/operator/alerts", "listDefects" to "/v1/defects", "listVehicleMasters" to "/v1/operator/vehicle-master", "listDrivers" to "/v1/drivers", "getEffectiveConfig" to "/v1/config/effective", "listServiceAreas" to "/v1/service-areas", "listTariffRevisions" to "/v1/tariffs", "listStops" to "/v1/stops", "listPassengers" to "/v1/passengers", "listNotifications" to "/v1/notifications",
        "listChecklistTemplates" to "/v1/checklist-templates", "listChecklistTemplateRevisions" to "/v1/operator/checklist-templates", "listRetentionRules" to "/v1/retention-rules", "listErasureJobs" to "/v1/erasure-jobs", "listErasureJournal" to "/v1/erasure-journal",
        "listCostObjects" to "/v1/cost-objects", "listCostEvents" to "/v1/cost-events", "listFiscalPeriods" to "/v1/fiscal-periods", "listLedgerExports" to "/v1/ledger/exports", "listExpenses" to "/v1/expenses", "listBookings" to "/v1/bookings",
    )
    private val selfScoped = setOf(
        "getMe", "listOwnRosterShifts", "listOwnAbsences", "searchPlaces", "listServiceOfferModels", "getCompanyProfile", "getMyProfile", "getMyPrivacySettings", "getMyTransportSupport", "listMySessions", "listConfigParameters",
        "getMe", "listDriverShifts", "listServiceOfferModels", "getCompanyProfile", "getMyProfile", "getMyPrivacySettings", "getMyTransportSupport", "listMySessions", "listConfigParameters",
        // WP-M2-28: Änderungsstrom und Snapshot je Empfängerkanal, ohne Pfadparameter und ausschließlich auf den eigenen Datenbestand begrenzt (Isolation: PlanRevisionSyncTest.zwei_mandanten_am_selben_strom_getrennt)
        "listPlanChanges", "getPlanChangesSnapshot", "listDriverPlanChanges", "getDriverPlanChangesSnapshot",
        // WP-M3-35: Live-Kanal (Strom und Abruf), ohne Pfadparameter, durch Mandant und Dienstscope des Abonnenten begrenzt (Isolation: LiveChannelHttpTest.abonnement_sieht_nur_den_eigenen_mandanten_und_die_berechtigten_dienste,
        // zwei_tabs_mit_unterschiedlichem_mandantenkontext_bleiben_getrennt)
        "streamOperatorLiveEvents", "listOperatorLiveEvents", "streamDriverLiveEvents",
    )

    /**
     * Per Signatur (HMAC) authentifizierter Provider-Eingang ohne Mandantentoken: der Mandant wird aus dem verifizierten Signaturschlüssel/der Konfiguration abgeleitet,
     * der Pfadparameter `{provider}` ist kein Mandantenobjekt (kein fremdes Objekt adressierbar). Isolation ist nachgewiesen durch
     * `payment/ProviderEventApiTest.manipulierte_tenant_id_ist_wirkungslos_der_mandant_folgt_dem_schluessel` (und `PspSimulatorWebhookTest`).
     */
    private val providerAuthenticated = setOf("receiveProviderEvent")

    private fun normalized(r: Resp): String {
        val j = r.json
        return listOf(r.status, j["type"]?.asString(), j["code"]?.asString(), j["title"]?.asString(), j["detail"]?.asString()).joinToString("|")
    }

    private fun call(a: Side, p: Probe, path: String, token: String = when { p.role == "planner" -> a.plannerToken; p.role == "dispatcher" -> a.dispatcherToken; p.role == "driver" -> a.driverToken; p.role == "finance" -> a.financeToken; p.role == "passenger" -> a.passengerToken; p.cc -> a.ccToken; else -> a.adminToken }): Resp =
        env.server.send(p.method, path, p.body, env.headers(token, extra = (if (p.method != "GET") idem() else emptyMap()) + (if (p.ifMatch) mapOf("If-Match" to "\"1\"") else emptyMap()) + (if (p.purpose) mapOf("X-Access-Purpose" to "BOOKING_ASSISTANCE") else emptyMap())))

    private fun snapshot(b: Side): String = listOf("/v1/regions", "/v1/depots", "/v1/legal-entities", "/v1/organizations", "/v1/services", "/v1/cost-centers", "/v1/config/revisions", "/v1/memberships", "/v1/webhook-subscriptions")
        .joinToString("\n") { env.get(it, b.adminToken).text.replace(Regex("\"as_of\":\"[^\"]*\""), "") }

    @Test
    fun fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen() {
        val a = side(); val b = side(); val ids = populate(b); val probes = probes(a)
        val registry = env.server.registry
        val built = registry.operations.filter { it.operationId !in pending && it.path.startsWith("/v1/") }
        // Vollständigkeit: jeder gebaute Endpunkt mit Pfadparameter steht im Scan, jeder Listenweg ebenfalls
        val withParams = built.filter { '{' in it.path }.map { it.operationId }.toSet()
        assertEquals(emptySet<String>(), withParams - probes.keys - providerAuthenticated, "gebaute Endpunkte mit Pfadparameter ohne Isolationsfall (CrossTenantIsolationTest.probes ergänzen)")
        val noParams = built.filter { '{' !in it.path && it.method == "GET" }.map { it.operationId }.toSet()
        assertEquals(emptySet<String>(), noParams - listPaths.keys - selfScoped, "gebaute Leseendpunkte ohne Pfadparameter ohne Isolationsfall")
        val before = snapshot(b)

        for ((op, probe) in probes) {
            val params = Regex("\\{(\\w+)}").findAll(probe.path).map { it.groupValues[1] }.toList()
            fun fill(value: (String) -> String) = params.fold(probe.path) { acc, p -> acc.replace("{$p}", value(p)) }
            val foreign = call(a, probe, fill { p -> if (p == "subject") ids.subject else ids.map.getValue(p) })
            val missing = call(a, probe, fill { p -> if (p == "subject") UUID.randomUUID().toString() else UUID.randomUUID().toString() })
            assertEquals(404, missing.status, "$op: nicht vorhandene ID: ${missing.text}")
            assertEquals(normalized(missing), normalized(foreign), "$op: fremde und nicht vorhandene ID müssen gleichartig beantwortet werden")
            ids.map.values.forEach { assertFalse(foreign.text.contains(it), "$op: Antwort enthält eine Kennung aus Mandant B") }
            assertFalse(foreign.text.contains(b.tenant.toString()), "$op: Antwort nennt den Mandanten B")
        }

        // Listen: nie Objekte oder Kennungen von B
        for ((op, path) in listPaths) {
            val r = env.get(path, when (op) { "listPassengers" -> a.ccToken; "listVehicleMasters", "listOperatorVehicles", "listOperatorAlerts", "listDefects", "listBookings" -> a.dispatcherToken; "listDrivers", "listAbsences", "listTimeEvents", "listTimeIntervals", "listTimeTargetActual", "listRosterShifts", "listRosters", "listStaffShifts" -> a.plannerToken; "listCostObjects", "listCostEvents", "listFiscalPeriods", "listLedgerExports", "listExpenses" -> a.financeToken; else -> a.adminToken })
            assertTrue(r.status == 200 || r.status == 403 || (op == "listRosterShifts" && r.status == 404), "$op: ${r.status} ${r.text}")
            if (r.status == 200) {
                ids.map.filterKeys { it != "session_id" }.values.forEach { assertFalse(r.text.contains(it), "$op: Liste von A enthält Kennung aus B") }
                assertFalse(r.text.contains(b.tenant.toString()), "$op: Liste von A nennt Mandant B")
            }
        }
        // Zeitereignisse (WP-M4-08): die Personalrolle von A fragt gezielt die Person von B ab: leer, nie Ereignisse von B
        for (path in listOf("/v1/time-events?person_ref=${b.driver}", "/v1/time-events/intervals?person_ref=${b.driver}&from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z")) {
            val r = env.get(path, a.plannerToken)
            assertEquals(200, r.status, r.text)
            assertEquals(0, r.json[if (path.contains("intervals")) "intervals" else "items"].size(), "$path: Ereignisse von B sichtbar")
            assertFalse(r.text.contains(ids.map.getValue("time_event_id")))
        }
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id=?", b.tenant.value), "Zeitereignis in B unverändert")
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.absence_request WHERE tenant_id=? AND state='APPROVED' AND revision=1", b.tenant.value), "Abwesenheit in B unverändert (Rücknahme aus A wirkungslos)")
        assertEquals(before, snapshot(b), "Mandant B ist nach allen Fremdversuchen unverändert (Änderung und Löschung wirkungslos)")
    }

    @Test
    fun manipulierte_mandantenangabe_wird_nicht_beachtet_und_sicherheitsereignis_enthaelt_keine_b_daten() {
        val a = side(); val b = side(); val ids = populate(b)
        val before = env.events(a.tenant, "security.tenant.mismatch")
        val attempts = listOf(
            env.get("/v1/regions", a.adminToken, extra = mapOf("X-Tenant-Id" to b.tenant.toString())),
            env.get("/v1/regions?tenant_id=${b.tenant}", a.adminToken),
            env.get("/v1/memberships", a.adminToken, extra = mapOf("Tenant-Id" to b.tenant.toString())),
        )
        attempts.forEach { assertEquals(404, it.status, it.text); assertFalse(it.text.contains(b.tenant.toString())) }
        assertEquals(before + attempts.size, env.events(a.tenant, "security.tenant.mismatch"), "je Fremdzugriff ein Sicherheitsereignis im Mandanten des Akteurs")
        assertEquals(0, env.events(b.tenant, "security.tenant.mismatch"), "nichts im Mandanten B")
        // Inhalt der Sicherheitsereignisse: weder Kennungen noch Personendaten von B
        val admin = TaktoviaTestSupport.adminDataSourceOrNull()!!
        admin.connection.use { c ->
            c.prepareStatement("SELECT actor_ref, object_ref, redacted_changes::text FROM taktovia.audit_event WHERE tenant_id=? AND action LIKE 'security.%'").use { ps ->
                ps.setObject(1, a.tenant.value)
                ps.executeQuery().use { rs ->
                    var n = 0
                    while (rs.next()) {
                        n++
                        val row = (1..3).joinToString(" ") { rs.getString(it) }
                        val foreignValues = ids.map.values + listOf(b.tenant.toString(), b.admin.toString(), b.passenger.toString(), b.callcenter.toString())
                        foreignValues.forEach { assertFalse(row.contains(it), "Sicherheitsereignis enthält Daten von Mandant B: $it") }
                    }
                    assertTrue(n >= attempts.size)
                }
            }
        }
    }

    @Test
    fun a_und_b_arbeiten_parallel_und_unabhaengig() {
        val a = side(); val b = side()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val futures = (1..24).map { i ->
            val s = if (i % 2 == 0) a else b
            pool.submit<Pair<TenantId, String>> { s.tenant to env.post("/v1/regions", """{"name":"R-$i"}""", s.adminToken, extra = idem()).json["id"].asString() }
        }
        val created = futures.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }; pool.shutdown()
        for (s in listOf(a, b)) {
            val own = created.filter { it.first == s.tenant }.map { it.second }.toSet()
            val listed = env.get("/v1/regions?limit=100", s.adminToken).json["items"].values().map { it["id"].asString() }.toSet()
            assertEquals(own, listed, "jeder Mandant sieht genau die eigenen Regionen")
        }
    }
}
