"""WP-M3-10 (LH-6.21-CHK-003): Checklistenvorlagen und Prüfelemente auf Datenbankebene, unter der echten Runtime-Rolle.

Positiv: Vorlage mit allen Eingabetypen, Hilfetext und Beispielbild-Name, Freigabe mit Vier-Augen-Angabe. Negativ: Prüfelement ohne Titel/Eingabetyp/Pflichtstatus,
freigegebene Revision und ihre Schritte unveränderlich, Freigabe ohne Prüfelement oder durch den Ersteller, unbekannter Antwortstatus (CHECK), Beispielbild-Name mit Pfadtricks.
Konkurrierend: zwei gleichzeitige Freigaben derselben Vorlage -> genau eine wirksame Revision."""
import threading
import unittest

import psycopg

from helpers import admin, seed_tenant, tx, uid

A, B = uid(), uid()
AUTHOR = 'user:11111111-1111-4111-8111-111111111111'
REVIEWER = 'user:22222222-2222-4222-8222-222222222222'


def draft(tenant, template_id=None, version=1, steps=True):
    rid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) "
                  "VALUES (%s,%s,%s,%s,'PKW','ONDEMAND','Vorlage',%s)", (tenant, rid, template_id or uid(), version, AUTHOR))
        if steps:
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,help_text,example_image_name,mandatory,safety_critical,answer_kind,na_requires_reason,created_by) "
                      "VALUES (%s,%s,%s,'BRAKES',1,'Bremsen','Pedal treten','checklist/brakes.png',true,true,'OK_DEFECT_NA',true,%s)", (tenant, uid(), rid, AUTHOR))
    return rid


def publish(tenant, rid, approver=REVIEWER):
    with tx(tenant) as c:
        c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s, revision=revision+1 WHERE tenant_id=%s AND id=%s", (approver, tenant, rid))


