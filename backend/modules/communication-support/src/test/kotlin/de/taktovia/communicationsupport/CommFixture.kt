package de.taktovia.communicationsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.ContactLookup
import de.taktovia.communicationsupport.api.DeliveryGateway
import de.taktovia.communicationsupport.api.DispatchConfig
import de.taktovia.communicationsupport.api.FailureReason
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.communicationsupport.api.OutboundMessage
import de.taktovia.communicationsupport.api.ProviderResult
import de.taktovia.communicationsupport.api.ProviderStatus
import de.taktovia.communicationsupport.api.ProviderTimeoutException
import de.taktovia.communicationsupport.api.RecipientDirectory
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.alert.OperatorWarning
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Datenbankabhängige Tests des Moduls `communication-support` (WP-M2-32, -33): Zugangsdaten aus der Umgebung; fehlen sie, werden die Tests übersprungen, mit
 * `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie fehl. Die Anwendung läuft unter der Runtime-Rolle; Mandanten legt der Administrator an.
 * Provider, Empfängerverzeichnis und Uhr sind Testdoubles ([ScriptedGateway], [MapDirectory], [MutableClock]); der Simulator-Provider des Adapters
 * `notification` darf vom Modul nicht gekannt werden (Architekturregel) und wird im Adapter und in der Anwendung geprüft.
 */
object CommTestDb {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private fun url(app: String) = "jdbc:postgresql://${env("TAKTOVIA_DB_HOST") ?: "127.0.0.1"}:${env("TAKTOVIA_DB_PORT") ?: "5432"}/${env("TAKTOVIA_DB_NAME") ?: "taktovia"}?ApplicationName=$app"

    fun require() {
        if (env("TAKTOVIA_DB_RUNTIME_PASSWORD") == null || env("TAKTOVIA_DB_ADMIN_PASSWORD") == null) {
            if (env("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")
            assumeTrue(false, "Datenbank-Zugangsdaten nicht gesetzt")
        }
    }

    private fun pool(app: String, user: String, password: String, size: Int) = HikariDataSource(HikariConfig().also {
        it.jdbcUrl = url(app); it.username = user; it.password = password; it.maximumPoolSize = size; it.minimumIdle = 1
    })

    fun runtime(app: String, size: Int): HikariDataSource = pool(app, "taktovia_runtime", env("TAKTOVIA_DB_RUNTIME_PASSWORD")!!, size)
    fun admin(): HikariDataSource = pool("comm-admin", env("TAKTOVIA_DB_ADMIN_USER") ?: "dev_admin", env("TAKTOVIA_DB_ADMIN_PASSWORD")!!, 2)
}

class MutableClock(@Volatile var now: Instant = Instant.now()) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

/** Empfängerverzeichnis als Testdouble: Kontakte und Sprachen je Empfänger, veränderbar (Sprachwechsel). */
class MapDirectory : RecipientDirectory {
    val contacts = ConcurrentHashMap<Pair<UUID, NotificationChannel>, String>()
    val languages = ConcurrentHashMap<UUID, String>()
    override fun contact(tenant: TenantId, recipientRef: UUID, channel: NotificationChannel): ContactLookup =
        contacts[recipientRef to channel]?.let { ContactLookup.Found(it) } ?: ContactLookup.None
    override fun language(tenant: TenantId, recipientRef: UUID): String? = languages[recipientRef]
}

class RecordingAlerts : OperatorAlertPort {
    val warnings = CopyOnWriteArrayList<OperatorWarning>()
    override fun report(warning: OperatorWarning) { warnings += warning }
}

/**
 * Provider als Testdouble mit steuerbarem Verhalten je Aufruf (Skript) und providerseitiger Idempotenz über die Nachrichten-ID: dieselbe ID ergibt höchstens eine
 * Zustellung an den Endnutzer ([deliveries]). `TIMEOUT_DELIVERED` ist der Fall „Timeout mit spätem Erfolg“: der Provider stellt zu, die Antwort kommt nie an.
 */
class ScriptedGateway : DeliveryGateway {
    enum class Step { ACCEPT, REJECT_TEMPORARY, REJECT_INVALID_CONTACT, REJECT_PERMANENT, TIMEOUT_DELIVERED, TIMEOUT_LOST }
    class Sent(val messageId: UUID, val channel: NotificationChannel, val to: String, val subject: String?, val body: String)

    val script = ConcurrentLinkedQueue<Step>()
    /** Schritte je Nachrichten-ID (Vorrang vor [script]); die Reihenfolge der Verarbeitung innerhalb eines Laufs ist nicht festgelegt. */
    private val perMessage = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Step>>()
    fun forMessage(id: UUID, vararg steps: Step) { perMessage.getOrPut(id) { ConcurrentLinkedQueue() }.addAll(steps) }
    @Volatile var default = Step.ACCEPT
    @Volatile var statusUnavailable = false
    @Volatile var beforeSend: () -> Unit = {}
    val sent = CopyOnWriteArrayList<Sent>()
    val statusCalls = AtomicInteger()
    private val delivered = ConcurrentHashMap<UUID, Sent>()

    /** Nachrichten, die den Endnutzer tatsächlich erreicht haben (je Nachrichten-ID höchstens eine). */
    val deliveries: Map<UUID, Sent> get() = delivered

    override fun send(message: OutboundMessage): ProviderResult {
        val rec = Sent(message.messageId, message.channel, message.to, message.subject, message.body)
        sent += rec
        beforeSend()
        return when (perMessage[message.messageId]?.poll() ?: script.poll() ?: default) {
            Step.ACCEPT -> { delivered.putIfAbsent(message.messageId, rec); ProviderResult.Accepted("ref-${message.messageId}") }
            Step.REJECT_TEMPORARY -> ProviderResult.Rejected(false, FailureReason.PROVIDER_ERROR)
            Step.REJECT_INVALID_CONTACT -> ProviderResult.Rejected(true, FailureReason.INVALID_CONTACT)
            Step.REJECT_PERMANENT -> ProviderResult.Rejected(true, FailureReason.PROVIDER_ERROR)
            Step.TIMEOUT_DELIVERED -> { delivered.putIfAbsent(message.messageId, rec); throw ProviderTimeoutException() }
            Step.TIMEOUT_LOST -> throw ProviderTimeoutException()
        }
    }

    override fun status(channel: NotificationChannel, messageId: UUID): ProviderStatus {
        statusCalls.incrementAndGet()
        if (statusUnavailable) return ProviderStatus.Unavailable
        return if (delivered.containsKey(messageId)) ProviderStatus.Accepted("ref-$messageId") else ProviderStatus.NotKnown
    }
}

class CommFixture(poolSize: Int = 16) : AutoCloseable {
    init { CommTestDb.require() }
    val appName = "comm-test-" + UUID.randomUUID()
    val ds: HikariDataSource = CommTestDb.runtime(appName, poolSize)
    private val adminDs = CommTestDb.admin()
    val tx = TenantTransactions(ds)
    val ctxTx = ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val clock = MutableClock()
    val audit = JdbcAuditPort()
    val api: CommunicationsupportApi = CommunicationsupportApi.create(tx, ctxTx, audit, Outbox(), clock)
    val gateway = ScriptedGateway()
    val directory = MapDirectory()
    val alerts = RecordingAlerts()

    fun dispatcher(config: DispatchConfig = DispatchConfig(maxAttempts = 4), metrics: MeterRegistry? = null) = api.dispatcher(gateway, directory, alerts, config, metrics)

    /** Ein Mandant mit Fahrgästen, zwei Diensten (Verkehrsprodukte) und einem Administrator-Kontext. */
    class World(val tenant: TenantId, val passengers: List<UUID>, val serviceA: UUID, val serviceB: UUID) {
        val passenger get() = passengers[0]
    }

    fun world(passengers: Int = 2): World {
        val t = UUID.randomUUID(); val le = UUID.randomUUID(); val org = UUID.randomUUID(); val rev = UUID.randomUUID()
        val svcA = UUID.randomUUID(); val svcB = UUID.randomUUID(); val people = List(passengers) { UUID.randomUUID() }
        exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", t, "t-$t")
        tx.run(TenantId(t)) { j ->
            j.update("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (?,?,'LE','DE')", t, le)
            j.update("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (?,?,?,?,?,CAST(? AS text[]))", t, org, le, "Org", "f-$org", "{TRANSPORT_COMPANY}")
            j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t, rev, svcA)
            j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Rufbus','Europe/Berlin',?,'user:test')", t, svcA, org, rev)
            val rev2 = UUID.randomUUID()
            j.update("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) VALUES (?,?,'SERVICE',?,1,'1','{}','2026-01-01','user:test','Test')", t, rev2, svcB)
            j.update("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id,created_by) VALUES (?,?,?,'Linienbedarfsverkehr','Europe/Berlin',?,'user:test')", t, svcB, org, rev2)
            people.forEach { p -> j.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (?,?,?,now() + interval '1 year')", t, p, "tok-$p") }
        }
        people.forEachIndexed { i, p ->
            directory.contacts[p to NotificationChannel.SMS] = "+49170000${1000 + i}"; directory.contacts[p to NotificationChannel.EMAIL] = "fahrgast$i-${p.toString().take(6)}@example.org"
            directory.contacts[p to NotificationChannel.PUSH] = "device-token-$p"
        }
        return World(TenantId(t), people, svcA, svcB)
    }

    fun context(tenant: TenantId, subject: String = "admin-" + UUID.randomUUID()) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/comm", subject, "sid-$subject", true), tenant, setOf("tenant_admin"), setOf("notifications:read"), emptySet(), UUID.randomUUID())

    /** Legt einen Auftrag in einer eigenen Mandantentransaktion an (wie ein Fachmodul in seiner Transaktion). */
    fun enqueue(w: World, eventType: String = "booking.confirmed", channel: NotificationChannel = NotificationChannel.SMS, recipient: UUID = w.passenger,
                eventId: UUID = UUID.randomUUID(), service: UUID? = w.serviceA, params: Map<String, String> = defaultParams()): UUID =
        tx.run(w.tenant) { j -> api.notifications.send(j, NotificationRequest(eventId, recipient, channel, eventType, service, params)).notificationId }

    fun defaultParams(ref: String = "BK-" + UUID.randomUUID().toString().take(8)) =
        mapOf("booking_reference" to ref, "booking_number" to "4711", "meeting_point" to "Marktplatz", "time_window" to "10:00-10:15", "eta" to "10:07")

    fun stateOf(id: UUID): String = strings("SELECT state FROM taktovia.notification WHERE id = ?", id).single()
    fun failureOf(id: UUID): String? = strings("SELECT failure_reason FROM taktovia.notification WHERE id = ?", id).singleOrNull()
    fun attemptsOf(id: UUID): List<String> = strings("SELECT outcome FROM taktovia.notification_attempt WHERE notification_id = ? ORDER BY attempt_no", id)

    fun exec(sql: String, vararg args: Any?) { adminDs.connection.use { c -> c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() } } }

    fun count(sql: String, vararg args: Any?): Int = adminDs.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    fun strings(sql: String, vararg args: Any?): List<String> = adminDs.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    /** Offene Transaktionen der Runtime-Verbindungen dieser Fixture (Anwendungsname), z. B. um „kein Providerruf in offener Transaktion“ zu belegen. */
    fun openTransactions(): Int = count("SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND state IN ('idle in transaction', 'active') AND pid <> pg_backend_pid() AND xact_start IS NOT NULL AND query NOT LIKE 'SHOW%'", appName)

    override fun close() { ds.close(); adminDs.close() }
}
