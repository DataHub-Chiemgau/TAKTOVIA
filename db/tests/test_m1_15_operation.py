"""WP-M1-15: operation mit Lease und Fencing unter der Runtime-Rolle (Constraints, Guard-Trigger, RLS, Teilindex)."""
import threading, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()


def new_op(c, tenant, kind='TEST_COMMAND', journey=None, status='QUEUED'):
    oid = uid()
    c.execute("""INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at)
                 VALUES (%s,%s,%s,%s,%s,now() + interval '1 hour')""", (tenant, oid, journey, kind, status))
    return oid


class OperationDb(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'op-a'); seed_tenant(B, 'op-b')

    def test_operation_ohne_reise_ist_zulaessig_und_startet_queued(self):
        with tx(A) as c:
            oid = new_op(c, A)
            row = c.execute("SELECT status, journey_id, fencing_token, attempts, revision FROM taktovia.operation WHERE id=%s", (oid,)).fetchone()
            self.assertEqual(row, ('QUEUED', None, 0, 0, 1))

    def test_fencing_token_ist_monoton_und_kann_nicht_sinken(self):
        with tx(A) as c:
            oid = new_op(c, A)
            c.execute("UPDATE taktovia.operation SET fencing_token=2 WHERE id=%s", (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.operation SET fencing_token=1 WHERE id=%s", (oid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.operation SET fencing_token=3 WHERE id=%s", (oid,))

    def test_unerlaubter_zustandsuebergang_wird_von_der_datenbank_abgewiesen(self):
        with tx(A) as c:
            oid = new_op(c, A)
        with self.assertRaises(psycopg.errors.CheckViolation):   # QUEUED -> SUCCEEDED ist nicht erlaubt
            with tx(A) as c:
                c.execute("UPDATE taktovia.operation SET status='SUCCEEDED', result_code='OK' WHERE id=%s", (oid,))

    def test_endzustand_ist_unveraenderlich_und_ohne_lease(self):
        with tx(A) as c:
            oid = new_op(c, A)
            c.execute("UPDATE taktovia.operation SET status='RUNNING' WHERE id=%s", (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # Endzustand mit offener Lease
            with tx(A) as c:
                c.execute("UPDATE taktovia.operation SET status='SUCCEEDED', result_code='DONE', lease_until=now() + interval '1 minute' WHERE id=%s", (oid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.operation SET status='SUCCEEDED', result_code='DONE', lease_until=NULL WHERE id=%s", (oid,))
        for sql in ("UPDATE taktovia.operation SET result_code='ANDERS' WHERE id=%s", "UPDATE taktovia.operation SET status='FAILED' WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c:
                    c.execute(sql, (oid,))
        with admin() as a:   # auch der Besitzer kann den Endzustand nicht ändern
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.operation SET attempts=9 WHERE id=%s", (oid,))

    def test_identitaet_der_operation_ist_unveraenderlich(self):
        with tx(A) as c:
            oid = new_op(c, A)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.operation SET kind='CONFIRM' WHERE id=%s", (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.operation SET deadline_at = deadline_at + interval '1 day' WHERE id=%s", (oid,))

    def test_fremder_mandant_sieht_keine_operation(self):
        with tx(A) as c:
            oid = new_op(c, A)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.operation WHERE id=%s", (oid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.operation SET attempts=5 WHERE id=%s", (oid,)).rowcount, 0)

    def test_teilindex_fuer_offene_operationen_existiert(self):
        with admin() as c:
            d = c.execute("SELECT indexdef FROM pg_indexes WHERE schemaname='taktovia' AND indexname='operation_open'").fetchone()[0]
            self.assertIn("status", d); self.assertIn("QUEUED", d); self.assertIn("RECONCILING", d)

    def test_konkurrierend_zwei_transaktionen_uebernehmen_nur_eine_die_lease(self):
        with tx(A) as c:
            oid = new_op(c, A)
        winners = []
        barrier = threading.Barrier(8)

        def claim():
            with tx(A) as c:
                barrier.wait()
                row = c.execute("""WITH x AS (SELECT id FROM taktovia.operation WHERE id=%s AND status='QUEUED' FOR UPDATE SKIP LOCKED)
                                   UPDATE taktovia.operation o SET status='RUNNING', fencing_token=o.fencing_token+1 FROM x WHERE o.id=x.id RETURNING o.fencing_token""", (oid,)).fetchone()
                if row:
                    winners.append(row[0])
        ts = [threading.Thread(target=claim) for _ in range(8)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(winners, [1], 'genau ein Worker erhält die Lease')


if __name__ == '__main__':
    unittest.main()
