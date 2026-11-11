package de.taktovia.identity.internal

import de.taktovia.identity.api.ChannelStatus
import de.taktovia.identity.api.ChannelView
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.ContactInput
import de.taktovia.identity.api.DeliveryUnavailableException
import de.taktovia.identity.api.GrantMembership
import de.taktovia.identity.api.PassengerAccounts
import de.taktovia.identity.api.PassengerSettings
import de.taktovia.identity.api.PassengerSummary
import de.taktovia.identity.api.RecoveryAcknowledged
import de.taktovia.identity.api.RecoveryDispatch
import de.taktovia.identity.api.Registered
import de.taktovia.identity.api.RegistrationCaller
import de.taktovia.identity.api.VerificationInfo
import de.taktovia.identity.api.VerificationSender
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.fieldprotection.ProtectedColumns
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Registrierung, Kontaktverifikation und Wiederherstellungsregel (WP-M1-28). Kontaktdaten gelangen nur verschlüsselt in die Datenbank
 * (Feldschutz, getrennte Verbindung); in der Fachtabelle stehen nur HMAC-Token, Verifikationscodes nur als HMAC. Klartext von Kontakt und Code
 * erscheint in keinem Log, Audit oder Fehlertext. Aufrufe des Versandwegs und des IdP laufen nie innerhalb einer SQL-Transaktion.
 */
