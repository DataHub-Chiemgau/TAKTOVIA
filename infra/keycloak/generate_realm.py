#!/usr/bin/env python3
"""Erzeugt die Keycloak-Realms als Code (WP-M0-09, erweitert in WP-M1-04 und WP-M1-40).

Aufruf: python3 infra/keycloak/generate_realm.py   (schreibt je Umgebung eine JSON-Datei neben das Skript)

  realm-taktovia-dev.json          Realm taktovia-dev: nur lokal, synthetische Testakteure mit Entwicklungspasswoertern (dev-only-...)
  realm-taktovia-staging.json      Realm taktovia-staging          } getrennte Identitaetsdomaenen je Umgebung (Kap. 01 §1.8):
  realm-taktovia-performance.json  Realm taktovia-performance      } eigener Realmname/Issuer, keine Nutzer, keine Passwoerter,
  realm-taktovia-production.json   Realm taktovia-production       } keine Client-Secrets in der Datei, kein Passwort-Grant

Passwort-Policy mit adaptivem Hash (Argon2), MFA ueber Authentisierungsstaerke (acr loa1/loa2, Step-up-Flow), passwortloser Weg (WebAuthn/Passkey).
Die erzeugten Dateien sind versioniert; Aenderungen am Realm hier vornehmen und neu erzeugen. NICHT in einen laufenden Keycloak importiert
(kein Docker); die Struktur folgt der Keycloak-Dokumentation, die Abnahme beim ersten Import steht aus (siehe docs/umsetzungsplan/vertraege/idp-realm.md).
"""
import json
import pathlib

TENANT_A = "00000000-0000-4000-8000-00000000000a"
TENANT_B = "00000000-0000-4000-8000-00000000000b"
AUDIENCE = "taktovia-api"
ROLES = {  # Rolle -> (Beschreibung, privilegiert/MFA). Vorlaeufig; Rollenkatalog ist Detailvertrag M1 (IAM, OP-11).
    "passenger": ("Fahrgast", False),
    "dispatcher": ("Disponent/Leitstelle", True),
    "driver": ("Fahrer", False),
    "finance": ("Finanzrolle", True),
    "tenant_admin": ("Tenantadmin", True),
}
SCOPES = ["driver:events"]  # vorlaeufig, aus openapi.yaml; weitere in M1
DEV_NOTE = "NUR LOKAL: Entwicklungs-Passwort, synthetische Testidentitaet"


# --- Umgebungen: je Umgebung eine eigene Identitaetsdomaene (Realm, Issuer, Redirect-Ziele). Kein gemeinsamer Realm (Kap. 01 §1.8, WP-M1-40). ---
ENVIRONMENTS = {
    "development": {"realm": "taktovia-dev", "file": "realm-taktovia-dev.json", "synthetic_users": True,
                    "redirects": ["http://localhost:*/*", "http://127.0.0.1:*/*"], "ssl": "none",
                    "display": "TAKTOVIA Entwicklung (nur lokal, synthetische Daten)"},
    "staging": {"realm": "taktovia-staging", "file": "realm-taktovia-staging.json", "synthetic_users": False,
                "redirects": ["https://passenger.staging.taktovia.example/*", "https://operator.staging.taktovia.example/*"], "ssl": "all",
                "display": "TAKTOVIA Staging (synthetische Daten)"},
    "performance": {"realm": "taktovia-performance", "file": "realm-taktovia-performance.json", "synthetic_users": False,
                    "redirects": ["https://passenger.performance.taktovia.example/*", "https://operator.performance.taktovia.example/*"], "ssl": "all",
                    "display": "TAKTOVIA Performance (produktionsaehnlich, synthetisch)"},
    "production": {"realm": "taktovia-production", "file": "realm-taktovia-production.json", "synthetic_users": False,
                   "redirects": ["https://passenger.taktovia.example/*", "https://operator.taktovia.example/*"], "ssl": "all",
                   "display": "TAKTOVIA Produktion"},
}

