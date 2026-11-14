"""WP-M3-22 / LH-6.21-CHK-015: Fahrzeugwechsel in der Datenbank unter der echten Runtime-Rolle.

Invariante: eine Einsatzfreigabe trägt nur ein abgeschlossener, bestandener Prüflauf desselben Fahrzeugs und derselben Schicht; die Prüfung des alten Fahrzeugs trägt keine Freigabe des
Ersatzfahrzeugs (auch bei direktem SQL). Wechselhistorie: nur anhängen, mandantengetrennt, Begründung Pflicht."""
import unittest

import psycopg

from helpers import seed_tenant, tx, uid

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def vehicle(c, tenant):
    vid = uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, vid))
    return vid


def shift(c, tenant, v):
    sid = uid()
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
              "VALUES (%s,%s,%s,%s,now() - interval '1 hour',now()+interval '6 hours','Europe/Berlin',0,'STARTED')", (tenant, sid, uid(), v))
    return sid


def template(c, tenant):
    t = uid()
    c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,state,published_at,created_by) VALUES (%s,%s,%s,1,'PKW','STANDARD','DRAFT',NULL,%s)",
              (tenant, t, uid(), ACTOR))
    return t


def run(c, tenant, s, v, t, status='IN_PROGRESS', outcome=None):
    rid = uid()
    c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'VEHICLE_CHANGE',now(),%s)",
              (tenant, rid, s, v, uid(), t, ACTOR))
    if status == 'COMPLETED':
        c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome=%s, completed_at=now(), received_at=now() WHERE id=%s", (outcome, rid))
    return rid


def release(c, tenant, v, s, inspection):
    c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until,issued_by) VALUES (%s,%s,%s,%s,1,%s,1,now()+interval '4 hours',%s)",
              (tenant, uid(), v, s, inspection, ACTOR))


class Fahrzeugwechsel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm322-a')
        seed_tenant(B, 'm322-b')

    def test_keine_freigabe_fuer_ersatzfahrzeug_ohne_eigene_pruefung(self):
        with tx(A) as c:
            old, new = vehicle(c, A), vehicle(c, A)
            s = shift(c, A, new)   # Schicht nach dem Wechsel: Ersatzfahrzeug
            t = template(c, A)
            old_run = run(c, A, s, old, t, 'COMPLETED', 'PASSED')   # Prüfung des alten Fahrzeugs derselben Schicht
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    release(c, A, new, s, old_run)
            open_run = run(c, A, s, new, t)   # Prüfung des Ersatzfahrzeugs läuft noch
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    release(c, A, new, s, open_run)
            blocked = run(c, A, s, vehicle(c, A), t, 'COMPLETED', 'BLOCKED')   # anderes Fahrzeug, nicht bestanden: trägt auch nicht
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    release(c, A, new, s, blocked)
        with tx(A) as c:
            # Positiv: eigener, bestätigter Prüflauf des Ersatzfahrzeugs
            old, new = vehicle(c, A), vehicle(c, A)
            s = shift(c, A, new)
            own = run(c, A, s, new, template(c, A), 'COMPLETED', 'PASSED')
            release(c, A, new, s, own)
            self.assertEqual(1, c.execute("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref=%s AND vehicle_id=%s", (own, new)).fetchone()[0])

    def test_pruefung_einer_anderen_schicht_traegt_nicht(self):
        with tx(A) as c:
            v = vehicle(c, A)
            s1, s2 = shift(c, A, v), None
            t = template(c, A)
            other = uid()
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now()+interval '10 hours',now()+interval '14 hours','Europe/Berlin',0,'PLANNED')",
                      (A, other, uid(), v))
            foreign = run(c, A, s1, v, t, 'COMPLETED', 'PASSED')
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    release(c, A, v, other, foreign)
            release(c, A, v, s1, foreign)

    def test_wechselhistorie_nur_anhaengen_mit_begruendung(self):
        with tx(A) as c:
            old, new = vehicle(c, A), vehicle(c, A)
            s = shift(c, A, new)
            sql = ("INSERT INTO taktovia.shift_vehicle_switch(tenant_id,id,shift_id,driver_ref,from_vehicle_id,to_vehicle_id,reason_code,reason_note,actor,actor_role) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'DRIVER')")
            sid = uid()
            c.execute(sql, (A, sid, s, uid(), old, new, 'BREAKDOWN', None, ACTOR))
            for bad in ((old, old, 'BREAKDOWN', None), (old, new, 'OTHER', None), (old, new, 'KEINE_AHNUNG', None), (old, new, 'DEFECT', '   ')):
                with self.subTest(bad=bad), self.assertRaises(psycopg.errors.CheckViolation):
                    with c.transaction():
                        c.execute(sql, (A, uid(), s, uid(), bad[0], bad[1], bad[2], bad[3], ACTOR))
            for stmt in ("UPDATE taktovia.shift_vehicle_switch SET reason_code='OTHER' WHERE id=%s", "DELETE FROM taktovia.shift_vehicle_switch WHERE id=%s"):
                with self.assertRaises(psycopg.errors.Error):
                    with c.transaction():
                        c.execute(stmt, (sid,))
            self.assertEqual(1, c.execute("SELECT count(*) FROM taktovia.shift_vehicle_switch WHERE id=%s", (sid,)).fetchone()[0])

    def test_wechselhistorie_mandantengetrennt(self):
        with tx(A) as c:
            old, new = vehicle(c, A), vehicle(c, A)
            s = shift(c, A, new)
            c.execute("INSERT INTO taktovia.shift_vehicle_switch(tenant_id,id,shift_id,driver_ref,from_vehicle_id,to_vehicle_id,reason_code,actor,actor_role) VALUES (%s,%s,%s,%s,%s,%s,'DEFECT',%s,'DISPATCH')",
                      (A, uid(), s, uid(), old, new, ACTOR))
        with tx(B) as c:
            self.assertEqual(0, c.execute("SELECT count(*) FROM taktovia.shift_vehicle_switch").fetchone()[0])
            with self.assertRaises(psycopg.errors.Error):
                with c.transaction():
                    c.execute("INSERT INTO taktovia.shift_vehicle_switch(tenant_id,id,shift_id,driver_ref,from_vehicle_id,to_vehicle_id,reason_code,actor,actor_role) VALUES (%s,%s,%s,%s,%s,%s,'DEFECT',%s,'DISPATCH')",
                              (A, uid(), uid(), uid(), uid(), uid(), ACTOR))


if __name__ == '__main__':
    unittest.main()
