package de.taktovia.app.fleet

import de.taktovia.documentsfinance.api.MalwareScanner
import de.taktovia.documentsfinance.api.ScanResult
import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.fleetsafety.api.AlertSettings
import de.taktovia.fleetsafety.api.DefectEvidenceWiring
import de.taktovia.fleetsafety.api.DefectSettings
import de.taktovia.fleetsafety.api.EvidencePolicy
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.MediaInspectors
import de.taktovia.fleetsafety.api.MediaVerdict
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.outbox.TenantSource
import de.taktovia.stafftime.api.PlanAckOverdueSink
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.storage.SignedUrls
import de.taktovia.platform.storage.TenantObjectStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.security.SecureRandom
import java.time.Duration

/**
 * Verdrahtung des Moduls `fleet-safety` (WP-M1-34, WP-M3-44). Fotoevidenz: mandantengetrennter Objektspeicher und Malware-Prüfer sind die Bausteine aus WP-M1-11 und WP-M4-21 (Konfiguration
 * `taktovia.documents.*`: ohne Prüfer bleibt jedes Foto in Quarantäne, fail-closed); Schlüssel der signierten Links `taktovia.fleet.evidence-link-secret` (mindestens 32 Zeichen, **Pflicht** außerhalb
 * von `local`/`development`; ohne Angabe dort ein zufälliger Schlüssel je Prozess), Ticketgültigkeit `taktovia.fleet.evidence-ticket-ttl-minutes` (15), Linkgültigkeit höchstens 60 s.
 */
@Configuration
class FleetConfiguration {
    private val log = LoggerFactory.getLogger(FleetConfiguration::class.java)

    @Bean
    fun defectEvidenceWiring(env: Environment, store: TenantObjectStore, scanner: MalwareScanner): DefectEvidenceWiring {
        val local = (env.getProperty("taktovia.environment")?.trim()?.ifEmpty { null } ?: "production") in setOf("local", "development")
        val configured = env.getProperty("taktovia.fleet.evidence-link-secret")?.trim()?.ifEmpty { null }
        check(configured != null || local) { "taktovia.fleet.evidence-link-secret fehlt: außerhalb der lokalen Umgebung ist der Schlüssel der signierten Fotolinks Pflicht" }
        val secret = configured?.toByteArray() ?: ByteArray(32).also { SecureRandom().nextBytes(it); log.warn("Kein taktovia.fleet.evidence-link-secret gesetzt: zufälliger Schlüssel nur für diesen Prozess") }
        val inspector = MediaInspectors.basic(extraScan = { bytes ->
            when (val r = scanner.scan(bytes)) {
                ScanResult.Clean -> null
                is ScanResult.Infected -> MediaVerdict.Infected(r.signature)
                is ScanResult.Unavailable -> MediaVerdict.Unavailable(r.reason)
            }
        }, engine = "media+" + scanner.engine)
        val policy = EvidencePolicy(ticketTtl = Duration.ofMinutes(env.getProperty("taktovia.fleet.evidence-ticket-ttl-minutes", Long::class.java, 15L)))
        return DefectEvidenceWiring(store, inspector, SignedUrls(secret), policy)
    }

    /**
     * Alarm-Grundlage (WP-M3-52): `taktovia.alerts.escalation-delay-minutes` (Planungsdefault 10, `alert_escalation_delay_min`), `…vehicle-unavailability-minutes` (15,
     * `vehicle_unavailability_alert_min`), `…max-escalation-level` (1). Die Werte sind Planungsdefaults, im Parameterkatalog stehen sie noch nicht (Katalog eingefroren).
     */
    @Bean fun alertSettings(env: Environment): AlertSettings = AlertSettings(
        escalationDelay = Duration.ofMinutes(env.getProperty("taktovia.alerts.escalation-delay-minutes", Long::class.java, 10L)),
        vehicleUnavailabilityAlert = Duration.ofMinutes(env.getProperty("taktovia.alerts.vehicle-unavailability-minutes", Long::class.java, 15L)),
        maxEscalationLevel = env.getProperty("taktovia.alerts.max-escalation-level", Int::class.java, 1),
    )

    /** Aufträge je Fahrzeug aus dem Fahrtenplan für die Leitstellenansicht (WP-M3-36). */
    @Bean fun operatorOrders(ctxTx: ContextTransactions): de.taktovia.bookingdispatch.api.OperatorOrders = de.taktovia.bookingdispatch.api.OperatorOrders.create(ctxTx)

