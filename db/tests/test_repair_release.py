"""WP-M3-51: Reparaturauftrag und Wiederfreigabeentscheidung in der Datenbank unter der echten Runtime-Rolle. Hier: Zustandsautomat des Reparaturauftrags per Trigger (auch bei direktem SQL),
Reparaturnachweis nur mit Nachweis der Akte und Kostenbezug, höchstens ein nicht stornierter Auftrag je Akte, Vieraugenpflicht und Eindeutigkeit der Freigabeentscheidung, Unveränderlichkeit."""
import itertools
import threading
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
OTHER = 'user:22222222-2222-4222-8222-222222222222'
STATES = ['OPEN', 'IN_PROGRESS', 'PROOF_RECORDED', 'CANCELED']
ALLOWED = {('OPEN', 'IN_PROGRESS'), ('OPEN', 'CANCELED'), ('IN_PROGRESS', 'PROOF_RECORDED'), ('IN_PROGRESS', 'CANCELED')}


def defect(c, tenant=A):
    vid, cid = uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, vid))
    c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BRAKES','x',%s)",
              (tenant, cid, c.execute('SELECT taktovia.next_defect_case_number()').fetchone()[0], vid, uid(), ACTOR))
    return cid, vid


def evidence(c, cid, tenant=A):
    eid = uid()
    c.execute("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (%s,%s,%s,'obj',%s,now(),%s)", (tenant, eid, cid, '0' * 64, ACTOR))
    return eid


def order(c, cid, vid, status='OPEN', tenant=A):
    oid = uid()
    c.execute("INSERT INTO taktovia.repair_order(tenant_id,id,defect_id,vehicle_id,status,created_by,cost_reference) VALUES (%s,%s,%s,%s,%s,%s,'KST-1')", (tenant, oid, cid, vid, status, ACTOR))
    return oid


def inspection(c, vid, tenant=A):
    shift, tpl, run = uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','Europe/Berlin',0,'PUBLISHED')", (tenant, shift, uid(), vid))
    c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,created_by) VALUES (%s,%s,%s,1,'PKW','AREA',%s)", (tenant, tpl, uid(), ACTOR))
    c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'FOLLOW_UP',now(),%s)", (tenant, run, shift, vid, uid(), tpl, ACTOR))
    return run


def decision(c, oid, vid, run, kind, by=ACTOR, second=None, tenant=A):
    c.execute("INSERT INTO taktovia.vehicle_release_decision(tenant_id,id,vehicle_id,repair_order_id,follow_up_inspection_id,decision,decided_by,second_approver,safety_revision) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,1)",
              (tenant, uid(), vid, oid, run, kind, by, second))


def step(c, oid, to, revision, **cols):
    sets = ''.join(f", {k}=%s" for k in cols)
    c.execute(f"UPDATE taktovia.repair_order SET status=%s, revision=%s{sets} WHERE id=%s", (to, revision, *cols.values(), oid))


