package de.taktovia.app.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Konfigurationsnachweis des IdP-Realms als Code (`infra/keycloak/realm-*.json`, WP-M1-04): adaptiver Passwort-Hash, passwortloser Weg,
 * MFA-Pflichtfluss, PKCE-Clients. **Geprüft wird die Datei, nicht ein laufender Keycloak** (nicht ausgeführt: Import und Anmeldung, kein Docker).
 */
class IdpRealmExportTest {
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val json = JsonMapper()
    private fun realm(env: String): JsonNode = json.readTree(Files.readString(repo.resolve("infra/keycloak/realm-taktovia-$env.json")))
    private val envs = listOf("dev", "staging", "performance", "production")

    private fun flows(r: JsonNode) = r["authenticationFlows"].associateBy { it["alias"].asString() }
    private fun authenticators(f: JsonNode) = f["authenticationExecutions"].mapNotNull { it["authenticator"]?.asString() }

    @Test
    fun adaptiver_hash_und_passwortlos_konfiguriert() {
        envs.forEach { env ->
            val r = realm(env)
            val policy = r["passwordPolicy"].asString()
            // adaptiver, speicherharter Hash; keine schwachen Standard-Hashes (MD5, SHA-1, schnelles SHA-256/512 ohne Dehnung)
            assertTrue(policy.contains("hashAlgorithm(argon2)"), "$env: $policy")
            assertFalse(Regex("(?i)md5|sha-?1|sha-?256\\)|plaintext|pbkdf2-sha1").containsMatchIn(policy), "$env: $policy")
            assertTrue(Regex("length\\((\\d+)\\)").find(policy)!!.groupValues[1].toInt() >= 12, "$env: Mindestlänge")
            val params = json.readTree(r["attributes"]["taktovia.password.hash"].asString())
            assertEquals("argon2id", params["algorithm"].asString())
            assertTrue(params["memory_kib"].asString().toInt() >= 19456 && params["iterations"].asString().toInt() >= 2, "$env: Parameter unter OWASP-Mindestwert")

            // passwortloser Anmeldeweg: WebAuthn-Passwordless-Policy mit Nutzerverifikation und Teil des Browser-Flows
            assertEquals("required", r["webAuthnPolicyPasswordlessUserVerificationRequirement"].asString(), env)
            assertEquals("taktovia-browser", r["browserFlow"].asString(), env)
            val flows = flows(r)
            assertTrue("webauthn-authenticator-passwordless" in authenticators(flows.getValue("taktovia-first-factor")), "$env: passwortloser Weg im Flow")
            assertTrue("auth-password-form" in authenticators(flows.getValue("taktovia-first-factor")), env)
            assertTrue(r["requiredActions"].any { it["alias"].asString() == "webauthn-register-passwordless" && it["enabled"].asBoolean() }, env)
        }
    }

