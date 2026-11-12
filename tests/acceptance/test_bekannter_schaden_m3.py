"""WP-M3-21 (AT-29, Teilumfang Datenbank): Altschaden anzeigen und quittieren, unter der echten Runtime-Rolle auf einer eigenen, frisch migrierten Datenbank.

AT-29 (Kap. 11.2): Offene Alt-Schadenakte wird vor Schichtstart quittiert; der alte Fall bleibt referenziert, kein unbegründeter neuer Schadensfall. Geprüft wird hier die Datenbank-Seite
(Quittierung, Abschlusssperre des Prüflaufs, keine Akte, keine Verursacherspalte); die Fahrer-App (UI-09) und der HTTP-Pfad sind Gegenstand von backend/app (KnownDefectApiTest) und mobile/driver
(nicht ausgeführt, kein Flutter). Aufruf: cd tests/acceptance && python -m unittest test_bekannter_schaden_m3 (DB-Umgebung wie db/db.py)."""
import sys
import unittest
import uuid
from pathlib import Path

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb  # noqa: E402

DB = "taktovia_at_m3_21"
USER = "user:11111111-1111-4111-8111-111111111111"
REVIEWER = "user:22222222-2222-4222-8222-222222222222"


def uid():
    return uuid.uuid4()


class BekannterSchadenAt29(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        atdb.reset_database(DB)
        cls.tenant = uid()
        with atdb.dbtool.admin(DB) as c:
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'at29','ACTIVE')", (cls.tenant,))

    @classmethod
    def tearDownClass(cls):
        atdb.drop_database(DB)

    def _fixture(self):
        """Fahrzeug mit einem bekannten offenen Schaden (gemeldet von einem früheren Fahrer) und ein gestarteter Prüflauf eines neuen Fahrers."""
        veh, defect, shift, tpl, run = uid(), uid(), uid(), uid(), uid()
        with atdb.tx(DB, self.tenant) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_type) VALUES (%s,%s,'fixture','{}','PKW')", (self.tenant, veh))
            number = c.execute("SELECT taktovia.next_defect_case_number()").fetchone()[0]
            c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) "
                      "VALUES (%s,%s,%s,%s,%s,clock_timestamp()-interval '2 days','BODY','Kratzer Tür',%s)", (self.tenant, defect, number, veh, uid(), USER))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now()-interval '1 hour',now()+interval '7 hours','Europe/Berlin',0,'PLANNED')",
                      (self.tenant, shift, uid(), veh))
            c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) VALUES (%s,%s,%s,1,'PKW','STANDARD','V',%s)", (self.tenant, tpl, uid(), USER))
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,'S1',1,'T',true,false,'OK_DEFECT_NA',%s)", (self.tenant, uid(), tpl, USER))
            c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s, revision=revision+1 WHERE tenant_id=%s AND id=%s", (REVIEWER, self.tenant, tpl))
            c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s)",
                      (self.tenant, run, shift, veh, uid(), tpl, USER))
        return veh, defect, run

    def _complete(self, run):
        with atdb.tx(DB, self.tenant) as c:
            c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now() WHERE tenant_id=%s AND id=%s", (self.tenant, run))

    def test_pruefabschluss_ohne_quittierung_abgewiesen(self):
        veh, defect, run = self._fixture()
        with atdb.tx(DB, self.tenant) as c:
            known = [r[0] for r in c.execute("SELECT * FROM taktovia.inspection_known_defects(%s,%s)", (self.tenant, run)).fetchall()]
            cases_before = c.execute("SELECT count(*) FROM taktovia.defect_case WHERE vehicle_id=%s", (veh,)).fetchone()[0]
        self.assertEqual(known, [defect])                                   # der neue Fahrer sieht den Altschaden vor der Prüfung
        with self.assertRaises(psycopg.Error) as cm:                        # ohne Quittierung kein Abschluss
            self._complete(run)
        self.assertEqual(cm.exception.sqlstate, "23514"); self.assertIn("KNOWN_DEFECTS_UNACKNOWLEDGED", str(cm.exception))
        with atdb.tx(DB, self.tenant) as c:
            c.execute("INSERT INTO taktovia.known_defect_acknowledgement(tenant_id,id,inspection_id,defect_id,acknowledged_by) VALUES (%s,%s,%s,%s,%s)", (self.tenant, uid(), run, defect, USER))
        self._complete(run)                                                 # nach der Quittierung möglich
        with atdb.tx(DB, self.tenant) as c:
            self.assertEqual(c.execute("SELECT status FROM taktovia.inspection_run WHERE id=%s", (run,)).fetchone()[0], "COMPLETED")
            # alter Fall bleibt referenziert und unverändert, kein neuer Schadensfall, keine Verursacherangabe
            self.assertEqual(c.execute("SELECT count(*), max(revision) FROM taktovia.defect_case WHERE vehicle_id=%s", (veh,)).fetchone(), (cases_before, 1))
            self.assertEqual(c.execute("SELECT defect_id, defect_revision, defect_status FROM taktovia.known_defect_acknowledgement WHERE inspection_id=%s", (run,)).fetchall(), [(defect, 1, "NEW")])
            cols = {r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='known_defect_acknowledgement'").fetchall()}
        self.assertFalse([x for x in cols if any(k in x for k in ("cause", "fault", "blame", "schuld", "verursach"))])


if __name__ == "__main__":
    unittest.main()
