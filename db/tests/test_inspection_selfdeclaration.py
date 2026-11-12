"""WP-M3-19 / LH-6.21-CHK-010: Selbstauskunft „Einsatzbereitschaft“ ohne Textspalte, unter der echten Runtime-Rolle.

Positiv: Antwort JA und NEIN ohne Zusatzwerte, Freigabe zu einem Lauf mit JA. Negativ (auch bei direktem SQL): Notiz, Text, Zahl oder Begründung zur Selbstauskunft, JA/NEIN an einem anderen
Prüfelement, OK am Selbstauskunftselement, Nachtrag zur Selbstauskunft, Freigabe bei NEIN oder ohne Antwort. Strukturell: das Schema der Selbstauskunft kennt nur Ja/Nein."""
import json
import unittest
from pathlib import Path

import psycopg

from helpers import runtime, seed_tenant, set_tenant, tx, uid

T = uid()
ACTOR, ACTOR2 = 'user:11111111-1111-4111-8111-111111111111', 'user:22222222-2222-4222-8222-222222222222'
SCHEMA_FILE = Path(__file__).resolve().parents[2] / 'api' / 'schemas' / 'self-declaration.v1.schema.json'


def setup_run(tenant, with_readiness=True):
    w = {k: uid() for k in ('vehicle', 'shift', 'template', 'run', 'readiness_step', 'ok_step')}
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{\"seats\":4,\"wheelchair\":1}')", (tenant, w['vehicle']))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now() - interval '1 hour',now()+interval '8 hours','Europe/Berlin',0,'STARTED')", (tenant, w['shift'], uid(), w['vehicle']))
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,state,published_at,created_by) "
                  "VALUES (%s,%s,%s,1,'PKW','ONDEMAND','DRAFT',NULL,%s)", (tenant, w['template'], uid(), ACTOR))
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                  "VALUES (%s,%s,%s,'STEP_1',1,'Sicht',true,false,'OK_DEFECT_NA',%s)", (tenant, w['ok_step'], w['template'], ACTOR))
        if with_readiness:
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                      "VALUES (%s,%s,%s,'EINSATZBEREITSCHAFT',2,'Einsatzbereit',true,false,'READINESS_YES_NO',%s)", (tenant, w['readiness_step'], w['template'], ACTOR))
        c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s WHERE id=%s", (ACTOR2, w['template']))
        c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) "
                  "VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s)", (tenant, w['run'], w['shift'], w['vehicle'], uid(), w['template'], ACTOR))
    return w


def answer(c, tenant, run, step, value, **cols):
    names = ['tenant_id', 'id', 'inspection_id', 'step_code', 'answer', 'answered_at', 'created_by'] + list(cols)
    vals = [tenant, uid(), run, step, value, '2026-05-04T05:55:00Z', ACTOR] + list(cols.values())
    c.execute(f"INSERT INTO taktovia.inspection_answer({','.join(names)}) VALUES ({','.join(['%s'] * len(vals))})", vals)


def release(c, tenant, w):
    c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) "
              "VALUES (%s,%s,%s,%s,1,%s,1,now()+interval '4 hours')", (tenant, uid(), w['vehicle'], w['shift'], w['run']))


