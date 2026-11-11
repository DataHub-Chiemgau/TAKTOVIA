"""Deterministischer Seed "Mandanten A/B" (Fixture von PH-AT-001, WP-M0-22). Ausschließlich synthetische Daten.

Seed-Verfahren (Offene Punkte 10): Die Runtime-Rolle hat auf `tenant` nur SELECT (Befund B-02). Mandanten legt daher
die Rolle taktovia_migrator an (Eigentümer, kein Superuser, kein BYPASSRLS) – NICHT per Superuser. Weil `tenant` RLS
erzwingt (FORCE), setzt der Seed vor dem INSERT den transaktionslokalen Kontext app.tenant_id auf die neue Mandanten-ID;
die Policy own_tenant (WITH CHECK id = current_tenant()) lässt dann genau diese eine Zeile zu. Alle weiteren Daten legt
die Runtime-Rolle unter dem Kontext des jeweiligen Mandanten an. Siehe docs/seed-verfahren.md.

Alle IDs sind uuid5-abgeleitet: gleiche Eingabe, gleiche IDs. Die Mandanten-IDs entsprechen den Testidentitäten
aus infra/keycloak (WP-M0-09).
"""
import hashlib
import json
import uuid

from .db import migrator_conn, runtime_conn, set_tenant

SEED_NAME, SEED_VERSION = "two-tenants", "1.0.0"
TENANTS = {"A": uuid.UUID("00000000-0000-4000-8000-00000000000a"), "B": uuid.UUID("00000000-0000-4000-8000-00000000000b")}
NS = uuid.UUID("7a6b5c4d-0000-4000-8000-0000000000a7")
ISSUER = "http://localhost:18080/realms/taktovia-dev"   # nur lokal (Compose-Keycloak, WP-M0-08/-09)
ROLES = ("passenger", "dispatcher", "driver", "finance", "tenant_admin")
COST_CENTER_REF = "KST-1000"                            # in A und B identisch benannt (PH-AT-001)
SEEDED_TABLES = ["legal_entity", "organization", "membership", "passenger_private", "vehicle", "expense_document", "cost_object"]
OWN_TENANT_TABLE = "tenant"


def _id(label, kind, extra=""):
    return uuid.uuid5(NS, f"{label}/{kind}/{extra}")


def fixture_ids():
    out = {}
    for label in TENANTS:
        d = {"tenant": TENANTS[label]}
        for k in ("legal_entity", "organization", "passenger", "vehicle", "expense_document", "cost_object"):
            d[k] = _id(label, k)
        for r in ROLES:
            d["membership_" + r] = _id(label, "membership", r)
        out[label] = d
    # ab.dispatcher ist Mitglied in beiden Mandanten (Identität aus WP-M0-09)
    for label in TENANTS:
        out[label]["membership_ab_dispatcher"] = _id(label, "membership", "ab.dispatcher")
    return out


def test_identities():
    """Rollen und Identitäten je Mandant (WP-M0-09). Der Benutzername dient bis zum Keycloak-Import als subject."""
    return {label: {r: f"{label.lower()}.{r}" for r in ROLES} | {"dispatcher_ab": "ab.dispatcher"} for label in TENANTS}


def _seed_tenant_rows(dbname):
    """Mandantenzeilen unter Migrator + transaktionslokalem Kontext (kein Superuser, kein BYPASSRLS)."""
    done = []
    for label, tid in TENANTS.items():
        c = migrator_conn(dbname)
        try:
            set_tenant(c, tid)
            who = c.execute("SELECT current_user, (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user)").fetchone()
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE') ON CONFLICT (id) DO NOTHING",
                      (tid, f"Mandant {label} (synthetisch)"))
            c.commit()
            done.append(who)
        finally:
            c.close()
    return done


def _seed_runtime_rows(dbname, label, ids):
    tid = TENANTS[label]
    c = runtime_conn(dbname)
    try:
        set_tenant(c, tid)
        who = c.execute("SELECT current_user, (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user)").fetchone()
        x = c.execute
        x("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,%s,'DE') ON CONFLICT DO NOTHING",
          (tid, ids["legal_entity"], f"Rechtsträger {label} (synthetisch)"))
        x("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
          (tid, ids["organization"], ids["legal_entity"], f"Betreiber {label} (synthetisch)", f"fed-org-{label.lower()}", ["TRANSPORT_COMPANY"]))
        for r in ROLES:
            x("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
              (tid, ids["membership_" + r], ISSUER, f"{label.lower()}.{r}", r))
        x("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
          (tid, ids["membership_ab_dispatcher"], ISSUER, "ab.dispatcher", "dispatcher"))
        x("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,'2030-01-01T00:00:00Z') ON CONFLICT DO NOTHING",
          (tid, ids["passenger"], f"synthetic-token-{label.lower()}-passenger"))
        x("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,%s,%s) ON CONFLICT DO NOTHING",
          (tid, ids["vehicle"], f"fed-org-{label.lower()}", json.dumps({"seats": 4, "wheelchair": 1})))
        sha = hashlib.sha256(f"synthetischer-beleg-{label}".encode()).hexdigest()
        x("INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at) "
          "VALUES (%s,%s,%s,%s,%s,'application/pdf','RECEIVED',1,%s,'2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING",
          (tid, ids["expense_document"], ids["legal_entity"], f"synthetic/{label.lower()}/beleg-0001.pdf", sha, f"{label.lower()}.finance"))
        x("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref) VALUES (%s,%s,%s,'COST_CENTER',%s) ON CONFLICT DO NOTHING",
          (tid, ids["cost_object"], ids["legal_entity"], COST_CENTER_REF))
        c.commit()
        return who
    finally:
        c.close()


def data_state(dbname):
    """Hash über die geseedeten Zeilen (Datenstand). Reihenfolge und Inhalt deterministisch."""
    h = hashlib.sha256()
    ids = fixture_ids()
    for label, tid in TENANTS.items():
        c = runtime_conn(dbname)
        try:
            set_tenant(c, tid)
            own = [str(v) for v in ids[label].values()]
            for t in [OWN_TENANT_TABLE] + SEEDED_TABLES:
                col = "id"
                rows = c.execute(f"SELECT row_to_json(t)::text FROM taktovia.{t} t WHERE {col} = ANY(%s::uuid[]) ORDER BY {col}", (own,)).fetchall()
                h.update(json.dumps([t, [json.loads(r[0]) for r in rows]], sort_keys=True).encode())
        finally:
            c.close()
    return h.hexdigest()


def seed_two_tenants(dbname):
    """Legt Mandanten A/B an (idempotent). Rückgabe: Protokoll des Seed-Verfahrens inkl. Datenstand-Hash."""
    ids = fixture_ids()
    tenant_exec = _seed_tenant_rows(dbname)
    runtime_exec = [_seed_runtime_rows(dbname, label, ids[label]) for label in TENANTS]
    return {"seed": SEED_NAME, "seed_version": SEED_VERSION, "tenant_creation_role": sorted({w[0] for w in tenant_exec}),
            "data_role": sorted({w[0] for w in runtime_exec}),
            "privileged": any(w[1] for w in tenant_exec + runtime_exec),
            "tenants": {k: str(v) for k, v in TENANTS.items()}, "data_state_sha256": data_state(dbname)}