internal class JdbcPassengerAccounts(
    private val tx: TenantTransactions, private val ctxTx: ContextTransactions, private val fieldTx: TenantTransactions, private val store: ProtectedColumnStore,
    private val audit: AuditPort, private val security: SecurityEventPort, private val membership: JdbcMembershipAdmin, private val tokens: ContactTokens,
    private val sender: VerificationSender, private val recovery: RecoveryDispatch, private val settings: PassengerSettings, private val clock: Clock,
) : PassengerAccounts {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun register(caller: RegistrationCaller, tenant: TenantId, locale: String, contact: ContactInput): Registered {
        if (locale !in settings.locales) throw ProblemException.invalid(Violation("/locale", ViolationCode.NOT_IN_ENUM))
        val value = Contacts.normalize(contact.channel, contact.value, "/contact/value")
        val passengerId = UUID.randomUUID(); val verificationId = UUID.randomUUID()
        val code = tokens.newCode(settings.codeLength)
        val expires = clock.instant().plus(settings.codeTtl)
        val summary = try {
            tx.run(tenant) { j -> createAccount(j, caller, tenant, passengerId, verificationId, locale, contact.channel, value, code, expires) }
        } catch (e: DuplicateKeyException) {
            // Konto des IdP oder Kontakt schon vergeben: nicht unterscheidbar (keine Konto-Enumeration über die Registrierung)
            throw ProblemException(ErrorCode.CONFLICT, "Die Registrierung ist mit diesen Angaben nicht möglich.")
        }
        // Verschlüsselter Kontakt über den Modulzugang; der Wert liegt zu keinem Zeitpunkt unverschlüsselt in der Datenbank.
        try {
            fieldTx.run(tenant) { j -> store.write(j, tenant.value, ProtectedColumns.CONTACT, passengerId, ContactDocument.encode(value.takeIf { contact.channel == ContactChannel.EMAIL }, value.takeIf { contact.channel == ContactChannel.PHONE })) }
        } catch (e: RuntimeException) {
            log.error("Kontakt des neuen Fahrgastkontos nicht gespeichert ({})", e.javaClass.simpleName)
            throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Das Konto wurde angelegt, der Kontakt konnte nicht gesichert werden; bitte im Profil erneut eintragen.")
        }
        deliver(contact.channel, value, code)
        return Registered(summary, VerificationInfo(contact.channel, expires))
    }

    @AuditRequired
    private fun createAccount(
        j: JdbcOperations, caller: RegistrationCaller, tenant: TenantId, id: UUID, verificationId: UUID, locale: String, channel: ContactChannel, value: String, code: String, expires: Instant,
    ): PassengerSummary {
        val status = j.query("SELECT status FROM taktovia.tenant WHERE id = taktovia.current_tenant()", { rs, _ -> rs.getString(1) }).firstOrNull()
        if (status != "ACTIVE") throw ProblemException(ErrorCode.FORBIDDEN)
        val token = tokens.contactToken(tenant.value, channel, value)
        val s = j.query(
            "INSERT INTO taktovia.passenger_private (tenant_id, id, subject_token, retention_until, idp_issuer, idp_subject, locale) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?) RETURNING id, status, locale, created_at, revision",
            { rs, _ -> summary(rs) }, id, token, Timestamp.from(clock.instant().plus(settings.retention)), caller.issuer, caller.subject, locale).single()
        j.update("INSERT INTO taktovia.passenger_contact (tenant_id, id, passenger_id, channel, contact_token) VALUES (taktovia.current_tenant(), ?, ?, ?, ?)", UUID.randomUUID(), id, channel.name, token)
        membership.grantIn(j, actor(caller.subject), caller.correlationId, GrantMembership(caller.issuer, caller.subject, "passenger", null))
        j.update("INSERT INTO taktovia.contact_verification (tenant_id, id, passenger_id, channel, contact_token, code_mac, expires_at) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            verificationId, id, channel.name, token, tokens.codeMac(verificationId, code), Timestamp.from(expires))
        audit.record(j, AuditEntry(actor(caller.subject), AuditActions.PASSENGER_REGISTERED, ObjectRef("passenger", id), caller.correlationId, mapOf("channel" to channel.name, "locale" to locale)))
        return s
    }

    override fun startVerification(ctx: RequestContext, channel: ContactChannel): VerificationInfo {
        // 1. Stand lesen (ohne Sperre): Konto, Token und Status des Kanals
        val (pid, rowToken, status) = ctxTx.run(ctx, readOnly = true) { j -> contactRow(j, ctx, channel) }
        if (status == ChannelStatus.VERIFIED) throw ProblemException(ErrorCode.CONFLICT, "Der Kanal ist bereits verifiziert.")
        // 2. Kontakt entschlüsseln (Modulzugang) und gegen das Token prüfen: nur so geht der Code an den Kontakt, der gerade gilt
        val doc = fieldTx.run(ctx.tenant, readOnly = true) { fj -> store.read(fj, ctx.tenant.value, ProtectedColumns.CONTACT, pid) }
        val (email, phone) = ContactDocument.decode((doc as? ProtectedColumnStore.Read.Value)?.plaintext)
        val destination = (if (channel == ContactChannel.EMAIL) email else phone) ?: throw staleContact()
        if (!tokens.same(tokens.contactToken(ctx.tenant.value, channel, destination), rowToken)) throw staleContact()
        // 3. Aufgabe anlegen: Limit, offene Aufgabe ersetzen
        val code = tokens.newCode(settings.codeLength)
        val id = UUID.randomUUID(); val expires = clock.instant().plus(settings.codeTtl)
        val limited = ctxTx.run(ctx) { j -> start(j, ctx, pid, channel, rowToken, id, code, expires) }
        if (limited) throw tooMany()
        deliver(channel, destination, code)
        return VerificationInfo(channel, expires)
    }

    /** `true`, wenn das Limit greift (Sicherheitsereignis wird in derselben Transaktion festgeschrieben). */
    @AuditRequired
    private fun start(j: JdbcOperations, ctx: RequestContext, pid: UUID, channel: ContactChannel, token: String, id: UUID, code: String, expires: Instant): Boolean {
        // Kontaktzeile sperren: serialisiert Start, Einlösung und Kontaktänderung desselben Kanals
        val current = j.query("SELECT contact_token, status FROM taktovia.passenger_contact WHERE passenger_id = ? AND channel = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getString(2) }, pid, channel.name).firstOrNull()
        if (current == null || !tokens.same(current.first, token) || current.second == "VERIFIED") throw staleContact()
        val now = clock.instant()
        val lockedUntil = j.query("SELECT closed_at FROM taktovia.contact_verification WHERE passenger_id = ? AND channel = ? AND state = 'LOCKED' ORDER BY closed_at DESC LIMIT 1",
            { rs, _ -> rs.getTimestamp(1).toInstant() }, pid, channel.name).firstOrNull()?.plus(settings.lockout)
        val recent = j.queryForObject("SELECT count(*) FROM taktovia.contact_verification WHERE passenger_id = ? AND channel = ? AND created_at > ?", Int::class.java, pid, channel.name, Timestamp.from(now.minusSeconds(3600))) ?: 0
        if ((lockedUntil != null && lockedUntil.isAfter(now)) || recent >= settings.maxStartsPerHour) {
            security.report(j, SecurityEvent(actor(ctx.actor.subject), AuditActions.SECURITY_CONTACT_VERIFICATION_LIMITED, ObjectRef("passenger", pid), ctx.correlationId, mapOf("channel" to channel.name, "phase" to "start")))
            return true
        }
        j.update("UPDATE taktovia.contact_verification SET state = 'SUPERSEDED', closed_at = clock_timestamp() WHERE passenger_id = ? AND channel = ? AND state = 'PENDING'", pid, channel.name)
        j.update("INSERT INTO taktovia.contact_verification (tenant_id, id, passenger_id, channel, contact_token, code_mac, expires_at) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?)",
            id, pid, channel.name, token, tokens.codeMac(id, code), Timestamp.from(expires))
        audit.record(j, AuditEntry(actor(ctx.actor.subject), AuditActions.PASSENGER_VERIFICATION_STARTED, ObjectRef("passenger", pid), ctx.correlationId, mapOf("channel" to channel.name)))
        return false
    }

    private enum class Confirm { OK, INVALID, LIMITED }

    override fun confirmVerification(ctx: RequestContext, channel: ContactChannel, code: String): ChannelView {
        var verifiedAt: Instant? = null
        val outcome = ctxTx.run(ctx) { j -> confirm(j, ctx, channel, code) { verifiedAt = it } }
        when (outcome) {
            Confirm.OK -> Unit
            Confirm.INVALID -> throw ProblemException.invalid(Violation("/code", ViolationCode.INVALID_VALUE))
            Confirm.LIMITED -> throw tooMany()
        }
        return ChannelView(null, ChannelStatus.VERIFIED, verifiedAt)
    }

    @AuditRequired
    private fun confirm(j: JdbcOperations, ctx: RequestContext, channel: ContactChannel, code: String, verified: (Instant) -> Unit): Confirm {
        val pid = j.query("SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull()
            ?: throw ProblemException.notFound()
        val who = actor(ctx.actor.subject); val obj = ObjectRef("passenger", pid)
        fun failed(reason: String) = security.report(j, SecurityEvent(who, AuditActions.SECURITY_CONTACT_VERIFICATION_FAILED, obj, ctx.correlationId, mapOf("channel" to channel.name, "reason" to reason)))
        // Die jüngste Aufgabe sperren: parallele Einlösungen mit demselben Code laufen nacheinander, die zweite sieht CONSUMED
        val v = j.query("SELECT id, contact_token, code_mac, expires_at, attempts, state, closed_at FROM taktovia.contact_verification WHERE passenger_id = ? AND channel = ? ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE",
            { rs, _ -> V(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getInt(5), rs.getString(6), rs.getTimestamp(7)?.toInstant()) }, pid, channel.name).firstOrNull()
        if (v == null) { failed("no_verification"); return Confirm.INVALID }
        val now = clock.instant()
        if (v.state == "LOCKED") {
            return if (v.closedAt != null && v.closedAt.plus(settings.lockout).isAfter(now)) { limited(j, who, obj, ctx, channel, "locked"); Confirm.LIMITED } else { failed("locked_expired"); Confirm.INVALID }
        }
        if (v.state != "PENDING") { failed("not_pending"); return Confirm.INVALID }
        if (!v.expiresAt.isAfter(now)) { failed("expired"); return Confirm.INVALID }
        val current = j.query("SELECT contact_token FROM taktovia.passenger_contact WHERE passenger_id = ? AND channel = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, pid, channel.name).firstOrNull()
        if (current == null || !tokens.same(current, v.contactToken)) {
            j.update("UPDATE taktovia.contact_verification SET state = 'SUPERSEDED', closed_at = clock_timestamp() WHERE id = ?", v.id)
            failed("contact_changed"); return Confirm.INVALID
        }
        if (!tokens.same(tokens.codeMac(v.id, code), v.codeMac)) {
            val attempts = v.attempts + 1
            if (attempts >= settings.maxAttempts) {
                j.update("UPDATE taktovia.contact_verification SET attempts = ?, state = 'LOCKED', closed_at = clock_timestamp() WHERE id = ?", attempts, v.id)
                limited(j, who, obj, ctx, channel, "attempts_exceeded"); return Confirm.LIMITED
            }
            j.update("UPDATE taktovia.contact_verification SET attempts = ? WHERE id = ?", attempts, v.id)
            failed("wrong_code"); return Confirm.INVALID
        }
        j.update("UPDATE taktovia.contact_verification SET state = 'CONSUMED', closed_at = clock_timestamp() WHERE id = ?", v.id)
        val at = j.queryForObject("UPDATE taktovia.passenger_contact SET status = 'VERIFIED', verified_at = clock_timestamp() WHERE passenger_id = ? AND channel = ? RETURNING verified_at", Timestamp::class.java, pid, channel.name)!!.toInstant()
        j.update("UPDATE taktovia.passenger_private SET status = 'ACTIVE', updated_at = clock_timestamp() WHERE id = ? AND status = 'PENDING_VERIFICATION'", pid)
        audit.record(j, AuditEntry(who, AuditActions.PASSENGER_CONTACT_VERIFIED, obj, ctx.correlationId, mapOf("channel" to channel.name)))
        verified(at)
        return Confirm.OK
    }

    private fun limited(j: JdbcOperations, who: ActorRef, obj: ObjectRef, ctx: RequestContext, channel: ContactChannel, reason: String) {
        security.report(j, SecurityEvent(who, AuditActions.SECURITY_CONTACT_VERIFICATION_LIMITED, obj, ctx.correlationId, mapOf("channel" to channel.name, "reason" to reason, "phase" to "confirm")))
    }

    private class V(val id: UUID, val contactToken: String, val codeMac: String, val expiresAt: Instant, val attempts: Int, val state: String, val closedAt: Instant?)

    override fun requestRecovery(tenant: TenantId, channel: ContactChannel, value: String, correlationId: UUID): RecoveryAcknowledged {
        val normalized = Contacts.normalizeOrNull(channel, value)
        val token = tokens.contactToken(tenant.value, channel, normalized ?: value.take(200))
        var target: Triple<String, String, String>? = null   // issuer, subject, destination (nur für einen verifizierten Kanal)
        try {
            tx.run(tenant) { j ->
                val row = j.query("SELECT p.id, p.idp_issuer, p.idp_subject, c.status FROM taktovia.passenger_contact c JOIN taktovia.passenger_private p ON p.tenant_id = c.tenant_id AND p.id = c.passenger_id WHERE c.channel = ? AND c.contact_token = ?",
                    { rs, _ -> arrayOf<Any?>(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4)) }, channel.name, token).firstOrNull()
                val system = ActorRef.system("passenger-recovery")
                if (row != null && row[3] == "VERIFIED" && normalized != null && row[1] != null && row[2] != null) {
                    audit.record(j, AuditEntry(system, AuditActions.SECURITY_ACCOUNT_RECOVERY_STARTED, ObjectRef("passenger", row[0] as UUID), correlationId, mapOf("channel" to channel.name)))
                    target = Triple(row[1] as String, row[2] as String, normalized)
                } else {
                    // verworfen: unbekannter oder unverifizierter Kontakt; der Grund steht nur im Audit, nie in der Antwort
                    val obj = if (row != null) ObjectRef("passenger", row[0] as UUID) else ObjectRef("tenant", tenant.value)
                    security.report(j, SecurityEvent(system, AuditActions.SECURITY_ACCOUNT_RECOVERY_DENIED, obj, correlationId, mapOf("channel" to channel.name, "reason" to if (row == null) "unknown_contact" else "contact_not_verified")))
                }
            }
        } catch (e: RuntimeException) {
            log.warn("Wiederherstellungsanfrage nicht ausgewertet ({})", e.javaClass.simpleName)
        }
        target?.let { (issuer, subject, destination) ->
            try { recovery.dispatch(issuer, subject, channel, destination) } catch (e: RuntimeException) { log.warn("Wiederherstellung nicht angestoßen ({})", e.javaClass.simpleName) }
        }
        return RecoveryAcknowledged
    }

    override fun own(ctx: RequestContext): PassengerSummary? = ctxTx.run(ctx, readOnly = true) { j ->
        j.query("SELECT id, status, locale, created_at, revision FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> summary(rs) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull()
    }

    override fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<PassengerSummary> = ctxTx.run(ctx, readOnly = true) { j ->
        if (after == null) j.query("SELECT id, status, locale, created_at, revision FROM taktovia.passenger_private ORDER BY created_at, id LIMIT ?", { rs, _ -> summary(rs) }, limit + 1)
        else j.query("SELECT id, status, locale, created_at, revision FROM taktovia.passenger_private WHERE (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?", { rs, _ -> summary(rs) }, Timestamp.from(after.first), after.second, limit + 1)
    }

    private fun contactRow(j: JdbcOperations, ctx: RequestContext, channel: ContactChannel): Triple<UUID, String, ChannelStatus> {
        val r = j.query("SELECT p.id, c.contact_token, c.status FROM taktovia.passenger_private p JOIN taktovia.passenger_contact c ON c.tenant_id = p.tenant_id AND c.passenger_id = p.id AND c.channel = ? WHERE p.idp_issuer = ? AND p.idp_subject = ?",
            { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), ChannelStatus.valueOf(rs.getString(3))) }, channel.name, ctx.actor.issuer, ctx.actor.subject)
        return r.firstOrNull() ?: throw ProblemException.notFound()
    }

    private fun deliver(channel: ContactChannel, destination: String, code: String) {
        try { sender.send(channel, destination, code) } catch (e: RuntimeException) {
            // Der Code ist gespeichert (nur als HMAC); der Fahrgast kann einen neuen anfordern. Weder Code noch Ziel werden protokolliert.
            log.warn("Verifikationscode nicht zugestellt ({})", if (e is DeliveryUnavailableException) "unavailable" else e.javaClass.simpleName)
        }
    }

    private fun staleContact() = ProblemException(ErrorCode.CONFLICT, "Der Kontakt hat sich geändert; bitte im Profil erneut eintragen.")
    private fun tooMany() = ProblemException(ErrorCode.RATE_LIMITED, "Zu viele Versuche; bitte später erneut versuchen.", headers = mapOf("Retry-After" to settings.lockout.seconds.toString()))
    private fun actor(subject: String) = ActorRef("user:$subject")
    private fun summary(rs: java.sql.ResultSet) = PassengerSummary(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getLong(5))
}
