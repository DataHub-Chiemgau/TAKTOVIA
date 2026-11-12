"""WP-M3-47 / LH-6.22-DMG-007: Statusautomat der Schadenakte in der Datenbank unter der echten Runtime-Rolle. Originalabnahme: unzulässige Statussprünge werden verhindert, auch bei direktem SQL.
Tabellengetrieben: jeder Übergang außerhalb der Tabelle scheitert (SQLSTATE 23514), jeder zulässige gelingt; Endzustände nur mit dokumentierter Wiedereröffnung; Revision zählt;
Historie append-only; sicherheitskritischer Abschluss nur mit Freigabeentscheidung."""
import itertools
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A = uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
STATES = ['NEW', 'REVIEWED', 'IN_PROGRESS', 'REPAIR', 'CLOSED', 'DISMISSED']
ALLOWED = {('NEW', 'REVIEWED'), ('NEW', 'DISMISSED'), ('REVIEWED', 'IN_PROGRESS'), ('REVIEWED', 'DISMISSED'), ('REVIEWED', 'CLOSED'), ('IN_PROGRESS', 'REPAIR'),
           ('IN_PROGRESS', 'CLOSED'), ('IN_PROGRESS', 'DISMISSED'), ('REPAIR', 'CLOSED'), ('REPAIR', 'IN_PROGRESS')}


def case(c, status='NEW', severity=None):
    vid, cid = uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (A, vid))
    c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by,status,severity,dismissed_reason) "
              "VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BODY','x',%s,%s,%s,%s)",
              (A, cid, c.execute('SELECT taktovia.next_defect_case_number()').fetchone()[0], vid, uid(), ACTOR, status, severity, 'Fehlmeldung' if status == 'DISMISSED' else None))
    return cid


def release_fixture(c, cid, decision):
    """Schicht, Vorlage, Folgeprüfung, Reparaturauftrag und Freigabeentscheidung zur Akte (Fixtures wie die Anwendung sie anlegt)."""
    veh = c.execute("SELECT vehicle_id FROM taktovia.defect_case WHERE id=%s", (cid,)).fetchone()[0]
    shift, tpl, run, ro = uid(), uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','Europe/Berlin',0,'PUBLISHED')", (A, shift, uid(), veh))
    c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,created_by) VALUES (%s,%s,%s,1,'PKW','AREA',%s)", (A, tpl, uid(), ACTOR))
    c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'FOLLOW_UP',now(),%s)", (A, run, shift, veh, uid(), tpl, ACTOR))
    c.execute("INSERT INTO taktovia.repair_order(tenant_id,id,defect_id,vehicle_id,created_by) VALUES (%s,%s,%s,%s,%s)", (A, ro, cid, veh, ACTOR))
    second = 'user:22222222-2222-4222-8222-222222222222' if decision == 'RELEASED' else None
    c.execute("INSERT INTO taktovia.vehicle_release_decision(tenant_id,id,vehicle_id,repair_order_id,follow_up_inspection_id,decision,decided_by,second_approver,safety_revision) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,1)",
              (A, uid(), veh, ro, run, decision, ACTOR, second))


def move(c, cid, to, revision, reopen=False):
    c.execute("UPDATE taktovia.defect_case SET status=%s, revision=%s, dismissed_reason=CASE WHEN %s='DISMISSED' THEN 'Fehlmeldung' ELSE dismissed_reason END, "
              "reopen_count=reopen_count + %s WHERE id=%s", (to, revision, to, 1 if reopen else 0, cid))


