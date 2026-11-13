"""WP-M3-43 / LH-6.22-DMG-001, DMG-002: Schadenakte in der Datenbank unter der echten Runtime-Rolle. Originalabnahme: Schaden ohne aktive Buchung mit eindeutiger
Fallnummer; Pflichtdaten validiert; Fall über Fahrzeug und Datum auffindbar. Hier: Eindeutigkeit und Lückenlosigkeit der Fallnummer je Mandant (auch unter
Parallelität und Rollback), Mandantenisolation, Plausibilität der Ereigniszeit, Dublettenschutz über die Geräteereignis-ID."""
import threading
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def vehicle(c, tenant):
    vid = uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org','{}'::jsonb)", (tenant, vid))
    return vid


def new_case(c, tenant, veh, occurred="clock_timestamp() - interval '1 hour'", device_event=None, category='BODY'):
    """Legt eine Akte an wie die Anwendung: Nummer aus der Funktion in derselben Transaktion."""
    cid = uid()
    number = c.execute("SELECT taktovia.next_defect_case_number()").fetchone()[0]
    c.execute(f"INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by,device_event_id) "
              f"VALUES (%s,%s,%s,%s,%s,{occurred},%s,'Kratzer',%s,%s)", (tenant, cid, number, veh, uid(), category, ACTOR, device_event))
    return cid, number


