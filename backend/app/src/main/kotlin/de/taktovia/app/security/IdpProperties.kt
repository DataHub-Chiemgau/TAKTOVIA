package de.taktovia.app.security

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/**
 * Konfiguration des Ressourcenservers (`taktovia.idp.*`, WP-M1-04). Planungsdefaults für nicht bezifferte Werte stehen in
 * `docs/umsetzungsplan/vertraege/idp-realm.md` (OP-13). Fehlt der Issuer, startet die Anwendung nicht (fail closed).
 */
data class IdpProperties(
    val issuer: String,
    val jwksUri: String,
    val audience: String,
    /** Wie lange geladene Schlüssel ohne erneutes Laden gelten. Ein zurückgezogener Schlüssel wird spätestens danach nicht mehr angenommen. */
    val jwksCacheSeconds: Long,
    /** Mindestabstand erneuter Ladeversuche bei unbekannter `kid` (Schutz vor Lastangriff mit Zufalls-kid). */
    val jwksMinRefreshSeconds: Long,
    /** Wie lange bei nicht erreichbarem JWKS zuletzt geladene Schlüssel weitergelten; danach 503 statt Annahme. */
    val maxStaleSeconds: Long,
    val clockSkewSeconds: Long,
    val maxTokenLifetimeSeconds: Long,
    val humanClients: Set<String>,
    /** Rollen, für die ein zweiter Faktor Pflicht ist (OP-11); Mindestens die Administratorrolle. */
    val privilegedRoles: Set<String>,
    /** Dienstkonto-Client (`adapter-<typ>`) → höchstens erlaubte Scopes (Obergrenze, unabhängig vom Token). */
    val serviceClients: Map<String, Set<String>>,
) {
    companion object {
        const val PREFIX = "taktovia.idp"

        fun from(env: Environment): IdpProperties {
            fun str(k: String) = env.getProperty("$PREFIX.$k")?.trim()?.takeIf { it.isNotEmpty() }
            fun num(k: String, d: Long) = env.getProperty("$PREFIX.$k", Long::class.java, d)
            fun set(k: String, d: String) = (env.getProperty("$PREFIX.$k") ?: d).split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

            val issuer = str("issuer-uri") ?: error("taktovia.idp.issuer-uri fehlt: ohne Issuer startet der Ressourcenserver nicht")
            val services: Map<String, String> = Binder.get(env).bind("$PREFIX.service-clients", Bindable.mapOf(String::class.java, String::class.java)).orElse(null) ?: emptyMap()
            return IdpProperties(
                issuer = issuer,
                jwksUri = str("jwks-uri") ?: issuer.trimEnd('/') + "/protocol/openid-connect/certs",
                audience = str("audience") ?: "taktovia-api",
                jwksCacheSeconds = num("jwks-cache-seconds", 300),
                jwksMinRefreshSeconds = num("jwks-min-refresh-seconds", 10),
                maxStaleSeconds = num("max-stale-seconds", 3600),
                clockSkewSeconds = num("clock-skew-seconds", 30),
                maxTokenLifetimeSeconds = num("max-token-lifetime-seconds", 900),
                humanClients = set("human-clients", "passenger-web,operator-web,driver-app"),
                privilegedRoles = set("privileged-roles", "tenant_admin,dispatcher,finance"),
                serviceClients = services.mapValues { (_, v) -> v.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet() },
            )
        }
    }
}
