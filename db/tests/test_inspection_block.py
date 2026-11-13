"""WP-M3-16 / LH-6.21-CHK-004 (Kritischer Mangel sperrt synchron) und WP-M3-12 / LH-6.21-CHK-007 (eingeschränkte Freigabe) in der Datenbank, unter der echten Runtime-Rolle.

Kritischer Mangel: ein abgeschlossener Prüflauf mit Ergebnis BLOCKED und einem DEFECT auf einem sicherheitskritischen Element existiert nur gemeinsam mit einer Sperre dieses Laufs (Fahrzeug- oder
Dienstsperre) in derselben Transaktion; ohne Sperre scheitert der Commit (zurückgestellter Constraint-Trigger, auch bei direktem SQL), mit Sperre gelingt er, ein Rollback nimmt beides zurück.
Sperrart nur bei kritischen Elementen; Dienstsperre nur ACTIVE -> LIFTED, nie gelöscht; Vorgangsnummer lückenlos je Mandant und Jahr, auch bei echt gleichzeitigen Transaktionen.
Eingeschränkte Freigabe: Einschränkung genau bei PASSED_RESTRICTED, „Nicht prüfbar“ nur für Ausstattungsprüfpunkte, Ausstattungsbedingung und einschränkende Dimension nur gemeinsam."""
import threading
import unittest

import psycopg

from helpers import seed_tenant, tx, uid

A, B = uid(), uid()
USER = 'user:11111111-1111-4111-8111-111111111111'
REVIEWER = 'user:22222222-2222-4222-8222-222222222222'
SYSTEM = 'system:inspection'


def world(tenant, critical=True, block_kind=None, equipment=False):
    """Fahrzeug, Schicht, veröffentlichte Vorlage (BREMSEN kritisch, optional Rampenprüfpunkt) und offener Prüflauf mit Antworten; liefert die Kennungen."""
    veh, shift, tpl, run = uid(), uid(), uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_type) VALUES (%s,%s,'fixture','{}','PKW')", (tenant, veh))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now()-interval '1 hour',now()+interval '7 hours','Europe/Berlin',0,'STARTED')", (tenant, shift, uid(), veh))
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) VALUES (%s,%s,%s,1,'PKW','STANDARD','V',%s)", (tenant, tpl, uid(), USER))
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,block_kind,answer_kind,created_by) VALUES (%s,%s,%s,'BREMSEN',1,'B',true,%s,%s,'OK_DEFECT_NA',%s)",
                  (tenant, uid(), tpl, critical, block_kind, USER))
        if equipment:
            c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,equipment_condition,restricts_dimension,created_by) "
                      "VALUES (%s,%s,%s,'RAMPE',2,'R',true,false,'OK_DEFECT_NA','RAMP_OR_LIFT','wheelchair',%s)", (tenant, uid(), tpl, USER))
        c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now(), approved_by=%s, revision=revision+1 WHERE tenant_id=%s AND id=%s", (REVIEWER, tenant, tpl))
        c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s)",
                  (tenant, run, shift, veh, uid(), tpl, USER))
    return veh, shift, run


def answer(c, tenant, run, step, value):
    c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,note,answered_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,now(),%s)",
              (tenant, uid(), run, step, value, 'Befund' if value == 'DEFECT' else None, USER))


def complete(c, tenant, run, outcome, restriction=None):
    c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome=%s, restriction=%s::jsonb, completed_at=now(), received_at=now() WHERE tenant_id=%s AND id=%s", (outcome, restriction, tenant, run))


def vehicle_block(c, tenant, veh, run, case=None):
    c.execute("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,reason_ref,set_by,case_number) VALUES (%s,%s,%s,'SAFETY','INSPECTION_FAILED',%s,%s,%s)",
              (tenant, uid(), veh, f'{run}:BREMSEN', SYSTEM, case or c.execute('SELECT taktovia.next_block_case_number()').fetchone()[0]))


def shift_block(c, tenant, veh, shift, run):
    c.execute("INSERT INTO taktovia.shift_block(tenant_id,id,shift_id,vehicle_id,reason_ref,case_number,set_by) VALUES (%s,%s,%s,%s,%s,%s,%s)",
              (tenant, uid(), shift, veh, f'{run}:BREMSEN', c.execute('SELECT taktovia.next_block_case_number()').fetchone()[0], SYSTEM))


