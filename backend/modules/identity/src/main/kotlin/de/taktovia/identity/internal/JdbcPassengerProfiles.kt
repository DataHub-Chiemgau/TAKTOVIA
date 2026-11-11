package de.taktovia.identity.internal

import de.taktovia.identity.api.ChannelStatus
import de.taktovia.identity.api.ChannelView
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.FavoriteView
import de.taktovia.identity.api.PassengerProfiles
import de.taktovia.identity.api.PassengerSettings
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.identity.api.ProfileView
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.AuditRequired
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.fieldprotection.ProtectedColumns
import de.taktovia.platform.fieldprotection.SupportNeeds
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/**
 * Eigenes Profil lesen und ändern (WP-M1-29): Aggregat mit Revision (`passenger_private.revision`, ETag), Trennung von Kontakt (verschlüsselt über den
 * Feldschutz) und Präferenzen (`passenger_preference`). Das Profil ist an das Konto des Akteurs gebunden (Objektbindung): eine fremde Kennung gibt es hier nicht.
 *
 * Änderungsablauf in einer Transaktion der Runtime-Rolle: Seriensperre je Fahrgast (Advisory-Lock, endet mit der Transaktion) -> `If-Match` gegen die
 * Revision -> Prüfungen -> Präferenzen und Kontaktzeilen -> Ciphertext über den Modulzugang (eigene Verbindung, eigene Transaktion; sie berührt nur die
 * Kontozeile, die diese Transaktion noch nicht gesperrt hat) -> Kontozeile (erhöht die Revision). Zwei gleichzeitige Änderungen laufen nacheinander, die
 * zweite sieht die neue Revision und erhält 412. Scheitert der Commit nach dem Ciphertext, bleibt der Token-Abgleich beim Lesen und beim Verifizieren
 * (ein nicht passender Kontakt wird weder angezeigt noch mit einem Code bedient).
 */
