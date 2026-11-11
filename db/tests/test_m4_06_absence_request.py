"""WP-M4-06: absence_request unter der echten Runtime-Rolle: Mandantentrennung, neutrale Art ohne Diagnose-/Nachweisfeld, Zeitraumprüfung,
Rücknahme als einzige zulässige Änderung (Revision +1, endgültig), Identität unveränderlich, kein DELETE."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()


def absence(c, tenant, person='emp-1', start='2026-12-01T00:00:00Z', end='2026-12-05T00:00:00Z', kind='LEAVE'):
    aid = uid()
    c.execute("INSERT INTO taktovia.absence_request(tenant_id,id,person_ref,starts_at,ends_at,kind,created_by) VALUES (%s,%s,%s,%s,%s,%s,'hr-1')", (tenant, aid, person, start, end, kind))
    return aid


class Abwesenheit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm406-a'); seed_tenant(B, 'm406-b')

    def test_schema_enthaelt_kein_diagnose_oder_nachweisfeld(self):  # AK 3
        with admin() as c:
            cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='absence_request'")]
        self.assertTrue(cols)
        for forbidden in ('diagnos', 'medical', 'health', 'sick', 'certificate', 'attest', 'nachweis', 'comment', 'note', 'reason', 'text'):
            self.assertEqual([c for c in cols if forbidden in c], [], f'Spalte mit {forbidden}')

    def test_art_ist_ein_neutraler_katalogwert(self):  # keine Krankheit (TIM-010, M7)
        with tx(A) as c: absence(c, A, kind='TRAINING')
        for bad in ('SICK', 'Krank', 'DIAGNOSIS', ''):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): absence(c, A, kind=bad)

    def test_zeitraum_muss_positiv_sein(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): absence(c, A, start='2026-12-05T00:00:00Z', end='2026-12-05T00:00:00Z')

    def test_rls_fremder_mandant_sieht_nichts_und_schreibt_nicht(self):  # Abwesenheitsteil von AT-49
        with tx(A) as c: aid = absence(c, A)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.absence_request WHERE id=%s', (aid,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): absence(c, A)   # WITH CHECK
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.absence_request').fetchone()[0], 0)

    def test_rücknahme_ist_die_einzige_aenderung_und_endgueltig(self):
        with tx(A) as c: aid = absence(c, A)
        with tx(A) as c:
            for stmt in ("UPDATE taktovia.absence_request SET person_ref='x' WHERE id=%s", "UPDATE taktovia.absence_request SET kind='OTHER' WHERE id=%s",
                         "UPDATE taktovia.absence_request SET starts_at=starts_at - interval '1 day' WHERE id=%s", "UPDATE taktovia.absence_request SET created_by='y' WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt):
                    with c.transaction(): c.execute(stmt, (aid,))
        with tx(A) as c:
            rev = c.execute("UPDATE taktovia.absence_request SET state='WITHDRAWN' WHERE id=%s RETURNING revision", (aid,)).fetchone()[0]
            self.assertEqual(rev, 2)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.absence_request SET state='APPROVED' WHERE id=%s", (aid,))   # Rücknahme ist endgültig

    def test_kein_delete(self):
        with tx(A) as c: aid = absence(c, A)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.absence_request WHERE id=%s', (aid,))


if __name__ == '__main__':
    unittest.main()