class Reparaturauftrag(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm351-a'); seed_tenant(B, 'm351-b')

    def test_nur_tabellenuebergaenge_sind_speicherbar(self):
        for frm, to in itertools.permutations(STATES, 2):
            with tx(A) as c:
                cid, vid = defect(c); eid = evidence(c, cid); oid = order(c, cid, vid, frm)
                extra = {}
                if to == 'PROOF_RECORDED': extra = dict(proof_evidence_id=eid, proof_recorded_at='2026-01-01T00:00:00Z', proof_recorded_by=ACTOR)
                if to == 'CANCELED': extra = dict(cancel_reason='zurueckgezogen')
                if frm in ('PROOF_RECORDED', 'CANCELED') or (frm, to) not in ALLOWED:
                    with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{frm}->{to}'):
                        step(c, oid, to, 2, **extra)
                else:
                    step(c, oid, to, 2, **extra)

    def test_nachweis_braucht_evidenz_der_akte_und_kostenbezug(self):
        with tx(A) as c:
            cid, vid = defect(c); other, _ = defect(c); oid = order(c, cid, vid, 'IN_PROGRESS')
            with self.assertRaises(psycopg.errors.CheckViolation):   # ohne Nachweis
                step(c, oid, 'PROOF_RECORDED', 2, proof_recorded_at='2026-01-01T00:00:00Z', proof_recorded_by=ACTOR)
        with tx(A) as c:
            cid, vid = defect(c); other, _ = defect(c); foreign_ev = evidence(c, other); oid = order(c, cid, vid, 'IN_PROGRESS')
            with self.assertRaises(psycopg.errors.CheckViolation):   # Nachweis einer anderen Akte
                step(c, oid, 'PROOF_RECORDED', 2, proof_evidence_id=foreign_ev, proof_recorded_at='2026-01-01T00:00:00Z', proof_recorded_by=ACTOR)
        with tx(A) as c:
            cid, vid = defect(c); eid = evidence(c, cid); oid = uid()
            c.execute("INSERT INTO taktovia.repair_order(tenant_id,id,defect_id,vehicle_id,status,created_by) VALUES (%s,%s,%s,%s,'IN_PROGRESS',%s)", (A, oid, cid, vid, ACTOR))
            with self.assertRaises(psycopg.errors.CheckViolation):   # ohne Kostenbezug
                step(c, oid, 'PROOF_RECORDED', 2, proof_evidence_id=eid, proof_recorded_at='2026-01-01T00:00:00Z', proof_recorded_by=ACTOR)
        with tx(A) as c:   # vollständig: gelingt, danach endgültig
            cid, vid = defect(c); eid = evidence(c, cid); oid = order(c, cid, vid, 'IN_PROGRESS')
            step(c, oid, 'PROOF_RECORDED', 2, proof_evidence_id=eid, proof_recorded_at='2026-01-01T00:00:00Z', proof_recorded_by=ACTOR)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with admin() as a: a.execute("UPDATE taktovia.repair_order SET cancel_reason='x' WHERE id=%s", (oid,))

    def test_revision_je_aenderung_und_kein_loeschen_und_stornogrund(self):
        with tx(A) as c:
            cid, vid = defect(c); oid = order(c, cid, vid, 'OPEN')
            with self.assertRaises(psycopg.errors.CheckViolation):   # Revision muss um 1 steigen
                step(c, oid, 'IN_PROGRESS', 5)
        with tx(A) as c:
            cid, vid = defect(c); oid = order(c, cid, vid, 'OPEN')
            with self.assertRaises(psycopg.errors.CheckViolation):   # Storno ohne Grund
                step(c, oid, 'CANCELED', 2)
        with tx(A) as c:
            cid, vid = defect(c); oid = order(c, cid, vid, 'OPEN')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # die Runtime-Rolle hat kein Löschrecht
                c.execute("DELETE FROM taktovia.repair_order WHERE id=%s", (oid,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # und auch die Administration löscht nicht (Trigger)
            with admin() as a: a.execute("DELETE FROM taktovia.repair_order WHERE id=%s", (oid,))

    def test_hoechstens_ein_nicht_stornierter_auftrag_je_akte(self):
        with tx(A) as c:
            cid, vid = defect(c); order(c, cid, vid, 'OPEN')
            with self.assertRaises(psycopg.errors.UniqueViolation):
                order(c, cid, vid, 'OPEN')
        with tx(A) as c:   # ein stornierter Auftrag blockiert keinen neuen
            cid, vid = defect(c); order(c, cid, vid, 'CANCELED'); order(c, cid, vid, 'OPEN')

    def test_rls_trennt_mandanten(self):
        with tx(A) as c:
            cid, vid = defect(c); order(c, cid, vid)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.repair_order WHERE defect_id=%s", (cid,)).fetchone()[0], 0)


class Freigabeentscheidung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm351-a')

    def fixture(self, c):
        cid, vid = defect(c); oid = order(c, cid, vid, 'PROOF_RECORDED'); return cid, vid, oid, inspection(c, vid)

    def test_vieraugenpflicht_in_der_datenbank(self):
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
            with self.assertRaises(psycopg.errors.CheckViolation):   # abgeschlossene Entscheidung ohne zweite Person
                decision(c, oid, vid, run, 'RELEASED')
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
            with self.assertRaises(psycopg.errors.CheckViolation):   # dieselbe Person als zweite
                decision(c, oid, vid, run, 'RELEASED', second=ACTOR)
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
            with self.assertRaises(psycopg.errors.CheckViolation):   # ausstehende Entscheidung benennt nie sich selbst als zweite Person
                decision(c, oid, vid, run, 'PENDING_SECOND_APPROVAL', second=ACTOR)
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
            decision(c, oid, vid, run, 'PENDING_SECOND_APPROVAL'); decision(c, oid, vid, run, 'RELEASED', second=OTHER)

    def test_je_auftrag_hoechstens_eine_ausstehende_und_eine_abgeschlossene_entscheidung(self):
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
            decision(c, oid, vid, run, 'PENDING_SECOND_APPROVAL')
            with self.assertRaises(psycopg.errors.UniqueViolation):
                decision(c, oid, vid, run, 'PENDING_SECOND_APPROVAL', by=OTHER)
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
            decision(c, oid, vid, run, 'RELEASED', second=OTHER)
            with self.assertRaises(psycopg.errors.UniqueViolation):
                decision(c, oid, vid, run, 'RELEASED', by=OTHER, second=ACTOR)

    def test_parallele_ausstehende_entscheidungen_genau_eine(self):
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c)
        results = []
        def go(by):
            try:
                with tx(A) as c: decision(c, oid, vid, run, 'PENDING_SECOND_APPROVAL', by=by)
                results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('konflikt')
        ts = [threading.Thread(target=go, args=(f'user:{uid()}',)) for _ in range(5)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['konflikt'] * 4 + ['ok'])

    def test_entscheidung_ist_unveraenderlich(self):
        with tx(A) as c:
            cid, vid, oid, run = self.fixture(c); decision(c, oid, vid, run, 'PENDING_SECOND_APPROVAL')
        for sql in ("UPDATE taktovia.vehicle_release_decision SET decision='REJECTED'", "DELETE FROM taktovia.vehicle_release_decision"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with admin() as a: a.execute(sql)


if __name__ == '__main__':
    unittest.main()