    @Test
    fun mfa_pflichtfluss_fuer_privilegierte_rollen() {
        val backendDefault = Regex("privilegedRoles = set\\(\"privileged-roles\", \"([^\"]+)\"\\)").find(
            Files.readString(repo.resolve("backend/app/src/main/kotlin/de/taktovia/app/security/IdpProperties.kt")))!!.groupValues[1].split(',').toSet()
        envs.forEach { env ->
            val r = realm(env)
            val flows = flows(r)
            // Stufe 2 (loa2) verlangt einen zweiten Faktor (OTP oder WebAuthn) und ist über die Level-Bedingung an acr=loa2 gebunden
            val level2 = flows.getValue("taktovia-level2")["authenticationExecutions"]
            assertTrue(level2.any { it["authenticator"]?.asString() == "conditional-level-of-authentication" && it["authenticatorConfig"].asString() == "taktovia-loa2" }, env)
            assertEquals(setOf("auth-otp-form", "webauthn-authenticator"), authenticators(flows.getValue("taktovia-second-factor")).toSet(), env)
            val loa = r["authenticatorConfig"].associate { it["alias"].asString() to it["config"] }
            assertEquals("2", loa.getValue("taktovia-loa2")["loa-condition-level"].asString(), env)
            assertEquals("0", loa.getValue("taktovia-loa2")["loa-max-age"].asString(), "$env: Stufe 2 gilt nicht über die SSO-Sitzung hinaus")
            assertEquals(mapOf("loa1" to 1, "loa2" to 2), json.readValue(r["attributes"]["acr.loa.map"].asString(), Map::class.java).mapValues { (it.value as Number).toInt() }, env)

            // Operator-Oberfläche fordert Stufe 2 an; privilegierte Rollen im Realm = privilegierte Rollen im Backend (Standard)
            val operator = r["clients"].first { it["clientId"].asString() == "operator-web" }
            assertEquals("loa2", operator["attributes"]["default.acr.values"].asString(), env)
            val privileged = r["attributes"]["taktovia.mfa.privileged_roles"].asString().split(',').toSet()
            assertEquals(backendDefault, privileged, "$env: Rollenliste im Realm und im Backend weichen ab")
            assertEquals(privileged, r["roles"]["realm"].filter { it["attributes"]["mfa_required"][0].asString() == "true" }.map { it["name"].asString() }.toSet(), env)
            assertTrue("tenant_admin" in privileged, "mindestens die Administratorrolle (OP-11)")
        }
    }

    @Test
    fun clients_pkce_dienstkonten_und_trennung_der_domaenen() {
        val issuers = mutableSetOf<String>()
        envs.forEach { env ->
            val r = realm(env)
            assertTrue(issuers.add(r["realm"].asString()), "Realmname je Umgebung eindeutig")
            val clients = r["clients"].associateBy { it["clientId"].asString() }
            listOf("passenger-web", "operator-web", "driver-app").forEach { c ->
                val cl = clients.getValue(c)
                assertTrue(cl["publicClient"].asBoolean() && cl["standardFlowEnabled"].asBoolean() && !cl["implicitFlowEnabled"].asBoolean(), "$env $c")
                assertEquals("S256", cl["attributes"]["pkce.code.challenge.method"].asString(), "$env $c")
                assertTrue(cl["protocolMappers"].any { it["config"]["claim.name"]?.asString() == "principal_type" && it["config"]["claim.value"].asString() == "human" }, "$env $c: principal_type")
                assertEquals(env == "dev", cl["directAccessGrantsEnabled"].asBoolean(), "$env $c: Passwort-Grant nur im Entwicklungsrealm")
            }
            clients.values.filter { it["clientId"].asString().startsWith("adapter-") }.forEach { cl ->
                assertTrue(cl["serviceAccountsEnabled"].asBoolean() && !cl["standardFlowEnabled"].asBoolean() && !cl["directAccessGrantsEnabled"].asBoolean())
                assertTrue(cl["protocolMappers"].any { it["config"]["claim.name"]?.asString() == "principal_type" && it["config"]["claim.value"].asString() == "service" })
            }
            // Keine Klartextpasswörter und keine Client-Secrets außerhalb des lokalen Entwicklungsrealms
            val text = json.writeValueAsString(r)
            if (env == "dev") {
                val creds = r["users"].flatMap { it["credentials"].toList() }
                assertTrue(creds.isNotEmpty() && creds.all { it["value"].asString().startsWith("dev-only-") }, "nur Entwicklungspasswörter im lokalen Realm")
                assertTrue(clients.values.mapNotNull { it["secret"]?.asString() }.all { it.startsWith("dev-only-") })
            }
            else {
                assertFalse(text.contains("\"credentials\""), "$env: keine Zugangsdaten im Export")
                assertFalse(text.contains("\"secret\""), "$env: keine Client-Secrets im Export")
                assertEquals(0, r["users"].size(), env)
                assertEquals("all", r["sslRequired"].asString(), env)
            }
        }
    }
}
