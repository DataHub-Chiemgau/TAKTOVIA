package de.taktovia.app.identity

import com.fasterxml.jackson.annotation.JsonValue
import de.taktovia.identity.api.ChannelView
import de.taktovia.identity.api.ConsentPurpose
import de.taktovia.identity.api.ContactChannel
import de.taktovia.identity.api.FavoriteView
import de.taktovia.identity.api.PassengerApi
import de.taktovia.identity.api.ProfilePatch
import de.taktovia.identity.api.ProfileView
import de.taktovia.platform.api.Etag
import de.taktovia.platform.api.Revisioned
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

class ProfileResource(@get:JsonValue val data: Map<String, Any?>, override val revision: Long) : Revisioned

/**
 * Eigenes Profil, Kontaktverifikation, Datenschutzeinstellungen und eigene Unterstützungsauswahl (WP-M1-28 bis -30). Alle Operationen beziehen sich auf das Konto des
 * Akteurs; Mandant und Konto stammen aus dem [de.taktovia.platform.context.RequestContext], nie aus der Anfrage.
 */
@RestController
class ProfileController(private val passengerApi: org.springframework.beans.factory.ObjectProvider<PassengerApi>) {
    private val passengers get() = passengerApi.getObject()

    @GetMapping("/v1/me/profile")
    fun profile(request: HttpServletRequest): ProfileResource = resource(passengers.profiles.get(RequestContexts.require(request)))

    @PatchMapping("/v1/me/profile")
    fun update(@RequestHeader("If-Match", required = false) ifMatch: String?, @RequestBody body: Map<String, Any?>, request: HttpServletRequest): ProfileResource {
        val contact = body["contact"] as Map<*, *>?
        val patch = ProfilePatch(
            locale = body["locale"] as String?, email = contact?.get("email") as String?, phone = contact?.get("phone") as String?,
            favorites = (body["favorites"] as List<*>?)?.map { val f = it as Map<*, *>; FavoriteView(f["label"] as String, UUID.fromString(f["stop_id"] as String)) },
            preferredStops = (body["preferred_stops"] as List<*>?)?.map { UUID.fromString(it as String) },
            notificationChannels = (body["notification_channels"] as List<*>?)?.map { it as String },
        )
        return resource(passengers.profiles.update(RequestContexts.require(request), Etag.parseIfMatch(ifMatch), patch))
    }

    @PostMapping("/v1/me/contact-verifications")
    fun start(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val channel = ContactChannel.valueOf(body["channel"] as String)
        val v = passengers.accounts.startVerification(RequestContexts.require(request), channel)
        return linkedMapOf("channel" to v.channel.name, "expires_at" to v.expiresAt.toString())
    }

    @PostMapping("/v1/me/contact-verifications/confirmations")
    fun confirm(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): Map<String, Any?> {
        val channel = ContactChannel.valueOf(body["channel"] as String)
        val v = passengers.accounts.confirmVerification(RequestContexts.require(request), channel, body["code"] as String)
        return linkedMapOf("channel" to channel.name, "status" to v.status.name, "verified_at" to v.verifiedAt.toString())
    }

    @GetMapping("/v1/me/privacy")
    fun privacy(request: HttpServletRequest): Map<String, Any?> {
        val s = passengers.consents.settings(RequestContexts.require(request))
        return linkedMapOf(
            "current_text_version" to s.currentTextVersion,
            "consents" to s.consents.map { linkedMapOf("purpose" to it.purpose.name, "granted" to it.granted, "changeable" to it.changeable, "recorded_at" to it.recordedAt?.toString(), "text_version" to it.textVersion) },
            "history" to s.history.map { revision(it) },
        )
    }

    @PostMapping("/v1/me/privacy/consents")
    fun consent(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val r = passengers.consents.record(RequestContexts.require(request), ConsentPurpose.valueOf(body["purpose"] as String), body["granted"] as Boolean, body["text_version"] as String)
        return ResponseEntity.status(HttpStatus.CREATED).body(revision(r))
    }

    @GetMapping("/v1/me/transport-support")
    fun transportSupport(request: HttpServletRequest): Map<String, Any?> = linkedMapOf("codes" to passengers.profiles.readSupportNeeds(RequestContexts.require(request)))

    @PutMapping("/v1/me/transport-support")
    fun replaceTransportSupport(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Void> {
        passengers.profiles.replaceSupportNeeds(RequestContexts.require(request), (body["codes"] as List<*>).map { it as String })
        return ResponseEntity.noContent().build()
    }

    private fun revision(r: de.taktovia.identity.api.ConsentRevisionView): Map<String, Any?> =
        linkedMapOf("id" to r.id.toString(), "purpose" to r.purpose.name, "sequence" to r.sequence, "granted" to r.granted, "recorded_at" to r.recordedAt.toString(), "text_version" to r.textVersion)

    private fun channel(c: ChannelView?): Map<String, Any?>? = c?.let { linkedMapOf("value" to it.value, "status" to it.status.name, "verified_at" to it.verifiedAt?.toString()) }

    private fun resource(p: ProfileView) = ProfileResource(linkedMapOf(
        "id" to p.id.toString(), "status" to p.status, "locale" to p.locale, "created_at" to p.createdAt.toString(), "revision" to p.revision,
        "contact" to linkedMapOf("email" to channel(p.email), "phone" to channel(p.phone)),
        "favorites" to p.favorites.map { linkedMapOf("label" to it.label, "stop_id" to it.stopId.toString()) },
        "preferred_stops" to p.preferredStops.map { it.toString() }, "notification_channels" to p.notificationChannels,
    ), p.revision)
}