class KritischerMangel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm3-16-a'); seed_tenant(B, 'm3-16-b')

    def _count(self, tenant, sql, *args):
        with tx(tenant) as c:
            return c.execute(sql, args).fetchone()[0]

    def test_sperre_und_ergebnis_in_einer_transaktion(self):
        veh, shift, run = world(A)
        # Ergebnis gesperrt mit kritischem Mangel, aber ohne Sperre: der Commit scheitert, das Ergebnis wird nicht gespeichert (kein Zustand „Mangel gespeichert, Fahrzeug disponierbar“)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c:
                answer(c, A, run, 'BREMSEN', 'DEFECT'); complete(c, A, run, 'BLOCKED')
        self.assertEqual(cm.exception.sqlstate, '23514'); self.assertIn('CRITICAL_DEFECT_WITHOUT_BLOCK', str(cm.exception))
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.inspection_run WHERE id=%s AND status='COMPLETED'", run), 0)
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id=%s", run), 0)
        # mit Sperre in derselben Transaktion gelingt der Abschluss; Sperre und Ergebnis stehen gemeinsam da, Vorgangsnummer im Format SPE-<Jahr>-<Nummer>
        with tx(A) as c:
            answer(c, A, run, 'BREMSEN', 'DEFECT'); vehicle_block(c, A, veh, run); complete(c, A, run, 'BLOCKED')
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=%s AND status='ACTIVE' AND case_number ~ '^SPE-[0-9]{4}-[0-9]{6,}$'", veh), 1)
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.inspection_run WHERE id=%s AND outcome='BLOCKED'", run), 1)

    def test_rollback_nimmt_sperre_und_ergebnis_gemeinsam_zurueck(self):
        veh, shift, run = world(A)
        try:
            with tx(A) as c:
                answer(c, A, run, 'BREMSEN', 'DEFECT'); vehicle_block(c, A, veh, run); complete(c, A, run, 'BLOCKED')
                raise RuntimeError('Abbruch nach dem Schreiben der Sperre')
        except RuntimeError:
            pass
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=%s", veh), 0)
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.inspection_run WHERE id=%s AND status='IN_PROGRESS'", run), 1)
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.inspection_answer WHERE inspection_id=%s", run), 0)

    def test_dienstsperre_genuegt_dem_invariant_und_sperrt_nur_die_schicht(self):
        veh, shift, run = world(A, block_kind='SERVICE')
        with tx(A) as c:
            answer(c, A, run, 'BREMSEN', 'DEFECT'); shift_block(c, A, veh, shift, run); complete(c, A, run, 'BLOCKED')
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=%s", veh), 0)
        # nur ACTIVE -> LIFTED, nie gelöscht, Inhalt unveränderlich
        for sql in ("DELETE FROM taktovia.shift_block WHERE shift_id=%s", "UPDATE taktovia.shift_block SET case_number='SPE-2000-000001' WHERE shift_id=%s",
                    "UPDATE taktovia.shift_block SET status='LIFTED' WHERE shift_id=%s"):
            with self.assertRaises(psycopg.Error) as cm:
                with tx(A) as c:
                    c.execute(sql, (shift,))
            self.assertIn(cm.exception.sqlstate, ('23514', '42501'), sql)
        # dieselbe Sperre (Schicht, Quellbezug) höchstens einmal aktiv
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c:
                shift_block(c, A, veh, shift, run)

    def test_unkritischer_mangel_braucht_keine_sperre(self):
        veh, shift, run = world(A, critical=False)
        with tx(A) as c:
            answer(c, A, run, 'BREMSEN', 'DEFECT'); complete(c, A, run, 'PASSED')
        self.assertEqual(self._count(A, "SELECT count(*) FROM taktovia.inspection_run WHERE id=%s AND outcome='PASSED'", run), 1)

    def test_sperrart_nur_bei_kritischen_elementen(self):
        veh, shift, run = world(A)
        with self.assertRaises(psycopg.Error) as cm:
            world(A, critical=False, block_kind='VEHICLE')
        self.assertEqual(cm.exception.sqlstate, '23514')
        with self.assertRaises(psycopg.Error) as cm:
            world(A, block_kind='ZUG')
        self.assertEqual(cm.exception.sqlstate, '23514')

    def test_vorgangsnummer_luckenlos_und_doppelfrei_bei_gleichzeitigen_transaktionen(self):
        numbers, errors = [], []
        def take():
            try:
                with tx(B) as c:
                    numbers.append(c.execute('SELECT taktovia.next_block_case_number()').fetchone()[0])
            except Exception as e:  # pragma: no cover
                errors.append(e)
        threads = [threading.Thread(target=take) for _ in range(12)]
        [t.start() for t in threads]; [t.join() for t in threads]
        self.assertEqual(errors, []); self.assertEqual(len(set(numbers)), 12)
        seq = sorted(int(n.rsplit('-', 1)[1]) for n in numbers); self.assertEqual(seq, list(range(seq[0], seq[0] + 12)))
        # fremder Mandant: eigener Zähler
        with tx(A) as c:
            other = c.execute('SELECT taktovia.next_block_case_number()').fetchone()[0]
        self.assertTrue(other.startswith('SPE-'))


