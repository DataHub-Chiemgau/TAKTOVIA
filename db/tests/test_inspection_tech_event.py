"""WP-M3-18 / LH-6.21-CHK-009: Technikereignis unter der echten Runtime-Rolle.

Positiv: die Technikvorlage verlangt je einen Pflichtpunkt für Fahrer-App, Mobilfunk, Navigation und Zubehör; ein Ereignis zu einer Mangel-Antwort trägt Prüflauf, Fahrzeug und Schicht.
Negativ (auch bei direktem SQL): Duplikat desselben Schritts, Ereignis ohne Mangel-Antwort, zu einem Lauf anderer Art, mit fremdem Fahrzeug oder fremder Schicht, UPDATE/DELETE, fremder Mandant.
Konkurrierend: zwei echte parallele Transaktionen für denselben Schritt ergeben genau ein Ereignis."""
import threading
import unittest

import psycopg

from contextlib import contextmanager

from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

T, T2 = uid(), uid()
ACTOR, ACTOR2 = 'user:11111111-1111-4111-8111-111111111111', 'user:22222222-2222-4222-8222-222222222222'
TECH = ('FAHRER_APP', 'MOBILFUNK', 'NAVIGATION', 'DIENSTZUBEHOER')


@contextmanager
def admin_tx(tenant):
    c = admin()
    try:
        c.autocommit = False
        c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
        yield c
        c.commit()
    finally:
        c.rollback(); c.close()


def setup_run(tenant, kind='TECH_READINESS', template_type='TECHNICAL_READINESS', steps=TECH):
    w = {k: uid() for k in ('vehicle', 'shift', 'template', 'run')}
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, w['vehicle']))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now() - interval '1 hour',now()+interval '8 hours','Europe/Berlin',0,'STARTED')", (tenant, w['shift'], uid(), w['vehicle']))
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,state,created_by,template_type) "
                  "VALUES (%s,%s,%s,1,'PKW','ONDEMAND','DRAFT',%s,%s)", (tenant, w['template'], uid(), ACTOR, template_type))
        for i, code in enumerate(steps, 1):
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                      "VALUES (%s,%s,%s,%s,%s,%s,true,false,'OK_DEFECT_NA',%s)", (tenant, uid(), w['template'], code, i, code, ACTOR))
        c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s WHERE id=%s", (ACTOR2, w['template']))
        c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) "
                  "VALUES (%s,%s,%s,%s,%s,%s,%s,now(),%s)", (tenant, w['run'], w['shift'], w['vehicle'], uid(), w['template'], kind, ACTOR))
    return w


def answer(c, tenant, run, step, value, note=None):
    c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,note,answered_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,now(),%s)",
              (tenant, uid(), run, step, value, note, ACTOR))


def event(c, tenant, w, step='NAVIGATION', **over):
    v = dict(id=uid(), inspection_id=w['run'], vehicle_id=w['vehicle'], shift_id=w['shift']); v.update(over)
    c.execute("INSERT INTO taktovia.tech_event(tenant_id,id,inspection_id,step_code,vehicle_id,shift_id,created_by) VALUES (%s,%s,%s,%s,%s,%s,'system:inspection')",
              (tenant, v['id'], v['inspection_id'], step, v['vehicle_id'], v['shift_id']))
    return v['id']


def count(c, w):
    return c.execute("SELECT count(*) FROM taktovia.tech_event WHERE inspection_id=%s", (w['run'],)).fetchone()[0]


