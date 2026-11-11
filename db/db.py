#!/usr/bin/env python3
"""Datenbankwerkzeug für lokale Umgebung und CI (WP-M0-15/16).

  db.py bootstrap   als Administrator: Rollen, Datenbank, PostGIS (einmalig, idempotent)
  db.py migrate     als Migrator: V*.sql in Versionsreihenfolge, R__*.sql bei geänderter Prüfsumme
  db.py reset       als Administrator: Datenbank verwerfen und neu bootstrappen
  db.py provision-system-tenant   als Administrator: reservierten Systemmandanten für Auditereignisse ohne Mandant anlegen (WP-M1-12, idempotent)
  db.py import-dump <datei>   Dump nach Importpruefung einspielen (WP-M1-40: Produktionsdump nie in Nicht-Produktion), siehe dumpguard.py

Konfiguration ausschließlich über Umgebungsvariablen (keine Secrets im Repository):
  TAKTOVIA_DB_HOST/PORT/NAME, TAKTOVIA_DB_ADMIN_USER/PASSWORD,
  TAKTOVIA_DB_MIGRATOR_PASSWORD, TAKTOVIA_DB_RUNTIME_PASSWORD,
  TAKTOVIA_ENVIRONMENT (development|staging|performance|production, Vorgabe development): Umgebungskennung der Datenbank (WP-M1-40)

Die Fachmigrationen sind Flyway-kompatibel benannt (V<n>__name.sql, R__name.sql). Dieses Werkzeug ist ein
bewusst kleiner Übergang, bis das Gradle-Projekt (WP-M0-02) Flyway einbindet; die Historientabelle
liegt getrennt von den Fachdaten in taktovia_meta.schema_history.
"""
import hashlib, os, re, sys
from pathlib import Path
import psycopg
from psycopg import sql

HERE = Path(__file__).resolve().parent
MIG = HERE / 'migrations'
ROLES = ('taktovia_migrator', 'taktovia_runtime')
GROUPS = ('taktovia_pii', 'taktovia_hr', 'taktovia_finance')


def env(k, d=None):
    v = os.environ.get(k, d)
    if v is None:
        sys.exit(f'Umgebungsvariable {k} fehlt')
    return v


def conn(user, password, dbname=None, autocommit=True):
    return psycopg.connect(host=env('TAKTOVIA_DB_HOST', '127.0.0.1'), port=env('TAKTOVIA_DB_PORT', '5432'),
                           dbname=dbname or env('TAKTOVIA_DB_NAME', 'taktovia'), user=user,
                           password=password, autocommit=autocommit)


def admin(dbname):
    return conn(env('TAKTOVIA_DB_ADMIN_USER', 'dev_admin'), env('TAKTOVIA_DB_ADMIN_PASSWORD'), dbname)


def bootstrap():
    name = env('TAKTOVIA_DB_NAME', 'taktovia')
    with admin('postgres') as c:
        for role, pw in (('taktovia_migrator', env('TAKTOVIA_DB_MIGRATOR_PASSWORD')),
                         ('taktovia_runtime', env('TAKTOVIA_DB_RUNTIME_PASSWORD'))):
            exists = c.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (role,)).fetchone()
            verb = 'ALTER' if exists else 'CREATE'
            c.execute(sql.SQL(verb + ' ROLE {} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOINHERIT '
                              'PASSWORD {}').format(sql.Identifier(role), sql.Literal(pw)))
        for g in GROUPS:
            if not c.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (g,)).fetchone():
                c.execute(sql.SQL('CREATE ROLE {} NOLOGIN NOSUPERUSER NOBYPASSRLS').format(sql.Identifier(g)))
        if not c.execute('SELECT 1 FROM pg_database WHERE datname=%s', (name,)).fetchone():
            c.execute(sql.SQL('CREATE DATABASE {} OWNER taktovia_migrator').format(sql.Identifier(name)))
    with admin(name) as c:
        c.execute(sql.SQL('REVOKE ALL ON DATABASE {} FROM PUBLIC').format(sql.Identifier(name)))
        c.execute(sql.SQL('GRANT CONNECT ON DATABASE {} TO taktovia_migrator, taktovia_runtime').format(sql.Identifier(name)))
        c.execute('CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public')
        c.execute('REVOKE CREATE ON SCHEMA public FROM PUBLIC')
        c.execute('CREATE SCHEMA IF NOT EXISTS taktovia_meta AUTHORIZATION taktovia_migrator')
    print(f'bootstrap ok: {name}')


