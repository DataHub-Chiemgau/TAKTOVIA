"""WP-M2-09 / LH-6.10-PAY-001: `fare_quote` unter der echten Runtime-Rolle: Quote unveränderlich (UPDATE und DELETE schlagen fehl, auch für die Administration),
Inhaltshash und Snapshot haben die vorgeschriebene Form, Preisbindung an eine Tarifrevision desselben Mandanten, RLS (fremder Mandant sieht und referenziert nichts)."""
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from geo_helpers import make_service
from tarif_helpers import add_revision

A, B = uid(), uid()
HASH = 'c' * 64


def make_quote(c, tenant, revision, *, snapshot='{"schema_version": "1.0"}', content_hash=HASH, amount=1000):
    qa, cfg, q = uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (%s,%s,now(),'WEB')", (tenant, qa))
    c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
              "VALUES (%s,%s,'SERVICE',%s,1,'1','{}',now(),'user:u','Testdaten')", (tenant, cfg, uid()))
    c.execute("INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash) "
              "VALUES (%s,%s,%s,'EUR',%s,0,'{}',now() + interval '5 minutes',%s,%s,%s::jsonb,%s)", (tenant, q, qa, amount, revision, cfg, snapshot, content_hash))
    return q


class FareQuote(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm209-a'); seed_tenant(B, 'm209-b')

    def setUp(self):
        with tx(A) as c:
            self.rev = add_revision(c, A, make_service(c, A), state='VEROEFFENTLICHT')
            self.quote = make_quote(c, A, self.rev)

    def test_quote_update_delete_abgelehnt(self):
        # Runtime-Rolle: kein UPDATE/DELETE-Recht; Administration: der Trigger `quote_immutable` (Gegenereignis statt Änderung, Kap. 04 §4.5)
        for sql in ("UPDATE taktovia.fare_quote SET pay_at_platform_minor = 1 WHERE id = %s", "UPDATE taktovia.fare_quote SET snapshot = '{}'::jsonb WHERE id = %s",
                    "UPDATE taktovia.fare_quote SET content_hash = %s WHERE id = %s", "DELETE FROM taktovia.fare_quote WHERE id = %s"):
            args = (HASH, self.quote) if 'content_hash' in sql else (self.quote,)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c: c.execute(sql, args)
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with admin() as a: a.execute(sql, args)
        with tx(A) as c:
            row = c.execute("SELECT pay_at_platform_minor, content_hash FROM taktovia.fare_quote WHERE id=%s", (self.quote,)).fetchone()
        self.assertEqual(row[0], 1000)
        self.assertEqual(row[1].strip(), HASH)

    def test_quote_fremder_mandant_unsichtbar(self):
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fare_quote WHERE id=%s", (self.quote,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                              # WITH CHECK: fremde tenant_id
            with tx(B) as c: make_quote(c, A, self.rev)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):                                # Preisbindung an die Tarifrevision eines anderen Mandanten
            with tx(B) as c: make_quote(c, B, self.rev)

    def test_hash_und_snapshot_form_erzwungen(self):
        for bad in ('x' * 64, 'A' * 64, 'a' * 63 + 'g', ''):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.StringDataRightTruncation), msg=bad):
                with tx(A) as c: make_quote(c, A, self.rev, content_hash=bad)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: make_quote(c, A, self.rev, snapshot='[1]')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: make_quote(c, A, self.rev, amount=-1)

    def test_tarifrevision_pflicht(self):
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: make_quote(c, A, uid())


if __name__ == '__main__':
    unittest.main()
