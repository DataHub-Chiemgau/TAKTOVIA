#!/usr/bin/env python3
"""Erzeugt infra/keycloak/realm-taktovia-dev.json (WP-M0-09). Nur lokale Entwicklungswerte, keine echten Personen/Geheimnisse.

Aufruf: python3 infra/keycloak/generate_realm.py   (schreibt die JSON-Datei neben das Skript)
Die erzeugte Datei ist versioniert; Aenderungen am Realm hier vornehmen und neu erzeugen.
"""
import json
import pathlib

TENANT_A = "00000000-0000-4000-8000-00000000000a"
TENANT_B = "00000000-0000-4000-8000-00000000000b"
AUDIENCE = "taktovia-api"
ROLES = {  # Rolle -> (Beschreibung, privilegiert/MFA). Vorlaeufig; Rollenkatalog ist Detailvertrag M1 (IAM).
    "passenger": ("Fahrgast", False),
    "dispatcher": ("Disponent/Leitstelle", True),
    "driver": ("Fahrer", False),
    "finance": ("Finanzrolle", True),
    "tenant_admin": ("Tenantadmin", True),
}
SCOPES = ["driver:events"]  # vorlaeufig, aus openapi.yaml; weitere in M1
DEV_NOTE = "NUR LOKAL: Entwicklungs-Passwort, synthetische Testidentitaet"


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
        "requiredActions": [],  # MFA-Durchsetzung erst in M1 (hier nur Kennzeichnung)
    }


users = []
for t, tid in (("a", TENANT_A), ("b", TENANT_B)):
    for role, (desc, _) in ROLES.items():
        users.append(user(f"{t}.{role}", role, [tid], f"Test {role}", f"Mandant {t.upper()}"))
users.append(user("ab.dispatcher", "dispatcher", [TENANT_A, TENANT_B], "Test dispatcher", "Beide Mandanten"))
users.append(user("none.passenger", "passenger", [], "Test passenger", "Ohne Mitgliedschaft"))


def mapper(name, typ, config):
    return {"name": name, "protocol": "openid-connect", "protocolMapper": typ, "config": config}


common_mappers = [
    mapper("audience-taktovia-api", "oidc-audience-mapper",
           {"included.custom.audience": AUDIENCE, "access.token.claim": "true", "id.token.claim": "false"}),
    mapper("tenant-ids", "oidc-usermodel-attribute-mapper",
           {"user.attribute": "tenant_ids", "claim.name": "tenant_ids", "jsonType.label": "String",
            "multivalued": "true", "access.token.claim": "true", "id.token.claim": "false"}),
    mapper("mfa-required", "oidc-usermodel-attribute-mapper",
           {"user.attribute": "mfa_required", "claim.name": "mfa_required", "jsonType.label": "String",
            "access.token.claim": "true", "id.token.claim": "false"}),
    mapper("realm-roles", "oidc-usermodel-realm-role-mapper",
           {"claim.name": "roles", "multivalued": "true", "jsonType.label": "String", "access.token.claim": "true"}),
]


def human_client(cid, name, extra_scopes=()):
    return {
        "clientId": cid, "name": name, "enabled": True, "publicClient": True, "protocol": "openid-connect",
        "standardFlowEnabled": True, "directAccessGrantsEnabled": True,  # Passwort-Grant NUR fuer lokale Tests (Tokenbezug)
        "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
        "redirectUris": ["http://localhost:*/*", "http://127.0.0.1:*/*"], "webOrigins": ["+"],
        "attributes": {"pkce.code.challenge.method": "S256", "access.token.lifespan": "300"},
        "protocolMappers": common_mappers,
        "defaultClientScopes": ["profile", "email", *extra_scopes],
    }


ADAPTERS = ["partner-tomp", "partner-osdm", "timetable-gtfs", "psp", "erp-export", "ocr", "notification"]


def machine_client(adapter):
    return {
        "clientId": f"adapter-{adapter}", "name": f"Maschinen-Client Adapter {adapter}", "enabled": True,
        "publicClient": False, "protocol": "openid-connect", "clientAuthenticatorType": "client-secret",
        "secret": f"dev-only-adapter-{adapter}",  # NUR LOKAL
        "standardFlowEnabled": False, "directAccessGrantsEnabled": False, "serviceAccountsEnabled": True,
        "attributes": {"access.token.lifespan": "300"},
        "protocolMappers": [common_mappers[0]],
        "defaultClientScopes": [],
    }


realm = {
    "realm": "taktovia-dev",
    "displayName": "TAKTOVIA Entwicklung (nur lokal, synthetische Daten)",
    "enabled": True, "sslRequired": "none", "registrationAllowed": False, "loginWithEmailAllowed": True,
    "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 1800,
    "attributes": {"taktovia.note": DEV_NOTE, "taktovia.mfa.privileged_roles": ",".join(r for r, (_, p) in ROLES.items() if p),
                   "taktovia.mfa.enforcement": "vorbereitet, Durchsetzung in M1"},
    "roles": {"realm": [{"name": r, "description": d, "attributes": {"mfa_required": ["true" if p else "false"]}}
                        for r, (d, p) in ROLES.items()]},
    "clientScopes": [{"name": s, "protocol": "openid-connect", "attributes": {"include.in.token.scope": "true"}} for s in SCOPES],
    "clients": [human_client("passenger-web", "Passenger Web"),
                human_client("operator-web", "Operator (Leitstelle/Finanzen/Admin)"),
                human_client("driver-app", "Driver App", extra_scopes=SCOPES)] + [machine_client(a) for a in ADAPTERS],
    "users": users,
}

out = pathlib.Path(__file__).with_name("realm-taktovia-dev.json")
out.write_text(json.dumps(realm, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"geschrieben: {out} ({len(users)} Nutzer, {len(realm['clients'])} Clients)")
