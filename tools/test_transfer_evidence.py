import copy, csv, json, os, shutil, subprocess, sys, tempfile, unittest
from multiprocessing import Process
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import transfer_evidence as te  # noqa: E402

PY = sys.executable


def base_evidence():
    """Echte Evidenz des Rauchtests (TECH-SMOKE-001) mit gesetzter Build-ID; benötigt die Testdatenbank."""
    out = Path(tempfile.mkdtemp())
    env = {**os.environ, 'TAKTOVIA_BUILD_ID': 'ci-12345-abcdef1', 'TAKTOVIA_EVIDENCE_DIR': str(out)}
    r = subprocess.run([PY, str(ROOT / 'tests/acceptance/run.py'), '--bootstrap', '--scenario', 'TECH-SMOKE-001'], capture_output=True, text=True, env=env)
    files = list(out.rglob('*.json')) or list((ROOT / 'tests/acceptance/evidence-out').rglob('*.json'))
    if r.returncode != 0 or not files:
        raise unittest.SkipTest(f'Rauchtest nicht ausführbar (Datenbank?): {r.stderr[-200:]}')
    return json.loads(sorted(files, key=lambda p: p.stat().st_mtime)[-1].read_text())


def as_test(ev, tid, kind, result='PASSED'):
    e = copy.deepcopy(ev); e['test']['id'] = tid; e['test']['kind'] = kind; e['result'] = result; e['run_id'] = 'run-' + format(abs(hash(tid)) % (16 ** 16), '016x')
    return e


class Transfer(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ev = base_evidence()

    def setUp(self):
        self.d = Path(tempfile.mkdtemp()); shutil.copy(ROOT / 'docs/pflichtenheft/e2e.csv', self.d / 'e2e.csv')
        self.csv = self.d / 'e2e.csv'

    def write(self, ev, name='ev.json'):
        p = self.d / name; p.write_text(json.dumps(ev)); return p

    def row(self, tid):
        with self.csv.open(encoding='utf-8-sig') as f:
            return next(r for r in csv.DictReader(f, delimiter=';') if r['test_id_original'] == tid)

    def test_gueltige_evidenz_wird_uebertragen(self):
        self.assertEqual(te.transfer([self.write(as_test(self.ev, 'AT-03', 'AT'))], self.csv), {})
        r = self.row('AT-03'); self.assertEqual((r['status'], r['build'], r['freigabe']), ('Bestanden', 'ci-12345-abcdef1', ''))

    def test_erfundene_build_id_wird_abgelehnt(self):
        e = as_test(self.ev, 'AT-03', 'AT'); e['software_build']['build_id'] = 'meinlauf'
        self.assertTrue(te.transfer([self.write(e)], self.csv))
        self.assertEqual(self.row('AT-03')['status'], 'Nicht ausgeführt')

    def test_build_id_ohne_pipelinelauf_wird_abgelehnt(self):
        rej = te.transfer([self.write(as_test(self.ev, 'AT-03', 'AT'))], self.csv, known={'ci-1-aaaaaaa'})
        self.assertTrue(any('Pipeline-Lauf' in m for ms in rej.values() for m in ms))

    def test_schemaverletzung_wird_abgelehnt(self):
        e = as_test(self.ev, 'AT-03', 'AT'); del e['soll_ist']
        self.assertTrue(te.transfer([self.write(e)], self.csv))

    def test_unbekannter_test_und_technisches_szenario(self):
        self.assertTrue(te.transfer([self.write(as_test(self.ev, 'AT-99', 'AT'))], self.csv))
        self.assertEqual(te.transfer([self.write(self.ev)], self.csv), {})  # TECH-: übersprungen

    def test_csv_bleibt_106_zeilen(self):
        te.transfer([self.write(as_test(self.ev, 'AT-03', 'AT'))], self.csv)
        with self.csv.open(encoding='utf-8-sig') as f: self.assertEqual(len(list(csv.DictReader(f, delimiter=';'))), 106)

    def test_konkurrierende_laeufe_verlieren_keinen_eintrag(self):
        ids = [f'AT-{i:02d}' for i in range(1, 9)]
        paths = [self.write(as_test(self.ev, t, 'AT'), f'{t}.json') for t in ids]
        ps = [Process(target=te.transfer, args=([p], self.csv)) for p in paths]
        [p.start() for p in ps]; [p.join() for p in ps]
        self.assertEqual([self.row(t)['status'] for t in ids], ['Bestanden'] * 8)


if __name__ == '__main__':
    unittest.main()
