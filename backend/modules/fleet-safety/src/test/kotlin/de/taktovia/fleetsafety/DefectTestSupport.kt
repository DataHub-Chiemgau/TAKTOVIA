package de.taktovia.fleetsafety

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/** Testhilfen der Schadenakte (WP-M3-43/-44/-47/-48), eigene Klasse je Cluster: Kontexte mit Rollen, Schichten, Rohmeldungen. */
class DefectWorld(val fx: FleetFixture) {
    val w = fx.world(1)
    val tenant: TenantId = w.tenant
    val vehicle: UUID = w.vehicle

    fun ctx(vararg roles: String, subject: UUID = UUID.randomUUID(), tenant: TenantId = this.tenant) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/fleet", subject.toString(), "sid-$subject", true), tenant, roles.toSet(), setOf("defects:read", "defects:write"), emptySet(), UUID.randomUUID())

    /** Schicht des Fahrers für das Fahrzeug (Annahme: `driver_ref` = IdP-Subject), Beginn relativ zu jetzt. */
    fun shift(driver: UUID, vehicle: UUID = this.vehicle, startOffsetHours: Long = -2, lengthHours: Long = 8) =
        fx.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now() + (? || ' hours')::interval, now() + (? || ' hours')::interval,'Europe/Berlin',0,'PUBLISHED')",
            tenant.value, UUID.randomUUID(), driver, vehicle, startOffsetHours.toString(), (startOffsetHours + lengthHours).toString())

    fun raw(vehicle: UUID = this.vehicle, occurredAt: Instant = Instant.now().minusSeconds(3600), category: String = "BODY", description: String? = "Kratzer an der Beifahrertür",
            extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>("vehicle_id" to vehicle.toString(), "occurred_at" to occurredAt.toString(), "category_code" to category,
        "description" to description).also { m -> if (description == null) m.remove("description"); m.putAll(extra) }

    /** Setzt die Klasse (Triage, WP-M3-45) per Administration, solange die Triage nicht gebaut ist. */
    fun classify(defect: UUID, severity: String) = fx.exec("UPDATE taktovia.defect_case SET severity = ? WHERE id = ?", severity, defect)
}

/** Steuerbare Uhr für Ablauf von Ticket und Link. */
class TestClock(var now: Instant = Instant.now()) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

/** Medienprüfer mit schaltbarer Verfügbarkeit (Ersatz für den Ausfall eines Virenscanners). */
class SwitchableInspector(private val delegate: de.taktovia.fleetsafety.api.MediaInspector = de.taktovia.fleetsafety.api.MediaInspectors.basic()) : de.taktovia.fleetsafety.api.MediaInspector {
    @Volatile var available = true
    override val engine get() = delegate.engine
    override fun inspect(bytes: ByteArray, mime: String) = if (available) delegate.inspect(bytes, mime) else de.taktovia.fleetsafety.api.MediaVerdict.Unavailable("Prüfer ausgefallen")
}

/** Fleet-API mit Objektspeicher, steuerbarer Uhr und signierten Links, dazu Bilder und Ticketaufbau. */
class EvidenceHarness(val fx: FleetFixture) {
    val clock = TestClock()
    val store = de.taktovia.platform.storage.InMemoryObjectStore()
    val inspector = SwitchableInspector()
    val urls = de.taktovia.platform.storage.SignedUrls(ByteArray(32) { (it * 7 + 3).toByte() }, clock)
    val fleet: de.taktovia.fleetsafety.api.FleetsafetyApi = de.taktovia.fleetsafety.api.FleetsafetyApi.create(fx.ctxTx, de.taktovia.platform.audit.JdbcAuditPort(), de.taktovia.platform.outbox.Outbox(), clock,
        null, de.taktovia.fleetsafety.api.DefectEvidenceWiring(store, inspector, urls))

    /** Gültiges JPEG; [seed] macht den Inhalt verschieden. */
    fun jpeg(seed: Int = 1, exif: Boolean = false): ByteArray {
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 16) img.setRGB(x, y, (seed * 2654435761L + x * 31 + y * 17).toInt() and 0xFFFFFF)
        val out = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(img, "jpg", out)
        val b = out.toByteArray()
        if (!exif) return b
        // APP1-Segment „Exif“ mit Standortplatzhalter direkt hinter SOI einfügen
        val payload = "Exif\u0000\u0000GPS-48.1N-11.5E".toByteArray(Charsets.ISO_8859_1)
        val len = payload.size + 2
        return b.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + b.copyOfRange(2, b.size)
    }

    fun hash(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)
    fun hashWire(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash(b))
    fun hashHex(b: ByteArray) = hash(b).joinToString("") { "%02x".format(it) }

    fun ticketRaw(bytes: ByteArray, mime: String = "image/jpeg", declaredHash: String = hashWire(bytes), size: Long = bytes.size.toLong()): Map<String, Any?> =
        mapOf("mime" to mime, "size_bytes" to size, "sha256" to declaredHash, "captured_at" to clock.instant().minusSeconds(120).toString())

    fun ticket(ctx: RequestContext, bytes: ByteArray, mime: String = "image/jpeg", declaredHash: String = hashWire(bytes), size: Long = bytes.size.toLong()) =
        fleet.defectEvidence.requestTicket(ctx, de.taktovia.fleetsafety.api.RequestEvidenceTicket.parse(ticketRaw(bytes, mime, declaredHash, size)))

    /** Der „Client“ lädt direkt in die Quarantäne (vorsignierter Upload, nicht ausgeführt: S3/MinIO); danach schließt er das Ticket ab. */
    fun putQuarantine(w: DefectWorld, t: de.taktovia.fleetsafety.api.EvidenceTicketView, bytes: ByteArray) =
        store.put(de.taktovia.platform.storage.ObjectKey(w.tenant, t.uploadKey), bytes, t.mime)

    fun complete(ctx: RequestContext, t: de.taktovia.fleetsafety.api.EvidenceTicketView) = fleet.defectEvidence.complete(ctx, t.id, t.revision)

    fun report(ctx: RequestContext, w: DefectWorld, vararg tickets: java.util.UUID, extra: Map<String, Any?> = emptyMap()) =
        fleet.defects.report(ctx, de.taktovia.fleetsafety.api.ReportDefect.parse(w.raw(extra = extra + mapOf("evidence_ids" to tickets.map { it.toString() }))))
}