# --- Passwort-Policy und adaptiver Hash (IAM-002). Das System speichert selbst keine Passwoerter; sie liegen nur hier im IdP. ---
# Argon2id (adaptiv, speicherhart). Die Parameter gehoeren zur Serverkonfiguration des Hash-Providers (nicht zum Realm-Export) und stehen
# als Realm-Attribute zur Nachweisfuehrung; Keycloak-Option: --spi-password-hashing--argon2--<memory|iterations|parallelism|hash-length|type>.
PASSWORD_POLICY = "hashAlgorithm(argon2) and length(12) and notUsername and notEmail and passwordHistory(5) and forceExpiredPasswordChange(365)"
HASH_PARAMS = {"algorithm": "argon2id", "memory_kib": "19456", "iterations": "2", "parallelism": "1", "hash_length_bytes": "32"}  # OWASP-Mindestwerte 2024
ACR_LOA_MAP = {"loa1": 1, "loa2": 2}
LEVEL_MAX_AGE = {"loa1": "36000", "loa2": "0"}   # Stufe 2 gilt nicht ueber die SSO-Sitzung hinaus: bei Anforderung stets neu abfragen


def execution(authenticator=None, requirement="REQUIRED", priority=10, flow_alias=None, config=None):
    e = {"requirement": requirement, "priority": priority, "userSetupAllowed": False}
    if flow_alias:
        e.update({"authenticatorFlow": True, "flowAlias": flow_alias})
    else:
        e.update({"authenticator": authenticator, "authenticatorFlow": False})
        if config:
            e["authenticatorConfig"] = config
    return e


def flow(alias, description, executions, top=False):
    return {"alias": alias, "description": description, "providerId": "basic-flow", "topLevel": top, "builtIn": False, "authenticationExecutions": executions}


def authentication_flows():
    """Browser-Flow mit Step-up (Keycloak "Step-up Authentication"): Stufe 1 = Benutzername + (Passwort ODER Passkey), Stufe 2 = zweiter Faktor (OTP ODER WebAuthn)."""
    return [
        flow("taktovia-browser", "TAKTOVIA Browser-Anmeldung: Passwort oder Passkey, zweiter Faktor bei Stufe loa2", [
            execution("auth-cookie", "ALTERNATIVE", 10),
            execution(requirement="ALTERNATIVE", priority=20, flow_alias="taktovia-forms")], top=True),
        flow("taktovia-forms", "Formularteil", [
            execution(requirement="CONDITIONAL", priority=10, flow_alias="taktovia-level1"),
            execution(requirement="CONDITIONAL", priority=20, flow_alias="taktovia-level2")]),
        flow("taktovia-level1", "Stufe 1 (loa1): erster Faktor", [
            execution("conditional-level-of-authentication", "REQUIRED", 10, config="taktovia-loa1"),
            execution("auth-username-form", "REQUIRED", 20),
            execution(requirement="REQUIRED", priority=30, flow_alias="taktovia-first-factor")]),
        flow("taktovia-first-factor", "Erster Faktor: Passwort oder passwortlos (WebAuthn/Passkey)", [
            execution("auth-password-form", "ALTERNATIVE", 10),
            execution("webauthn-authenticator-passwordless", "ALTERNATIVE", 20)]),
        flow("taktovia-level2", "Stufe 2 (loa2): zweiter Faktor", [
            execution("conditional-level-of-authentication", "REQUIRED", 10, config="taktovia-loa2"),
            execution(requirement="REQUIRED", priority=20, flow_alias="taktovia-second-factor")]),
        flow("taktovia-second-factor", "Zweiter Faktor: TOTP oder WebAuthn (Sicherheitsschluessel)", [
            execution("auth-otp-form", "ALTERNATIVE", 10),
            execution("webauthn-authenticator", "ALTERNATIVE", 20)]),
    ]


def authenticator_configs():
    return [{"alias": f"taktovia-{lvl}", "config": {"loa-condition-level": str(ACR_LOA_MAP[lvl]), "loa-max-age": LEVEL_MAX_AGE[lvl]}} for lvl in ("loa1", "loa2")]


def pw(label):
    return {"type": "password", "value": f"dev-only-{label}", "temporary": False}


