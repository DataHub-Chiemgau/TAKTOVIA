package de.taktovia.app.web

import jakarta.servlet.http.HttpServletRequest

/**
 * Mandant und Akteur der Anfrage für Quoten (Kap. 05 §5.1). Authentisierung und Mandantenkontext liefern WP-M1-04/-06,
 * die einen eigenen Resolver aus dem geprüften Token bereitstellen. Bis dahin gilt [RemoteAddressIdentity]: kein
 * Mandant, Akteur = Peer-Adresse. Header wie `X-Forwarded-For` oder ein Mandantenheader werden nie vertraut.
 */
interface RequestIdentityResolver {
    fun tenant(request: HttpServletRequest): String
    fun actor(request: HttpServletRequest): String
}

object RemoteAddressIdentity : RequestIdentityResolver {
    override fun tenant(request: HttpServletRequest) = "-"
    override fun actor(request: HttpServletRequest) = request.remoteAddr ?: "unknown"
}
