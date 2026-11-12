"""WP-M5-02, Kriterium 1: Aufbau der Umgebung aus dem Repository ist reproduzierbar (zweiter Aufbau = derselbe Migrationsstand), Produktion ist ausgeschlossen.
Läuft lokal gegen die PostgreSQL der Testumgebung (zwei Wegwerf-Datenbanken); ein Aufbau in einer gehosteten Umgebung ist **nicht ausgeführt** (D-05)."""
import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
HAS_DB = bool(os.environ.get('TAKTOVIA_DB_ADMIN_PASSWORD') and os.environ.get('TAKTOVIA_DB_RUNTIME_PASSWORD'))
if not HAS_DB and os.environ.get('TAKTOVIA_REQUIRE_DB') == 'true':
    raise AssertionError('Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)')


def run(args, env):
    return subprocess.run(args, cwd=REPO, env=env, capture_output=True, text=True)


@unittest.skipUnless(HAS_DB, 'Datenbank-Zugangsdaten nicht gesetzt')
class Umgebungsaufbau(unittest.TestCase):
    NAMES = ('taktovia_m502_a', 'taktovia_m502_b')

    def env(self, name, environment='staging'):
        e = dict(os.environ); e['TAKTOVIA_DB_NAME'] = name; e['TAKTOVIA_ENVIRONMENT'] = environment
        return e

    def test_zweiter_aufbau_ergibt_denselben_migrationsstand_und_die_umgebungskennung(self):
        states = []
        for name in self.NAMES:
            e = self.env(name)
            for step in ('reset', 'migrate'):
                r = run([sys.executable, 'db/db.py', step], e)
                self.assertEqual(r.returncode, 0, r.stderr[-500:])
            r = run([sys.executable, 'infra/umgebungen/migrationsstand.py'], e)
            self.assertEqual(r.returncode, 0, r.stderr[-500:]); states.append(json.loads(r.stdout))
        self.assertEqual(states[0]['sha256'], states[1]['sha256'], 'derselbe Migrationsstand')
        self.assertEqual({s['umgebungskennung'] for s in states}, {'staging'}); self.assertGreater(states[0]['migrationen'], 100)

    def test_aufbauskript_schliesst_produktion_und_falsche_kennung_aus(self):
        e = self.env(self.NAMES[0], 'production')
        r = run(['bash', 'infra/umgebungen/aufbau.sh', 'production'], e)
        self.assertEqual(r.returncode, 2); self.assertIn('nicht zulässig', r.stderr)
        r = run(['bash', 'infra/umgebungen/aufbau.sh', 'staging'], self.env(self.NAMES[0], 'performance'))
        self.assertEqual(r.returncode, 2); self.assertIn('TAKTOVIA_ENVIRONMENT muss staging sein', r.stderr)

    @classmethod
    def tearDownClass(cls):
        sys.path.insert(0, str(REPO / 'db'))
        import db as dbtool
        import psycopg
        with dbtool.admin('postgres') as c:
            for n in cls.NAMES:
                c.execute(psycopg.sql.SQL('DROP DATABASE IF EXISTS {}').format(psycopg.sql.Identifier(n)))


if __name__ == '__main__':
    unittest.main()
