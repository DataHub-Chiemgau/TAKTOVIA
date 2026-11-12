"""WP-M3-17 / LH-6.21-CHK-008: Schichtstartwerte in der Datenbank unter der echten Runtime-Rolle. Originalabnahme: offensichtlich unplausible Werte müssen überprüft oder mit Begründung
freigegeben werden. Startwerte (Kilometerstand, Energie, Identität, Plausibilitätsstand, Begründung samt Akteur und Zeit) sind nach dem Abschluss unveränderlich; der letzte bestätigte
Kilometerstand am Fahrzeug fällt nie zurück; eine begründete Freigabe trägt immer Begründung, Akteur und Zeit."""
import unittest

import psycopg

import test_inspection_immutability as base
from helpers import admin, tx, uid, seed_tenant

A = uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def fixture(c):
    run, tpl, ids = base.run_fixture(c, A, steps=('S_ODO', 'S_ENERGY', 'S_IDENT'))
    return run, ids


def start_answer(c, run, code, step_id, number=None, text=None, plausibility='PLAUSIBEL', previous=None):
    aid = uid()
    c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,step_id,answer,number_value,text_value,answered_at,created_by,plausibility,previous_value) "
              "VALUES (%s,%s,%s,%s,%s,'OK',%s,%s,now(),%s,%s,%s)", (A, aid, run, code, step_id, number, text, ACTOR, plausibility, previous))
    return aid


class Startwerte(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm317-a')

    def test_startwerte_nach_abschluss_unveraenderlich(self):
        with tx(A) as c:
            run, ids = fixture(c)
            odo = start_answer(c, run, 'S_ODO', ids['S_ODO'], number=12000, previous=10000)
            ident = start_answer(c, run, 'S_IDENT', ids['S_IDENT'], text='FZ-1')
            c.execute("UPDATE taktovia.inspection_answer SET number_value=12500 WHERE id=%s", (odo,))   # Entwurf: änderbar
            base.complete(c, run)
            for sql in ("UPDATE taktovia.inspection_answer SET number_value=1 WHERE id=%s", "UPDATE taktovia.inspection_answer SET text_value='X' WHERE id=%s",
                        "UPDATE taktovia.inspection_answer SET plausibility='UEBERPRUEFUNG_ERFORDERLICH' WHERE id=%s", "UPDATE taktovia.inspection_answer SET previous_value=1 WHERE id=%s"):
                for aid in (odo, ident):
                    with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                        with c.transaction():
                            c.execute(sql, (aid,))
            self.assertEqual(c.execute("SELECT number_value, plausibility, previous_value FROM taktovia.inspection_answer WHERE id=%s", (odo,)).fetchone()[0], 12500)
        with admin() as a:
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.inspection_answer SET number_value=1 WHERE id=%s", (odo,))

    def test_begruendete_freigabe_traegt_begruendung_akteur_und_zeit(self):
        with tx(A) as c:
            run, ids = fixture(c)
            ok = uid()
            c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,step_id,answer,number_value,answered_at,created_by,plausibility,previous_value,justification_reason,justified_by,justified_at) "
                      "VALUES (%s,%s,%s,'S_ODO',%s,'OK',9000,now(),%s,'BEGRUENDET_FREIGEGEBEN',10000,'Tacho defekt',%s,now())", (A, ok, run, ids['S_ODO'], ACTOR, ACTOR))
            for plaus, reason, by, at in (('BEGRUENDET_FREIGEGEBEN', None, ACTOR, 'now()'), ('BEGRUENDET_FREIGEGEBEN', 'x', None, 'now()'), ('BEGRUENDET_FREIGEGEBEN', 'x', ACTOR, None),
                                          ('BEGRUENDET_FREIGEGEBEN', '   ', ACTOR, 'now()'), ('PLAUSIBEL', 'x', ACTOR, 'now()'), ('PLAUSIBEL', None, ACTOR, None), ('UNBEKANNT', None, None, None)):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{plaus} {reason} {by} {at}'):
                    with c.transaction():
                        c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,step_id,answer,number_value,answered_at,created_by,plausibility,justification_reason,justified_by,justified_at) "
                                  "VALUES (%s,%s,%s,'S_ENERGY',%s,'OK',50,now(),%s,%s,%s,%s," + ('now()' if at else 'NULL') + ")", (A, uid(), run, ids['S_ENERGY'], ACTOR, plaus, reason, by))

    def test_letzter_kilometerstand_faellt_nicht_zurueck(self):
        with tx(A) as c:
            veh = uid()
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (A, veh))
            self.assertIsNone(c.execute("SELECT last_confirmed_odometer_km FROM taktovia.vehicle WHERE id=%s", (veh,)).fetchone()[0])
            c.execute("UPDATE taktovia.vehicle SET last_confirmed_odometer_km=10000 WHERE id=%s", (veh,))
            c.execute("UPDATE taktovia.vehicle SET last_confirmed_odometer_km=12000 WHERE id=%s", (veh,))
            for val in ('9999', 'NULL'):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=val):
                    with c.transaction():
                        c.execute(f"UPDATE taktovia.vehicle SET last_confirmed_odometer_km={val} WHERE id=%s", (veh,))
            with self.assertRaises(psycopg.errors.CheckViolation):   # negativ
                with c.transaction():
                    c.execute("UPDATE taktovia.vehicle SET last_confirmed_odometer_km=-1 WHERE id=%s", (veh,))
            # das bedingte UPDATE der Anwendung ändert bei kleinerem Wert nichts
            n = c.execute("UPDATE taktovia.vehicle SET last_confirmed_odometer_km=11000 WHERE id=%s AND (last_confirmed_odometer_km IS NULL OR last_confirmed_odometer_km < 11000)", (veh,)).rowcount
            self.assertEqual(n, 0)
            self.assertEqual(c.execute("SELECT last_confirmed_odometer_km FROM taktovia.vehicle WHERE id=%s", (veh,)).fetchone()[0], 12000)
        with admin() as a:   # auch für die Administration
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("UPDATE taktovia.vehicle SET last_confirmed_odometer_km=1 WHERE id=%s", (veh,))

    def test_eingabetypen_der_startwerte_sind_zulaessig_unbekannte_nicht(self):
        with tx(A) as c:
            tpl = uid()
            c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,created_by) VALUES (%s,%s,%s,1,'PKW','AREA',%s)", (A, tpl, uid(), ACTOR))
            for i, kind in enumerate(('ODOMETER_KM', 'ENERGY_PCT', 'VEHICLE_IDENTITY'), 1):
                c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,%s,%s,'T',true,false,%s,%s)",
                          (A, uid(), tpl, f'S_{i}', i, kind, ACTOR))
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,'S_9',9,'T',true,false,'UNBEKANNT',%s)", (A, uid(), tpl, ACTOR))
            with self.assertRaises(psycopg.errors.CheckViolation):   # Zahlengrenzen bleiben dem Typ NUMBER vorbehalten
                with c.transaction():
                    c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,number_min,created_by) VALUES (%s,%s,%s,'S_8',8,'T',true,false,'ODOMETER_KM',0,%s)", (A, uid(), tpl, ACTOR))


if __name__ == '__main__':
    unittest.main()
