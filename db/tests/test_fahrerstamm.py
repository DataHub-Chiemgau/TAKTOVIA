"""WP-M3-05: Fahrerstamm im Vollausbau und Qualifikationsrevisionen unter der echten Runtime-Rolle: Zuordnung zu genau einer Mitgliedschaft des Mandanten (zusammengesetzter
Schlüssel, eindeutig, endgültig), Mandantentrennung, Revisionen append-only (alte bleiben lesbar), Freigabestatus, fortlaufende Nummer je Fahrer."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def membership(tenant, role='driver'):
    m = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,'https://idp.test/x',%s,%s)", (tenant, m, f'sub-{m}', role))
    return m


def driver(c, tenant, member=None):
    d = uid()
    c.execute("INSERT INTO taktovia.driver(tenant_id,id,membership_id,display_name,contact_channel,contact_value,created_by) VALUES (%s,%s,%s,'Erika Muster','PHONE','+491701234567',%s)",
              (tenant, d, member, ACTOR))
    return d


def qual(c, tenant, drv, code='PBEF', rev=1, status='APPROVED', seq=None, valid_to=None):
    q = uid()
    c.execute("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,valid_to,approval_status,driver_seq,created_by) "
              "VALUES (%s,%s,%s,%s,%s,'2026-01-01T00:00:00Z',%s,%s,%s,%s)", (tenant, q, drv, code, rev, valid_to, status, seq, ACTOR))
    return q


class Fahrerstamm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm305-a'); seed_tenant(B, 'm305-b')

    def test_fahrer_genau_einer_mitgliedschaft_des_mandanten(self):
        ma, mb = membership(A), membership(B)
        with tx(A) as c:
            d = driver(c, A, ma)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):   # eine Mitgliedschaft höchstens einem Fahrer
                driver(c, A, ma)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # Mitgliedschaft eines fremden Mandanten
                driver(c, A, mb)
        with tx(A) as c:
            driver(c, A, None); driver(c, A, None)   # Altbestand ohne Zuordnung bleibt zulässig (expand/contract)
        # endgültig, auch für die Administration
        with admin() as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.driver SET membership_id=%s WHERE id=%s", (membership(A), d))
        with tx(A) as c:
            c.execute("UPDATE taktovia.driver SET display_name='Neu' WHERE id=%s", (d,))   # Stammdaten bleiben änderbar

    def test_rls_fremder_mandant_und_leerer_kontext(self):
        with tx(A) as c: d = driver(c, A); q = qual(c, A, d)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.driver WHERE id=%s", (d,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.qualification_revision WHERE id=%s", (q,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                qual(c, A, d, rev=2)
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # Qualifikation an fremdem Fahrer
                qual(c, B, d)
        with tx(None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.qualification_revision").fetchone()[0], 0)

    def test_revisionen_sind_append_only_und_alte_bleiben_lesbar(self):
        with tx(A) as c:
            d = driver(c, A); q1 = qual(c, A, d, rev=1, seq=1); qual(c, A, d, rev=2, seq=2, valid_to='2026-12-31T00:00:00Z')
        for sql in ("UPDATE taktovia.qualification_revision SET valid_to='2027-01-01T00:00:00Z' WHERE id=%s", "DELETE FROM taktovia.qualification_revision WHERE id=%s"):
            for ctx in ('runtime', 'admin'):
                with (tx(A) if ctx == 'runtime' else admin()) as c:
                    with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)) as cm:
                        c.execute(sql, (q1,))
                    if ctx == 'admin': self.assertEqual(cm.exception.sqlstate, '23514')
        with tx(A) as c:
            self.assertEqual([r[0] for r in c.execute("SELECT revision FROM taktovia.qualification_revision WHERE driver_id=%s ORDER BY revision", (d,))], [1, 2])
            with self.assertRaises(psycopg.errors.UniqueViolation):   # Revisionsnummer je Qualifikation eindeutig
                qual(c, A, d, rev=2, seq=3)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):   # Folgenummer je Fahrer eindeutig
                qual(c, A, d, code='ANDERE', rev=1, seq=2)

    def test_freigabestatus_und_gueltigkeit_per_check(self):
        with tx(A) as c: d = driver(c, A)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                qual(c, A, d, status='ERFUNDEN')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):   # Ende vor Beginn
                qual(c, A, d, valid_to='2025-01-01T00:00:00Z')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):   # wartend darf keinen Freigeber tragen
                c.execute("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,approved_by) "
                          "VALUES (%s,%s,%s,'PBEF',1,now(),'PENDING',%s)", (A, uid(), d, ACTOR))
        with tx(A) as c:   # Altbestand im Zuschnitt von WP-M1-35 (ohne die neuen Spalten) gilt als freigegeben
            q = uid()
            c.execute("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,evidence_ref) VALUES (%s,%s,%s,'PBEF',1,now()-interval '1 year','akte-1')", (A, q, d))
            self.assertEqual(c.execute("SELECT approval_status FROM taktovia.qualification_revision WHERE id=%s", (q,)).fetchone()[0], 'APPROVED')

    def test_qualifikationsnummer_steigt_nur(self):
        with tx(A) as c:
            d = driver(c, A)
            c.execute("UPDATE taktovia.driver SET qualification_seq=3 WHERE id=%s", (d,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.driver SET qualification_seq=2 WHERE id=%s", (d,))


if __name__ == '__main__':
    unittest.main()
