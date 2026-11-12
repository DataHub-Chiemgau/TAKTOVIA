"""WP-M2-13 / LH-6.5-OPT-011: Katalog `rejection_reason` und seine Durchsetzung an `travel_attempt` unter der echten Runtime-Rolle:
unbekannter Code wird beim Schreiben abgewiesen, genau ein Primärgrund (weitere nur als Nebengründe), technischer Fehler ist nie eine Ablehnung."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant, runtime

A = uid()
CODES = {'LEAD_TIME', 'HORIZON', 'WINDOW_NOT_FEASIBLE', 'OUTSIDE_HOURS', 'OUTSIDE_AREA', 'RULE_FORBIDDEN', 'NO_STOP_IN_RANGE', 'NO_ACCESSIBLE_STOP', 'NO_ROUTE',
         'EQUIPMENT_MISSING', 'NO_STAFF', 'NO_CAPACITY', 'TECHNICAL'}


def attempt(c, primary=None, secondary=(), outcome=None):
    """Versuch mit Ergebnis; ohne `outcome` richtet es sich nach dem Primärgrund (REJECTED) bzw. OFFERED."""
    aid = uid()
    outcome = outcome or ('REJECTED' if primary else 'OFFERED')
    c.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,primary_rejection,secondary_rejections,outcome) VALUES (%s,%s,now(),'WEB',%s,%s,%s)",
              (A, aid, primary, list(secondary), outcome))
    return aid


class Ablehnungsgruende(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm213-a')

    def test_katalog_ist_vollstaendig_global_und_nur_lesbar(self):
        with tx(A) as c:
            rows = {r[0]: r for r in c.execute('SELECT code, priority, counts_as_rejection, metric FROM taktovia.rejection_reason')}
            self.assertEqual(set(rows), CODES)
            self.assertEqual(len({r[1] for r in rows.values()}), len(rows), 'Prioritäten eindeutig')
            self.assertEqual([k for k, r in rows.items() if not r[2]], ['TECHNICAL'], 'nur TECHNICAL zählt nicht als Ablehnung')
            for stmt in ("INSERT INTO taktovia.rejection_reason(code,priority,counts_as_rejection,label,next_action) VALUES ('X_NEU',999,true,'a','b')",
                         "UPDATE taktovia.rejection_reason SET priority = priority + 1000", 'DELETE FROM taktovia.rejection_reason'):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute(stmt)
                c.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(A),))

    def test_unbekannter_code_wird_abgewiesen(self):  # AK negativ OPT-011
        for primary, secondary in (('GIBT_ES_NICHT', ()), ('OUTSIDE_AREA', ('GIBT_ES_NICHT',)), ('outside_area', ())):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.ForeignKeyViolation, msg=f'{primary} {secondary}'): attempt(c, primary, secondary)
        with tx(A) as c: attempt(c, 'NO_CAPACITY', ('OUTSIDE_AREA',))   # bekannte Codes gehen

    def test_genau_ein_primaergrund_nebengruende_nur_zusaetzlich(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Nebengrund ohne Primärgrund'): attempt(c, None, ('OUTSIDE_AREA',), outcome='OFFERED')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Primärgrund erneut als Nebengrund'): attempt(c, 'NO_CAPACITY', ('NO_CAPACITY',))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='doppelte Nebengründe'): attempt(c, 'NO_CAPACITY', ('OUTSIDE_AREA', 'OUTSIDE_AREA'))
        with tx(A) as c:
            aid = attempt(c, 'NO_CAPACITY', ('OUTSIDE_AREA',))
        with tx(A) as c:   # ein zweiter (anderer) Primärgrund ist nicht möglich: der Ausgang ist nach dem Schreiben endgültig
            for stmt in ("UPDATE taktovia.travel_attempt SET primary_rejection='OUTSIDE_AREA' WHERE id=%s", "UPDATE taktovia.travel_attempt SET secondary_rejections='{}' WHERE id=%s",
                         "UPDATE taktovia.travel_attempt SET outcome='OFFERED', primary_rejection=NULL WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt): c.execute(stmt, (aid,))
                c.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(A),))

    def test_technischer_fehler_ist_keine_fachliche_ablehnung(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='TECHNICAL nie Primärgrund'): attempt(c, 'TECHNICAL')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='TECHNICAL nie Nebengrund'): attempt(c, 'NO_CAPACITY', ('TECHNICAL',))
        with tx(A) as c:
            aid = attempt(c, None, (), outcome='TECHNICAL_ERROR')   # erfasst, aber ohne Ablehnungsgrund
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.travel_attempt WHERE id=%s AND primary_rejection IS NULL AND outcome='TECHNICAL_ERROR'", (aid,)).fetchone()[0], 1)
            # der technische Versuch darf genau einmal in ein fachliches Ergebnis übergehen (Retry), danach ist er endgültig
            c.execute("UPDATE taktovia.travel_attempt SET outcome='REJECTED', primary_rejection='NO_CAPACITY' WHERE id=%s", (aid,))
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.travel_attempt SET primary_rejection='OUTSIDE_AREA' WHERE id=%s", (aid,))

    def test_abfrage_jeder_abgelehnte_versuch_traegt_primaer_und_nebengruende(self):  # AK 5: Datenbasis für WP-M4-46/-51
        with tx(A) as c:
            attempt(c, 'OUTSIDE_AREA', ('NO_STOP_IN_RANGE',)); attempt(c, 'NO_CAPACITY'); attempt(c)
            rows = c.execute("""SELECT count(*) FILTER (WHERE outcome = 'REJECTED'), count(*) FILTER (WHERE outcome = 'REJECTED' AND primary_rejection IS NOT NULL),
                                       count(*) FILTER (WHERE outcome <> 'REJECTED' AND (primary_rejection IS NOT NULL OR cardinality(secondary_rejections) > 0))
                                FROM taktovia.travel_attempt WHERE tenant_id = %s""", (A,)).fetchone()
            self.assertEqual(rows[0], rows[1]); self.assertGreaterEqual(rows[0], 2); self.assertEqual(rows[2], 0)
            # Auswertung je Kennzahl über den Katalog (Join statt fest verdrahteter Listen)
            per = dict(c.execute("""SELECT r.metric, count(*) FROM taktovia.travel_attempt t JOIN taktovia.rejection_reason r ON r.code = t.primary_rejection
                                    WHERE t.tenant_id = %s AND r.counts_as_rejection GROUP BY r.metric""", (A,)).fetchall())
            self.assertGreaterEqual(per.get('AREA', 0), 1); self.assertGreaterEqual(per.get('CAPACITY', 0), 1)


if __name__ == '__main__':
    unittest.main()
