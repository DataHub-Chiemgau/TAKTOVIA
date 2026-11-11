"""WP-M1-38 / LH-6.18-SEC-009: aus OpenAPI generierter Angriffskatalog gegen die laufende Anwendung (oder `TAKTOVIA_BASE_URL`)."""
import json
import sys
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import attack_catalog as ac  # noqa: E402
from bundle import bundle  # noqa: E402
from harness import RunningApp  # noqa: E402

DOC = bundle()
APP = None
RESULTS = None


def setUpModule():
    global APP, RESULTS
    APP = RunningApp('--taktovia.api.rate-limit.requests=1000000')
    APP.__enter__()
    RESULTS = ac.run(APP.client(), ac.generate(DOC))


def tearDownModule():
    if APP:
        APP.__exit__(None, None, None)


class Angriffskatalog(unittest.TestCase):
    def test_standardangriffe_werden_abgewiesen(self):
        failures = [m for a, _, errs in RESULTS for m in errs if not m.split(': ', 1)[1].startswith(('Header ', 'Fehlerantwort', 'interne Details', 'Problem'))]
        self.assertEqual(failures[:20], [], f'{len(failures)} Angriffe nicht wie erwartet abgewiesen')
        cats = Counter(a.category for a, _, _ in RESULTS)
        for must in ('sql-injection-pfad', 'sql-injection-query', 'injektion-im-feld', 'uebergrosser-body', 'tief-verschachtelter-body', 'mass-assignment', 'pfadmanipulation',
                     'header-injektion', 'falscher-content-type', 'fremde-origin', 'csrf-cross-site', 'unbekannter-query-parameter', 'doppelte-schluessel', 'steuerzeichen-im-feld', 'ssrf'):
            self.assertGreater(cats[must], 0, f'Kategorie {must} wurde nicht erzeugt')
        ops = {a.op for a, _, _ in RESULTS}
        self.assertEqual(ops, {op['operationId'] for _, _, op in ac.operations(DOC)}, 'der Katalog deckt jeden Endpunkt der OpenAPI-Dateien ab')
        print(f'\nAngriffskatalog: {len(RESULTS)} Angriffe gegen {len(ops)} Endpunkte, Kategorien: {dict(sorted(cats.items()))}')

    def test_abweisung_ohne_interne_details(self):
        problems = [m for _, _, errs in RESULTS for m in errs if m.split(': ', 1)[1].startswith(('Header ', 'Fehlerantwort', 'interne Details', 'Problem'))]
        self.assertEqual(problems[:20], [], 'Antworten ohne Sicherheitsheader, ohne Problem-Form oder mit internen Details')
        # Antworten auf Angriffe verraten weder Server-Produkt noch Version
        for _, r, _ in RESULTS[:200]:
            self.assertNotIn('server', r.headers)
            self.assertNotIn('x-powered-by', r.headers)

    def test_ssrf_ziele_werden_abgewiesen(self):
        ssrf = [(a, r) for a, r, _ in RESULTS if a.category == 'ssrf']
        self.assertEqual({a.op for a, _ in ssrf}, {'createWebhookSubscription'})
        self.assertEqual(len(ssrf), len(ac.SSRF_URLS))
        for a, r in ssrf:
            self.assertEqual(r.status, 422, a.label())
            self.assertIn('TARGET_NOT_ALLOWED', r.body, a.label())
            self.assertEqual(json.loads(r.body)['violations'][0]['pointer'], '/url')
        # zulässiges öffentliches https-Ziel wird von der Eingabeprüfung nicht abgewiesen
        ok = APP.client().request('POST', '/v1/webhook-subscriptions', json.dumps({'url': 'https://partner.example.org/hooks/x', 'event_types': ['booking.confirmed']}),
                                  {'Idempotency-Key': 'idempotency-key-0123456789'})
        self.assertNotEqual(ok.status, 422, ok.body)

    def test_gueltige_requests_ohne_fehlalarm(self):
        client = APP.client()
        reqs = ac.valid_requests(DOC)
        self.assertEqual(len(reqs), len(list(ac.operations(DOC))))
        false_alarms = []
        for oid, method, path, body, headers in reqs:
            r = client.request(method, path, body, headers)
            # Die Härtungsschicht darf einen gültigen Request nicht abweisen. 404 = Endpunkt noch nicht gebaut (siehe api/contract-pending.txt).
            if r.status in (400, 403, 413, 415, 422, 429) or r.status >= 500:
                false_alarms.append(f'{oid} {method} {path}: {r.status} {r.body[:200]}')
            errs = ac.invariants(r, oid)
            false_alarms += errs
        self.assertEqual(false_alarms, [])


if __name__ == '__main__':
    unittest.main()
