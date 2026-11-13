"""WP-M2-13 / LH-6.5-OPT-011: Katalog `rejection_reason` und seine Durchsetzung an `travel_attempt` unter der echten Runtime-Rolle:
unbekannter Code wird beim Schreiben abgewiesen, genau ein Primärgrund (weitere nur als Nebengründe), technischer Fehler ist nie eine Ablehnung."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant, runtime

A = uid()
CODES = {'LEAD_TIME', 'HORIZON', 'WINDOW_NOT_FEASIBLE', 'OUTSIDE_HOURS', 'OUTSIDE_AREA', 'RULE_FORBIDDEN', 'NO_STOP_IN_RANGE', 'NO_ACCESSIBLE_STOP', 'NO_ROUTE',
         'EQUIPMENT_MISSING', 'NO_STAFF', 'NO_CAPACITY', 'TECHNICAL',
         'NO_TARIFF', 'NO_VEHICLE', 'SHIFT_ENDED', 'MAX_WAIT_EXCEEDED', 'MAX_DETOUR_EXCEEDED', 'PARTNER_UNAVAILABLE'}   # ab WP-M4-48/-49/-50 erweitert
# KPI-003: die fünf ressourcenbezogenen Gründe und ihre Katalogkennzahl; KPI-004: die sieben weiteren Gründe (Arbeitsnamen des Vertrags -> Katalogcode)
RESOURCE_CODES = {'NO_CAPACITY': 'CAPACITY', 'NO_VEHICLE': 'CAPACITY', 'NO_STAFF': 'PERSONNEL', 'SHIFT_ENDED': 'PERSONNEL', 'EQUIPMENT_MISSING': 'EQUIPMENT'}
OTHER_CODES = {'OUTSIDE_AREA': 'AREA', 'OUTSIDE_HOURS': 'TIME_WINDOW', 'LEAD_TIME': 'TIME_WINDOW', 'MAX_WAIT_EXCEEDED': 'TIME_WINDOW', 'MAX_DETOUR_EXCEEDED': None, 'PARTNER_UNAVAILABLE': 'PARTNER', 'TECHNICAL': None}


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
            for stmt in ("UPDATE taktovia.rejection_reason SET label = 'x'", "UPDATE taktovia.rejection_reason SET reason_group = 'AREA'", "DELETE FROM taktovia.rejection_reason"):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute(stmt)
                c.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(A),))
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


def fact(c, primary=None, secondary=(), outcome=None, requested='2026-12-01T05:00:00Z'):
    c.execute("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,primary_reason,secondary_reasons,time_mode,requested_at,passengers) VALUES (%s,%s,%s,%s,%s,%s,'DEPARTURE',%s,1)",
              (A, uid(), uid(), outcome or ('REJECTED' if primary else 'RECORDED'), primary, list(secondary), requested))


class AblehnungskatalogUndFakten(unittest.TestCase):
    """WP-M4-48 bis -50 (KPI-002/003/004): Gruppen, Texte, Kennzahlzuordnung und die Durchsetzung an der Faktenbasis `fact_attempt`."""
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm213-a')

    def test_unbekannter_code_und_sekundaer_ohne_primaergrund_werden_abgewiesen(self):
        for primary, secondary in (('GIBT_ES_NICHT', ()), ('OUTSIDE_AREA', ('GIBT_ES_NICHT',)), ('outside_area', ())):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.ForeignKeyViolation, msg=f'Versuch {primary} {secondary}'): attempt(c, primary, secondary)
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.ForeignKeyViolation, msg=f'Fakt {primary} {secondary}'): fact(c, primary, secondary)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Nebengrund ohne Primärgrund (Versuch)'): attempt(c, None, ('OUTSIDE_AREA',), outcome='OFFERED')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Nebengrund ohne Primärgrund (Fakt)'): fact(c, None, ('OUTSIDE_AREA',))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Primärgrund als Nebengrund'): fact(c, 'NO_CAPACITY', ('NO_CAPACITY',))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='doppelte Nebengründe'): fact(c, 'NO_CAPACITY', ('OUTSIDE_AREA', 'OUTSIDE_AREA'))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='technischer Fehler ist nie ein Ablehnungsgrund'): fact(c, 'TECHNICAL')
        with tx(A) as c:
            fact(c, 'NO_CAPACITY', ('OUTSIDE_AREA', 'NO_TARIFF')); fact(c)   # gültige Fälle

    def test_alle_sieben_codes_haben_deutschen_text(self):
        with tx(A) as c:
            rows = {r[0]: r for r in c.execute("SELECT code, label, next_action, texts->>'en', reason_group, metric FROM taktovia.rejection_reason")}
            for code in list(RESOURCE_CODES) + list(OTHER_CODES):
                self.assertIn(code, rows, code)
            for code, (_, label, action, en, group, metric) in rows.items():
                self.assertTrue(label and label.strip(), f'{code}: deutscher Text fehlt'); self.assertTrue(label.endswith('.'), f'{code}: Text ist kein Satz')
                self.assertTrue(action and action.strip(), f'{code}: nächste Handlung fehlt'); self.assertTrue(en and en.strip(), f'{code}: englischer Text fehlt')
                self.assertTrue(group, code)
            # ein Code ohne deutschen Text (NULL oder leer) wird von der Datenbank abgewiesen
            with admin() as a:
                for bad in ('NULL', "''"):
                    with self.assertRaises((psycopg.errors.NotNullViolation, psycopg.errors.CheckViolation), msg=bad):
                        a.execute(f"INSERT INTO taktovia.rejection_reason(code,priority,counts_as_rejection,label,next_action,reason_group) VALUES ('X_TEXT',9999,true,{bad},'a','AREA')")
                    a.rollback()

    def test_fuenf_ressourcencodes_sind_unterschiedlich_und_den_katalogkennzahlen_zugeordnet(self):
        with tx(A) as c:
            rows = {r[0]: r for r in c.execute("SELECT code, metric, reason_group, priority FROM taktovia.rejection_reason")}
        self.assertEqual(len(RESOURCE_CODES), 5)
        for code, metric in RESOURCE_CODES.items():
            self.assertEqual(rows[code][1], metric, code)
        order = sorted(RESOURCE_CODES, key=lambda k: rows[k][3])
        self.assertEqual(order, ['NO_VEHICLE', 'EQUIPMENT_MISSING', 'NO_STAFF', 'SHIFT_ENDED', 'NO_CAPACITY'], 'Rangfolge bei mehreren zutreffenden Gründen')

    def test_weitere_codes_sind_einzeln_auswertbar_und_gruppiert(self):
        with tx(A) as c:
            rows = {r[0]: r for r in c.execute("SELECT code, metric, reason_group, counts_as_rejection FROM taktovia.rejection_reason")}
        for code, metric in OTHER_CODES.items():
            self.assertEqual(rows[code][1], metric, code)
        self.assertEqual({rows[c][2] for c in ('OUTSIDE_HOURS',)}, {'TIME_OPENING'}); self.assertEqual({rows[c][2] for c in ('LEAD_TIME', 'MAX_WAIT_EXCEEDED')}, {'TIME_DEADLINE'})
        self.assertFalse(rows['TECHNICAL'][3]); self.assertTrue(rows['PARTNER_UNAVAILABLE'][3])
        self.assertEqual(rows['NO_TARIFF'][2], 'TARIFF')


if __name__ == '__main__':
    unittest.main()
