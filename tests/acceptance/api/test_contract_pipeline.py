"""WP-M1-03 / LH-6.17-INT-001: Die Vertrags-Pipeline bricht den Build bei einer Route ohne Vertrag (CI-Schritt check_routes.py)."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'api/tools'
sys.path.insert(0, str(TOOLS))
import check_routes  # noqa: E402
from bundle import bundle  # noqa: E402

REAL_ROUTES = REPO / 'backend/app/build/contract/routes.json'


def run_cli(routes, pending=None):
    """Ruft das CLI wie die CI auf und liefert (Exitcode, Ausgabe)."""
    with tempfile.TemporaryDirectory() as d:
        f = Path(d) / 'routes.json'
        f.write_text(json.dumps(routes))
        cmd = [sys.executable, str(TOOLS / 'check_routes.py'), str(f)]
        if pending is not None:
            p = Path(d) / 'pending.txt'
            p.write_text('\n'.join(pending) + '\n')
            cmd += ['--pending', str(p)]
        r = subprocess.run(cmd, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


class ContractPipeline(unittest.TestCase):
    def test_undokumentierte_route_bricht_build(self):
        # Eine absichtlich undokumentierte Testroute lässt den Schritt scheitern (Exitcode 1, Route wird benannt).
        rc, out = run_cli([{'method': 'GET', 'path': '/v1/undokumentiert'}])
        self.assertEqual(rc, 1, out)
        self.assertIn('GET /v1/undokumentiert', out)
        self.assertIn('keine operationId', out)
        # Ohne Fremdroute: die Pipeline ist grün (Betriebsendpunkte sind erlaubt).
        rc, out = run_cli([{'method': 'GET', 'path': '/actuator/health'}, {'method': 'ANY', 'path': '/error'}])
        self.assertEqual(rc, 0, out)

    def test_weitere_verstoesse_bricht_build(self):
        ops = {i['operationId']: (m.upper(), p) for p, item in bundle()['paths'].items() for m, i in item.items() if m in ('get', 'post', 'put', 'patch', 'delete')}
        method, path = ops['getOperation']
        route = {'method': method, 'path': path.replace('{operation_id}', '{beliebiger_name}')}  # Variablenname ist egal
        all_ids = sorted(ops)
        # gebaute Operation, die noch als „noch nicht gebaut“ geführt wird -> Fehler
        rc, out = run_cli([route], pending=[i for i in all_ids])
        self.assertEqual(rc, 1, out); self.assertIn('getOperation ist gebaut', out)
        # gebaute Operation, korrekt aus der Liste gestrichen -> grün
        rc, out = run_cli([route], pending=[i for i in all_ids if i != 'getOperation'])
        self.assertEqual(rc, 0, out)
        # dokumentierte Operation ohne Route und nicht in der Liste -> Fehler
        rc, out = run_cli([route], pending=[i for i in all_ids if i not in ('getOperation', 'getMe')])
        self.assertEqual(rc, 1, out); self.assertIn('getMe ist dokumentiert, hat keine Route', out)
        # Route doppelt, Pfad ausserhalb /v1, unbekannte Pending-ID
        rc, out = run_cli([route, route, {'method': 'GET', 'path': '/intern/debug'}], pending=[i for i in all_ids if i != 'getOperation'] + ['gibtEsNicht'])
        self.assertEqual(rc, 1, out)
        for text in ('doppelt geroutet', '/intern/debug', 'gibtEsNicht'): self.assertIn(text, out)

    def test_falsche_methode_ist_undokumentiert(self):
        self.assertTrue(any('keine operationId' in e for e in check_routes.verify(
            [{'method': 'DELETE', 'path': '/v1/operations/{operation_id}'}], bundle(), check_routes.read_pending(REPO / 'api/contract-pending.txt'))))

    @unittest.skipUnless(REAL_ROUTES.exists(), 'Routenliste fehlt: erst ./gradlew :app:test --tests "*ContractCoverageTest*" ausführen (CI tut das vor diesem Schritt)')
    def test_tatsaechliche_routen_der_anwendung_sind_vertragskonform(self):
        rc, out = run_cli(json.loads(REAL_ROUTES.read_text()), pending=None)
        # ohne --pending gilt api/contract-pending.txt
        self.assertEqual(rc, 0, out)


if __name__ == '__main__':
    unittest.main()
