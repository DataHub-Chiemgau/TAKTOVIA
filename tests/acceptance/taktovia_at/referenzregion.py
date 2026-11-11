"""Referenzregion für M2 (WP-M2-02): Fixture-Dateien, deterministische IDs und Laden des DB-Seeds unter der Migrator-Rolle.

Der Seed (`fixtures/m2_referenzregion/seed.sql`) ist kein Modulcode. Er läuft unter `taktovia_migrator` (kein Superuser, kein BYPASSRLS) und setzt je Mandant
transaktionslokal den Tenantkontext. Alle IDs sind `md5('m2ref/<mandant>/<schlüssel>')::uuid` (Mandant `a` oder `b`); `fixture_id` berechnet dieselben Werte.
"""
import hashlib
import json
import uuid
from pathlib import Path

from .db import migrator_conn, runtime_conn, set_tenant

DIR = Path(__file__).resolve().parents[1] / "fixtures" / "m2_referenzregion"
TENANTS = {"a": uuid.UUID("00000000-0000-4000-8000-00000000000a"), "b": uuid.UUID("00000000-0000-4000-8000-00000000000b")}
FIXTURE_NOW = "2026-12-01T05:00:00Z"
WINDOW = ("2026-12-01T06:00:00Z", "2026-12-01T07:00:00Z")


def fixture_id(tenant_label: str, key: str) -> uuid.UUID:
    """Dieselbe ID wie `md5('m2ref/<tenant>/<key>')::uuid` im Seed."""
    return uuid.UUID(hashlib.md5(f"m2ref/{tenant_label}/{key}".encode()).hexdigest())


def _json(name):
    return json.loads((DIR / name).read_text(encoding="utf-8"))


def region():
    return _json("region.json")


def graph():
    return _json("strassengraph.json")


def expectations():
    return _json("erwartete-fahrzeiten.json")


def tariff_relations():
    return _json("tarif-testrelationen.json")


def passengers():
    return _json("fahrgaeste.json")


def load(dbname: str) -> dict:
    """Lädt den Seed unter der Migrator-Rolle (idempotent). Rückgabe: Rolle und Zeilenzahlen je Mandant (gelesen unter der Runtime-Rolle)."""
    sql = (DIR / "seed.sql").read_text(encoding="utf-8")
    c = migrator_conn(dbname)
    try:
        who = c.execute("SELECT current_user, (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user)").fetchone()
        c.execute(sql)
        c.commit()
    finally:
        c.close()
    return {"role": who[0], "privileged": who[1], "counts": counts(dbname)}


def counts(dbname: str) -> dict:
    out = {}
    for label, tid in TENANTS.items():
        c = runtime_conn(dbname)
        try:
            set_tenant(c, tid)
            out[label] = {t: c.execute(f"SELECT count(*) FROM taktovia.{t}").fetchone()[0]
                          for t in ("service", "service_area", "stop_revision", "vehicle", "staff_shift", "resource_release", "vehicle_pool_assignment")}
        finally:
            c.close()
    return out
