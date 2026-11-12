"""WP-M2-08 / LH-6.10-PAY-002: `fare_component` unter der echten Runtime-Rolle: Komponentencodes, Beträge `bigint` (Vorzeichenregel), Währung aus dem Katalog, Bezug auf Angebot
und Regel desselben Mandanten, RLS, unveränderlich (kein UPDATE/DELETE)."""
import unittest

import psycopg
from helpers import tx, uid, seed_tenant
from geo_helpers import make_service
from tarif_helpers import add_revision

A, B = uid(), uid()


def make_quote(c, tenant, revision, n=1):
    qa, cfg, q = uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (%s,%s,now(),'WEB')", (tenant, qa))
    c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
              "VALUES (%s,%s,'SERVICE',%s,%s,'1','{}',now(),'user:u','Testdaten')", (tenant, cfg, uid(), n))
    c.execute("INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash) "
              "VALUES (%s,%s,%s,'EUR',1000,0,'{}',now(),%s,%s,'{}',%s)", (tenant, q, qa, revision, cfg, 'd' * 64))
    return q


def add_component(c, tenant, quote, no, code='TRANSPORT', amount=935, currency='EUR', rule=None, segment=1):
    c.execute("INSERT INTO taktovia.fare_component(tenant_id,id,quote_id,segment_no,component_no,code,amount_minor,currency,rule_id) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
              (tenant, uid(), quote, segment, no, code, amount, currency, rule))


class Preiskomponenten(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm208-a'); seed_tenant(B, 'm208-b')

    def setUp(self):
        with tx(A) as c:
            svc = make_service(c, A); self.rev = add_revision(c, A, svc, state='VEROEFFENTLICHT')
            self.rule = c.execute("SELECT id FROM taktovia.tariff_rule WHERE revision_id=%s", (self.rev,)).fetchone()[0]
            self.quote = make_quote(c, A, self.rev)

    def test_komponenten_summe_je_segment_und_regelbezug(self):
        with tx(A) as c:
            add_component(c, A, self.quote, 1, 'TRANSPORT', 935, rule=self.rule); add_component(c, A, self.quote, 2, 'SURCHARGE', 93, rule=self.rule)
            add_component(c, A, self.quote, 3, 'TAX', 72, rule=self.rule); add_component(c, A, self.quote, 1, 'TRANSPORT', 500, segment=2)
            self.assertEqual(c.execute("SELECT sum(amount_minor) FROM taktovia.fare_component WHERE quote_id=%s AND segment_no=1", (self.quote,)).fetchone()[0], 1100)
        for code in ('MWST', 'transport'):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c: add_component(c, A, self.quote, 9, code)
        with self.assertRaises(psycopg.errors.UniqueViolation):                                  # Position je Segment eindeutig
            with tx(A) as c: add_component(c, A, self.quote, 1, 'TRANSPORT', 1)
        with self.assertRaises(psycopg.errors.CheckViolation):                                    # negativ nur bei Rabatt/Zuschuss (P2)
            with tx(A) as c: add_component(c, A, self.quote, 9, 'TRANSPORT', -1)
        with tx(A) as c: add_component(c, A, self.quote, 10, 'DISCOUNT', -50)
        with self.assertRaises(psycopg.errors.CheckViolation):                                    # kein Betrag außerhalb von ±(2^53-1)
            with tx(A) as c: add_component(c, A, self.quote, 11, 'TRANSPORT', 9_007_199_254_740_992)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):                               # Währung aus dem Katalog
            with tx(A) as c: add_component(c, A, self.quote, 12, 'TRANSPORT', 1, currency='XXX')
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):                               # unbekanntes Angebot / unbekannte Regel
            with tx(A) as c: add_component(c, A, uid(), 1)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: add_component(c, A, self.quote, 13, rule=uid())
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):                               # Angebot eines anderen Mandanten
            with tx(B) as c: add_component(c, B, self.quote, 1)

    def test_unveraenderlich_und_mandantentrennung(self):
        with tx(A) as c: add_component(c, A, self.quote, 1)
        for sql in ("UPDATE taktovia.fare_component SET amount_minor=1 WHERE quote_id=%s", "DELETE FROM taktovia.fare_component WHERE quote_id=%s"):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=sql):
                with tx(A) as c: c.execute(sql, (self.quote,))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fare_component WHERE quote_id=%s", (self.quote,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                             # WITH CHECK: fremde tenant_id
            with tx(B) as c: add_component(c, A, self.quote, 2)


if __name__ == '__main__':
    unittest.main()