def checksum(p):
    return int.from_bytes(hashlib.sha256(p.read_bytes()).digest()[:4], 'big', signed=True)


def migrate():
    files = sorted((p for p in MIG.glob('V*__*.sql')), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))
    files += sorted(MIG.glob('R__*.sql'))
    done = []
    with conn('taktovia_migrator', env('TAKTOVIA_DB_MIGRATOR_PASSWORD')) as c:
        c.execute('CREATE TABLE IF NOT EXISTS taktovia_meta.schema_history ('
                  'script text PRIMARY KEY, checksum integer NOT NULL, installed_on timestamptz NOT NULL DEFAULT now())')
        applied = dict(c.execute('SELECT script, checksum FROM taktovia_meta.schema_history').fetchall())
        for p in files:
            cs = checksum(p)
            repeatable = p.name.startswith('R__')
            if p.name in applied:
                if applied[p.name] != cs and not repeatable:
                    sys.exit(f'Prüfsummenfehler: {p.name} wurde nach Auslieferung geändert')
                if applied[p.name] == cs:
                    continue
            with c.transaction():
                c.execute(p.read_text())
                c.execute('INSERT INTO taktovia_meta.schema_history(script,checksum) VALUES (%s,%s) '
                          'ON CONFLICT (script) DO UPDATE SET checksum=EXCLUDED.checksum, installed_on=now()', (p.name, cs))
            done.append(p.name)
    mark_environment()
    print('migriert:', ', '.join(done) if done else 'nichts zu tun')


ENVIRONMENTS = ('development', 'staging', 'performance', 'production')


def mark_environment():
    """WP-M1-40: setzt die unveraenderliche Umgebungskennung der Datenbank einmalig; eine abweichende Kennung bricht ab."""
    want = env('TAKTOVIA_ENVIRONMENT', 'development')
    if want not in ENVIRONMENTS:
        sys.exit(f'Umgebungskennung {want!r} unbekannt (erlaubt: {", ".join(ENVIRONMENTS)})')
    with conn('taktovia_migrator', env('TAKTOVIA_DB_MIGRATOR_PASSWORD')) as c:
        have = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchone()
        if have is None:
            c.execute('INSERT INTO taktovia_meta.environment_marker(environment) VALUES (%s)', (want,))
        elif have[0] != want:
            sys.exit(f'Umgebungskennung der Datenbank ist {have[0]!r}, angefordert {want!r}: Migration verweigert (WP-M1-40)')


def reset():
    name = env('TAKTOVIA_DB_NAME', 'taktovia')
    with admin('postgres') as c:
        c.execute(sql.SQL('DROP DATABASE IF EXISTS {} WITH (FORCE)').format(sql.Identifier(name)))
    bootstrap()


SYSTEM_TENANT = '00000000-0000-0000-0000-000000000001'


def provision_system_tenant():
    """WP-M1-12 (OP-14): legt den reservierten Systemmandanten für Auditereignisse ohne Mandantenkontext an (idempotent, Plattformbetrieb).
    Status SUSPENDED: nie Fachmandant, kein Wechsel, keine Mitgliedschaft."""
    with admin(env('TAKTOVIA_DB_NAME', 'taktovia')) as c:
        c.execute("SELECT set_config('app.tenant_id', %s, false)", (SYSTEM_TENANT,))
        c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'platform-system','SUSPENDED') ON CONFLICT (id) DO NOTHING", (SYSTEM_TENANT,))
    print('Systemmandant bereitgestellt')


def import_dump():
    import dumpguard
    sys.exit(dumpguard.main(sys.argv[2:]))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    {'bootstrap': bootstrap, 'migrate': migrate, 'reset': reset, 'import-dump': import_dump,
            'provision-system-tenant': provision_system_tenant}.get(cmd, lambda: sys.exit(__doc__))()
