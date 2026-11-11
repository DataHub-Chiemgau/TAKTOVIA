"""WP-M1-38 (konkurrierend): Rate-Limit unter parallelem Missbrauch greift, ohne legitime Mandanten zu beeinträchtigen."""
import sys
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import RunningApp  # noqa: E402

LIMIT = 30


class RateLimit(unittest.TestCase):
    def test_rate_limit_unter_parallelem_missbrauch(self):
        # Zwei Absender (127.0.0.1 = Missbrauch, 127.0.0.2 = legitim) haben je eine eigene Quote (Akteur = Peer-Adresse bis zur Anmeldung, WP-M1-04/-06).
        with RunningApp(f'--taktovia.api.rate-limit.requests={LIMIT}', '--taktovia.api.rate-limit.window-seconds=60') as app:
            abuser, legit = app.client('127.0.0.1'), app.client('127.0.0.2')

            def hit(c):
                r = c.request('GET', '/v1/me')
                return r.status, r.headers.get('retry-after'), r.is_problem()

            with ThreadPoolExecutor(32) as pool:
                heavy = list(pool.map(lambda _: hit(abuser), range(300)))
                # legitimer Absender läuft parallel zum Missbrauch weiter
                mixed = [pool.submit(hit, abuser) for _ in range(150)] + [pool.submit(hit, legit) for _ in range(10)]
                results = [f.result() for f in mixed]
            statuses = [s for s, _, _ in heavy]
            self.assertEqual(sum(1 for s in statuses if s != 429), LIMIT, 'genau die Quote wird gewährt, auch bei 32 parallelen Clients')
            self.assertEqual(sum(1 for s in statuses if s == 429), 300 - LIMIT)
            limited = [x for x in heavy if x[0] == 429]
            self.assertTrue(all(ra and 1 <= int(ra) <= 60 for _, ra, _ in limited), 'jede 429-Antwort trägt Retry-After')
            self.assertTrue(all(p for _, _, p in limited), '429 als application/problem+json')
            legit_results = results[-10:]
            self.assertTrue(all(s != 429 for s, _, _ in legit_results), f'legitimer Absender wurde gedrosselt: {legit_results}')
            self.assertTrue(all(s == 429 for s, _, _ in results[:150]), 'der Missbrauch bleibt gedrosselt')


if __name__ == '__main__':
    unittest.main()
