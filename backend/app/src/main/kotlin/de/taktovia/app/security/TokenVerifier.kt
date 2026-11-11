package de.taktovia.app.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.util.UUID

/** Ein Token ist ungültig (401). [reason] ist ein interner Code für das Log; er gelangt nie in die Antwort. */
class TokenRejectedException(val reason: String) : RuntimeException(reason)

/**
 * Prüft ein Bearer-Token vollständig und leitet den [AuthenticatedPrincipal] ab (WP-M1-04, IAM-002):
 * Algorithmen-Whitelist (RS256, PS256, ES256; nie `none`, nie HMAC), Schlüssel nur über `kid` aus dem JWKS des Issuers (keine
 * `jku`/`jwk`/`x5u`-Header), Signatur, `iss` exakt, `aud`, `exp`/`nbf`/`iat` mit kleiner Toleranz, Höchstlaufzeit, Pflichtclaims nach
 * dem Claims-Vertrag und Widerspruchsfreiheit von `acr`/`amr`.
 */
class TokenVerifier(private val props: IdpProperties, private val keys: JwksKeySource, private val clock: Clock = Clock.systemUTC()) {
    private fun reject(reason: String): Nothing = throw TokenRejectedException(reason)

    fun verify(token: String): AuthenticatedPrincipal {
        if (token.length > MAX_TOKEN_LENGTH) reject("token_too_large")
        val parts = token.split('.')
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) reject("malformed")

        val header = try { JWSHeader.parse(Base64URL(parts[0])) } catch (e: Exception) { reject("header_unparsable") }
        if (header.algorithm !in ALLOWED_ALGORITHMS) reject("algorithm_not_allowed")
        if (header.getJWKURL() != null || header.getJWK() != null || header.getX509CertURL() != null || header.x509CertChain != null || !header.criticalParams.isNullOrEmpty()) reject("header_key_reference")
        val kid = header.keyID?.takeIf { it.isNotBlank() && it.length <= 128 } ?: reject("kid_missing")

        val jwk = keys.key(kid) ?: reject("kid_unknown")
        if (jwk.algorithm != null && jwk.algorithm != header.algorithm) reject("key_algorithm_mismatch")
        val signed = try { SignedJWT.parse(token) } catch (e: Exception) { reject("jwt_unparsable") }
        val verified = try {
            when {
                jwk is RSAKey && header.algorithm.name.let { it.startsWith("RS") || it.startsWith("PS") } -> signed.verify(RSASSAVerifier(jwk))
                jwk is ECKey && header.algorithm == JWSAlgorithm.ES256 -> signed.verify(ECDSAVerifier(jwk))
                else -> reject("key_type_mismatch")
            }
        } catch (e: TokenRejectedException) { throw e } catch (e: Exception) { reject("signature_check_failed") }
        if (!verified) reject("signature_invalid")

        val claims = try { signed.jwtClaimsSet } catch (e: Exception) { reject("claims_unparsable") }
        return try { principal(claims) } catch (e: TokenRejectedException) { throw e } catch (e: Exception) { reject("claims_invalid") }
    }

    private fun principal(c: JWTClaimsSet): AuthenticatedPrincipal {
        if (c.issuer != props.issuer) reject("issuer_mismatch")
        val aud = c.audience ?: reject("audience_missing")
        if (props.audience !in aud) reject("audience_mismatch")

        val now = clock.instant().epochSecond
        val exp = (c.expirationTime ?: reject("exp_missing")).time / 1000
        val iat = (c.issueTime ?: reject("iat_missing")).time / 1000
        val skew = props.clockSkewSeconds
        if (now > exp + skew) reject("expired")
        c.notBeforeTime?.let { if (now + skew < it.time / 1000) reject("not_yet_valid") }
        if (iat > now + skew) reject("issued_in_future")
        if (exp - iat > props.maxTokenLifetimeSeconds) reject("lifetime_too_long")

        val subject = c.subject?.let(::uuid) ?: reject("sub_invalid")
        val client = c.getStringClaim("azp")?.takeIf { CLIENT.matches(it) } ?: reject("azp_invalid")
        val type = when (c.getStringClaim("principal_type")) { "human" -> PrincipalType.HUMAN; "service" -> PrincipalType.SERVICE; else -> reject("principal_type_invalid") }
        val acr = c.getStringClaim("acr")?.takeIf { it == AuthenticatedPrincipal.ACR_BASIC || it == AuthenticatedPrincipal.ACR_MFA } ?: reject("acr_invalid")
        val amr = c.getStringListClaim("amr")
        if (amr != null) {
            if (amr.size > 8 || amr.any { it.isEmpty() || it.length > 32 }) reject("amr_invalid")
            if (acr == AuthenticatedPrincipal.ACR_MFA && amr.none { it in MFA_METHODS }) reject("acr_amr_contradiction")
        }
        val sid = c.getStringClaim("sid")
        when (type) {
            PrincipalType.HUMAN -> { if (sid == null || sid.length !in 8..128) reject("sid_invalid"); if (client !in props.humanClients) reject("human_client_unknown") }
            PrincipalType.SERVICE -> if (sid != null) reject("service_with_session")
        }
        val roles = c.getStringListClaim("roles")?.also { if (it.size > 32 || it.any { r -> !ROLE.matches(r) }) reject("roles_invalid") } ?: emptyList()
        val tenants = c.getStringListClaim("tenant_ids")?.also { if (it.size > 100) reject("tenant_ids_invalid") }?.map { uuid(it) ?: reject("tenant_ids_invalid") } ?: emptyList()
        if (type == PrincipalType.SERVICE && tenants.size > 1) reject("service_with_tenant_switch")
        val scope = c.getStringClaim("scope")?.also { if (it.length > 1024) reject("scope_invalid") }
        return AuthenticatedPrincipal(
            issuer = c.issuer, subject = subject, type = type, clientId = client, sessionId = sid, acr = acr,
            scopes = scope?.split(' ')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(), roles = roles.toSet(), tenantIds = tenants,
        )
    }

    private fun uuid(s: String): UUID? = runCatching { UUID.fromString(s) }.getOrNull()?.takeIf { it.toString() == s.lowercase() }

    companion object {
        const val MAX_TOKEN_LENGTH = 8192
        private val ALLOWED_ALGORITHMS = setOf(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256)
        private val MFA_METHODS = setOf("otp", "hwk", "swk", "webauthn", "fpt", "face", "sc", "mfa")
        private val CLIENT = Regex("^[a-z0-9][a-z0-9-]{1,62}$")
        private val ROLE = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$")
    }
}