class Statusautomat(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm347-a')

    def test_nur_tabellenuebergaenge_sind_speicherbar(self):
        for frm, to in itertools.permutations(STATES, 2):
            with tx(A) as c:
                cid = case(c, frm)
                if frm in ('CLOSED', 'DISMISSED'):
                    # Endzustand ohne Zähler: nie
                    with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{frm}->{to}'):
                        move(c, cid, to, 2)
                elif (frm, to) in ALLOWED:
                    move(c, cid, to, 2)
                    self.assertEqual(c.execute("SELECT status FROM taktovia.defect_case WHERE id=%s", (cid,)).fetchone()[0], to)
                else:
                    with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{frm}->{to}'):
                        move(c, cid, to, 2)

    def test_wiedereroeffnung_nur_nach_reviewed_mit_zaehler(self):
        for end in ('CLOSED', 'DISMISSED'):
            for to in ('NEW', 'IN_PROGRESS', 'REPAIR', 'CLOSED', 'DISMISSED'):
                if to == end: continue
                with tx(A) as c:
                    cid = case(c, end)
                    with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{end}->{to}'):
                        move(c, cid, to, 2, reopen=True)
            with tx(A) as c:
                cid = case(c, end)
                with self.assertRaises(psycopg.errors.CheckViolation):
                    move(c, cid, 'REVIEWED', 2)                          # ohne Zähler
            with tx(A) as c:
                cid = case(c, end)
                move(c, cid, 'REVIEWED', 2, reopen=True)                 # dokumentierte Wiedereröffnung
                self.assertEqual(c.execute("SELECT status, reopen_count FROM taktovia.defect_case WHERE id=%s", (cid,)).fetchone(), ('REVIEWED', 1))
        with tx(A) as c:   # der Zähler allein (ohne Statuswechsel) und außerhalb der Wiedereröffnung ist nicht änderbar
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_case SET reopen_count = 5 WHERE id=%s", (cid,))
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_case SET status='REVIEWED', revision=2, reopen_count=1 WHERE id=%s", (cid,))

    def test_statuswechsel_zaehlt_die_revision(self):
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):
                move(c, cid, 'REVIEWED', 1)       # Revision unverändert
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):
                move(c, cid, 'REVIEWED', 5)       # Sprung

    def test_state_check_und_historie_append_only(self):
        # unbekannter Statuswert: CHECK der Spalte
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_case SET status='ERFUNDEN', revision=2 WHERE id=%s", (cid,))
        # Historie: anhängen und lesen, aber weder ändern noch löschen (auch nicht für die Administration)
        with tx(A) as c:
            cid = case(c); hid = uid()
            c.execute("INSERT INTO taktovia.defect_status_history(tenant_id,id,defect_id,from_status,to_status,actor,reason,revision) VALUES (%s,%s,%s,NULL,'NEW',%s,NULL,1)", (A, hid, cid, ACTOR))
            for sql in ("UPDATE taktovia.defect_status_history SET reason='x' WHERE id=%s", "DELETE FROM taktovia.defect_status_history WHERE id=%s"):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                    c.execute(sql, (hid,))
                c.connection.rollback()
        with tx(A) as c:
            cid = case(c); hid = uid()
            c.execute("INSERT INTO taktovia.defect_status_history(tenant_id,id,defect_id,from_status,to_status,actor,reason,revision) VALUES (%s,%s,%s,NULL,'NEW',%s,NULL,1)", (A, hid, cid, ACTOR))
        for sql in ("UPDATE taktovia.defect_status_history SET reason='x' WHERE id=%s", "DELETE FROM taktovia.defect_status_history WHERE id=%s"):
            with admin() as a:
                with self.assertRaises(psycopg.errors.CheckViolation):
                    a.execute(sql, (hid,))

    def test_abschluss_sicherheitskritisch_braucht_freigabeentscheidung(self):
        with tx(A) as c:
            cid = case(c, 'REPAIR', severity='SAFETY_CRITICAL')
            with self.assertRaises(psycopg.errors.CheckViolation):
                move(c, cid, 'CLOSED', 2)
        with tx(A) as c:   # nicht sicherheitskritisch: ohne Entscheidung abschließbar
            cid = case(c, 'REPAIR', severity='COSMETIC'); move(c, cid, 'CLOSED', 2)
        with tx(A) as c:   # mit Reparaturauftrag, Folgeprüfung und Freigabeentscheidung RELEASED
            cid = case(c, 'REPAIR', severity='SAFETY_CRITICAL')
            release_fixture(c, cid, decision='RELEASED')
            move(c, cid, 'CLOSED', 2)
            self.assertEqual(c.execute("SELECT status FROM taktovia.defect_case WHERE id=%s", (cid,)).fetchone()[0], 'CLOSED')
        with tx(A) as c:   # eine abgelehnte Entscheidung genügt nicht
            cid = case(c, 'REPAIR', severity='SAFETY_CRITICAL')
            release_fixture(c, cid, decision='REJECTED')
            with self.assertRaises(psycopg.errors.CheckViolation):
                move(c, cid, 'CLOSED', 2)


if __name__ == '__main__':
    unittest.main()
