"""Gemeinsame Testhilfen (WP-M0-18): Verbindungen unter der echten Runtime-Rolle, transaktionslokaler Tenantkontext."""
import os, sys, uuid
from contextlib import contextmanager
from pathlib import Path
import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import db as dbtool  # noqa: E402


def runtime():
    return dbtool.conn('taktovia_runtime', dbtool.env('TAKTOVIA_DB_RUNTIME_PASSWORD'), autocommit=False)


def fields():
    """Modulzugang für sensible Felder (WP-M1-31): nur Schlüssel- und Ciphertext-Spalten von passenger_private."""
    return dbtool.conn('taktovia_fields', dbtool.fields_password(), autocommit=False)


def module(role):
    """Modulzugang eines M4-Moduls (WP-M4-64): `taktovia_mod_stafftime`, `taktovia_mod_docsfin`, `taktovia_mod_analytics`."""
    return dbtool.conn(role, dbtool.module_password(role, True), autocommit=False)


@contextmanager
def module_tx(role, tenant):
    c = module(role)
    try:
        set_tenant(c, tenant)
        yield c
        c.commit()
    except BaseException:
        c.rollback()
        raise
    finally:
        c.close()


def migrator():
    return dbtool.conn('taktovia_migrator', dbtool.env('TAKTOVIA_DB_MIGRATOR_PASSWORD'), autocommit=True)


def admin():
    return dbtool.admin(dbtool.env('TAKTOVIA_DB_NAME', 'taktovia'))


def set_tenant(c, tenant):
    """Transaktionslokal (is_local=true): wirkt nur bis COMMIT/ROLLBACK, nie sessionweit (Kap. 04 §4.6)."""
    c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant) if tenant else '',))


@contextmanager
def tx(tenant):
    c = runtime()
    try:
        set_tenant(c, tenant)
        yield c
        c.commit()
    except BaseException:
        c.rollback()
        raise
    finally:
        c.close()


def uid():
    return uuid.uuid4()


SCHEMA = 'taktovia'


def seed_tenant(tenant, name):
    """Mandanten legt der Plattformbetrieb an (Admin); die Runtime sieht nur den eigenen (own_tenant)."""
    with admin() as c:
        c.execute('INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,%s) ON CONFLICT DO NOTHING',
                  (tenant, name, 'ACTIVE'))
        c.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")


def search_path(c):
    c.execute('SET search_path = taktovia, public')
