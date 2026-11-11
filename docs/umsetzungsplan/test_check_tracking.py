import csv, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path
import check_tracking as ct

ROOT = Path(__file__).resolve().parents[2]


def copy():
    t = Path(tempfile.mkdtemp())
    for rel in ['docs/umsetzungsplan', 'docs/pflichtenheft/anforderungen.csv', 'docs/pflichtenheft/e2e.csv']:
        s = ROOT / rel
        if s.is_dir(): shutil.copytree(s, t / rel, ignore=shutil.ignore_patterns('__pycache__'))
        else: (t / rel).parent.mkdir(parents=True, exist_ok=True); shutil.copy2(s, t / rel)
    return t


def edit(t, fn, name='arbeitspakete.csv', base='docs/umsetzungsplan'):
    p = t / base / name
    with p.open(encoding='utf-8', newline='') as f:
        rows = list(csv.DictReader(f, delimiter=';'))
    h = list(rows[0])
    fn(rows)
    with p.open('w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=h, delimiter=';', quoting=csv.QUOTE_ALL); w.writeheader(); w.writerows(rows)


def row(rows, key): return next(r for r in rows if r['source_key'] == key)


class Tracking(unittest.TestCase):
    def test_aktueller_stand_gruen(self):
        self.assertEqual(ct.check(ROOT), [])

    def test_muss_umgesetzt_ohne_testfall_faellt_durch(self):  # AK 1
        t = copy()
        def f(rows): r = row(rows, 'LH-6.1-SYS-001'); r['status'] = 'Umgesetzt'; r['detailvertrag'] = 'docs/umsetzungsplan/vertraege/LH-6.1-SYS-002.md'
        edit(t, f)
        errs = ct.check(t)
        self.assertTrue(any('ohne testfall' in e for e in errs))

    def test_bereit_ohne_vertrag_oder_test(self):
        t = copy(); edit(t, lambda rows: row(rows, 'LH-6.1-SYS-001').update(status='Bereit'))
        errs = ct.check(t); self.assertTrue(any('ohne detailvertrag' in e for e in errs))

    def test_umgesetzt_braucht_ausfuehrbaren_test(self):
        t = copy(); edit(t, lambda rows: row(rows, 'LH-6.2-IAM-005').update(status='Umgesetzt'))
        self.assertTrue(any('ausführbaren Testfall' in e for e in ct.check(t)))

    def test_phase_prioritaet_geaendert(self):  # AK 4
        t = copy(); edit(t, lambda rows: row(rows, 'LH-6.1-SYS-001').update(prioritaet_original='SOLL'))
        self.assertTrue(any('Phase/Priorität' in e for e in ct.check(t)))

    def test_muss_nie_abweichung(self):
        t = copy(); edit(t, lambda rows: row(rows, 'LH-6.1-SYS-001').update(status='Abweichung freigegeben'))
        self.assertTrue(any('MUSS' in e for e in ct.check(t)))

    def test_unbekannter_status_und_testfall(self):
        t = copy()
        def f(rows): row(rows, 'LH-6.1-SYS-001')['status'] = 'Fertig'; row(rows, 'LH-6.2-IAM-005')['testfall'] = 'AT-99'
        edit(t, f)
        errs = ct.check(t); self.assertTrue(any('unbekannter Status' in e for e in errs)); self.assertTrue(any('nicht im Testkatalog' in e for e in errs))

    def test_doppelte_und_fehlende_source_key(self):
        t = copy(); edit(t, lambda rows: rows.__setitem__(0, dict(rows[1])))
        errs = ct.check(t); self.assertTrue(any('nicht eindeutig' in e for e in errs))

    def test_vertrag_mit_geaendertem_originaltext(self):
        t = copy(); v = t / 'docs/umsetzungsplan/vertraege/LH-6.2-IAM-005.md'
        v.write_text(v.read_text().replace('Mandant, Dienst und konkretes Objekt', 'Mandant und Dienst'))
        self.assertTrue(any('Originaltext' in e for e in ct.check(t)))

    def test_wp_verweis_auf_nicht_existierendes_wp(self):
        t = copy(); edit(t, lambda rows: row(rows, 'LH-6.1-SYS-001').update(arbeitspaket='WP-M1-99'))
        self.assertTrue(any('nicht in' in e for e in ct.check(t)))

    def test_e2e_von_hand_geaendert_ohne_build(self):  # AK 2
        t = copy(); edit(t, lambda rows: rows[0].update(status='Bestanden'), name='e2e.csv', base='docs/pflichtenheft')
        self.assertTrue(any('ohne Build-ID' in e for e in ct.check(t)))

    def test_neuerzeugung_erhaelt_gepflegte_spalten(self):
        t = copy(); shutil.copytree(ROOT / 'docs/pflichtenheft', t / 'docs/pflichtenheft', dirs_exist_ok=True, ignore=shutil.ignore_patterns('*.docx'))
        before = (t / 'docs/umsetzungsplan/arbeitspakete.csv').read_text()
        subprocess.run([sys.executable, str(t / 'docs/umsetzungsplan/check_wp.py'), '--write'], check=True, capture_output=True)
        self.assertEqual((t / 'docs/umsetzungsplan/arbeitspakete.csv').read_text(), before)
        edit(t, lambda rows: row(rows, 'LH-6.1-SYS-001').update(status='Vertrag in Arbeit', detailvertrag='docs/umsetzungsplan/vertraege/LH-6.1-SYS-002.md'))
        subprocess.run([sys.executable, str(t / 'docs/umsetzungsplan/check_wp.py'), '--write'], check=True, capture_output=True)
        with (t / 'docs/umsetzungsplan/arbeitspakete.csv').open(encoding='utf-8') as f:
            r = row(list(csv.DictReader(f, delimiter=';')), 'LH-6.1-SYS-001')
        self.assertEqual(r['status'], 'Vertrag in Arbeit')


if __name__ == '__main__':
    unittest.main()
