"""WP-M3-20 / LH-6.21-CHK-011, LH-6.22-DMG-003: Herkunft der Schadenakte (Prüfschritt, Incident) in der Datenbank unter der echten Runtime-Rolle. Je Prüfschritt höchstens eine Akte
(auch bei echt gleichzeitigen Transaktionen), eine Akte hat höchstens eine Herkunft, die Herkunft ist unveränderlich, und das Verknüpfen berührt die Antwort eines abgeschlossenen
Prüflaufs nie (die Gegenreferenz steht ausschließlich an der Akte, nicht an `inspection_answer`)."""
import threading
import unittest

import psycopg

from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid
from test_damage_ack import USER, run, vehicle

A, B = uid(), uid()


def answer(tenant, inspection, step='S1', value='DEFECT'):
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,note,answered_at,created_by) VALUES (%s,%s,%s,%s,%s,'Riss',now(),%s)",
                  (tenant, uid(), inspection, step, value, USER))


def case(c, tenant, veh, inspection=None, step=None, incident=None):
    cid = uid()
    number = c.execute("SELECT taktovia.next_defect_case_number()").fetchone()[0]
    c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by,source_inspection_id,source_step_code,source_incident_id) "
              "VALUES (%s,%s,%s,%s,%s,clock_timestamp() - interval '1 hour','BODY','Mangel',%s,%s,%s,%s)", (tenant, cid, number, veh, uid(), USER, inspection, step, incident))
    return cid


def incident(tenant, veh):
    iid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.incident(tenant_id,id,kind,vehicle_id,dedupe_key,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE',%s,%s,'system:test')", (tenant, iid, veh, f'VEHICLE_OUTAGE:{veh}'))
    return iid


class Herkunft(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm320-a'); seed_tenant(B, 'm320-b')

    def test_je_pruefschritt_hoechstens_eine_akte(self):
        veh = vehicle(A); rid = run(A, veh); answer(A, rid)
        with tx(A) as c:
            case(c, A, veh, rid, 'S1')
        with self.assertRaises(psycopg.errors.UniqueViolation), tx(A) as c:
            case(c, A, veh, rid, 'S1')
        with tx(A) as c:   # ein anderer Prüfschritt desselben Laufs und eine Akte ohne Herkunft bleiben möglich
            case(c, A, veh, rid, 'S2'); case(c, A, veh)

    def test_zwei_geraete_gleichzeitig_genau_eine_akte(self):
        veh = vehicle(A); rid = run(A, veh); answer(A, rid)
        barrier = threading.Barrier(2); outcomes = []

        def worker():
            c = runtime()
            try:
                set_tenant(c, A); barrier.wait()
                case(c, A, veh, rid, 'S1'); c.commit(); outcomes.append('ok')
            except psycopg.errors.UniqueViolation:
                c.rollback(); outcomes.append('duplicate')
            finally:
                c.close()
        ts = [threading.Thread(target=worker) for _ in range(2)]
        [t.start() for t in ts]; [t.join(60) for t in ts]
        self.assertEqual(sorted(outcomes), ['duplicate', 'ok'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_case WHERE source_inspection_id=%s AND source_step_code='S1'", (rid,)).fetchone()[0], 1)

    def test_herkunft_hoechstens_eine_und_unveraenderlich(self):
        veh = vehicle(A); rid = run(A, veh); inc = incident(A, veh)
        with self.assertRaises(psycopg.errors.CheckViolation), tx(A) as c:       # Prüfschritt und Incident zugleich
            case(c, A, veh, rid, 'S1', inc)
        with tx(A) as c:
            d = case(c, A, veh, incident=inc); case(c, A, veh, incident=inc)       # zu einem Incident dürfen mehrere Akten gehören
        for sql in ("UPDATE taktovia.defect_case SET source_incident_id=NULL WHERE id=%s", "UPDATE taktovia.defect_case SET source_step_code='S2' WHERE id=%s"):
            with self.assertRaises(psycopg.Error) as cm, tx(A) as c:
                c.execute(sql, (d,))
            self.assertEqual(cm.exception.sqlstate, '23514', sql)

    def test_incident_referenz_ist_mandantengebunden(self):
        vb = vehicle(B); inc_b = incident(B, vb); va = vehicle(A)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation), tx(A) as c:   # Incident eines anderen Mandanten
            case(c, A, va, incident=inc_b)

    def test_verknuepfung_veraendert_abgeschlossene_antwort_nicht(self):
        veh = vehicle(A); rid = run(A, veh); answer(A, rid)
        with tx(A) as c:
            c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now() WHERE id=%s", (rid,))
        snapshot = "SELECT xmin::text, row_to_json(a)::text FROM taktovia.inspection_answer a WHERE inspection_id=%s"
        with tx(A) as c:
            before = c.execute(snapshot, (rid,)).fetchall()
        with tx(A) as c:       # die Akte entsteht aus dem Prüfschritt des abgeschlossenen Laufs
            case(c, A, veh, rid, 'S1')
        with tx(A) as c:
            self.assertEqual(c.execute(snapshot, (rid,)).fetchall(), before, 'die Antwort wurde nicht angefasst (auch nicht ihre Zeilenversion)')
            cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='inspection_answer'").fetchall()]
        self.assertNotIn('defect_case_id', cols, 'die Gegenreferenz steht an der Akte, nicht an der Antwort')
        # der DB-Schutz der abgeschlossenen Antwort bleibt wirksam (CHK-013)
        with self.assertRaises(psycopg.Error) as cm, tx(A) as c:
            c.execute("UPDATE taktovia.inspection_answer SET answer='OK' WHERE inspection_id=%s", (rid,))
        self.assertEqual(cm.exception.sqlstate, '23514')


if __name__ == '__main__':
    unittest.main()
