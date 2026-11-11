"""WP-M1-40 (LH-6.18-SEC-012): Trennung von Test- und Produktionsdaten.

Umgebungskennung in der Datenbank (`taktovia_meta.environment_marker`, einmalig gesetzt, unveränderlich), Importprüfung für Dumps
(`db/dumpguard.py`), synthetischer Seed (`infra/seed/seed_dev.py`). Die Tests legen eigene Hilfsdatenbanken `<TAKTOVIA_DB_NAME>_envt_*` an
(echte PostgreSQL-Datenbanken, echte Dumps mit pg_dump) und verwerfen sie danach; die Datenbank der übrigen Tests bleibt unberührt.
"""
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import contextmanager
from pathlib import Path

import psycopg

from helpers import admin, dbtool, migrator, runtime

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(REPO / 'db'))
sys.path.insert(0, str(REPO / 'infra' / 'seed'))
import dumpguard  # noqa: E402

BASE = os.environ.get('TAKTOVIA_DB_NAME', 'taktovia')
SCRATCH_MARK = '_envt_'


@contextmanager
def _env(**kv):
    old = {k: os.environ.get(k) for k in kv}
    os.environ.update({k: v for k, v in kv.items() if v is not None})
    try:
        yield
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def scratch_db(suffix, environment):
    """Frische, migrierte Datenbank mit Umgebungskennung `environment` (nur Hilfsdatenbanken mit `_envt_` im Namen)."""
    name = f'{BASE}{SCRATCH_MARK}{suffix}'
    assert SCRATCH_MARK in name
    with _env(TAKTOVIA_DB_NAME=name, TAKTOVIA_ENVIRONMENT=environment):
        _quiet(dbtool.reset)
        _quiet(dbtool.migrate)
    return name


def drop_scratch(name):
    assert SCRATCH_MARK in name
    with dbtool.admin('postgres') as c:
        c.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')


def _quiet(fn):
    import contextlib, io
    with contextlib.redirect_stdout(io.StringIO()):
        fn()


def pg_dump(dbname, out, fmt='c'):
    env = dict(os.environ, PGPASSWORD=os.environ['TAKTOVIA_DB_ADMIN_PASSWORD'])
    subprocess.run(['pg_dump', '-h', os.environ.get('TAKTOVIA_DB_HOST', '127.0.0.1'), '-p', os.environ.get('TAKTOVIA_DB_PORT', '5432'),
                    '-U', os.environ.get('TAKTOVIA_DB_ADMIN_USER', 'dev_admin'), '-F', fmt, '-f', str(out), dbname], check=True, env=env)


def marker(dbname):
    with dbtool.admin(dbname) as c:
        r = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
    return [x[0] for x in r]


class Umgebungskennung(unittest.TestCase):
    def test_kennung_der_datenbank_gesetzt_unveraenderlich_und_fuer_runtime_lesbar(self):
        self.assertEqual(marker(BASE), ['development'])
        r = runtime()
        try:
            self.assertEqual(r.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall(), [('development',)])
            r.rollback()
            for sql in ("UPDATE taktovia_meta.environment_marker SET environment='production'", 'DELETE FROM taktovia_meta.environment_marker',
                        "INSERT INTO taktovia_meta.environment_marker(environment) VALUES ('production')", 'TRUNCATE taktovia_meta.environment_marker'):
                with self.assertRaises(psycopg.errors.Error, msg=sql):
                    r.execute(sql)
                r.rollback()
        finally:
            r.close()
        # auch Migrator und Administrator können die Kennung nicht umschreiben, löschen oder ergänzen (Trigger, nicht nur Rechte)
        for conn in (migrator(), admin()):
            with conn:
                for sql in ("UPDATE taktovia_meta.environment_marker SET environment='production'", 'DELETE FROM taktovia_meta.environment_marker',
                            "INSERT INTO taktovia_meta.environment_marker(singleton, environment) VALUES (false, 'production')", 'TRUNCATE taktovia_meta.environment_marker'):
                    with self.assertRaises(psycopg.errors.Error, msg=sql):
                        conn.execute(sql)
        self.assertEqual(marker(BASE), ['development'])

    def test_migrate_mit_fremder_umgebungskennung_scheitert(self):
        name = scratch_db('mark', 'staging')
        try:
            self.assertEqual(marker(name), ['staging'])
            with _env(TAKTOVIA_DB_NAME=name, TAKTOVIA_ENVIRONMENT='production'):
                with self.assertRaises(SystemExit) as e:
                    _quiet(dbtool.migrate)
            self.assertIn('Umgebungskennung', str(e.exception))
            self.assertEqual(marker(name), ['staging'])
            with _env(TAKTOVIA_DB_NAME=name, TAKTOVIA_ENVIRONMENT='testing'):
                with self.assertRaises(SystemExit):
                    _quiet(dbtool.migrate)
        finally:
            drop_scratch(name)


