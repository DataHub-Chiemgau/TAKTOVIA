"""WP-M0-21: Regressionstests für die Befunde B-01/B-02 aus dem Befundbericht."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

T, LE = uid(), uid()


def exec_(stmt, params=()):
    with tx(T) as c:
        return c.execute(stmt, params).rowcount


class Haertung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T, 'haertung')
        cls.closed, cls.open_ = uid(), uid()
        with tx(T) as c:
            c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (T, LE))
            # seit WP-M4-36 überschneiden sich Perioden eines Rechtsträgers nie: die offene Periode folgt der geschlossenen
            for pid, closed, starts, ends in ((cls.closed, True, '2026-09-01', '2026-10-01'), (cls.open_, False, '2026-10-01', '2026-11-01')):
                c.execute("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on,closed_at) VALUES (%s,%s,%s,%s,%s,%s)",
                          (T, pid, LE, starts, ends, 'now' if closed else None))

    def test_b01_geschlossene_periode_nicht_wieder_oeffnen_oder_verschieben(self):
        for stmt in ('UPDATE taktovia.fiscal_period SET closed_at=NULL WHERE id=%s',
                     "UPDATE taktovia.fiscal_period SET ends_on='2027-01-01' WHERE id=%s",
                     "UPDATE taktovia.fiscal_period SET starts_on='2026-01-01' WHERE id=%s",
                     'DELETE FROM taktovia.fiscal_period WHERE id=%s'):
            with self.assertRaises((psycopg.errors.RaiseException, psycopg.errors.InsufficientPrivilege), msg=stmt):
                exec_(stmt, (self.closed,))

    def test_b01_auch_fuer_administrator_gesperrt_trigger_ist_unabhaengig_von_grants(self):
        with admin() as a:
            with self.assertRaises(psycopg.errors.RaiseException):
                a.execute('UPDATE taktovia.fiscal_period SET closed_at=NULL WHERE id=%s', (self.closed,))

    def test_b01_offene_periode_kann_geschlossen_werden_identitaet_bleibt(self):
        self.assertEqual(exec_('UPDATE taktovia.fiscal_period SET closed_at=now() WHERE id=%s', (self.open_,)), 1)
        with self.assertRaises(psycopg.errors.RaiseException):
            exec_('UPDATE taktovia.fiscal_period SET id=%s WHERE id=%s', (uid(), self.open_))

    def test_b02_runtime_aendert_mandantenstamm_nicht(self):
        # WP-M1-05: Mandantenanlage/Statuswechsel durch die Plattformverwaltung brauchen spaltenweise Rechte (INSERT id/name/status, UPDATE status).
        # Name, Kennung und Löschen bleiben der Runtime-Rolle verwehrt (B-02), ebenso jede Änderung eines fremden Mandanten (RLS).
        for stmt in ("UPDATE taktovia.tenant SET name='x'", "UPDATE taktovia.tenant SET id=gen_random_uuid()", 'DELETE FROM taktovia.tenant'):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt):
                exec_(stmt)
        self.assertEqual(exec_("UPDATE taktovia.tenant SET status='ACTIVE' WHERE id=%s", (uid(),)), 0)  # fremder Mandant: unsichtbar
        self.assertEqual(exec_('SELECT 1 FROM taktovia.tenant'), 1)  # Lesen des eigenen Mandanten bleibt möglich


if __name__ == '__main__':
    unittest.main()