class TechEvent(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T, 'technik'); seed_tenant(T2, 'technik-fremd')

    def test_vorlage_verlangt_je_einen_pflichtpunkt(self):
        # Positiv: vier Pflichtpunkte -> veröffentlichbar (setup_run veröffentlicht)
        setup_run(T)
        # Negativ: es fehlt ein Punkt (Zubehör) -> die Veröffentlichung scheitert bei direktem SQL
        with self.assertRaises(psycopg.errors.CheckViolation) as cm:
            setup_run(T, steps=('FAHRER_APP', 'MOBILFUNK', 'NAVIGATION'))
        self.assertIn('DIENSTZUBEHOER', str(cm.exception))
        with tx(T) as c:
            keys = c.execute("SELECT taktovia.checklist_mandatory_keys('TECHNICAL_READINESS')").fetchone()[0]
        self.assertEqual(sorted(TECH), sorted(keys))

    def test_ereignis_zu_mangel_antwort_traegt_lauf_fahrzeug_schicht(self):
        w = setup_run(T)
        with tx(T) as c:
            answer(c, T, w['run'], 'NAVIGATION', 'DEFECT', note='Karte laedt nicht')
            answer(c, T, w['run'], 'MOBILFUNK', 'OK')
            eid = event(c, T, w)
        with tx(T) as c:
            row = c.execute("SELECT inspection_id, vehicle_id, shift_id, step_code FROM taktovia.tech_event WHERE id=%s", (eid,)).fetchone()
        self.assertEqual((w['run'], w['vehicle'], w['shift'], 'NAVIGATION'), row)

    def test_duplikat_erzeugt_kein_zweites_ereignis(self):
        w = setup_run(T)
        with tx(T) as c:
            answer(c, T, w['run'], 'NAVIGATION', 'DEFECT', note='x')
            event(c, T, w)
        # Duplikat per ON CONFLICT DO NOTHING: keine Zeile, kein Fehler
        with tx(T) as c:
            n = c.execute("INSERT INTO taktovia.tech_event(tenant_id,id,inspection_id,step_code,vehicle_id,shift_id,created_by) VALUES (%s,%s,%s,'NAVIGATION',%s,%s,'system:inspection') "
                          "ON CONFLICT (tenant_id, inspection_id, step_code) DO NOTHING", (T, uid(), w['run'], w['vehicle'], w['shift'])).rowcount
        self.assertEqual(0, n)
        # Duplikat ohne ON CONFLICT scheitert hart
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(T) as c:
                event(c, T, w)
        with tx(T) as c:
            self.assertEqual(1, count(c, w))

    def test_ereignis_nur_zu_gueltigem_bezug(self):
        w = setup_run(T)
        other = setup_run(T)
        with tx(T) as c:
            answer(c, T, w['run'], 'NAVIGATION', 'OK'); answer(c, T, w['run'], 'MOBILFUNK', 'DEFECT', note='x')
        for label, fn in (
            ('ohne Mangel-Antwort', lambda c: event(c, T, w, 'NAVIGATION')),
            ('Schritt ohne Antwort', lambda c: event(c, T, w, 'FAHRER_APP')),
            ('fremdes Fahrzeug', lambda c: event(c, T, w, 'MOBILFUNK', vehicle_id=other['vehicle'])),
            ('fremde Schicht', lambda c: event(c, T, w, 'MOBILFUNK', shift_id=other['shift'])),
        ):
            with self.subTest(label), self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.ForeignKeyViolation)):
                with tx(T) as c:
                    fn(c)
        # Lauf einer anderen Art (Vorabprüfung der Schicht) kann kein Technikereignis tragen
        pre = setup_run(T, kind='PRE_SHIFT', template_type='GENERAL', steps=('NAVIGATION',))
        with tx(T) as c:
            answer(c, T, pre['run'], 'NAVIGATION', 'DEFECT', note='x')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(T) as c:
                event(c, T, pre, 'NAVIGATION')

    def test_unveraenderlich_und_mandantengetrennt(self):
        w = setup_run(T)
        with tx(T) as c:
            answer(c, T, w['run'], 'NAVIGATION', 'DEFECT', note='x'); eid = event(c, T, w)
        for sql in ("UPDATE taktovia.tech_event SET step_code='MOBILFUNK' WHERE id=%s", "DELETE FROM taktovia.tech_event WHERE id=%s"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Runtime-Rolle hat nur SELECT, INSERT
                with tx(T) as c:
                    c.execute(sql, (eid,))
            with self.assertRaises(psycopg.errors.CheckViolation):          # zweite Schicht: Trigger auch für die Administration
                with admin_tx(T) as c:
                    c.execute(sql, (eid,))
        with tx(T2) as c:
            self.assertEqual(0, c.execute("SELECT count(*) FROM taktovia.tech_event WHERE id=%s", (eid,)).fetchone()[0])
        with self.assertRaises(psycopg.errors.Error):   # Insert mit fremdem Mandanten verletzt die RLS-Policy
            with tx(T2) as c:
                event(c, T, w)

    def test_parallele_uebermittlung_desselben_schritts_ergibt_ein_ereignis(self):
        w = setup_run(T)
        with tx(T) as c:
            answer(c, T, w['run'], 'NAVIGATION', 'DEFECT', note='x')
        barrier = threading.Barrier(8); results = []

        def worker():
            conn = runtime()
            try:
                set_tenant(conn, T)
                barrier.wait()
                n = conn.execute("INSERT INTO taktovia.tech_event(tenant_id,id,inspection_id,step_code,vehicle_id,shift_id,created_by) VALUES (%s,%s,%s,'NAVIGATION',%s,%s,'system:inspection') "
                                 "ON CONFLICT (tenant_id, inspection_id, step_code) DO NOTHING", (T, uid(), w['run'], w['vehicle'], w['shift'])).rowcount
                conn.commit(); results.append(n)
            except BaseException as e:   # pragma: no cover
                conn.rollback(); results.append(e)
            finally:
                conn.close()
        threads = [threading.Thread(target=worker) for _ in range(8)]
        [t.start() for t in threads]; [t.join() for t in threads]
        self.assertEqual(sorted(results), [0] * 7 + [1], results)
        with tx(T) as c:
            self.assertEqual(1, count(c, w))


if __name__ == '__main__':
    unittest.main()