class Checklistenvorlage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'chk-a'); seed_tenant(B, 'chk-b')

    def _scheitert(self, sql, args=(), tenant=A, state='23514'):
        with self.assertRaises(psycopg.Error) as cm:
            with tx(tenant) as c:
                c.execute(sql, args)
        self.assertEqual(cm.exception.sqlstate, state, str(cm.exception))

    def test_vorlage_mit_allen_eingabetypen_und_freigabe(self):
        rid = draft(A)
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,number_min,number_max,number_unit,created_by) "
                      "VALUES (%s,%s,%s,'TYRE_DEPTH',2,'Profiltiefe',true,false,'NUMBER',1.6,10,'mm',%s)", (A, uid(), rid, AUTHOR))
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                      "VALUES (%s,%s,%s,'REMARK',3,'Bemerkung',false,false,'TEXT_SHORT',%s)", (A, uid(), rid, AUTHOR))
        publish(A, rid)
        with tx(A) as c:
            kinds = [r[0] for r in c.execute("SELECT answer_kind FROM taktovia.checklist_step WHERE template_revision_id=%s ORDER BY position", (rid,))]
            self.assertEqual(kinds, ['OK_DEFECT_NA', 'NUMBER', 'TEXT_SHORT'])
            self.assertEqual(c.execute("SELECT state, approved_by FROM taktovia.checklist_template_revision WHERE id=%s", (rid,)).fetchone(), ('PUBLISHED', REVIEWER))

    def test_pruefelement_ohne_titel_eingabetyp_pflichtstatus_nicht_speicherbar(self):
        rid = draft(A, steps=False)
        base = ("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                "VALUES (%s,%s,%s,'S1',1,%s,%s,false,%s,%s)")
        self._scheitert(base, (A, uid(), rid, None, True, 'OK_DEFECT_NA', AUTHOR), state='23502')            # Titel fehlt
        self._scheitert(base, (A, uid(), rid, '   ', True, 'OK_DEFECT_NA', AUTHOR), state='23514')           # Titel leer
        self._scheitert(base, (A, uid(), rid, 'T', None, 'OK_DEFECT_NA', AUTHOR), state='23502')             # Pflichtstatus fehlt
        self._scheitert(base, (A, uid(), rid, 'T', True, None, AUTHOR), state='23502')                       # Eingabetyp fehlt
        self._scheitert(base, (A, uid(), rid, 'T', True, 'FREITEXT_LANG', AUTHOR), state='23514')            # unbekannter Eingabetyp
        self._scheitert("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,number_min,created_by) "
                        "VALUES (%s,%s,%s,'S2',2,'T',true,false,'TEXT_SHORT',1,%s)", (A, uid(), rid, AUTHOR))  # Zahlengrenze nur bei NUMBER
        self._scheitert("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,example_image_name,created_by) "
                        "VALUES (%s,%s,%s,'S3',3,'T',true,false,'OK_DEFECT_NA','../geheim.png',%s)", (A, uid(), rid, AUTHOR))  # Pfadtrick im Bildnamen

    def test_freigegebene_revision_unveraenderlich_und_statuswerte(self):
        rid = draft(A)
        publish(A, rid)
        self._scheitert("UPDATE taktovia.checklist_step SET title='X' WHERE template_revision_id=%s", (rid,))
        self._scheitert("DELETE FROM taktovia.checklist_step WHERE template_revision_id=%s", (rid,))
        self._scheitert("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                        "VALUES (%s,%s,%s,'NEU',2,'Neu',true,false,'OK_DEFECT_NA',%s)", (A, uid(), rid, AUTHOR))
        self._scheitert("UPDATE taktovia.checklist_template_revision SET name='Anders' WHERE id=%s", (rid,))
        self._scheitert("UPDATE taktovia.checklist_template_revision SET approved_by=%s WHERE id=%s", ('user:33333333-3333-4333-8333-333333333333', rid))
        self._scheitert("DELETE FROM taktovia.checklist_template_revision WHERE id=%s", (rid,), state="42501")   # kein DELETE-Recht der Runtime-Rolle
        # Antwortwerte: nur die definierten Statuswerte; ein unbekannter Wert wird von der Datenbank abgewiesen (NOT_CHECKABLE ist durch OP-16 nur vorgehalten)
        shift, vehicle, run = uid(), uid(), uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, vehicle))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                      "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", (A, shift, uid(), vehicle))
            c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s)",
                      (A, run, shift, vehicle, uid(), rid, AUTHOR))
        for value in ('OK', 'DEFECT', 'NOT_APPLICABLE'):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,answered_at,created_by) VALUES (%s,%s,%s,%s,%s,now(),%s)",
                          (A, uid(), run, 'S_' + value[:3], value, AUTHOR))
        for bad in ('MAYBE', 'ok', '', 'UNKNOWN'):
            self._scheitert("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,answered_at,created_by) VALUES (%s,%s,%s,'S_BAD',%s,now(),%s)", (A, uid(), run, bad, AUTHOR))

    def test_freigabe_nur_mit_pruefelement_und_zweiter_person(self):
        empty = draft(A, steps=False)
        self._scheitert("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s WHERE id=%s", (REVIEWER, empty))
        rid = draft(A)
        self._scheitert("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s WHERE id=%s", (AUTHOR, rid))   # Ersteller selbst
        self._scheitert("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now() WHERE id=%s", (rid,))                          # ohne Freigebenden
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT state FROM taktovia.checklist_template_revision WHERE id=%s", (rid,)).fetchone()[0], 'DRAFT')

    def test_ein_entwurf_und_eine_wirksame_revision_je_vorlage_und_mandantentrennung(self):
        tid = uid()
        d1 = draft(A, tid)
        with self.assertRaises(psycopg.Error) as cm:
            draft(A, tid, version=2)   # zweiter Entwurf derselben Vorlage
        self.assertEqual(cm.exception.sqlstate, '23505')
        publish(A, d1)
        d2 = draft(A, tid, version=2)
        with self.assertRaises(psycopg.Error) as cm:
            publish(A, d2)             # zweite wirksame Revision, ohne die erste abzulösen
        self.assertEqual(cm.exception.sqlstate, '23505')
        with tx(A) as c:
            c.execute("UPDATE taktovia.checklist_template_revision SET state='RETIRED', revision=revision+1 WHERE id=%s", (d1,))
        publish(A, d2)
        with tx(B) as c:   # fremder Mandant sieht nichts
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.checklist_template_revision WHERE template_id=%s", (tid,)).fetchone()[0], 0)

    def test_gleichzeitige_freigaben_genau_eine_wirksame_revision(self):
        tid = uid()
        rid = draft(A, tid)
        results, barrier = [], threading.Barrier(2)

        def worker(approver):
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10)
                    n = c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s, revision=revision+1 "
                                  "WHERE tenant_id=%s AND id=%s AND state='DRAFT'", (approver, A, rid)).rowcount
                results.append(n)
            except psycopg.Error as e:
                results.append(e.sqlstate)

        ts = [threading.Thread(target=worker, args=(a,)) for a in (REVIEWER, 'user:44444444-4444-4444-8444-444444444444')]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(str(r) for r in results), ['0', '1'], results)   # genau eine Freigabe wirksam, die andere findet den Entwurf nicht mehr
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.checklist_template_revision WHERE template_id=%s AND state='PUBLISHED'", (tid,)).fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
