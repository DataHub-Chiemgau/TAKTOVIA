"""Referenzdaten für M4 (WP-M4-02): Fixture-Dateien, deterministische IDs und Laden des DB-Seeds unter der Migrator-Rolle.

Der Seed (`fixtures/m4_referenz/seed.sql`) ist kein Modulcode. Er läuft unter `taktovia_migrator` (kein Superuser, kein BYPASSRLS) und setzt je Mandant
transaktionslokal den Tenantkontext. Alle IDs sind `md5('m4ref/<mandant>/<schlüssel>')::uuid` (Mandant `a` oder `b`); `fixture_id` berechnet dieselben Werte.
"""
import hashlib
import json
import uuid
from pathlib import Path

from .db import migrator_conn, runtime_conn, set_tenant

DIR = Path(__file__).resolve().parents[1] / "fixtures" / "m4_referenz"
TENANTS = {"a": uuid.UUID("00000000-0000-4000-8000-0000000000a4"), "b": uuid.UUID("00000000-0000-4000-8000-0000000000b4")}
TABLES = ("legal_entity", "organization", "region", "depot", "cost_object", "vehicle", "vehicle_assignment", "driver", "qualification_revision", "staff_shift",
          "service", "stop_revision", "vehicle_run", "route_stop", "capacity_hold", "booking", "position_sample", "travel_attempt")


def fixture_id(tenant_label: str, key: str) -> uuid.UUID:
    """Dieselbe ID wie `md5('m4ref/<tenant>/<key>')::uuid` im Seed."""
    return uuid.UUID(hashlib.md5(f"m4ref/{tenant_label}/{key}".encode()).hexdigest())


def _json(name):
    return json.loads((DIR / name).read_text(encoding="utf-8"))


def katalog():
    return _json("katalog.json")


def sollwerte():
    return _json("sollwerte.json")


def kontenrahmen():
    return _json("kontenrahmen.json")


def steuerregel():
    return _json("steuerregel.json")


def musterfahrt():
    return _json("musterfahrt.json")


def dst_stichtage():
    return _json("dst-stichtage.json")


def ocr_szenarien():
    return _json("ocr-szenarien.json")


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
            out[label] = {t: c.execute(f"SELECT count(*) FROM taktovia.{t}").fetchone()[0] for t in TABLES}
        finally:
            c.close()
    return out