    /**
     * Schadenklassifikation (WP-M3-45): `taktovia.defects.workshop-auto-block-severity` (`workshop_auto_block_severity`, Planungsdefault `SAFETY_CRITICAL`) nennt die Klasse, ab der die Klassifikation
     * das Fahrzeug sperrt. Unzulässige Werte (auch solche, die sicherheitskritische Schäden von der Sperre ausnähmen) verhindern den Start; der Parameterkatalog ist eingefroren (wie bei den Alarmwerten).
     */
    @Bean fun defectSettings(env: Environment): DefectSettings = DefectSettings.parse(env.getProperty("taktovia.defects.workshop-auto-block-severity"))

    @Bean fun fleetsafetyApi(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, store: TenantObjectStore, evidence: DefectEvidenceWiring, alertSettings: AlertSettings, defectSettings: DefectSettings): FleetsafetyApi =
        FleetsafetyApi.create(ctxTx, audit, outbox, store, evidence = evidence, alertSettings = alertSettings, defectSettings = defectSettings)

    /** Port der Alarm-Grundlage für Fachmodule und technische Betriebsalarme (die Tabellen gehören `fleet-safety`). */
    @Bean fun businessAlertPort(fleet: FleetsafetyApi): BusinessAlertPort = fleet.alerts.port

    /**
     * Hinweis an die Leitstelle bei ausbleibender Planbestätigung (WP-M3-27): als Alarm `PLAN_ACK_OVERDUE` je Schicht und Revision genau einmal (Dedupe über den Bedingungsschlüssel; ein
     * wiederholter Lauf des Betriebsauftrags erzeugt keinen zweiten).
     */
    @Bean fun planAckOverdueSink(ctxTx: ContextTransactions, port: BusinessAlertPort): PlanAckOverdueSink = PlanAckOverdueSink { tenant, hint ->
        ctxTx.runSystem(tenant) { j ->
            port.raise(j, BusinessAlertRequest(AlertKinds.PLAN_ACK_OVERDUE, dedupeKey = "PLAN_ACK_OVERDUE|${hint.shiftId}|${hint.planRevision}", vehicleId = hint.vehicleId, refType = "staff_shift", refId = hint.shiftId,
                reasonCode = "PLAN_NOT_ACKNOWLEDGED"))
        }
    }

    /**
     * Betriebsauftrag der Eskalation und der Überwachung unbestätigter Planrevisionen (WP-M3-52/-27): läuft nur mit `taktovia.alerts.worker-enabled=true` und einer [TenantSource]-Bean des Betriebs
     * (RLS erlaubt keine Aufzählung der Mandanten, OP-06/OP-08, wie beim Outbox-Relay); `taktovia.alerts.poll-seconds` (30). Die Läufe sind je Mandant gegen Parallelität gesichert.
     */
    @Bean fun alertWorker(fleet: FleetsafetyApi, staff: de.taktovia.stafftime.api.StafftimeApi, tenants: org.springframework.beans.factory.ObjectProvider<TenantSource>, env: Environment): AlertWorker {
        val worker = AlertWorker(fleet, staff)
        val source = tenants.getIfAvailable()
        if (source != null && env.getProperty("taktovia.alerts.worker-enabled", Boolean::class.java, false)) {
            val poll = env.getProperty("taktovia.alerts.poll-seconds", Long::class.java, 30L)
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "alert-worker").also { it.isDaemon = true } }
                .scheduleWithFixedDelay({ runCatching { worker.runOnce(source) }.onFailure { log.warn("Alarm-Betriebsauftrag fehlgeschlagen ({})", it.javaClass.simpleName) } }, poll, poll, java.util.concurrent.TimeUnit.SECONDS)
        }
        return worker
    }
}

/** Ein Durchlauf der Alarm-Betriebsaufträge über die Mandanten der Quelle; Fehler eines Mandanten stoppen die anderen nicht. */
class AlertWorker(private val fleet: FleetsafetyApi, private val staff: de.taktovia.stafftime.api.StafftimeApi) {
    fun runOnce(source: TenantSource): Int = source.tenants().distinct().sumOf { t ->
        runCatching { staff.planAcks.reportOverdue(t).size + fleet.alerts.escalateDue(t).size }.getOrDefault(0)
    }
}
