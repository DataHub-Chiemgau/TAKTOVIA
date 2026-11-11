"""WP-M1-32 (LH-6.18-SEC-004): Dateninventar mit Zweck und Rechtsgrundlage; CI-Prüfung "Schema <-> Inventar".

Jede Spalte jeder Tabelle des Schemas `taktovia` steht im Inventar (db/dateninventar.d/*.csv); ein neues Feld ohne Eintrag,
ein unbekannter Zweck oder eine unbekannte Rechtsgrundlage lassen die Prüfung scheitern. Der Katalog der Zwecke und
Rechtsgrundlagen ist vorläufig und nicht freigegeben (OP-24); die Tests prüfen die Mechanik und die vollständige Zuordnung.
"""
import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path

from helpers import admin

DB = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DB))
import dateninventar as inv  # noqa: E402


def schema():
    with admin() as c:
        return inv.schema_columns(c)


class Inventarabgleich(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = inv.load_catalog()
        cls.columns, cls.tables = inv.load_inventory()

    def test_alle_spalten_klassifiziert(self):
        cols = schema()
        self.assertGreater(len(cols), 300)
        self.assertEqual(inv.check(cols, self.columns, self.tables, self.catalog), [], 'null unklassifizierte Felder erwartet')

    def test_jede_tabelle_hat_eine_loeschklasse(self):
        names = {t for t, _ in schema()}
        self.assertEqual(sorted(names - set(self.tables)), [])
        for t, row in self.tables.items():
            self.assertIn(row['loeschklasse'], self.catalog['loeschklassen'], t)

    def test_pflichtklassifikation_aus_dem_vertrag(self):
        for t, c in [('passenger_private', 'contact_ciphertext'), ('passenger_private', 'support_ciphertext'),
                     ('passenger_private', 'subject_token'), ('audit_event', 'actor_ref'), ('payment_intent', 'provider_ref'),
                     ('travel_attempt', 'coarse_origin'), ('travel_attempt', 'coarse_destination')]:
            row = self.columns[(t, c)]
            self.assertEqual(row['personenbezogen'], 'ja', (t, c))
            for feld in ('zweck', 'rechtsgrundlage', 'schutzklasse', 'loeschklasse'):
                self.assertTrue(row[feld], (t, c, feld))
        self.assertEqual(self.columns[('passenger_private', 'contact_ciphertext')]['schutzklasse'], 'KONTAKT_UNTERSTUETZUNG')
        self.assertEqual(self.columns[('travel_attempt', 'coarse_origin')]['schutzklasse'], 'STANDORT_ETA')

    def test_travel_attempt_fuehrt_nur_grobe_herkunft_und_ziel(self):
        """Befund-Reviewpunkt: die Auflösung ist am Typ `text` nicht ablesbar; die Namen sind es. Kein Feld mit feinerer Herkunft/Ziel."""
        names = {c for t, c in schema() if t == 'travel_attempt'}
        feinere = {c for c in names if any(w in c for w in ('origin', 'destination', 'lat', 'lon', 'point', 'address'))}
        self.assertEqual(feinere, {'coarse_origin', 'coarse_destination'})

    def test_katalog_ist_als_nicht_freigegeben_gekennzeichnet(self):
        self.assertIs(self.catalog['freigegeben'], False)
        self.assertIn('OP-24', self.catalog['hinweis'])

    def test_schutzklassen_enthalten_die_fuenf_klassen_aus_kapitel_1_7(self):
        for k in ('KONTAKT_UNTERSTUETZUNG', 'STANDORT_ETA', 'BELEGE_JOURNAL', 'SECURITY_AUDIT', 'AGGREGIERTE_STATISTIK'):
            self.assertIn(k, self.catalog['schutzklassen'])

    def test_ui_export_entspricht_dem_inventar(self):
        """Die Oberfläche (UI-20) liest den eingecheckten Export; er darf nicht vom Inventar abweichen."""
        path = DB.parent / 'web/operator/src/datenschutz/dateninventar.json'
        self.assertEqual(json.loads(path.read_text()), inv.export_document(self.columns, self.tables, self.catalog))

    def test_kommandozeile_check_ist_gruen(self):
        r = subprocess.run([sys.executable, str(DB / 'dateninventar.py'), 'check'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('0 unklassifiziert', r.stdout)


class Negativfaelle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = inv.load_catalog()
        cls.columns, cls.tables = inv.load_inventory()
        cls.cols = schema()

    def problems(self, columns=None, tables=None, cols=None):
        return inv.check(cols if cols is not None else self.cols, columns if columns is not None else self.columns,
                         tables if tables is not None else self.tables, self.catalog)

    def test_migration_ohne_inventareintrag_scheitert(self):
        """Echte Testmigration: neue Spalte in einer Transaktion, die Prüfung erkennt sie, danach Rollback."""
        with admin() as c:
            c.autocommit = False
            try:
                c.execute('ALTER TABLE taktovia.travel_attempt ADD COLUMN genaue_adresse text')
                found = inv.schema_columns(c)
            finally:
                c.rollback()
        self.assertIn(('travel_attempt', 'genaue_adresse'), found)
        problems = inv.check(found, self.columns, self.tables, self.catalog)
        self.assertEqual(len(problems), 1)
        self.assertIn('travel_attempt.genaue_adresse', problems[0])
        self.assertIn('ohne Inventareintrag', problems[0])
        self.assertEqual(self.problems(), [], 'nach dem Rollback ist das Schema wieder vollständig klassifiziert')

    def test_neue_tabelle_ohne_eintrag_scheitert(self):
        cols = self.cols + [('neue_tabelle', 'id'), ('neue_tabelle', 'tenant_id')]
        problems = self.problems(cols=cols)
        self.assertTrue(any('neue_tabelle.id' in p for p in problems))
        self.assertTrue(any('Tabelle neue_tabelle ohne Löschklasse' in p for p in problems))

    def test_eintrag_fuer_nicht_existierende_spalte_scheitert(self):
        columns = copy.deepcopy(self.columns)
        columns[('journey', 'gibt_es_nicht')] = dict(columns[('journey', 'state')], tabelle='journey', spalte='gibt_es_nicht')
        problems = self.problems(columns=columns)
        self.assertEqual(len(problems), 1)
        self.assertIn('journey.gibt_es_nicht', problems[0])
        self.assertIn('nicht im Schema', problems[0])

    def test_unbekannter_zweck_scheitert(self):
        columns = copy.deepcopy(self.columns)
        columns[('journey', 'state')]['zweck'] = 'WERBUNG_FUER_ALLES'
        problems = self.problems(columns=columns)
        self.assertEqual(len(problems), 1)
        self.assertIn('unbekannter Zweck', problems[0])

    def test_unbekannte_rechtsgrundlage_scheitert(self):
        columns = copy.deepcopy(self.columns)
        columns[('passenger_private', 'contact_ciphertext')]['rechtsgrundlage'] = 'ICH_DARF_DAS'
        self.assertTrue(any('unbekannte Rechtsgrundlage' in p for p in self.problems(columns=columns)))

    def test_personenbezogenes_feld_ohne_rechtsgrundlage_scheitert(self):
        columns = copy.deepcopy(self.columns)
        columns[('passenger_private', 'contact_ciphertext')]['rechtsgrundlage'] = ''
        problems = self.problems(columns=columns)
        self.assertEqual(len(problems), 1)
        self.assertIn('personenbezogen ohne Rechtsgrundlage', problems[0])

    def test_personenbezogenes_feld_ohne_schutzklasse_oder_loeschklasse_scheitert(self):
        for feld, text in (('schutzklasse', 'Schutzklasse'), ('loeschklasse', 'Löschklasse')):
            columns = copy.deepcopy(self.columns)
            columns[('audit_event', 'actor_ref')][feld] = ''
            self.assertTrue(any(f'personenbezogen ohne {text}' in p for p in self.problems(columns=columns)), feld)

    def test_unbekannte_schutz_oder_loeschklasse_scheitert(self):
        columns = copy.deepcopy(self.columns)
        columns[('audit_event', 'actor_ref')]['schutzklasse'] = 'EGAL'
        columns[('audit_event', 'actor_ref')]['loeschklasse'] = 'EGAL'
        problems = self.problems(columns=columns)
        self.assertTrue(any('unbekannte Schutzklasse' in p for p in problems))
        self.assertTrue(any('unbekannte Löschklasse' in p for p in problems))

    def test_personenbezogen_muss_ja_oder_nein_sein(self):
        columns = copy.deepcopy(self.columns)
        columns[('journey', 'state')]['personenbezogen'] = 'vielleicht'
        self.assertTrue(any('personenbezogen muss ja oder nein sein' in p for p in self.problems(columns=columns)))

    def test_zweck_fehlt_scheitert(self):
        columns = copy.deepcopy(self.columns)
        columns[('journey', 'state')]['zweck'] = ''
        self.assertTrue(any('ohne Zweck' in p for p in self.problems(columns=columns)))

    def test_tabelle_mit_unbekannter_loeschklasse_scheitert(self):
        tables = copy.deepcopy(self.tables)
        tables['journey']['loeschklasse'] = 'LK-GIBTS-NICHT'
        self.assertTrue(any('journey' in p and 'unbekannte Löschklasse' in p for p in self.problems(tables=tables)))

    def test_doppelter_eintrag_in_den_csv_dateien_scheitert(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            header = 'tabelle;spalte;personenbezogen;zweck;rechtsgrundlage;schutzklasse;loeschklasse;bemerkung\n'
            row = 'journey;state;nein;BUCHUNGSABWICKLUNG;;;;\n'
            (Path(d) / 'a.csv').write_text(header + row)
            (Path(d) / 'b.csv').write_text(header + row)
            with self.assertRaises(inv.InventoryError):
                inv.load_inventory(Path(d))

    def test_falscher_csv_kopf_scheitert(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'a.csv').write_text('tabelle;spalte\njourney;state\n')
            with self.assertRaises(inv.InventoryError):
                inv.load_inventory(Path(d))


if __name__ == '__main__':
    unittest.main()