class DumpImport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='taktovia-dump-'))
        cls.prod = scratch_db('prod', 'production')
        cls.dev = scratch_db('dev', 'development')
        cls.prod_dump, cls.prod_plain, cls.dev_dump = cls.tmp / 'prod.dump', cls.tmp / 'prod.sql', cls.tmp / 'dev.dump'
        pg_dump(cls.prod, cls.prod_dump)
        pg_dump(cls.prod, cls.prod_plain, 'p')
        pg_dump(cls.dev, cls.dev_dump)

    @classmethod
    def tearDownClass(cls):
        for n in (cls.prod, cls.dev):
            drop_scratch(n)
        for f in cls.tmp.iterdir():
            f.unlink()
        cls.tmp.rmdir()

    def _refused(self, path, db):
        with self.assertRaises(dumpguard.ImportRefused) as e:
            dumpguard.import_dump(path, db)
        return str(e.exception)

    def test_produktions_dump_import_wird_abgewiesen(self):
        target = scratch_db('target', 'development')
        try:
            with dbtool.admin(target) as c:
                before = c.execute('SELECT count(*) FROM taktovia.tenant').fetchone()[0]
            self.assertEqual(dumpguard.inspect_dump(self.prod_dump), 'production')
            self.assertEqual(dumpguard.inspect_dump(self.prod_plain), 'production')
            for dump in (self.prod_dump, self.prod_plain):  # Custom- und Klartextformat
                msg = self._refused(dump, target)
                self.assertIn('Produktion', msg)
            # auch über das Werkzeug (Kommandozeile): Ausgang ungleich 0, kein Import
            for name in ('staging', 'performance'):
                t2 = scratch_db(name, name)
                try:
                    p = subprocess.run([sys.executable, str(REPO / 'db/db.py'), 'import-dump', str(self.prod_dump)], capture_output=True, text=True,
                                       env=dict(os.environ, TAKTOVIA_DB_NAME=t2))
                    self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
                    self.assertIn('abgewiesen', p.stderr)
                finally:
                    drop_scratch(t2)
            with dbtool.admin(target) as c:
                self.assertEqual(c.execute('SELECT count(*) FROM taktovia.tenant').fetchone()[0], before)
            self.assertEqual(marker(target), ['development'])
            # Produktion als Ziel gehört nicht zu diesem Werkzeug (Wiederherstellung: WP-M1-39)
            self.assertIn('Produktion', self._refused(self.prod_dump, self.prod))
            self.assertIn('Produktion', self._refused(self.dev_dump, self.prod))
        finally:
            drop_scratch(target)

    def test_zwei_parallele_dump_importe_werden_beide_abgewiesen(self):
        target = scratch_db('par', 'development')
        try:
            results, barrier = [], threading.Barrier(2)

            def attempt():
                barrier.wait()
                try:
                    dumpguard.import_dump(self.prod_dump, target)
                    results.append('importiert')
                except dumpguard.ImportRefused as e:
                    results.append(f'abgewiesen: {e}')

            threads = [threading.Thread(target=attempt) for _ in range(2)]
            [t.start() for t in threads]
            [t.join(60) for t in threads]
            self.assertEqual(len(results), 2)
            self.assertTrue(all(r.startswith('abgewiesen') and 'Produktion' in r for r in results), results)
            with dbtool.admin(target) as c:
                self.assertEqual(c.execute('SELECT count(*) FROM taktovia.tenant').fetchone()[0], 0)
            self.assertEqual(marker(target), ['development'])
        finally:
            drop_scratch(target)

    def test_dump_ohne_kennung_oder_unlesbar_wird_abgewiesen(self):
        target = scratch_db('unm', 'development')
        try:
            # Dump ohne Umgebungskennung (Tabelle fehlt oder Zeile entfernt): Herkunft unbekannt -> abgewiesen, nicht durchgelassen
            plain = self.tmp / 'ohne.sql'
            text = self.prod_plain.read_text(encoding='utf-8')
            plain.write_text('\n'.join(l for l in text.splitlines() if 'environment_marker' not in l and not l.strip().startswith('t\tproduction')), encoding='utf-8')
            self.assertIsNone(dumpguard.inspect_dump(plain))
            self.assertIn('Umgebungskennung', self._refused(plain, target))
            junk = self.tmp / 'kaputt.dump'
            junk.write_bytes(b'PGDMP\x01\x0e\x00 kaputt')
            self.assertIn('Umgebungskennung', self._refused(junk, target))
            self.assertIn('nicht', self._refused(self.tmp, target))  # Verzeichnisformat nicht prüfbar
            plain.unlink(); junk.unlink()
        finally:
            drop_scratch(target)

    def test_nichtproduktions_dump_in_nichtproduktion_wird_importiert(self):
        # Positivfall: die Sperre trifft nur Produktionsdumps. Entwicklungsdump (synthetisch geseedet) -> frische Entwicklungsdatenbank.
        sys.path.insert(0, str(REPO / 'tests' / 'acceptance'))
        from taktovia_at import seed
        seed.seed_two_tenants(self.dev)
        src_dump = self.tmp / 'dev-seeded.dump'
        pg_dump(self.dev, src_dump)
        target = scratch_db('imp', 'staging')
        try:
            self.assertEqual(dumpguard.inspect_dump(src_dump), 'development')
            dumpguard.import_dump(src_dump, target)
            with dbtool.admin(target) as c:
                self.assertEqual(c.execute('SELECT count(*) FROM taktovia.tenant').fetchone()[0], 2)
            self.assertEqual(marker(target), ['staging'])  # Kennung des Ziels bleibt, die des Dumps wird nicht übernommen
        finally:
            drop_scratch(target)
            src_dump.unlink()