class EingeschraenkteFreigabe(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm3-16-a')

    def test_einschraenkung_genau_bei_passed_restricted(self):
        veh, shift, run = world(A, equipment=True)
        with self.assertRaises(psycopg.Error) as cm:   # PASSED_RESTRICTED ohne Einschränkung
            with tx(A) as c:
                complete(c, A, run, 'PASSED_RESTRICTED')
        self.assertEqual(cm.exception.sqlstate, '23514')
        with self.assertRaises(psycopg.Error) as cm:   # Einschränkung ohne PASSED_RESTRICTED
            with tx(A) as c:
                complete(c, A, run, 'PASSED', '{"unavailable_dimensions":["wheelchair"],"step_codes":["RAMPE"]}')
        self.assertEqual(cm.exception.sqlstate, '23514')
        with tx(A) as c:
            answer(c, A, run, 'BREMSEN', 'OK'); answer(c, A, run, 'RAMPE', 'NOT_CHECKABLE')
            complete(c, A, run, 'PASSED_RESTRICTED', '{"unavailable_dimensions":["wheelchair"],"step_codes":["RAMPE"]}')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT restriction->'unavailable_dimensions'->>0 FROM taktovia.inspection_run WHERE id=%s", (run,)).fetchone()[0], 'wheelchair')

    def test_nicht_pruefbar_nur_fuer_ausstattungspunkte(self):
        veh, shift, run = world(A, equipment=True)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c:
                answer(c, A, run, 'BREMSEN', 'NOT_CHECKABLE')
        self.assertEqual(cm.exception.sqlstate, '23514')
        with tx(A) as c:
            answer(c, A, run, 'RAMPE', 'NOT_CHECKABLE')   # Ausstattungspunkt mit einschränkender Dimension: zulässig

    def test_dimension_nur_mit_ausstattungsbedingung(self):
        veh, shift, run = world(A)
        with self.assertRaises(psycopg.Error) as cm:
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,name,created_by) VALUES (%s,%s,%s,1,'PKW','X','V',%s)", (A, (t := uid()), uid(), USER))
                c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,restricts_dimension,created_by) VALUES (%s,%s,%s,'R',1,'R',true,false,'OK_DEFECT_NA','wheelchair',%s)", (A, uid(), t, USER))
        self.assertEqual(cm.exception.sqlstate, '23514')

    def test_widerrufsgrund_superseded_ist_zulaessig(self):
        """Der Grund SUPERSEDED (Freigabe durch jüngere Prüfung der Schicht ersetzt) steht in der Prüfregel der Spalte; die Verwendung im Dienst prüft ReadinessGateTest/AccessibilityReadinessTest."""
        with tx(A) as c:
            definition = c.execute("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'resource_release_revoke_reason_check'").fetchone()[0]
        self.assertIn('SUPERSEDED', definition); self.assertIn('BLOCK_SET', definition)


if __name__ == '__main__':
    unittest.main()
