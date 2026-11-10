#!/usr/bin/env python3
"""Datenbankwerkzeug für lokale Umgebung und CI (WP-M0-15/16).

  db.py bootstrap   als Administrator: Rollen, Datenbank, PostGIS (einmalig, idempotent)
  db.py migrate     als Migrator: V*.sql in Versionsreihenfolge, R__*.sql bei geänderter Prüfsumme
  db.py reset       als Administrator: Datenbank verwerfen und neu bootstrappen

Konfiguration ausschließlich über Umgebungsvariablen (keine Secrets im Repository):
  TAKTOVIA_DB_HOST/PORT/NAME, TAKTOVIA_DB_ADMIN_USER/PASSWORD,
  TAKTOVIA_DB_MIGRATOR_PASSWORD, TAKTOVIA_DB_RUNTIME_PASSWORD

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
    print('migriert:', ', '.join(done) if done else 'nichts zu tun')


def reset():
    name = env('TAKTOVIA_DB_NAME', 'taktovia')
    with admin('postgres') as c:
        c.execute(sql.SQL('DROP DATABASE IF EXISTS {} WITH (FORCE)').format(sql.Identifier(name)))
    bootstrap()


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    {'bootstrap': bootstrap, 'migrate': migrate, 'reset': reset}.get(cmd, lambda: sys.exit(__doc__))()