internal class JdbcPassengerProfiles(
    private val ctxTx: ContextTransactions, private val fieldTx: TenantTransactions, private val store: ProtectedColumnStore, private val audit: AuditPort,
    private val tokens: ContactTokens, private val settings: PassengerSettings,
) : PassengerProfiles {

    private class Base(val id: UUID, val status: String, val locale: String, val createdAt: Instant, val revision: Long,
                       val contacts: Map<String, Triple<String, String, Instant?>>, val prefs: List<Pref>)
    private class Pref(val kind: String, val label: String?, val stopId: UUID?, val channel: String?)

    override fun get(ctx: RequestContext): ProfileView {
        val b = ctxTx.run(ctx, readOnly = true) { j -> load(j, ctx) }
        val doc = fieldTx.run(ctx.tenant, readOnly = true) { fj -> store.read(fj, ctx.tenant.value, ProtectedColumns.CONTACT, b.id) }
        val (email, phone) = ContactDocument.decode((doc as? ProtectedColumnStore.Read.Value)?.plaintext)
        return view(ctx, b, email, phone)
    }

    private fun view(ctx: RequestContext, b: Base, email: String?, phone: String?): ProfileView {
        fun channel(ch: ContactChannel, value: String?): ChannelView? {
            val row = b.contacts[ch.name] ?: return null
            // Der angezeigte Wert gehört nur dann zum Kanal, wenn er zum Token der Kontaktzeile passt (Schutz vor halb geschriebenen Änderungen).
            val shown = value?.takeIf { tokens.same(tokens.contactToken(ctx.tenant.value, ch, it), row.first) }
            return ChannelView(shown, ChannelStatus.valueOf(row.second), row.third)
        }
        return ProfileView(b.id, b.status, b.locale, b.createdAt, b.revision, channel(ContactChannel.EMAIL, email), channel(ContactChannel.PHONE, phone),
            b.prefs.filter { it.kind == "FAVORITE" }.map { FavoriteView(it.label!!, it.stopId!!) }, b.prefs.filter { it.kind == "PREFERRED_STOP" }.map { it.stopId!! },
            b.prefs.filter { it.kind == "NOTIFICATION_CHANNEL" }.map { it.channel!! })
    }

    private fun load(j: JdbcOperations, ctx: RequestContext): Base {
        val p = j.query("SELECT id, status, locale, created_at, revision FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?",
            { rs, _ -> arrayOf<Any>(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getLong(5)) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull()
            ?: throw ProblemException.notFound()
        val id = p[0] as UUID
        val contacts = j.query("SELECT channel, contact_token, status, verified_at FROM taktovia.passenger_contact WHERE passenger_id = ?",
            { rs, _ -> rs.getString(1) to Triple(rs.getString(2), rs.getString(3), rs.getTimestamp(4)?.toInstant()) }, id).toMap()
        val prefs = j.query("SELECT kind, label, stop_id, channel FROM taktovia.passenger_preference WHERE passenger_id = ? ORDER BY kind, position",
            { rs, _ -> Pref(rs.getString(1), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getString(4)) }, id)
        return Base(id, p[1] as String, p[2] as String, p[3] as Instant, p[4] as Long, contacts, prefs)
    }

    override fun update(ctx: RequestContext, expectedRevision: Long, patch: ProfilePatch): ProfileView {
        if (patch.isEmpty) throw ProblemException.invalid(Violation("/", ViolationCode.REQUIRED))
        ctxTx.run(ctx) { j -> apply(j, ctx, expectedRevision, patch) }
        return get(ctx)
    }

    @AuditRequired
    private fun apply(j: JdbcOperations, ctx: RequestContext, expectedRevision: Long, patch: ProfilePatch) {
        val id = j.query("SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull()
            ?: throw ProblemException.notFound()
        j.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Any::class.java, "passenger-profile|${ctx.tenant}|$id")
        val current = j.queryForObject("SELECT revision FROM taktovia.passenger_private WHERE id = ?", Long::class.java, id)!!
        if (current != expectedRevision) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Profil wurde zwischenzeitlich geändert; aktuelle Revision lesen.")

        patch.locale?.let { if (it !in settings.locales) throw ProblemException.invalid(Violation("/locale", ViolationCode.NOT_IN_ENUM)) }
        val changed = linkedSetOf<String>()
        patch.locale?.let { changed += "locale" }
        patch.favorites?.let { f -> checkFavorites(j, ctx, f); replace(j, id, "FAVORITE", f.mapIndexed { i, x -> arrayOf<Any?>(i, x.label, x.stopId, null) }); changed += "favorites" }
        patch.preferredStops?.let { s -> checkStops(j, ctx, s, "/preferred_stops"); replace(j, id, "PREFERRED_STOP", s.mapIndexed { i, x -> arrayOf<Any?>(i, null, x, null) }); changed += "preferred_stops" }
        patch.notificationChannels?.let { c -> checkChannels(c); replace(j, id, "NOTIFICATION_CHANNEL", c.mapIndexed { i, x -> arrayOf<Any?>(i, null, null, x) }); changed += "notification_channels" }

        val newEmail = patch.email?.let { Contacts.normalize(ContactChannel.EMAIL, it, "/contact/email") }
        val newPhone = patch.phone?.let { Contacts.normalize(ContactChannel.PHONE, it, "/contact/phone") }
        val subjectMoves = mutableListOf<Pair<String, String>>()   // (alter, neuer) Kontaktschlüssel des Kontos
        try {
            newEmail?.let { changeContact(j, ctx, id, ContactChannel.EMAIL, it)?.let(subjectMoves::add); changed += "contact.email" }
            newPhone?.let { changeContact(j, ctx, id, ContactChannel.PHONE, it)?.let(subjectMoves::add); changed += "contact.phone" }
        } catch (e: DuplicateKeyException) {
            throw ProblemException(ErrorCode.CONFLICT, "Dieser Kontakt kann nicht verwendet werden.")
        }
        if (newEmail != null || newPhone != null) {
            // Ciphertext erst nach allen Prüfungen und Zeilenänderungen; Lesen, Zusammenführen und Schreiben in einer Transaktion des Modulzugangs
            fieldTx.run(ctx.tenant) { fj ->
                val cur = ContactDocument.decode((store.read(fj, ctx.tenant.value, ProtectedColumns.CONTACT, id) as? ProtectedColumnStore.Read.Value)?.plaintext)
                check(store.write(fj, ctx.tenant.value, ProtectedColumns.CONTACT, id, ContactDocument.encode(newEmail ?: cur.first, newPhone ?: cur.second))) { "Kontozeile fehlt" }
            }
        }
        // Erst jetzt die Kontozeile berühren (Zeilensperre): der Modulzugang hat seine Transaktion beendet, sonst warteten beide Verbindungen aufeinander
        try {
            subjectMoves.forEach { (old, new) -> j.update("UPDATE taktovia.passenger_private SET subject_token = ? WHERE id = ? AND subject_token = ?", new, id, old) }
        } catch (e: DuplicateKeyException) { throw ProblemException(ErrorCode.CONFLICT, "Dieser Kontakt kann nicht verwendet werden.") }
        j.update("UPDATE taktovia.passenger_private SET locale = COALESCE(?, locale), updated_at = clock_timestamp() WHERE id = ?", patch.locale, id)
        audit.record(j, AuditEntry(ActorRef("user:${ctx.actor.subject}"), AuditActions.PASSENGER_PROFILE_CHANGED, ObjectRef("passenger", id), ctx.correlationId, mapOf("fields" to changed.toList())))
    }

    /** Neuer Wert für einen Kanal: die Datenbank setzt den Kanal bei geändertem Token auf UNVERIFIED zurück; offene Aufgaben werden ersetzt. */
    private fun changeContact(j: JdbcOperations, ctx: RequestContext, id: UUID, channel: ContactChannel, value: String): Pair<String, String>? {
        val token = tokens.contactToken(ctx.tenant.value, channel, value)
        val row = j.query("SELECT contact_token FROM taktovia.passenger_contact WHERE passenger_id = ? AND channel = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, id, channel.name).firstOrNull()
        if (row == null) {
            j.update("INSERT INTO taktovia.passenger_contact (tenant_id, id, passenger_id, channel, contact_token) VALUES (taktovia.current_tenant(), ?, ?, ?, ?)", UUID.randomUUID(), id, channel.name, token)
            return null
        }
        if (tokens.same(row, token)) return null
        j.update("UPDATE taktovia.passenger_contact SET contact_token = ? WHERE passenger_id = ? AND channel = ?", token, id, channel.name)
        j.update("UPDATE taktovia.contact_verification SET state = 'SUPERSEDED', closed_at = clock_timestamp() WHERE passenger_id = ? AND channel = ? AND state = 'PENDING'", id, channel.name)
        // Der Kontaktschlüssel des Kontos folgt dem Kontakt, mit dem es angelegt wurde (wird nach dem Ciphertext in der Kontozeile nachgezogen)
        return row to token
    }

    private fun replace(j: JdbcOperations, id: UUID, kind: String, rows: List<Array<Any?>>) {
        j.update("DELETE FROM taktovia.passenger_preference WHERE passenger_id = ? AND kind = ?", id, kind)
        rows.forEach { r -> j.update("INSERT INTO taktovia.passenger_preference (tenant_id, id, passenger_id, kind, position, label, stop_id, channel) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), id, kind, r[0], r[1], r[2], r[3]) }
    }

    private fun checkFavorites(j: JdbcOperations, ctx: RequestContext, f: List<FavoriteView>) {
        if (f.size > MAX_FAVORITES) throw ProblemException.invalid(Violation("/favorites", ViolationCode.TOO_MANY_ITEMS))
        f.forEachIndexed { i, x -> if (x.label.isBlank() || x.label.length > 80) throw ProblemException.invalid(Violation("/favorites/$i/label", ViolationCode.INVALID_VALUE)) }
        checkStops(j, ctx, f.map { it.stopId }, "/favorites", "/stop_id")
    }

    private fun checkStops(j: JdbcOperations, ctx: RequestContext, stops: List<UUID>, base: String, suffix: String = "") {
        if (stops.size > MAX_STOPS.coerceAtLeast(MAX_FAVORITES)) throw ProblemException.invalid(Violation(base, ViolationCode.TOO_MANY_ITEMS))
        if (base == "/preferred_stops" && stops.toSet().size != stops.size) throw ProblemException.invalid(Violation(base, ViolationCode.NOT_UNIQUE))
        if (stops.isEmpty()) return
        val known = j.query("SELECT DISTINCT stop_id FROM taktovia.stop_revision WHERE stop_id = ANY(CAST(? AS uuid[]))", { rs, _ -> rs.getObject(1, UUID::class.java) }, stops.joinToString(",", "{", "}")).toSet()
        // Fixture-Haltepunkte (stop_revision); die Existenz gilt im Mandanten (RLS), fremde Haltestellen sind unbekannt
        stops.forEachIndexed { i, s -> if (s !in known) throw ProblemException.invalid(Violation("$base/$i$suffix", ViolationCode.INVALID_VALUE)) }
    }

    private fun checkChannels(c: List<String>) {
        c.forEachIndexed { i, x -> if (x !in CHANNELS) throw ProblemException.invalid(Violation("/notification_channels/$i", ViolationCode.NOT_IN_ENUM)) }
        if (c.toSet().size != c.size) throw ProblemException.invalid(Violation("/notification_channels", ViolationCode.NOT_UNIQUE))
    }

    override fun replaceSupportNeeds(ctx: RequestContext, codes: List<String>) {
        val needs = try { SupportNeeds.of(codes) } catch (e: IllegalArgumentException) { throw ProblemException.invalid(Violation("/codes", ViolationCode.INVALID_VALUE)) }
        val id = ctxTx.run(ctx) { j ->
            val pid = j.query("SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull()
                ?: throw ProblemException.notFound()
            audit.record(j, AuditEntry(ActorRef("user:${ctx.actor.subject}"), AuditActions.PASSENGER_SUPPORT_NEEDS_CHANGED, ObjectRef("passenger", pid), ctx.correlationId, mapOf("count" to needs.codes.size)))
            // Der Ciphertext liegt in der Kontozeile; der Modulzugang schreibt über eine eigene Verbindung (Audit steht in dieser Transaktion, Wert nie darin)
            check(fieldTx.run(ctx.tenant) { fj -> store.write(fj, ctx.tenant.value, ProtectedColumns.SUPPORT_NEEDS, pid, needs.toBytes()) })
            pid
        }
        check(id != null)
    }

    override fun readSupportNeeds(ctx: RequestContext): List<String> {
        val id = ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject).firstOrNull()
        } ?: throw ProblemException.notFound()
        val r = fieldTx.run(ctx.tenant, readOnly = true) { fj -> store.read(fj, ctx.tenant.value, ProtectedColumns.SUPPORT_NEEDS, id) }
        return (r as? ProtectedColumnStore.Read.Value)?.let { SupportNeeds.fromBytes(it.plaintext).codes.sorted() } ?: emptyList()
    }

    private companion object {
        const val MAX_FAVORITES = 20
        const val MAX_STOPS = 10
        val CHANNELS = setOf("PUSH", "EMAIL", "SMS")
    }
}
