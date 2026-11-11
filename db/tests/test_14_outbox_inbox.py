"""WP-M1-14: Outbox/Inbox-Schema (V1140): Eindeutigkeit, Zustellzustand, Dead-Letter-Invariante, Isolation, parallele Inbox-Inserts."""
import threading
import unittest

import psycopg
from helpers import admin, seed_tenant, tx, uid

A, B = uid(), uid()
OUT = """INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload)
         VALUES (%s,%s,%s,%s,%s,%s,now(),'{}'::jsonb)"""
IN = """INSERT INTO taktovia.inbox(tenant_id,consumer,event_id,payload_sha256,received_at,aggregate_id,aggregate_revision,event_type,occurred_at)
        VALUES (%s,%s,%s,%s,now(),%s,%s,'t.x',now())"""


class OutboxInbox(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'ob-a'); seed_tenant(B, 'ob-b')

    def test_eindeutig_je_aggregatrevision_und_ereignistyp(self):
        agg = uid()
        with tx(A) as c:
            c.execute(OUT, (A, uid(), agg, 1, 't.changed', '1.0'))
            c.execute(OUT, (A, uid(), agg, 1, 't.other', '1.0'))
            c.execute(OUT, (A, uid(), agg, 2, 't.changed', '1.0'))
        with self.assertRaises(psycopg.errors.UniqueViolation), tx(A) as c:
            c.execute(OUT, (A, uid(), agg, 1, 't.changed', '1.0'))

    def test_empfaenger_standard_ist_eigentuemer_und_zustellzustand_hat_vertraegliche_standardwerte(self):
        e = uid()
        with tx(A) as c:
            c.execute(OUT, (A, e, uid(), 1, 't.changed', '1.0'))
            row = c.execute("SELECT recipient_tenant_id, delivery_attempts, next_attempt_at, claimed_until, dead_lettered_at, recorded_at IS NOT NULL FROM taktovia.outbox WHERE event_id=%s", (e,)).fetchone()
        self.assertEqual(row, (A, 0, None, None, None, True))

    def test_constraints_dead_letter_fehlercode_und_schemaversion(self):
        for sql in ("UPDATE taktovia.outbox SET dead_lettered_at=now(), published_at=now() WHERE event_id=%s",
                    "UPDATE taktovia.outbox SET last_error='Freitext mit Leerzeichen und Personendaten' WHERE event_id=%s"):
            e = uid()
            with tx(A) as c:
                c.execute(OUT, (A, e, uid(), 1, 't.changed', '1.0'))
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql), tx(A) as c:
                c.execute(sql, (e,))
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute(OUT, (A, uid(), uid(), 1, 't.changed', 'v1'))

    def test_isolation_und_fremder_eigentuemer(self):
        e = uid()
        with tx(A) as c:
            c.execute(OUT, (A, e, uid(), 1, 't.changed', '1.0'))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.outbox WHERE event_id=%s", (e,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege), tx(B) as c:
            c.execute(OUT, (A, uid(), uid(), 1, 't.changed', '1.0'))

    def test_teilindizes_vorhanden(self):
        with admin() as c:
            idx = {r[0]: r[1] for r in c.execute("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='taktovia' AND tablename IN ('outbox','inbox')")}
        self.assertIn('WHERE (published_at IS NULL)', idx['outbox_unpublished'])
        self.assertIn('dead_lettered_at IS NULL', idx['outbox_due'])
        self.assertIn('inbox_by_aggregate', idx)

    def test_inbox_dedupe_unter_parallelen_inserts_genau_einer(self):
        e, agg = uid(), uid(); ok = []; barrier = threading.Barrier(8)

        def run():
            barrier.wait()
            try:
                with tx(A) as c:
                    c.execute(IN, (A, 'c1', e, 'a' * 64, agg, 1))
                ok.append(1)
            except psycopg.errors.UniqueViolation:
                pass

        ts = [threading.Thread(target=run) for _ in range(8)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(len(ok), 1)
        with tx(A) as c:   # anderer Consumer hat eigenen Dedupe-Raum
            c.execute(IN, (A, 'c2', e, 'a' * 64, agg, 1))

    def test_inbox_revision_muss_positiv_und_outcome_gueltig_sein(self):
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute(IN, (A, 'c1', uid(), 'a' * 64, uid(), 0))
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:
            c.execute(IN, (A, 'c1', uid(), 'a' * 64, uid(), 1)); c.execute("UPDATE taktovia.inbox SET outcome='ANDERS' WHERE tenant_id=%s", (A,))


if __name__ == '__main__':
    unittest.main()