class Seed(unittest.TestCase):
    def test_seed_enthaelt_nur_synthetische_daten(self):
        name = scratch_db('seed', 'development')
        try:
            r = subprocess.run([sys.executable, str(REPO / 'infra/seed/seed_dev.py')], capture_output=True, text=True, env=dict(os.environ, TAKTOVIA_DB_NAME=name))
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            from synthetic_scan import scan
            self.assertEqual(scan(name), [])
            with dbtool.admin(name) as c:
                self.assertEqual(c.execute('SELECT count(*) FROM taktovia.tenant').fetchone()[0], 2)
        finally:
            drop_scratch(name)

    def test_seed_laeuft_nicht_gegen_produktion_oder_fremde_umgebung(self):
        for env in ('production', 'staging', 'performance'):
            name = scratch_db('seedp', env)
            try:
                r = subprocess.run([sys.executable, str(REPO / 'infra/seed/seed_dev.py')], capture_output=True, text=True, env=dict(os.environ, TAKTOVIA_DB_NAME=name))
                self.assertEqual(r.returncode, 2, f'{env}: {r.stdout}{r.stderr}')
                self.assertIn('Entwicklung', r.stderr)
                with dbtool.admin(name) as c:
                    self.assertEqual(c.execute('SELECT count(*) FROM taktovia.tenant').fetchone()[0], 0)
            finally:
                drop_scratch(name)

    def test_entwicklungsdatenbank_enthaelt_keine_echten_fahrgastdaten(self):
        # Der Scanner erkennt echte Daten: Herkunft laut Matrix = synthetisch; alles Personenbezogene trägt die Synthetik-Markierung.
        name = scratch_db('scan', 'development')
        try:
            subprocess.run([sys.executable, str(REPO / 'infra/seed/seed_dev.py')], check=True, capture_output=True, env=dict(os.environ, TAKTOVIA_DB_NAME=name))
            from synthetic_scan import scan
            self.assertEqual(scan(name), [])
            # eingeschleuste "echte" Fahrgastdaten werden gefunden (Negativfall)
            real_tenant = '00000000-0000-4000-8000-0000000000ff'
            with dbtool.admin(name) as c:
                c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'Stadtwerke Beispielstadt','ACTIVE')", (real_tenant,))
                c.execute("SELECT set_config('app.tenant_id', %s, false)", (real_tenant,))
                c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until,contact_ciphertext) VALUES (%s, gen_random_uuid(), 'kunde-4711', now(), '\\x0102'::bytea)",
                          (real_tenant,))
            found = scan(name)
            self.assertTrue(any('passenger_private' in f for f in found), found)
            self.assertTrue(any('tenant' in f for f in found), found)
            # und: ein Datenbestand in einer als Produktion gekennzeichneten Datenbank gilt nie als Entwicklungsdatenbank
            self.assertTrue(any('Umgebungskennung' in f for f in scan(scratch_db('scanp', 'production'))), 'Produktionskennung')
        finally:
            drop_scratch(name)
            drop_scratch(f'{BASE}{SCRATCH_MARK}scanp')


if __name__ == '__main__':
    unittest.main()