class SelfDeclaration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T, 'selbstauskunft')

    def reject(self, fn):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(T) as c:
                fn(c)

    def test_einsatzbereitschaft_hat_keine_textspalte(self):
        w = setup_run(T)
        # Positiv: JA und NEIN ohne Zusatzwerte
        with tx(T) as c:
            answer(c, T, w['run'], 'EINSATZBEREITSCHAFT', 'NEIN')
            c.execute("UPDATE taktovia.inspection_answer SET answer='JA' WHERE inspection_id=%s AND step_code='EINSATZBEREITSCHAFT'", (w['run'],))
            row = c.execute("SELECT answer, note, text_value, number_value FROM taktovia.inspection_answer WHERE inspection_id=%s AND step_code='EINSATZBEREITSCHAFT'", (w['run'],)).fetchone()
        self.assertEqual(('JA', None, None, None), row)
        # Negativ, auch bei direktem SQL: kein Text, keine Notiz, keine Zahl, keine Begründung neben JA/NEIN
        for value in ('JA', 'NEIN'):
            for col, v in (('note', 'Kopfschmerzen'), ('text_value', 'erkältet'), ('number_value', 38.5), ('justification_reason', 'Grund'), ('plausibility', 'PLAUSIBEL')):
                w2 = setup_run(T)
                with self.subTest(value=value, column=col):
                    extra = {col: v}
                    if col == 'justification_reason':
                        extra.update(justified_by=ACTOR, justified_at='2026-05-04T05:55:00Z', plausibility='BEGRUENDET_FREIGEGEBEN')
                    self.reject(lambda c: answer(c, T, w2['run'], 'EINSATZBEREITSCHAFT', value, **extra))
        # die Textspalte note nimmt auch per UPDATE keinen Text auf
        w3 = setup_run(T)
        with tx(T) as c:
            answer(c, T, w3['run'], 'EINSATZBEREITSCHAFT', 'JA')
        self.reject(lambda c: c.execute("UPDATE taktovia.inspection_answer SET note='Rücken' WHERE inspection_id=%s AND step_code='EINSATZBEREITSCHAFT'", (w3['run'],)))

    def test_ja_nein_nur_am_selbstauskunftselement_und_umgekehrt(self):
        w = setup_run(T)
        self.reject(lambda c: answer(c, T, w['run'], 'STEP_1', 'JA'))                     # JA an einem anderen Prüfelement
        self.reject(lambda c: answer(c, T, w['run'], 'EINSATZBEREITSCHAFT', 'OK'))        # OK am Selbstauskunftselement
        self.reject(lambda c: answer(c, T, w['run'], 'EINSATZBEREITSCHAFT', 'DEFECT', note='Mangel'))
        wn = setup_run(T, with_readiness=False)
        self.reject(lambda c: answer(c, T, wn['run'], 'STEP_1', 'NEIN'))

    def test_keine_freigabe_ohne_ja_und_kein_nachtrag(self):
        w = setup_run(T)
        self.reject(lambda c: release(c, T, w))                                            # Selbstauskunftselement ohne Antwort
        wn = setup_run(T)
        with tx(T) as c:
            answer(c, T, wn['run'], 'EINSATZBEREITSCHAFT', 'NEIN')
        self.reject(lambda c: release(c, T, wn))                                           # NEIN
        wj = setup_run(T)
        with tx(T) as c:
            answer(c, T, wj['run'], 'STEP_1', 'OK'); answer(c, T, wj['run'], 'EINSATZBEREITSCHAFT', 'JA')
            c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now(), safety_revision=1 WHERE id=%s", (wj['run'],))
            release(c, T, wj)                                                              # JA: Freigabe möglich
            n = c.execute("SELECT count(*) FROM taktovia.resource_release WHERE inspection_ref=%s", (wj['run'],)).fetchone()[0]
        self.assertEqual(1, n)
        # Nachtrag zur Selbstauskunft eines abgeschlossenen Laufs ist ausgeschlossen
        def amend(c):
            aid = c.execute("SELECT id FROM taktovia.inspection_answer WHERE inspection_id=%s AND step_code='EINSATZBEREITSCHAFT'", (wj['run'],)).fetchone()[0]
            c.execute("INSERT INTO taktovia.inspection_amendment(tenant_id,id,inspection_id,step_code,amended_by,reason,new_answer,answer_id,version,kind,new_note) "
                      "VALUES (%s,%s,%s,'EINSATZBEREITSCHAFT',%s,'Korrektur','OK',%s,1,'CORRECTION','Rücken')", (T, uid(), wj['run'], ACTOR, aid))
        self.reject(amend)

    def test_schema_der_selbstauskunft_hat_kein_freitextfeld(self):
        schema = json.loads(SCHEMA_FILE.read_text(encoding='utf-8'))
        self.assertIs(False, schema['additionalProperties'])
        for name, prop in schema['properties'].items():
            if prop.get('type') == 'string' and 'enum' not in prop:
                self.assertTrue('pattern' in prop or 'format' in prop, f'{name}: freier Text ohne Muster/Format')
                self.assertNotIn(name, ('note', 'text', 'reason', 'comment', 'text_value'))
        self.assertEqual(['JA', 'NEIN'], schema['properties']['answer']['enum'])


if __name__ == '__main__':
    unittest.main()
