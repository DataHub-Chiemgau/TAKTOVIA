"""WP-M3-21 / LH-6.21-CHK-012, LH-6.22-DMG-012: Quittierung bekannter Altschäden in der Datenbank, unter der echten Runtime-Rolle.

Originalabnahmen: Ein bekannter Schaden wird nicht ungeprüft als neues Schadensereignis angelegt (CHK-012); der Fahrer quittiert einen vorhandenen Schaden, ohne ihn als selbst
verursacht zu markieren (DMG-012). Hier: Quittierung legt keine Akte an und ändert sie nicht, genau ein Eintrag je (Prüflauf, Altschaden) auch bei echt gleichzeitigen Transaktionen,
Stand der Akte (Revision, Status) kommt von der Datenbank, keine Verursacher-/Schuldspalte, Abschlusssperre des Prüflaufs, Mandantenisolation."""
import threading
import unittest

import psycopg

from helpers import admin, seed_tenant, tx, uid

A, B = uid(), uid()
USER = 'user:11111111-1111-4111-8111-111111111111'
REVIEWER = 'user:22222222-2222-4222-8222-222222222222'


def vehicle(tenant):
    vid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_type) VALUES (%s,%s,'fixture','{}','PKW')", (tenant, vid))
    return vid


def defect(tenant, veh, status='NEW', source_inspection=None):
    cid = uid()
    with tx(tenant) as c:
        number = c.execute("SELECT taktovia.next_defect_case_number()").fetchone()[0]
        c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by,source_inspection_id) "
                  "VALUES (%s,%s,%s,%s,%s,clock_timestamp() - interval '1 hour','BODY','Kratzer',%s,%s)", (tenant, cid, number, veh, uid(), USER, source_inspection))
        if status != 'NEW':
            # zulässiger Weg im Statusautomaten (V30470): NEW -> REVIEWED -> ... -> CLOSED über die Administration, hier nur bis zum benötigten Status
            for nxt in {'REVIEWED': ['REVIEWED'], 'IN_PROGRESS': ['REVIEWED', 'IN_PROGRESS'], 'DISMISSED': ['DISMISSED']}[status]:
                reason = ", dismissed_reason='Fehlmeldung'" if nxt == 'DISMISSED' else ''
                c.execute(f"UPDATE taktovia.defect_case SET status=%s, revision=revision+1{reason} WHERE tenant_id=%s AND id=%s", (nxt, tenant, cid))
    return cid


def run(tenant, veh, kind='PRE_SHIFT'):
    sid, tpl, rid = uid(), uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now()-interval '1 hour',now()+interval '7 hours','Europe/Berlin',0,'PLANNED')", (tenant, sid, uid(), veh))
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) VALUES (%s,%s,%s,1,'PKW','STANDARD','V',%s)",
                  (tenant, tpl, uid(), USER))
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,'S1',1,'T',true,false,'OK_DEFECT_NA',%s)",
                  (tenant, uid(), tpl, USER))
        c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s, revision=revision+1 WHERE tenant_id=%s AND id=%s", (REVIEWER, tenant, tpl))
        c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,%s,now(),%s)",
                  (tenant, rid, sid, veh, uid(), tpl, kind, USER))
    return rid


def ack(c, tenant, inspection, d):
    aid = uid()
    c.execute("INSERT INTO taktovia.known_defect_acknowledgement(tenant_id,id,inspection_id,defect_id,acknowledged_by) VALUES (%s,%s,%s,%s,%s)", (tenant, aid, inspection, d, USER))
    return aid


def complete(c, tenant, inspection):
    c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now() WHERE tenant_id=%s AND id=%s", (tenant, inspection))