def user(login, role, tenants, first, last):
    mfa = ROLES[role][1] if role else False
    return {
        "username": login, "email": f"{login}@example.test", "emailVerified": True, "enabled": True,
        "firstName": first, "lastName": last,
        "attributes": {"tenant_ids": tenants, "mfa_required": ["true" if mfa else "false"], "note": [DEV_NOTE]},
        "credentials": [pw(login.split("@")[0].replace(".", "-"))],
        "realmRoles": [role] if role else [],
        "requiredActions": [],  # zweiter Faktor wird ueber die Authentisierungsstaerke (acr loa2) erzwungen, nicht ueber Nutzerattribute
    }


def mapper(name, typ, config):
    return {"name": name, "protocol": "openid-connect", "protocolMapper": typ, "config": config}


def hardcoded(name, claim, value):
    return mapper(name, "oidc-hardcoded-claim-mapper", {"claim.name": claim, "claim.value": value, "jsonType.label": "String", "access.token.claim": "true", "id.token.claim": "false"})


common_mappers = [
    mapper("audience-taktovia-api", "oidc-audience-mapper",
           {"included.custom.audience": AUDIENCE, "access.token.claim": "true", "id.token.claim": "false"}),
    mapper("tenant-ids", "oidc-usermodel-attribute-mapper",
           {"user.attribute": "tenant_ids", "claim.name": "tenant_ids", "jsonType.label": "String",
            "multivalued": "true", "access.token.claim": "true", "id.token.claim": "false"}),
    mapper("mfa-required", "oidc-usermodel-attribute-mapper",   # nur Hinweis fuer Oberflaechen; das Backend entscheidet nach acr und Rollen, nie nach diesem Claim
           {"user.attribute": "mfa_required", "claim.name": "mfa_required", "jsonType.label": "String",
            "access.token.claim": "true", "id.token.claim": "false"}),
    mapper("realm-roles", "oidc-usermodel-realm-role-mapper",
           {"claim.name": "roles", "multivalued": "true", "jsonType.label": "String", "access.token.claim": "true"}),
]


def human_client(cid, name, env, default_acr=None, extra_scopes=()):
    attrs = {"pkce.code.challenge.method": "S256", "access.token.lifespan": "300", "acr.loa.map": json.dumps(ACR_LOA_MAP)}
    if default_acr:
        attrs["default.acr.values"] = default_acr   # Operator-Oberflaeche: immer Stufe 2 anfordern (Backend erzwingt zusaetzlich, RFC 9470)
    dev = env["synthetic_users"]
    return {
        "clientId": cid, "name": name, "enabled": True, "publicClient": True, "protocol": "openid-connect",
        "standardFlowEnabled": True, "directAccessGrantsEnabled": dev,  # Passwort-Grant NUR im lokalen Entwicklungsrealm (Tokenbezug fuer Tests)
        "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
        "redirectUris": env["redirects"], "webOrigins": ["+"] if dev else [u.rsplit("/*", 1)[0] for u in env["redirects"]],
        "attributes": attrs,
        "protocolMappers": common_mappers + [hardcoded("principal-type-human", "principal_type", "human")],
        "defaultClientScopes": ["profile", "email", *extra_scopes],
    }


ADAPTERS = ["partner-tomp", "partner-osdm", "timetable-gtfs", "psp", "erp-export", "ocr", "notification"]


def machine_client(adapter, env):
    c = {
        "clientId": f"adapter-{adapter}", "name": f"Maschinen-Client Adapter {adapter}", "enabled": True,
        "publicClient": False, "protocol": "openid-connect", "clientAuthenticatorType": "client-secret",
        "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "serviceAccountsEnabled": True,
        "attributes": {"access.token.lifespan": "300"},
        "protocolMappers": [common_mappers[0], hardcoded("principal-type-service", "principal_type", "service")],
        "defaultClientScopes": [],
    }
    if env["synthetic_users"]:
        c["secret"] = f"dev-only-adapter-{adapter}"  # NUR LOKAL
    # Nicht-Dev: kein Secret im Export; Keycloak erzeugt es beim Import, die Uebernahme in die Secret-Verwaltung der Umgebung
    # (<umgebung>-secrets, WP-M1-36/-40) ist Bereitstellungsschritt. Es gibt keine umgebungsuebergreifend geteilten Secrets.
    return c


