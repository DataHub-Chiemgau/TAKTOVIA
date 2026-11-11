"""Datenbankzugriff des Akzeptanzgerüsts: echte Rollen, transaktionslokaler Tenantkontext, eigene Testdatenbanken.

Konfiguration über Umgebungsvariablen wie in db/db.py (TAKTOVIA_DB_HOST/PORT/..., Passwörter). Die Zieldatenbank ist
TAKTOVIA_AT_DB, sonst TAKTOVIA_DB_NAME. Fremde Datenbanken werden nie gelöscht: drop_database() akzeptiert nur die
Präfixe taktovia_at_ und taktovia_spike_.
"""
import contextlib
import io
import os
from contextlib import contextmanager

import db as dbtool  # db/db.py (nur gelesen/benutzt, nicht geändert)

SAFE_PREFIXES = ("taktovia_at_", "taktovia_spike_")


def target_db() -> str:
    return os.environ.get("TAKTOVIA_AT_DB") or os.environ.get("TAKTOVIA_DB_NAME") or "taktovia"


@contextmanager
def _db_name(name):
    old = os.environ.get("TAKTOVIA_DB_NAME")
    os.environ["TAKTOVIA_DB_NAME"] = name
    try:
        yield
    finally:
        if old is None:
            os.environ.pop("TAKTOVIA_DB_NAME", None)
        else:
            os.environ["TAKTOVIA_DB_NAME"] = old


def ensure_database(name: str):
    """Wie CI: bootstrap + migrate (idempotent)."""
    with _db_name(name), contextlib.redirect_stdout(io.StringIO()):
        dbtool.bootstrap()
        dbtool.migrate()


def reset_database(name: str):
    if not name.startswith(SAFE_PREFIXES):
        raise ValueError(f"reset nur für Testdatenbanken {SAFE_PREFIXES}, nicht {name!r}")
    with _db_name(name), contextlib.redirect_stdout(io.StringIO()):
        dbtool.reset()
        dbtool.migrate()


def drop_database(name: str):
    if not name.startswith(SAFE_PREFIXES):
        raise ValueError(f"drop nur für Testdatenbanken {SAFE_PREFIXES}, nicht {name!r}")
    with dbtool.admin("postgres") as c:
        c.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')


def runtime_conn(dbname):
    return dbtool.conn("taktovia_runtime", dbtool.env("TAKTOVIA_DB_RUNTIME_PASSWORD"), dbname, autocommit=False)


def migrator_conn(dbname):
    return dbtool.conn("taktovia_migrator", dbtool.env("TAKTOVIA_DB_MIGRATOR_PASSWORD"), dbname, autocommit=False)


def set_tenant(c, tenant):
    """Transaktionslokal (is_local=true), Kap. 04 §4.6."""
    c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant) if tenant else "",))


@contextmanager
def tx(dbname, tenant, role="runtime"):
    c = runtime_conn(dbname) if role == "runtime" else migrator_conn(dbname)
    try:
        set_tenant(c, tenant)
        yield c
        c.commit()
    except BaseException:
        c.rollback()
        raise
    finally:
        c.close()
