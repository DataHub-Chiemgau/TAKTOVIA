"""WP-M4-58 / LH-I.6-KPI-011: parallele Abfragen zweier Mandanten über denselben (wiederverwendeten) Verbindungspool zeigen keine Vermischung.
Kein Mandantenkontext wirkt über das Transaktionsende hinaus fort (Kap. 04 §4.6): zwei Verbindungen werden von 12 Threads abwechselnd für beide Mandanten benutzt, jede Abfrage nutzt die Berichtsdimensionen."""
import queue
import threading
import unittest
from helpers import runtime, set_tenant, uid, seed_tenant, tx

A, B = uid(), uid()
SA, SB = uid(), uid()
RA, RB = uid(), uid()
ATTEMPT = ("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,time_mode,requested_at,passengers,operator_id,region_id) "
           "VALUES (taktovia.current_tenant(),%s,%s,'RECORDED','DEPARTURE','2026-12-01T07:10:00Z',1,%s,%s)")
QUERY = ("SELECT count(*), count(*) FILTER (WHERE region_id = %s), count(DISTINCT service_id) FROM taktovia.fact_attempt WHERE requested_at >= '2026-11-30T23:00:00Z' AND requested_at < '2026-12-01T23:00:00Z' "
         "AND (%s::uuid IS NULL OR service_id = %s::uuid)")


class AnalyticsPoolTrennung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm458p-a'); seed_tenant(B, 'm458p-b')
        for t, s, r, n in ((A, SA, RA, 7), (B, SB, RB, 3)):
            with tx(t) as c:
                for _ in range(n): c.execute(ATTEMPT, (uid(), s, uid(), r))

    def test_filter_kein_fremder_mandant_bei_parallelen_abfragen(self):
        pool = queue.Queue()
        conns = [runtime(), runtime()]            # bewusst weniger Verbindungen als Threads: Wiederverwendung über Mandanten hinweg
        for c in conns: pool.put(c)
        errors, bad, barrier = [], [], threading.Barrier(12)

        def worker(i):
            tenant, svc, region, expected = (A, SA, RA, 7) if i % 2 == 0 else (B, SB, RB, 3)
            foreign_service = SB if tenant == A else SA
            try:
                barrier.wait(30)
                for round_no in range(25):
                    conn = pool.get(timeout=60)
                    try:
                        set_tenant(conn, tenant)
                        total, in_region, services = conn.execute(QUERY, (region, None, None)).fetchone()
                        filtered = conn.execute(QUERY, (region, str(svc), str(svc))).fetchone()[0]
                        foreign = conn.execute(QUERY, (region, str(foreign_service), str(foreign_service))).fetchone()[0]
                        if (total, in_region, services, filtered, foreign) != (expected, expected, 1, expected, 0): bad.append((i, round_no, total, in_region, services, filtered, foreign))
                        conn.commit()
                        # kein fortwirkender Kontext nach dem Transaktionsende
                        if conn.execute("SELECT nullif(current_setting('app.tenant_id', true), '')").fetchone()[0] is not None: bad.append((i, round_no, 'Kontext wirkt fort'))
                        conn.commit()
                    except BaseException:
                        conn.rollback(); raise
                    finally:
                        pool.put(conn)
            except BaseException as e:   # noqa: BLE001
                errors.append(repr(e))
        ts = [threading.Thread(target=worker, args=(i,)) for i in range(12)]
        [t.start() for t in ts]; [t.join(180) for t in ts]
        for c in conns: c.close()
        self.assertEqual(errors, []); self.assertEqual(bad, [])


if __name__ == '__main__':
    unittest.main()
