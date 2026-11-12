"""WP-M2-07 / LH-6.10-PAY-003: Tarifrevision und Preisregeln mit Gültigkeit unter der echten Runtime-Rolle: keine überlappende veröffentlichte Revision je Dienst und
Tarifklasse (auch parallel), Pflichtangaben, Vier-Augen, vollständige Unveränderlichkeit nach der Veröffentlichung, Mandantentrennung, Preisbindung am `fare_quote`."""
import threading
import unittest

import psycopg
from helpers import tx, uid, seed_tenant
from geo_helpers import make_service
from tarif_helpers import add_revision, add_rule, publish

A, B = uid(), uid()


class Tarifrevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm207-a'); seed_tenant(B, 'm207-b')

    def test_ueberlappende_aktive_revision_abgewiesen(self):
        with tx(A) as c:
            svc = make_service(c, A)
            add_revision(c, A, svc, valid_from='2026-01-01Z', valid_to='2026-07-01Z', state='VEROEFFENTLICHT')
        with self.assertRaises(psycopg.errors.ExclusionViolation):                      # überlappend, gleiche Klasse
            with tx(A) as c: add_revision(c, A, svc, revision_no=2, valid_from='2026-06-30Z', state='VEROEFFENTLICHT')
        with tx(A) as c:                                                                  # links geschlossen, rechts offen: Anschluss ist zulässig
            add_revision(c, A, svc, revision_no=3, valid_from='2026-07-01Z', valid_to='2027-01-01Z', state='VEROEFFENTLICHT')
            add_revision(c, A, svc, tariff_class='ERMAESSIGT', revision_no=1, valid_from='2026-06-30Z', state='VEROEFFENTLICHT')   # andere Tarifklasse
            svc2 = make_service(c, A); add_revision(c, A, svc2, valid_from='2026-06-30Z', state='VEROEFFENTLICHT')                # anderer Dienst
            add_revision(c, A, svc, revision_no=4, valid_from='2026-06-30Z', valid_to='2026-12-31Z')                               # Entwurf überlappt: kein aktiver Stand
        with self.assertRaises(psycopg.errors.UniqueViolation):                          # Revisionsnummer je Dienst und Klasse eindeutig
            with tx(A) as c: add_revision(c, A, svc, revision_no=3, valid_from='2030-01-01Z')

    def test_regel_ohne_zeitraum_dienst_tarifklasse_abgewiesen_und_veroeffentlichte_revision_unveraenderlich(self):
        with tx(A) as c: svc = make_service(c, A)
        base = ("INSERT INTO taktovia.tariff_revision(tenant_id,id,service_id,tariff_class,revision_no,valid_from,valid_to,currency,reason,created_by) "
                "VALUES (%s,%s,%s,%s,1,%s,%s,'EUR','Grund','user:ersteller')")
        for args in ((A, uid(), svc, None, '2026-01-01Z', None),                         # ohne Tarifklasse
                     (A, uid(), None, 'STANDARD', '2026-01-01Z', None),                  # ohne Dienst
                     (A, uid(), svc, 'STANDARD', None, None)):                           # ohne Beginn der Gültigkeit
            with self.assertRaises(psycopg.errors.NotNullViolation):
                with tx(A) as c: c.execute(base, args)
        with self.assertRaises(psycopg.errors.CheckViolation):                           # valid_to <= valid_from
            with tx(A) as c: c.execute(base, (A, uid(), svc, 'STANDARD', '2026-01-01Z', '2026-01-01Z'))
        with self.assertRaises(psycopg.errors.CheckViolation):                           # Tarifklasse: Format
            with tx(A) as c: c.execute(base, (A, uid(), svc, 'standard', '2026-01-01Z', None))
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):                      # Dienst eines anderen Mandanten
            with tx(B) as c: c.execute(base, (B, uid(), svc, 'STANDARD', '2026-01-01Z', None))
        # Vier-Augen: Ersteller prüft und veröffentlicht nicht selbst (auch an der Anwendung vorbei)
        with tx(A) as c: rid = add_revision(c, A, svc)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.tariff_revision SET state='GEPRUEFT', reviewed_by='user:ersteller', reviewed_at=now() WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):                           # Zustand ohne Prüfer
            with tx(A) as c: c.execute("UPDATE taktovia.tariff_revision SET state='GEPRUEFT' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):                           # Sprung ENTWURF -> VEROEFFENTLICHT
            with tx(A) as c: publish(c, A, rid)
        with tx(A) as c:
            c.execute("UPDATE taktovia.tariff_revision SET state='GEPRUEFT', reviewed_by='user:pruefer', reviewed_at=now() WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):                           # Veröffentlichender = Ersteller
            with tx(A) as c: publish(c, A, rid, by='user:ersteller')
        with self.assertRaises(psycopg.errors.CheckViolation):                           # geprüfte Revision nicht mehr bearbeitbar
            with tx(A) as c: c.execute("UPDATE taktovia.tariff_revision SET reason='Neu' WHERE id=%s", (rid,))
        with tx(A) as c: publish(c, A, rid)
        # veröffentlicht: Revision und Regeln vollständig unveränderlich, kein DELETE
        for sql in ("UPDATE taktovia.tariff_revision SET reason='Neu' WHERE id=%s", "UPDATE taktovia.tariff_revision SET valid_to='2026-02-01Z' WHERE id=%s",
                    "UPDATE taktovia.tariff_rule SET amount_minor=1 WHERE revision_id=%s", "DELETE FROM taktovia.tariff_rule WHERE revision_id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c: c.execute(sql, (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.tariff_revision WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):                           # keine neue Regel an einer veröffentlichten Revision
            with tx(A) as c: add_rule(c, A, rid, 2)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                    # Spaltenrecht: Identität nicht änderbar
            with tx(A) as c: c.execute("UPDATE taktovia.tariff_revision SET tariff_class='X' WHERE id=%s", (rid,))
        # Revision ohne Regel oder mit gemischten Regeltypen ist nicht prüfbar
        with tx(A) as c:
            empty = uid()
            c.execute("INSERT INTO taktovia.tariff_revision(tenant_id,id,service_id,tariff_class,revision_no,valid_from,currency,reason,created_by) VALUES (%s,%s,%s,'STANDARD',9,'2030-01-01Z','EUR','x','user:ersteller')", (A, empty, svc))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.tariff_revision SET state='GEPRUEFT', reviewed_by='user:pruefer', reviewed_at=now() WHERE id=%s", (empty,))
        with tx(A) as c:
            add_rule(c, A, empty, 1); add_rule(c, A, empty, 2, rule_type='GRUNDPREIS_STRECKE', rate='0.5')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.tariff_revision SET state='GEPRUEFT', reviewed_by='user:pruefer', reviewed_at=now() WHERE id=%s", (empty,))
        # Regelform: Zonenpreis braucht beide Zonen, Festpreis keine
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_rule(c, A, empty, 3, rule_type='ZONENPREIS')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_rule(c, A, empty, 3, rule_type='FESTPREIS', zone_from=uid(), zone_to=uid())
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_rule(c, A, empty, 3, rule_type='UNBEKANNT')

    def test_mandantentrennung_rls_und_preisbindung(self):
        with tx(A) as c:
            svc = make_service(c, A); rid = add_revision(c, A, svc, state='VEROEFFENTLICHT')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.tariff_revision WHERE id=%s", (rid,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.tariff_rule WHERE revision_id=%s", (rid,)).fetchone()[0], 0)
        with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)):   # fremde tenant_id: RLS bzw. Trigger (die fremde Revision ist unsichtbar)
            with tx(B) as c: add_rule(c, A, rid, 5)
        # Preisbindung: fare_quote verweist auf eine vorhandene Tarifrevision desselben Mandanten
        def quote(c, tenant, revision, n=1):
            qa, cfg = uid(), uid()
            c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel) VALUES (%s,%s,now(),'WEB')", (tenant, qa))
            c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
                      "VALUES (%s,%s,'TENANT',%s,%s,'1','{}',now(),'user:u','Testdaten')", (tenant, cfg, tenant, n))
            c.execute("INSERT INTO taktovia.fare_quote(tenant_id,id,attempt_id,currency,pay_at_platform_minor,external_known_minor,external_unknown_leg_ids,expires_at,tariff_revision_id,config_revision_id,snapshot,content_hash) "
                      "VALUES (%s,%s,%s,'EUR',500,0,'{}',now(),%s,%s,'{}',%s)", (tenant, uid(), qa, revision, cfg, 'c' * 64))
        with tx(A) as c: quote(c, A, rid)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: quote(c, A, uid(), 2)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):                      # Revision eines anderen Mandanten
            with tx(B) as c: quote(c, B, rid)

    def test_parallele_ueberlappende_veroeffentlichung_genau_eine(self):
        with tx(A) as c:
            svc = make_service(c, A)
            r1 = add_revision(c, A, svc, revision_no=1, valid_from='2026-01-01Z', valid_to='2026-12-31Z', state='GEPRUEFT')
            r2 = add_revision(c, A, svc, revision_no=2, valid_from='2026-06-01Z', valid_to='2027-06-30Z', state='GEPRUEFT')
        barrier = threading.Barrier(2); results = []

        def worker(rid):
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10); publish(c, A, rid)
                results.append('ok')
            except (psycopg.errors.ExclusionViolation, psycopg.errors.DeadlockDetected):
                results.append('verloren')
        ts = [threading.Thread(target=worker, args=(r,)) for r in (r1, r2)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['ok', 'verloren'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.tariff_revision WHERE service_id=%s AND state='VEROEFFENTLICHT'", (svc,)).fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
