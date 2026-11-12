"""WP-M5-02: synthetischer Referenzdatenbestand (Generator unter der Runtime-Rolle) gegen die echte Datenbank.

Läuft mit `TAKTOVIA_DB_*` wie die DB-Tests (`db/tests`); ohne Zugangsdaten wird übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlägt der Test dann fehl.
Der Referenzumfang (30.000 Buchungen/Tag, mehrere Betriebstage) wird hier nicht gefüllt (zu groß für den Testlauf); die kleine Skala hat dieselben 250 Fahrzeuge je Mandant.
"""
import os
import random
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'db'))
sys.path.insert(0, str(REPO / 'tests' / 'performance'))
sys.path.insert(0, str(REPO / 'tests' / 'performance' / 'datagen'))

HAS_DB = bool(os.environ.get('TAKTOVIA_DB_RUNTIME_PASSWORD') and os.environ.get('TAKTOVIA_DB_ADMIN_PASSWORD'))
if not HAS_DB and os.environ.get('TAKTOVIA_REQUIRE_DB') == 'true':
    raise AssertionError('Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)')

if HAS_DB:
    import db as dbtool
    import psycopg
    from datagen import generator as G
    import fill as F


class _Recorder:
    """Verbindungsersatz ohne Datenbank: zeichnet die eingefügten Zeilen auf (für den Determinismus-Test)."""
    def __init__(self):
        self.rows = []
        self._cur = self

    def execute(self, *a, **k):
        return self

    def cursor(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def executemany(self, sql, batch):
        self.rows.append((sql, list(batch)))

    def commit(self):
        pass

    def fetchone(self):
        return None


@unittest.skipUnless(HAS_DB, 'Datenbank-Zugangsdaten nicht gesetzt')
class Referenzbestand(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed = random.randint(10_000_000, 99_999_999)      # eigener Seed je Lauf: eigene Mandanten, kein Zusammenstoß mit anderen Läufen in derselben Datenbank
        cls.proto = F.run(G.SMALL, cls.seed, provision=True)
        cls.ids = [G.tenant_id(cls.seed, 1), G.tenant_id(cls.seed, 2)]

    def test_zwei_mandanten_mit_je_250_fahrzeugen_und_zeilenzahlen_im_protokoll(self):
        self.assertTrue(self.proto['bestanden'], self.proto['pruefungen'])
        self.assertEqual(len(self.proto['mandanten']), 2)
        for m in self.proto['mandanten']:
            z = m['zeilen']
            self.assertGreaterEqual(z['vehicle'], 250); self.assertEqual(z['staff_shift'], 250 * G.SMALL.days); self.assertEqual(z['resource_release'], z['staff_shift'])
            self.assertEqual(z['booking'], G.SMALL.bookings_per_day * G.SMALL.days); self.assertEqual(z['payment_intent'], z['booking'])
            self.assertTrue(all(z[t] > 0 for t in G.TABLES), {t: n for t, n in z.items() if n == 0})   # alle befüllbaren Objekte des WP sind belegt
            self.assertEqual(len(m['datenstand_sha256']), 64)
        for key in ('seed', 'skala', 'umgebung', 'hardware', 'datenbank'):
            self.assertIn(key, self.proto)
        self.assertEqual(self.proto['umgebung']['umgebungskennung'], dbtool.env('TAKTOVIA_ENVIRONMENT', 'development'))

    def test_rls_ist_aktiv_und_stichprobe_fremder_mandant_liefert_nichts(self):
        self.assertEqual(self.proto['pruefungen']['rls_fremdzeilen_sichtbar'], 0)
        c = F.runtime_connection()
        try:
            for a, b in ((0, 1), (1, 0)):
                c.execute("SELECT set_config('app.tenant_id', %s, true)", (self.ids[a],))
                for t in ('vehicle', 'booking', 'driver', 'staff_shift', 'fare_quote', 'position_sample'):
                    self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id = %s', (self.ids[b],)).fetchone()[0], 0, t)
                    self.assertGreater(c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0], 0, t)
                c.rollback()
            # ohne Tenantkontext sieht die Runtime-Rolle keine Zeile
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle').fetchone()[0], 0)
        finally:
            c.close()

    def test_parallele_befuellung_beider_mandanten_ohne_fremdreferenzen(self):
        # beide Mandanten wurden in setUpClass parallel befüllt; kein Datensatz gehört zu beiden Mandanten, und die zusammengesetzten Fremdschlüssel (tenant_id, id) halten die Trennung
        with dbtool.admin(dbtool.env('TAKTOVIA_DB_NAME')) as a:
            for t in G.TABLES:
                both = a.execute(f'SELECT count(*) FROM (SELECT id FROM taktovia.{t} WHERE tenant_id = %s INTERSECT SELECT id FROM taktovia.{t} WHERE tenant_id = %s) x', tuple(self.ids)).fetchone()[0]
                self.assertEqual(both, 0, t)
            cross = a.execute("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.id = b.hold_id AND h.tenant_id <> b.tenant_id WHERE b.tenant_id = ANY(%s::uuid[])", (self.ids,)).fetchone()[0]
            self.assertEqual(cross, 0)
            cross = a.execute("SELECT count(*) FROM taktovia.staff_shift s JOIN taktovia.vehicle v ON v.id = s.vehicle_id AND v.tenant_id <> s.tenant_id WHERE s.tenant_id = ANY(%s::uuid[])", (self.ids,)).fetchone()[0]
            self.assertEqual(cross, 0)

    def test_befuellung_mit_migrator_oder_superuser_wird_abgewiesen(self):
        for label, conn in (('migrator', dbtool.conn('taktovia_migrator', dbtool.env('TAKTOVIA_DB_MIGRATOR_PASSWORD'), autocommit=False)), ('superuser', dbtool.admin(dbtool.env('TAKTOVIA_DB_NAME')))):
            with self.subTest(label):
                with self.assertRaises(G.ForbiddenConnection):
                    G.fill_tenant(conn, G.tenant_id(self.seed + 1, 1), self.seed + 1, G.SMALL, 1)
                conn.rollback()
                conn.close()
        with dbtool.admin(dbtool.env('TAKTOVIA_DB_NAME')) as a:
            self.assertEqual(a.execute('SELECT count(*) FROM taktovia.vehicle WHERE tenant_id = %s', (G.tenant_id(self.seed + 1, 1),)).fetchone()[0], 0, 'nichts geschrieben')

    def test_umgebungskennung_ausserhalb_der_erlaubten_wird_abgewiesen(self):
        c = F.runtime_connection()
        try:
            with self.assertRaises(G.ForbiddenConnection):
                G.require_runtime_connection(c, allow_environments=('staging',) if dbtool.env('TAKTOVIA_ENVIRONMENT', 'development') != 'staging' else ('performance',))
        finally:
            c.close()

    def test_keine_echten_kontaktdaten_und_die_pruefung_erkennt_fremde_daten(self):
        self.assertEqual(self.proto['pruefungen']['synthetikfunde'], 0)
        c = F.runtime_connection()
        try:
            self.assertEqual(G.scan_synthetic(c, self.ids[0]), [])
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (self.ids[0],))
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,gen_random_uuid(),'max.mustermann@example.org',now()+interval '1 day')", (self.ids[0],))
            c.commit()
            self.assertEqual(G.scan_synthetic(c, self.ids[0]), ["'max.mustermann@example.org'"])
        finally:
            c.close()
        with dbtool.admin(dbtool.env('TAKTOVIA_DB_NAME')) as a:
            a.execute("DELETE FROM taktovia.passenger_private WHERE subject_token = 'max.mustermann@example.org' AND tenant_id = %s", (self.ids[0],))

    def test_mandant_mit_gleichem_seed_ist_nicht_noch_einmal_befuellbar(self):
        c = F.runtime_connection()
        try:
            with self.assertRaises(psycopg.errors.UniqueViolation):
                G.fill_tenant(c, self.ids[0], self.seed, G.SMALL, 1)
            c.rollback()
        finally:
            c.close()


class Determinismus(unittest.TestCase):
    """Ohne Datenbank: gleicher Seed und gleiche Skala ergeben denselben Datenstand."""

    @staticmethod
    def _run(seed):
        import datagen.generator as G2
        orig = G2.require_runtime_connection
        G2.require_runtime_connection = lambda *a, **k: None
        try:
            rec = _Recorder() if HAS_DB else None
            if rec is None:
                from datagen import generator as G3  # noqa: F401
            return G2.fill_tenant(rec, G2.tenant_id(seed, 1), seed, G2.SMALL, 1), rec
        finally:
            G2.require_runtime_connection = orig

    @unittest.skipUnless(HAS_DB, 'Generator wird nur mit installiertem psycopg importiert')
    def test_gleicher_seed_gleicher_datenstand_anderer_seed_anderer(self):
        a, ra = self._run(42); b, rb = self._run(42); c, _ = self._run(43)
        self.assertEqual(a.data_state_sha256, b.data_state_sha256); self.assertEqual(ra.rows, rb.rows)
        self.assertNotEqual(a.data_state_sha256, c.data_state_sha256)
        self.assertEqual(a.rows['vehicle'], 250)


if __name__ == '__main__':
    unittest.main()
