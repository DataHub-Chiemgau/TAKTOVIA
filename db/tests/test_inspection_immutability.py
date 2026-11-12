"""WP-M3-14 / LH-6.21-CHK-013: Unveränderlichkeit des Prüfnachweises in der Datenbank unter der echten Runtime-Rolle, auch bei direktem SQL (UPDATE, DELETE, INSERT nach Abschluss).
Originalabnahme: eine abgeschlossene Prüfung ist später vollständig und unverfälscht rekonstruierbar; spätere Vorlagenänderungen verändern die Historie nicht (Fremdschlüssel auf
die unveränderliche Prüfelement-Zeile der festen Vorlagenrevision)."""
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A = uid()
B = uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
APPROVER = 'user:22222222-2222-4222-8222-222222222222'


def run_fixture(c, tenant=A, steps=('S_ONE', 'S_TWO')):
    """Fahrzeug, Schicht, freigegebene Vorlage mit Prüfelementen und ein offener Prüflauf."""
    veh, shift, tpl, run = uid(), uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, veh))
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','Europe/Berlin',0,'PUBLISHED')", (tenant, shift, uid(), veh))
    c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,created_by) VALUES (%s,%s,%s,1,'PKW','AREA',%s)", (tenant, tpl, uid(), ACTOR))
    ids = {}
    for i, code in enumerate(steps, 1):
        ids[code] = uid()
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,%s,%s,%s,true,false,'OK_DEFECT_NA',%s)",
                  (tenant, ids[code], tpl, code, i, 'Frage ' + code, ACTOR))
    c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s WHERE id=%s", (APPROVER, tpl))
    c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s)", (tenant, run, shift, veh, uid(), tpl, ACTOR))
    return run, tpl, ids


def answer(c, run, code, step_id=None, status='OK', tenant=A):
    aid = uid()
    c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,step_id,answer,answered_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,now(),%s)", (tenant, aid, run, code, step_id, status, ACTOR))
    return aid


def complete(c, run, by=ACTOR):
    c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now(), completed_by=%s, revision=revision+1 WHERE id=%s", (by, run))


class Pruefnachweis(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm314-a')
        seed_tenant(B, 'm314-b')

    def test_antworten_abgeschlossener_pruefung_unveraenderlich(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            a1 = answer(c, run, 'S_ONE', ids['S_ONE'])
            c.execute("UPDATE taktovia.inspection_answer SET answer='DEFECT', note='x' WHERE id=%s", (a1,))   # Entwurf: änderbar
            answer(c, run, 'S_TWO', ids['S_TWO'])
            complete(c, run)
            for sql in ("UPDATE taktovia.inspection_answer SET answer='OK' WHERE id=%s", "UPDATE taktovia.inspection_answer SET note='neu' WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    with c.transaction():
                        c.execute(sql, (a1,))
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # die Runtime-Rolle darf nicht löschen
                with c.transaction():
                    c.execute("DELETE FROM taktovia.inspection_answer WHERE id=%s", (a1,))
            with self.assertRaises(psycopg.errors.CheckViolation):   # keine neue Antwort in einen abgeschlossenen Lauf
                with c.transaction():
                    answer(c, run, 'S_NEW')
            self.assertEqual(c.execute("SELECT answer, note FROM taktovia.inspection_answer WHERE id=%s", (a1,)).fetchone(), ('DEFECT', 'x'))
        with admin() as a:   # auch die Administration kann nicht löschen oder ändern
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("DELETE FROM taktovia.inspection_answer WHERE id=%s", (a1,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.inspection_answer SET answer='OK' WHERE id=%s", (a1,))

    def test_kopfdaten_abgeschlossener_pruefung_unveraenderlich(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            answer(c, run, 'S_ONE', ids['S_ONE'])
            complete(c, run)
            for col, val in (("outcome", "'BLOCKED'"), ("completed_by", "'user:33333333-3333-4333-8333-333333333333'"), ("completed_at", "now() + interval '1 day'"),
                             ("received_at", "now() + interval '1 day'"), ("safety_revision", "5"), ("vehicle_id", "gen_random_uuid()"), ("shift_id", "gen_random_uuid()"),
                             ("template_revision_id", "gen_random_uuid()"), ("driver_ref", "gen_random_uuid()"), ("status", "'IN_PROGRESS'")):
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.ForeignKeyViolation), msg=col):
                    with c.transaction():
                        c.execute(f"UPDATE taktovia.inspection_run SET {col} = {val} WHERE id=%s", (run,))
            row = c.execute("SELECT status, outcome, completed_by FROM taktovia.inspection_run WHERE id=%s", (run,)).fetchone()
            self.assertEqual(row, ('COMPLETED', 'PASSED', ACTOR))
        with admin() as a:
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.inspection_run SET outcome='BLOCKED' WHERE id=%s", (run,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("DELETE FROM taktovia.inspection_run WHERE id=%s", (run,))

    def test_antwort_verweist_auf_unveraenderliche_pruefelementzeile_der_vorlage_des_laufs(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            other_run, other_tpl, other_ids = run_fixture(c)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Schritt einer anderen Vorlagenrevision
                with c.transaction():
                    answer(c, run, 'S_ONE', other_ids['S_ONE'])
            with self.assertRaises(psycopg.errors.CheckViolation):   # Schlüssel passt nicht zur Prüfelementzeile
                with c.transaction():
                    answer(c, run, 'S_TWO', ids['S_ONE'])
            answer(c, run, 'S_ONE', ids['S_ONE'])
            # die freigegebene Prüfelementzeile ist weder änderbar noch löschbar, solange Antworten auf sie verweisen
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.checklist_step SET title='Anders' WHERE id=%s", (ids['S_ONE'],))
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("DELETE FROM taktovia.checklist_step WHERE id=%s", (ids['S_ONE'],))

    def test_fremder_mandant_sieht_den_nachweis_nicht(self):
        with tx(A) as c:
            run, tpl, ids = run_fixture(c)
            a1 = answer(c, run, 'S_ONE', ids['S_ONE']); complete(c, run)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_answer WHERE id=%s", (a1,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_run WHERE id=%s", (run,)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