class Schadenakte(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm343-a'); seed_tenant(B, 'm343-b')
        with tx(A) as c: cls.va = vehicle(c, A)
        with tx(B) as c: cls.vb = vehicle(c, B)

    def test_fallnummer_je_mandant_eindeutig(self):
        with tx(A) as c:
            n1 = new_case(c, A, self.va)[1]; n2 = new_case(c, A, self.va)[1]
        self.assertRegex(n1, r'^SCH-\d{4}-\d{6,}$'); self.assertNotEqual(n1, n2)
        # dieselbe Nummer ein zweites Mal im Mandanten: UNIQUE (tenant_id, case_number)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):
                c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) "
                          "VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BODY','x',%s)", (A, uid(), n1, self.va, uid(), ACTOR))
        # in einem anderen Mandanten ist dieselbe Nummer zulässig (eigener Zähler) und verrät den Mandanten nicht
        with tx(B) as c:
            c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) "
                      "VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BODY','x',%s)", (B, uid(), n1, self.vb, uid(), ACTOR))

    def test_fallnummer_eindeutig_je_mandant(self):
        # Zähler je Mandant: der erste Fall eines frischen Mandanten trägt die Nummer 000001
        t = uid(); seed_tenant(t, 'm343-frisch')
        with tx(t) as c:
            v = vehicle(c, t)
            self.assertTrue(new_case(c, t, v)[1].endswith('-000001'))
            self.assertTrue(new_case(c, t, v)[1].endswith('-000002'))

    def test_defect_case_rls_mandantenisolation(self):
        with tx(A) as c: cid, number = new_case(c, A, self.va)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_case WHERE id=%s", (cid,)).fetchone()[0], 0)
            # Anlegen unter fremdem Mandanten-Schlüssel: WITH CHECK verweigert
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) "
                          "VALUES (%s,%s,'SCH-2026-999999',%s,%s,clock_timestamp(),'BODY','x',%s)", (A, uid(), self.va, uid(), ACTOR))

    def test_rls_ohne_tenantkontext_keine_zeilen(self):
        with tx(A) as c: new_case(c, A, self.va)
        with tx(None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_case").fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_case_counter").fetchone()[0], 0)

    def test_parallele_vergabe_lueckenlos_und_eindeutig(self):
        t = uid(); seed_tenant(t, 'm343-parallel')
        with tx(t) as c: v = vehicle(c, t)
        n, numbers, errors = 12, [], []
        barrier = threading.Barrier(n)

        def work():
            try:
                barrier.wait()
                with tx(t) as c:
                    numbers.append(new_case(c, t, v)[1])
            except BaseException as e:  # noqa: BLE001
                errors.append(e)
        threads = [threading.Thread(target=work) for _ in range(n)]
        [th.start() for th in threads]; [th.join() for th in threads]
        self.assertEqual(errors, [])
        self.assertEqual(len(set(numbers)), n)
        self.assertEqual(sorted(int(x.rsplit('-', 1)[1]) for x in numbers), list(range(1, n + 1)), 'lückenlos 1..n')

    def test_rollback_hinterlaesst_keine_luecke(self):
        t = uid(); seed_tenant(t, 'm343-rollback')
        with tx(t) as c: v = vehicle(c, t); new_case(c, t, v)          # 000001
        with self.assertRaises(RuntimeError):
            with tx(t) as c:
                new_case(c, t, v)                                       # 000002, wird zurückgerollt
                raise RuntimeError('abbruch')
        with tx(t) as c:
            self.assertTrue(new_case(c, t, v)[1].endswith('-000002'))

    def test_zaehler_steigt_nur_um_eins(self):
        t = uid(); seed_tenant(t, 'm343-zaehler')
        with tx(t) as c: v = vehicle(c, t); new_case(c, t, v)
        with tx(t) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_case_counter SET last_number = 500")
        with tx(t) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_case_counter SET last_number = 1")
        with tx(t) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("DELETE FROM taktovia.defect_case_counter")

    def test_ereigniszeit_in_der_zukunft_nicht_speicherbar(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                new_case(c, A, self.va, occurred="clock_timestamp() + interval '1 hour'")
        with tx(A) as c:   # innerhalb der Toleranz von 60 s zulässig
            new_case(c, A, self.va, occurred="clock_timestamp() + interval '10 seconds'")

    def test_geraeteereignis_id_verhindert_zweite_akte(self):
        ev = uid()
        with tx(A) as c: new_case(c, A, self.va, device_event=ev)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):
                new_case(c, A, self.va, device_event=ev)
        with tx(B) as c: new_case(c, B, self.vb, device_event=ev)   # je Mandant

    def test_akte_ueber_fahrzeug_und_datum_auffindbar(self):
        t = uid(); seed_tenant(t, 'm343-suche')
        with tx(t) as c:
            v1, v2 = vehicle(c, t), vehicle(c, t)
            target, _ = new_case(c, t, v1); new_case(c, t, v2)
            rows = c.execute("SELECT id FROM taktovia.defect_case WHERE vehicle_id=%s AND reported_at >= now() - interval '1 day' AND reported_at < now() + interval '1 day'", (v1,)).fetchall()
            self.assertEqual([r[0] for r in rows], [target])
            plan = "\n".join(r[0] for r in c.execute("EXPLAIN SELECT id FROM taktovia.defect_case WHERE vehicle_id=%s AND reported_at >= now()", (v1,)).fetchall())
            self.assertIn('defect_case', plan)

    # ---- WP-M3-45 / LH-6.22-DMG-005: Klasse, Regelversion und Klassifikationshistorie ----

    def test_klasse_check_und_klassifikationshistorie_append_only(self):
        classes = ['SAFETY_CRITICAL', 'OPERATIONALLY_RELEVANT', 'COSMETIC', 'OTHER']
        # genau eine der vier Klassen, nie ohne Regelversion und Herkunft
        with tx(A) as c:
            for k in classes:
                cid, _ = new_case(c, A, self.va)
                c.execute("UPDATE taktovia.defect_case SET severity=%s, severity_rule_version='dmg-klassen-v1', severity_source='RULE' WHERE id=%s", (k, cid))
        for bad_sql in ("severity='KRITISCH', severity_rule_version='v1', severity_source='RULE'",
                        "severity='COSMETIC'",
                        "severity='COSMETIC', severity_rule_version='v1'",
                        "severity='COSMETIC', severity_rule_version='v1', severity_source='MAGIE'",
                        "severity='COSMETIC', severity_rule_version='MIT LEERZEICHEN', severity_source='RULE'"):
            with tx(A) as c:
                cid, _ = new_case(c, A, self.va)
                with self.assertRaises(psycopg.errors.CheckViolation, msg=bad_sql):
                    c.execute(f"UPDATE taktovia.defect_case SET {bad_sql} WHERE id=%s", (cid,))
        # Historie: Erstklassifikation, Bestätigung, Korrektur nur mit Grund
        def hist(c, cid, frm, to, source='RULE', reason=None):
            c.execute("INSERT INTO taktovia.defect_classification_history(tenant_id,id,defect_id,from_severity,to_severity,rule_version,source,reason,actor,defect_revision) "
                      "VALUES (%s,%s,%s,%s,%s,'dmg-klassen-v1',%s,%s,%s,1)", (A, uid(), cid, frm, to, source, reason, ACTOR))
        with tx(A) as c:
            cid, _ = new_case(c, A, self.va)
            hist(c, cid, None, 'OPERATIONALLY_RELEVANT')
            hist(c, cid, 'OPERATIONALLY_RELEVANT', 'OPERATIONALLY_RELEVANT', 'TRIAGE')          # Bestätigung ohne Grund
            hist(c, cid, 'OPERATIONALLY_RELEVANT', 'SAFETY_CRITICAL', 'TRIAGE', 'Bremsscheibe gerissen')
        with tx(A) as c:
            cid, _ = new_case(c, A, self.va)
            with self.assertRaises(psycopg.errors.CheckViolation):
                hist(c, cid, 'COSMETIC', 'SAFETY_CRITICAL', 'TRIAGE', None)                      # Korrektur ohne Grund
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                hist(c, new_case(c, A, self.va)[0], None, 'GEFAEHRLICH')
        # append-only auch für die Administration, RLS trennt Mandanten
        with tx(A) as c:
            cid, _ = new_case(c, A, self.va); hist(c, cid, None, 'COSMETIC')
        for sql in ("UPDATE taktovia.defect_classification_history SET to_severity='OTHER'", "DELETE FROM taktovia.defect_classification_history"):
            with admin() as a:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    a.execute(sql)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_classification_history").fetchone()[0], 0)



class Herkunft(unittest.TestCase):
    """LH-6.22-DMG-003: Herkunft Prüfschritt je (Mandant, Prüflauf, Prüfschritt) höchstens einmal (ausführlich: test_defect_origin.py)."""

    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm343-a'); seed_tenant(B, 'm343-b')

    def test_herkunft_eindeutig_je_pruefschritt(self):
        from test_damage_ack import run, vehicle as run_vehicle
        veh = run_vehicle(A); rid = run(A, veh)

        def with_origin(c):
            cid = uid(); number = c.execute("SELECT taktovia.next_defect_case_number()").fetchone()[0]
            c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by,source_inspection_id,source_step_code) "
                      "VALUES (%s,%s,%s,%s,%s,clock_timestamp() - interval '1 hour','BODY','Mangel',%s,%s,'S1')", (A, cid, number, veh, uid(), ACTOR, rid))
        with tx(A) as c:
            with_origin(c)
        with self.assertRaises(psycopg.errors.UniqueViolation), tx(A) as c:
            with_origin(c)


if __name__ == '__main__':
    unittest.main()