def build(name, env):
    users = []
    if env["synthetic_users"]:
        for t, tid in (("a", TENANT_A), ("b", TENANT_B)):
            for role, (desc, _) in ROLES.items():
                users.append(user(f"{t}.{role}", role, [tid], f"Test {role}", f"Mandant {t.upper()}"))
        users.append(user("ab.dispatcher", "dispatcher", [TENANT_A, TENANT_B], "Test dispatcher", "Beide Mandanten"))
        users.append(user("none.passenger", "passenger", [], "Test passenger", "Ohne Mitgliedschaft"))
    privileged = ",".join(r for r, (_, p) in ROLES.items() if p)
    realm = {
        "realm": env["realm"], "displayName": env["display"],
        "enabled": True, "sslRequired": env["ssl"], "registrationAllowed": False, "loginWithEmailAllowed": True,
        "resetPasswordAllowed": True, "bruteForceProtected": True, "failureFactor": 5, "permanentLockout": False,
        "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 1800, "ssoSessionMaxLifespan": 36000,
        "passwordPolicy": PASSWORD_POLICY, "browserFlow": "taktovia-browser",
        "otpPolicyType": "totp", "otpPolicyAlgorithm": "HmacSHA256", "otpPolicyDigits": 6, "otpPolicyPeriod": 30,
        # Passwortloser Anmeldeweg (IAM-002; Verfahren/Zielgruppe offen, OP-12): WebAuthn/Passkey mit erzwungener Nutzerverifikation
        "webAuthnPolicyPasswordlessRpEntityName": "TAKTOVIA", "webAuthnPolicyPasswordlessSignatureAlgorithms": ["ES256", "RS256"],
        "webAuthnPolicyPasswordlessAttestationConveyancePreference": "not specified", "webAuthnPolicyPasswordlessAuthenticatorAttachment": "not specified",
        "webAuthnPolicyPasswordlessRequireResidentKey": "Yes", "webAuthnPolicyPasswordlessUserVerificationRequirement": "required",
        "webAuthnPolicyPasswordlessCreateTimeout": 0, "webAuthnPolicyPasswordlessAvoidSameAuthenticatorRegister": False,
        "attributes": {
            "taktovia.environment": name,
            "taktovia.note": DEV_NOTE if env["synthetic_users"] else "Keine Nutzer, keine Passwoerter und keine Secrets im Export (WP-M1-40)",
            "taktovia.mfa.privileged_roles": privileged,
            "taktovia.mfa.enforcement": "Backend: acr=loa2 fuer privilegierte Rollen (403 sonst); IdP: Step-up-Flow, operator-web fordert loa2 an",
            "taktovia.password.hash": json.dumps(HASH_PARAMS),
            "acr.loa.map": json.dumps(ACR_LOA_MAP),
        },
        "authenticationFlows": authentication_flows(),
        "authenticatorConfig": authenticator_configs(),
        "requiredActions": [
            {"alias": "CONFIGURE_TOTP", "name": "Configure OTP", "providerId": "CONFIGURE_TOTP", "enabled": True, "defaultAction": False, "priority": 10},
            {"alias": "webauthn-register", "name": "Webauthn Register", "providerId": "webauthn-register", "enabled": True, "defaultAction": False, "priority": 70},
            {"alias": "webauthn-register-passwordless", "name": "Webauthn Register Passwordless", "providerId": "webauthn-register-passwordless", "enabled": True, "defaultAction": False, "priority": 80},
        ],
        "roles": {"realm": [{"name": r, "description": d, "attributes": {"mfa_required": ["true" if p else "false"]}} for r, (d, p) in ROLES.items()]},
        "clientScopes": [{"name": s, "protocol": "openid-connect", "attributes": {"include.in.token.scope": "true"}} for s in SCOPES],
        "clients": [human_client("passenger-web", "Passenger Web", env),
                    human_client("operator-web", "Operator (Leitstelle/Finanzen/Admin)", env, default_acr="loa2"),
                    human_client("driver-app", "Driver App", env, extra_scopes=SCOPES)] + [machine_client(a, env) for a in ADAPTERS],
        "users": users,
    }
    return realm


here = pathlib.Path(__file__).parent
for env_name, env in ENVIRONMENTS.items():
    r = build(env_name, env)
    out = here / env["file"]
    out.write_text(json.dumps(r, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"geschrieben: {out.name} (Realm {r['realm']}, {len(r['users'])} Nutzer, {len(r['clients'])} Clients)")
