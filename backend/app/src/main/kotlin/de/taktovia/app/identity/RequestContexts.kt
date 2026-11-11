package de.taktovia.app.identity

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import jakarta.servlet.http.HttpServletRequest

/** Zugriff auf den geprüften [RequestContext] der Anfrage. Gesetzt ausschließlich vom [RequestContextFilter]. */
object RequestContexts {
    const val ATTRIBUTE = "taktovia.requestContext"

    fun of(request: HttpServletRequest): RequestContext? = request.getAttribute(ATTRIBUTE) as? RequestContext

    /** Deny-by-default: ohne Kontext keine Fachoperation (401, kein Hinweis auf den Grund). */
    fun require(request: HttpServletRequest): RequestContext = of(request) ?: throw ProblemException(ErrorCode.UNAUTHENTICATED, "Anmeldung fehlt oder ist ungültig.")
}
