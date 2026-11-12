"""WP-M3-13 (LH-6.21-CHK-002): Datenbankgrundlage des Prüflauf-Starts, unter der echten Runtime-Rolle.

Bedienart am Fahrzeug (Planungsannahme PA-08: Vorgabe STANDARD, Format wie die Vorlagenwerte), Vorlagenwahl über das Paar (Fahrzeugklasse, Bedienart) nur auf freigegebene Revisionen,
höchstens ein offener Prüflauf je (Schicht, Fahrzeug, Art) auch bei echt gleichzeitigen Transaktionen, Teil-Unique-Index lässt abgeschlossene Läufe daneben zu."""
import threading
import unittest

import psycopg

from helpers import seed_tenant, tx, uid

A = uid()
USER = 'user:11111111-1111-4111-8111-111111111111'
REVIEWER = 'user:22222222-2222-4222-8222-222222222222'


def vehicle(tenant, cls='PKW', mode=None):
    vid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_type) VALUES (%s,%s,'fixture','{}',%s)", (tenant, vid, cls))
        if mode is not None:
            c.execute("UPDATE taktovia.vehicle SET operation_kind=%s WHERE tenant_id=%s AND id=%s", (mode, tenant, vid))
    return vid


def shift(tenant, vid):
    sid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now()-interval '1 hour',now()+interval '7 hours','Europe/Berlin',0,'PLANNED')", (tenant, sid, uid(), vid))
    return sid


def template(tenant, cls, mode, published=True):
    rid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) VALUES (%s,%s,%s,1,%s,%s,'V',%s)",
                  (tenant, rid, uid(), cls, mode, USER))
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) VALUES (%s,%s,%s,'S1',1,'T',true,false,'OK_DEFECT_NA',%s)",
                  (tenant, uid(), rid, USER))
        if published:
            c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s, revision=revision+1 WHERE tenant_id=%s AND id=%s", (REVIEWER, tenant, rid))
    return rid


def run_insert(c, tenant, sid, vid, tpl, kind='PRE_SHIFT'):
    c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,%s,now(),%s)",
              (tenant, uid(), sid, vid, uid(), tpl, kind, USER))


class Pruefstart(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm3-13')

    def test_bedienart_vorgabe_standard_und_format(self):
        vid = vehicle(A)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT operation_kind FROM taktovia.vehicle WHERE id=%s", (vid,)).fetchone()[0], 'STANDARD')
        for bad in ('assistenz', 'A', '', 'MIT LEERZEICHEN'):
            with self.assertRaises(psycopg.Error) as cm:
                with tx(A) as c:
                    c.execute("UPDATE taktovia.vehicle SET operation_kind=%s WHERE id=%s", (bad, vid))
            self.assertEqual(cm.exception.sqlstate, '23514', bad)

    def test_vorlagenwahl_nur_freigegebene_revisionen_des_paares(self):
        cls = 'KLASSE' + uid().hex[:6].upper()
        entwurf = template(A, cls, 'STANDARD', published=False)
        with tx(A) as c:
            sel = "SELECT id FROM taktovia.checklist_template_revision WHERE state='PUBLISHED' AND vehicle_class=%s AND service_mode=%s"
            self.assertEqual(c.execute(sel, (cls, 'STANDARD')).fetchall(), [])             # Entwurf zählt nicht
        frei = template(A, cls, 'STANDARD'); andere = template(A, cls, 'ASSISTENZ')
        with tx(A) as c:
            self.assertEqual([r[0] for r in c.execute(sel, (cls, 'STANDARD')).fetchall()], [frei])
            self.assertEqual([r[0] for r in c.execute(sel, (cls, 'ASSISTENZ')).fetchall()], [andere])
            self.assertNotEqual(entwurf, frei)

    def test_ein_offener_lauf_je_schicht_fahrzeug_art_und_abgeschlossene_daneben(self):
        vid = vehicle(A); sid = shift(A, vid); tpl = template(A, 'PKW', 'STANDARD')
        with tx(A) as c:
            run_insert(c, A, sid, vid, tpl)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c:
                run_insert(c, A, sid, vid, tpl)
        self.assertEqual(cm.exception.sqlstate, '23505')
        with tx(A) as c:
            run_insert(c, A, sid, vid, tpl, kind='VEHICLE_CHANGE')   # andere Art ist erlaubt
            c.execute("UPDATE taktovia.inspection_run SET status='CANCELED' WHERE shift_id=%s AND kind='PRE_SHIFT'", (sid,))
        with tx(A) as c:
            run_insert(c, A, sid, vid, tpl)                          # nach Abbruch wieder ein offener Lauf möglich

    def test_gleichzeitiger_doppelstart_genau_ein_offener_lauf(self):
        vid = vehicle(A); sid = shift(A, vid); tpl = template(A, 'PKW', 'STANDARD')
        results = []
        barrier = threading.Barrier(6)

        def worker():
            try:
                with tx(A) as c:
                    barrier.wait(10)
                    cur = c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) "
                                    "VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s) ON CONFLICT (tenant_id, shift_id, vehicle_id, kind) WHERE status = 'IN_PROGRESS' DO NOTHING",
                                    (A, uid(), sid, vid, uid(), tpl, USER))
                    results.append(cur.rowcount)
            except Exception as e:  # noqa: BLE001
                results.append(repr(e))

        threads = [threading.Thread(target=worker) for _ in range(6)]
        [t.start() for t in threads]; [t.join(30) for t in threads]
        self.assertEqual(sorted(results), [0, 0, 0, 0, 0, 1], results)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_run WHERE shift_id=%s AND status='IN_PROGRESS'", (sid,)).fetchone()[0], 1)

    def test_vorlage_anderer_mandant_unsichtbar(self):
        B = uid(); seed_tenant(B, 'm3-13-b')
        cls = 'FREMD' + uid().hex[:6].upper()
        template(B, cls, 'STANDARD')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.checklist_template_revision WHERE state='PUBLISHED' AND vehicle_class=%s", (cls,)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
