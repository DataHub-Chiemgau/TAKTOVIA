#!/usr/bin/env python3
"""Datenbankwerkzeug für lokale Umgebung und CI (WP-M0-15/16).

  db.py bootstrap   als Administrator: Rollen, Datenbank, PostGIS (einmalig, idempotent)
  db.py migrate     als Migrator: V*.sql in Versionsreihenfolge, R__*.sql bei geänderter Prüfsumme
  db.py reset       als Administrator: Datenbank verwerfen und neu bootstrappen
  db.py provision-system-tenant   als Administrator: reservierten Systemmandanten für Auditereignisse ohne Mandant anlegen (WP-M1-12, idempotent)
  db.py import-dump <datei>   Dump nach Importpruefung einspielen (WP-M1-40: Produktionsdump nie in Nicht-Produktion), siehe dumpguard.py

Konfiguration ausschließlich über Umgebungsvariablen (keine Secrets im Repository):
  TAKTOVIA_DB_HOST/PORT/NAME, TAKTOVIA_DB_ADMIN_USER/PASSWORD,
  TAKTOVIA_DB_MIGRATOR_PASSWORD, TAKTOVIA_DB_RUNTIME_PASSWORD, TAKTOVIA_DB_FIELDS_PASSWORD (Modulzugang sensible Felder, WP-M1-31),
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
ROLES = ('taktovia_migrator', 'taktovia_runtime', 'taktovia_fields')
# Modulzugänge der M4-Module (WP-M4-64): eigene Anmeldung je Modul, DML nur auf die eigenen Tabellen (R__runtime_grants_m4_64.sql)
MODULE_ROLES = {'taktovia_mod_stafftime': 'STAFFTIME', 'taktovia_mod_docsfin': 'DOCSFIN', 'taktovia_mod_analytics': 'ANALYTICS'}
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


def fields_password(role_exists=False):
    """Passwort des Modulzugangs für sensible Felder (WP-M1-31, `taktovia_fields`): ausdrücklich `TAKTOVIA_DB_FIELDS_PASSWORD`.
    Nur außerhalb von Staging/Produktion wird ersatzweise ein Wert aus dem Runtime-Passwort abgeleitet (Entwicklung/Test, kein Klartext im
    Repository). In Staging/Produktion ohne ausdrückliches Passwort bleibt eine vorhandene Rolle unverändert (None), eine fehlende bricht ab:
    die Runtime-Rolle darf den Modulzugang nie erraten können."""
    explicit = os.environ.get('TAKTOVIA_DB_FIELDS_PASSWORD')
    if explicit:
        return explicit
    if os.environ.get('TAKTOVIA_ENVIRONMENT', 'development') in ('staging', 'production'):
        if role_exists:
            return None
        sys.exit('Umgebungsvariable TAKTOVIA_DB_FIELDS_PASSWORD fehlt (Modulzugang für sensible Felder)')
    return hashlib.sha256(('taktovia_fields:' + env('TAKTOVIA_DB_RUNTIME_PASSWORD')).encode()).hexdigest()


def module_password(role, role_exists=False):
    """Passwort eines Modulzugangs (WP-M4-64): ausdrücklich `TAKTOVIA_DB_MODULE_PASSWORD_<MODUL>`; nur außerhalb von Staging/Produktion
    ersatzweise aus dem Runtime-Passwort abgeleitet, in Staging/Produktion bleibt eine vorhandene Rolle unverändert, eine fehlende bricht ab."""
    explicit = os.environ.get('TAKTOVIA_DB_MODULE_PASSWORD_' + MODULE_ROLES[role])
    if explicit:
        return explicit
    if os.environ.get('TAKTOVIA_ENVIRONMENT', 'development') in ('staging', 'production'):
        if role_exists:
            return None
        sys.exit(f'Umgebungsvariable TAKTOVIA_DB_MODULE_PASSWORD_{MODULE_ROLES[role]} fehlt (Modulzugang {role})')
    return hashlib.sha256((role + ':' + env('TAKTOVIA_DB_RUNTIME_PASSWORD')).encode()).hexdigest()


def bootstrap():
    name = env('TAKTOVIA_DB_NAME', 'taktovia')
    with admin('postgres') as c:
        fields_exists = bool(c.execute("SELECT 1 FROM pg_roles WHERE rolname='taktovia_fields'").fetchone())
        for role, pw in (('taktovia_migrator', env('TAKTOVIA_DB_MIGRATOR_PASSWORD')),
                         ('taktovia_runtime', env('TAKTOVIA_DB_RUNTIME_PASSWORD')),
                         ('taktovia_fields', fields_password(fields_exists))):
            if pw is None:
                continue
            exists = c.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (role,)).fetchone()
            verb = 'ALTER' if exists else 'CREATE'
            c.execute(sql.SQL(verb + ' ROLE {} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOINHERIT '
                              'PASSWORD {}').format(sql.Identifier(role), sql.Literal(pw)))
        for role in MODULE_ROLES:
            exists = c.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (role,)).fetchone()
            pw = module_password(role, bool(exists))
            if pw is None:
                continue
            c.execute(sql.SQL(('ALTER' if exists else 'CREATE') + ' ROLE {} LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOINHERIT '
                              'PASSWORD {}').format(sql.Identifier(role), sql.Literal(pw)))
        for g in GROUPS:
            if not c.execute('SELECT 1 FROM pg_roles WHERE rolname=%s', (g,)).fetchone():
                c.execute(sql.SQL('CREATE ROLE {} NOLOGIN NOSUPERUSER NOBYPASSRLS').format(sql.Identifier(g)))
        if not c.execute('SELECT 1 FROM pg_database WHERE datname=%s', (name,)).fetchone():
            c.execute(sql.SQL('CREATE DATABASE {} OWNER taktovia_migrator').format(sql.Identifier(name)))
    with admin(name) as c:
        c.execute(sql.SQL('REVOKE ALL ON DATABASE {} FROM PUBLIC').format(sql.Identifier(name)))
        c.execute(sql.SQL('GRANT CONNECT ON DATABASE {} TO taktovia_migrator, taktovia_runtime, taktovia_fields, '
                           + ', '.join(MODULE_ROLES)).format(sql.Identifier(name)))
        c.execute('CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public')
        c.execute('REVOKE CREATE ON SCHEMA public FROM PUBLIC')
        c.execute('CREATE SCHEMA IF NOT EXISTS taktovia_meta AUTHORIZATION taktovia_migrator')
    print(f'bootstrap ok: {name}')


# Wiederholbare Skripte mit dieser Zeile laufen bei jedem `migrate` erneut (z. B. Rechte, die sich aus später hinzukommenden Tabellen ergeben, WP-M4-64).
ALWAYS = re.compile(r'^-- repeat: always\s*$', re.M)


def state_signature(c, files):
    """Zustand, von dem Skripte mit `-- repeat: always` abhängen: vorhandene Tabellen, Tabellenbesitz und Prüfsummen der übrigen wiederholbaren Skripte."""
    h = hashlib.sha256()
    for r in c.execute("SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='taktovia' AND c.relkind='r' ORDER BY 1"):
        h.update(r[0].encode())
    for r in c.execute('SELECT tabelle, modul FROM taktovia_meta.table_owner ORDER BY 1'):
        h.update(('|'.join(r)).encode())
    for p in files:
        if p.name.startswith('R__') and not ALWAYS.search(p.read_text()):
            h.update(f'{p.name}:{checksum(p)}'.encode())
    return int.from_bytes(h.digest()[:4], 'big') % (2 ** 30)


def checksum(p):
    return int.from_bytes(hashlib.sha256(p.read_bytes()).digest()[:4], 'big', signed=True)


def sync_table_owners(c):
    """WP-M4-64: spiegelt db/table_owners.d/*.csv nach taktovia_meta.table_owner (nur Migrator), damit Rechte der Modulzugänge aus dem Tabellenbesitz folgen
    und neue Tabellen eines Moduls keine Änderung an der Rechtedatei brauchen. Quelle der Wahrheit bleibt die CSV-Datei."""
    import csv
    c.execute('CREATE TABLE IF NOT EXISTS taktovia_meta.table_owner (tabelle text PRIMARY KEY, modul text NOT NULL)')
    rows = {}
    for f in sorted((HERE / 'table_owners.d').glob('*.csv')):
        with f.open(encoding='utf-8', newline='') as fh:
            for r in csv.DictReader(fh, delimiter=';'):
                rows[r['table']] = r['owner']
    with c.transaction():
        c.execute('DELETE FROM taktovia_meta.table_owner')
        for t, o in sorted(rows.items()):
            c.execute('INSERT INTO taktovia_meta.table_owner(tabelle, modul) VALUES (%s,%s)', (t, o))


def migrate():
    files = sorted((p for p in MIG.glob('V*__*.sql')), key=lambda p: int(re.match(r'V(\d+)__', p.name).group(1)))
    files += sorted(MIG.glob('R__*.sql'))
    done = []
    with conn('taktovia_migrator', env('TAKTOVIA_DB_MIGRATOR_PASSWORD')) as c:
        c.execute('CREATE TABLE IF NOT EXISTS taktovia_meta.schema_history ('
                  'script text PRIMARY KEY, checksum integer NOT NULL, installed_on timestamptz NOT NULL DEFAULT now())')
        applied = dict(c.execute('SELECT script, checksum FROM taktovia_meta.schema_history').fetchall())
        sync_table_owners(c)
        for p in files:
            cs = checksum(p)
            repeatable = p.name.startswith('R__')
            if repeatable and ALWAYS.search(p.read_text()):
                cs = (cs + state_signature(c, files)) % (2 ** 31)   # läuft erneut, sobald sich Tabellen, Besitz oder andere Rechtedateien ändern
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
