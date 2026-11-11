"""WP-M1-30 / LH-6.2-IAM-011: consent_revision ist append-only und mandantengetrennt (echte Runtime-Rolle)."""
import unittest

import psycopg

from helpers import admin, runtime, seed_tenant, set_tenant, uid

A, B = uid(), uid()


def passenger(tenant):
    p = uid()
    c = runtime()
    try:
        set_tenant(c, tenant)
        c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now()+interval '1 year')", (tenant, p, 'tok-' + str(p)))
        c.commit()
    finally:
        c.close()
    return p


INSERT = "INSERT INTO taktovia.consent_revision(tenant_id,id,passenger_id,purpose,sequence,granted,text_version) VALUES (%s,%s,%s,%s,%s,%s,%s)"


class Einwilligung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'consent-a-' + str(A)[:8]); seed_tenant(B, 'consent-b-' + str(B)[:8])
        cls.pa, cls.pb = passenger(A), passenger(B)
        cls.rev = uid()
        for t, p in ((A, cls.pa), (B, cls.pb)):
            c = runtime()
            try:
                set_tenant(c, t)
                c.execute(INSERT, (t, cls.rev if t == A else uid(), p, 'MARKETING', 1, True, 'v1'))
                c.commit()
            finally:
                c.close()

    def test_revision_update_delete_verweigert(self):
        c = runtime()
        try:
            set_tenant(c, A)
            # Die Runtime-Rolle hat kein UPDATE/DELETE-Recht; der Trigger verweigert es zusätzlich auch ohne Tabellenrecht (Administration)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("UPDATE taktovia.consent_revision SET granted=false WHERE id=%s", (self.rev,))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("DELETE FROM taktovia.consent_revision WHERE id=%s", (self.rev,))
        finally:
            c.rollback(); c.close()
        with admin() as a:
            a.execute("SELECT set_config('app.tenant_id', %s, false)", (str(A),))
            for stmt in ("UPDATE taktovia.consent_revision SET granted=false WHERE id=%s", "DELETE FROM taktovia.consent_revision WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation):
                    a.execute(stmt, (self.rev,))
                a.rollback()
            self.assertTrue(a.execute("SELECT granted FROM taktovia.consent_revision WHERE id=%s", (self.rev,)).fetchone()[0])

    def test_rls_und_fk_mandantentrennung(self):
        c = runtime()
        try:
            set_tenant(c, A)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.consent_revision').fetchone()[0], 1)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.consent_revision WHERE passenger_id=%s', (self.pb,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # fremde tenant_id: WITH CHECK
                c.execute(INSERT, (B, uid(), self.pb, 'MARKETING', 2, False, 'v1'))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # fremde passenger_id im eigenen Mandanten: zusammengesetzter FK
                c.execute(INSERT, (A, uid(), self.pb, 'MARKETING', 2, False, 'v1'))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.UniqueViolation):   # eindeutige Reihenfolge je Fahrgast und Zweck
                c.execute(INSERT, (A, uid(), self.pa, 'MARKETING', 1, False, 'v1'))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):   # betriebliche Mitteilung ist nicht abwählbar
                c.execute(INSERT, (A, uid(), self.pa, 'OPERATIONAL_SAFETY', 1, False, 'v1'))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Zweckklassen sind fest
                c.execute(INSERT, (A, uid(), self.pa, 'WERBUNG', 1, True, 'v1'))
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