class Quittierung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm3-21-a'); seed_tenant(B, 'm3-21-b')

    def test_quittierung_erzeugt_keine_schadenakte(self):
        veh = vehicle(A); d = defect(A, veh); rid = run(A, veh)
        with tx(A) as c:
            before = c.execute("SELECT count(*), max(revision) FROM taktovia.defect_case WHERE vehicle_id=%s", (veh,)).fetchone()
            ack(c, A, rid, d)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*), max(revision) FROM taktovia.defect_case WHERE vehicle_id=%s", (veh,)).fetchone(), before)   # Akte weder neu noch verändert
            row = c.execute("SELECT defect_id, defect_revision, defect_status FROM taktovia.known_defect_acknowledgement WHERE inspection_id=%s", (rid,)).fetchall()
        self.assertEqual(row, [(d, 1, 'NEW')])   # Stand zum Quittierungszeitpunkt trägt die Datenbank ein

    def test_stand_zum_quittierungszeitpunkt_bleibt_nach_abschluss_der_akte(self):
        veh = vehicle(A); d = defect(A, veh, 'IN_PROGRESS'); rid = run(A, veh)
        with tx(A) as c: ack(c, A, rid, d)
        with tx(A) as c: c.execute("UPDATE taktovia.defect_case SET status='REPAIR', revision=revision+1 WHERE id=%s", (d,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.defect_case SET status='CLOSED', revision=revision+1 WHERE id=%s", (d,))   # parallel abgeschlossen (Sicherheitsfreigabe hier nicht relevant)
        with tx(A) as c:
            complete(c, A, rid)                                                           # Abschluss bleibt möglich
            got = c.execute("SELECT defect_revision, defect_status FROM taktovia.known_defect_acknowledgement WHERE inspection_id=%s", (rid,)).fetchone()
        self.assertEqual(got, (3, 'IN_PROGRESS'))                                          # Revision 3 = IN_PROGRESS (NEW, REVIEWED, IN_PROGRESS), nicht der spätere Stand
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT status FROM taktovia.inspection_run WHERE id=%s", (rid,)).fetchone()[0], 'COMPLETED')

    def test_quittierung_eindeutig_je_altschaden_und_pruefung(self):
        veh = vehicle(A); d = defect(A, veh); rid = run(A, veh)
        with tx(A) as c: ack(c, A, rid, d)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c: ack(c, A, rid, d)
        self.assertEqual(cm.exception.sqlstate, '23505')
        # echt gleichzeitig (zwei Geräte): genau ein Eintrag
        d2 = defect(A, veh); results = []; barrier = threading.Barrier(2)

        def worker():
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10)
                    ack(c, A, rid, d2)
                results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=worker) for _ in range(2)]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(results), ['dup', 'ok'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.known_defect_acknowledgement WHERE inspection_id=%s AND defect_id=%s", (rid, d2)).fetchone()[0], 1)

    def test_quittierung_ohne_verursacher_oder_schuldspalte(self):
        with admin() as c:
            cols = {r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='known_defect_acknowledgement'").fetchall()}
        self.assertEqual(cols, {'tenant_id', 'id', 'inspection_id', 'defect_id', 'acknowledged_by', 'revision', 'created_at', 'defect_revision', 'defect_status'})
        for forbidden in ('cause', 'caused', 'fault', 'blame', 'guilt', 'schuld', 'verursach', 'liable', 'rating'):
            self.assertFalse([c for c in cols if forbidden in c], forbidden)

    def test_quittierung_nur_fuer_akte_desselben_fahrzeugs(self):
        veh = vehicle(A); other = vehicle(A); d_other = defect(A, other); rid = run(A, veh)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c: ack(c, A, rid, d_other)
        self.assertEqual(cm.exception.sqlstate, '23514')

    def test_quittierung_nur_fuer_offenen_pruefeinlauf_und_unveraenderlich(self):
        veh = vehicle(A); rid = run(A, veh)
        with tx(A) as c: complete(c, A, rid)
        d = defect(A, veh)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c: ack(c, A, rid, d)
        self.assertEqual(cm.exception.sqlstate, '23514')
        veh2 = vehicle(A); d2 = defect(A, veh2); rid2 = run(A, veh2)
        with tx(A) as c: aid = ack(c, A, rid2, d2)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c: c.execute("UPDATE taktovia.known_defect_acknowledgement SET defect_revision=9 WHERE id=%s", (aid,))
        self.assertEqual(cm.exception.sqlstate, '42501')   # Runtime hat kein UPDATE-/DELETE-Recht
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c: c.execute("DELETE FROM taktovia.known_defect_acknowledgement WHERE id=%s", (aid,))
        self.assertEqual(cm.exception.sqlstate, '42501')
        for sql in ("UPDATE taktovia.known_defect_acknowledgement SET defect_revision=9 WHERE id=%s", "DELETE FROM taktovia.known_defect_acknowledgement WHERE id=%s"):
            with self.assertRaises(psycopg.Error) as cm:      # auch die Administration: Trigger (V3020)
                with admin() as c: c.execute(sql, (aid,))
            self.assertEqual(cm.exception.sqlstate, '23514')

    def test_abschluss_ohne_quittierung_abgewiesen_mit_quittierung_moeglich(self):
        veh = vehicle(A); d = defect(A, veh); rid = run(A, veh)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c: complete(c, A, rid)
        self.assertEqual(cm.exception.sqlstate, '23514'); self.assertIn('KNOWN_DEFECTS_UNACKNOWLEDGED', str(cm.exception))
        with tx(A) as c: ack(c, A, rid, d)
        with tx(A) as c: complete(c, A, rid)
        # Leerzustand: Fahrzeug ohne offene Altschäden, und Akten im Endzustand verlangen keine Quittierung
        veh2 = vehicle(A); defect(A, veh2, 'DISMISSED'); rid2 = run(A, veh2)
        with tx(A) as c: complete(c, A, rid2)
        # Folgeprüfung der Werkstatt (FOLLOW_UP) verlangt keine Quittierung
        veh3 = vehicle(A); defect(A, veh3); rid3 = run(A, veh3, 'FOLLOW_UP')
        with tx(A) as c: complete(c, A, rid3)

    def test_akte_nach_lauf_start_und_akte_aus_dem_lauf_sind_kein_altschaden(self):
        veh = vehicle(A); rid = run(A, veh)
        defect(A, veh)                                   # neu gemeldet nach dem Start des Laufs: kein Altschaden dieses Laufs
        d_src = defect(A, veh, source_inspection=rid)    # Mangel aus diesem Lauf selbst
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_known_defects(%s,%s)", (A, rid)).fetchone()[0], 0)
            complete(c, A, rid)
        self.assertIsNotNone(d_src)

    def test_mandantenisolation(self):
        veh = vehicle(A); d = defect(A, veh); rid = run(A, veh)
        with tx(A) as c: ack(c, A, rid, d)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.known_defect_acknowledgement").fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_known_defects(%s,%s)", (A, rid)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
