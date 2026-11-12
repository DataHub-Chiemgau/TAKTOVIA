"""WP-M3-11 / LH-6.21-CHK-005, CHK-006: Pflichtvorlagen in der Datenbank unter der echten Runtime-Rolle, auch bei direktem SQL. Eine Revision der Typen VEHICLE_TECHNICAL (acht Pflichtpunkte) und
PASSENGER_COMPARTMENT (sechs Pflichtpunkte) lässt sich nur veröffentlichen, wenn alle Pflichtschlüssel als Pflichtschritte vorhanden sind; eine freigegebene Revision ist unveränderlich
(auch der Typ); die Art PRE_BOARDING ist zulässig."""
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A = uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
APPROVER = 'user:22222222-2222-4222-8222-222222222222'
TECH = ['BELEUCHTUNG', 'REIFEN_SICHTPRUEFUNG', 'SCHEIBEN', 'SPIEGEL', 'TUEREN', 'SICHERHEITSGURTE', 'SICHTBARE_LECKAGEN', 'WARNANZEIGEN']
BOARD = ['SAUBERKEIT', 'FAHRGASTRAUM', 'SITZPLAETZE', 'EINSTIEGSBEREICH', 'RUECKHALTESYSTEME', 'SICHERHEITSAUSRUESTUNG']


def draft(c, ttype, codes, mandatory=True):
    tpl = uid()
    c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,template_type,created_by) VALUES (%s,%s,%s,1,'PKW','AREA',%s,%s)", (A, tpl, uid(), ttype, ACTOR))
    for i, code in enumerate(codes, 1):
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,%s,%s,'T',%s,false,'OK_DEFECT_NA',%s)",
                  (A, uid(), tpl, code, i, mandatory, ACTOR))
    return tpl


def publish(c, tpl):
    c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s WHERE id=%s", (APPROVER, tpl))


class Pflichtvorlagen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm311-a')

    def test_pflichtschluessel_je_typ_stehen_in_der_datenbank(self):
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT taktovia.checklist_mandatory_keys('VEHICLE_TECHNICAL')").fetchone()[0], TECH)
            self.assertEqual(c.execute("SELECT taktovia.checklist_mandatory_keys('PASSENGER_COMPARTMENT')").fetchone()[0], BOARD)
            self.assertEqual(c.execute("SELECT taktovia.checklist_mandatory_keys('GENERAL')").fetchone()[0], [])

    def test_unvollstaendige_pflichtvorlage_nicht_veroeffentlichbar_je_fehlendem_schluessel(self):
        for ttype, keys in (('VEHICLE_TECHNICAL', TECH), ('PASSENGER_COMPARTMENT', BOARD)):
            for missing in keys:
                with tx(A) as c:
                    tpl = draft(c, ttype, [k for k in keys if k != missing])
                    with self.assertRaises(psycopg.errors.CheckViolation, msg=missing) as ctx:
                        publish(c, tpl)
                    self.assertIn('MANDATORY_POINTS_MISSING: ' + missing, str(ctx.exception))
        with tx(A) as c:
            tpl = draft(c, 'VEHICLE_TECHNICAL', TECH, mandatory=False)   # vorhanden, aber nicht verpflichtend: zählt als fehlend
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    publish(c, tpl)
            tpl = draft(c, 'VEHICLE_TECHNICAL', TECH + ['ZUSATZ'])
            publish(c, tpl)   # vollständig (mit Zusatzpunkt) ist veröffentlichbar
            general = draft(c, 'GENERAL', ['EINZEL'])
            publish(c, general)

    def test_freigegebene_revision_ist_unveraenderlich(self):
        with tx(A) as c:
            tpl = draft(c, 'PASSENGER_COMPARTMENT', BOARD)
            publish(c, tpl)
            for sql in ("UPDATE taktovia.checklist_template_revision SET template_type='GENERAL' WHERE id=%s", "UPDATE taktovia.checklist_template_revision SET name='Neu' WHERE id=%s",
                        "UPDATE taktovia.checklist_template_revision SET vehicle_class='BUS' WHERE id=%s", "UPDATE taktovia.checklist_step SET title='Anders' WHERE template_revision_id=%s",
                        "DELETE FROM taktovia.checklist_step WHERE template_revision_id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    with c.transaction():
                        c.execute(sql, (tpl,))
            with self.assertRaises(psycopg.errors.CheckViolation):   # keine neuen Schritte in einer freigegebenen Revision
                with c.transaction():
                    c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,'NEU',99,'T',true,false,'OK_DEFECT_NA',%s)", (A, uid(), tpl, ACTOR))
            self.assertEqual(c.execute("SELECT template_type, count(*) FROM taktovia.checklist_template_revision r JOIN taktovia.checklist_step s ON s.template_revision_id = r.id WHERE r.id=%s GROUP BY 1", (tpl,)).fetchone(), ('PASSENGER_COMPARTMENT', 6))
            self.assertEqual(c.execute("UPDATE taktovia.checklist_template_revision SET state='RETIRED' WHERE id=%s", (tpl,)).rowcount, 1)   # nur die Ablösung ist erlaubt
        with admin() as a:   # auch für die Administration
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.checklist_template_revision SET template_type='GENERAL' WHERE id=%s", (tpl,))

    def test_typ_ist_geschlossene_menge_und_pruefart_vor_fahrgastaufnahme_zulaessig(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    draft(c, 'ANDERER', ['X'])
            tpl = draft(c, 'PASSENGER_COMPARTMENT', BOARD); publish(c, tpl)
            veh, shift, run = uid(), uid(), uid()
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (A, veh))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','Europe/Berlin',0,'PUBLISHED')", (A, shift, uid(), veh))
            c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'PRE_BOARDING',now(),%s)", (A, run, shift, veh, uid(), tpl, ACTOR))
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'NIE',now(),%s)", (A, uid(), shift, veh, uid(), tpl, ACTOR))


if __name__ == '__main__':
    unittest.main()
