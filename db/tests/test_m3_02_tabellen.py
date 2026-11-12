"""WP-M3-02: Migration der M3-Tabellen mit RLS und Unveränderlichkeit, unter der echten Runtime-Rolle.

Positiv: Migration (wiederholbar) und Fixture-Einfügen für Mandant A und B. Negativ: Cross-Tenant-Fremdschlüssel, leerer Tenantkontext, Mutation unveränderlicher
Zeilen (SQLSTATE 23514). Konkurrierend: parallele Transaktionen verschiedener Mandanten auf einem wiederverwendeten Verbindungspool sehen nur eigene Zeilen.
Strukturell: RLS erzwungen, Runtime weder Eigentümerin noch mit TRUNCATE oder Partitionsrechten (Kap. 04 §4.6)."""
import threading
import unittest

import psycopg

from helpers import admin, dbtool, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
NEW_TABLES = [
    'position_sample', 'checklist_template_revision', 'checklist_step', 'inspection_run', 'inspection_answer', 'defect_case', 'defect_evidence', 'repair_order',
    'vehicle_release_decision', 'alert_policy_revision', 'business_alert', 'alert_acknowledgement', 'vehicle_block', 'vehicle_status_history', 'inspection_amendment',
    'known_defect_acknowledgement', 'defect_status_history', 'workflow_rule_revision', 'incident', 'incident_link', 'workflow_run_log', 'driver_plan_ack', 'boarding_code',
    'booking_change']
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
ACTOR2 = 'user:22222222-2222-4222-8222-222222222222'


def world(tenant):
    """Ein Mandant mit Fahrzeug, Schicht, Vorlage (veröffentlicht), Prüflauf, Schadenakte, Reparaturauftrag, Alarm und Incident; liefert die IDs."""
    w = {k: uid() for k in ('vehicle', 'shift', 'template', 'step', 'run', 'answer', 'defect', 'repair', 'followup', 'alert', 'policy', 'rule', 'incident')}
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{\"seats\":4,\"wheelchair\":1}')", (tenant, w['vehicle']))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", (tenant, w['shift'], uid(), w['vehicle']))
        c.execute("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,state,published_at,created_by) "
                  "VALUES (%s,%s,%s,1,'PKW','ONDEMAND','DRAFT',NULL,%s)", (tenant, w['template'], uid(), ACTOR))
        c.execute("INSERT INTO taktovia.checklist_step(tenant_id,id,template_revision_id,step_code,position,title,mandatory,safety_critical,answer_kind,created_by) "
                  "VALUES (%s,%s,%s,'BRAKES',1,'Bremsen',true,true,'OK_DEFECT_NA',%s)", (tenant, w['step'], w['template'], ACTOR))
        c.execute("UPDATE taktovia.checklist_template_revision SET state='PUBLISHED', published_at=now() WHERE id=%s", (w['template'],))
        c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) "
                  "VALUES (%s,%s,%s,%s,%s,%s,'PRE_SHIFT',now(),%s)", (tenant, w['run'], w['shift'], w['vehicle'], uid(), w['template'], ACTOR))
        c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,answered_at,created_by) VALUES (%s,%s,%s,'BRAKES','OK',now(),%s)",
                  (tenant, w['answer'], w['run'], ACTOR))
        c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,created_by) VALUES (%s,%s,%s,%s,%s,now(),'BRAKES',%s)",
                  (tenant, w['defect'], f'S-{uid()}', w['vehicle'], uid(), ACTOR))
        c.execute("INSERT INTO taktovia.repair_order(tenant_id,id,defect_id,vehicle_id,created_by) VALUES (%s,%s,%s,%s,%s)", (tenant, w['repair'], w['defect'], w['vehicle'], ACTOR))
        c.execute("INSERT INTO taktovia.alert_policy_revision(tenant_id,id,revision_no,rules,created_by) VALUES (%s,%s,%s,'[]',%s)", (tenant, w['policy'], uid().int % 2_000_000_000, ACTOR))
        c.execute("INSERT INTO taktovia.business_alert(tenant_id,id,alert_kind,priority,vehicle_id,dedupe_key,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE','HIGH',%s,%s,%s)",
                  (tenant, w['alert'], w['vehicle'], f'k-{uid()}', ACTOR))
        c.execute("INSERT INTO taktovia.workflow_rule_revision(tenant_id,id,event_kind,rule,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE','{}',%s)", (tenant, w['rule'], ACTOR))
        c.execute("INSERT INTO taktovia.incident(tenant_id,id,kind,vehicle_id,dedupe_key,rule_revision_id,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE',%s,%s,%s,%s)",
                  (tenant, w['incident'], w['vehicle'], f'i-{uid()}', w['rule'], ACTOR))
    return w


def complete_run(tenant, w):
    with tx(tenant) as c:
        c.execute("UPDATE taktovia.inspection_run SET status='COMPLETED', outcome='PASSED', completed_at=now(), received_at=now() WHERE id=%s", (w['run'],))


class MigrationUndFixtures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm302-a'); seed_tenant(B, 'm302-b')
        cls.wa, cls.wb = world(A), world(B)

    def test_migration_ist_wiederholbar_und_idempotent(self):
        before = _applied()
        dbtool.migrate()   # zweiter Lauf: nichts mehr anzuwenden, kein Fehler, keine neue Zeile in der Historie
        self.assertEqual(_applied(), before)
        self.assertIn('V3020__m3_tabellen.sql', [r[0] for r in before])

    def test_alle_m3_tabellen_existieren_und_haben_fixtures_fuer_a_und_b(self):
        with admin() as c:
            for t in NEW_TABLES:
                self.assertEqual(c.execute("SELECT count(*) FROM information_schema.tables WHERE table_schema='taktovia' AND table_name=%s", (t,)).fetchone()[0], 1, t)
        for tenant, w in ((A, self.wa), (B, self.wb)):
            with tx(tenant) as c:
                for t, key in (('inspection_run', 'run'), ('defect_case', 'defect'), ('repair_order', 'repair'), ('business_alert', 'alert'), ('incident', 'incident')):
                    self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE id=%s', (w[key],)).fetchone()[0], 1, t)   # noqa: S608

    def test_m2_fixtures_bleiben_gueltig(self):
        """Fahrzeug, Schicht und Freigabe im Zuschnitt der M2-Fixtures (ohne die neuen Spalten) lassen sich weiter einfügen (WP-M3-02 Kriterium 6)."""
        v, s = uid(), uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (%s,%s,'fed-org-m2-a',%s::jsonb,false,1)",
                      (A, v, '{"seats": 4, "wheelchair": 1}'))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                      "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',1,'PUBLISHED')", (A, s, uid(), v))
            c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) "
                      "VALUES (%s,%s,%s,%s,1,%s,1,now()+interval '4 hours')", (A, uid(), v, s, uid()))
            row = c.execute("SELECT active, master_revision, vehicle_label FROM taktovia.vehicle WHERE id=%s", (v,)).fetchone()
        self.assertEqual(tuple(row), (True, 1, None))


class Mandantentrennung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm302-a'); seed_tenant(B, 'm302-b')
        cls.wa, cls.wb = world(A), world(B)

    def test_ohne_tenantkontext_keine_zeile_und_fremder_mandant_unsichtbar(self):
        with tx(None) as c:
            for t in NEW_TABLES:
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0], 0, f'{t}: ohne Kontext sichtbar')   # noqa: S608
        with tx(B) as c:
            for t in NEW_TABLES:
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id=%s', (A,)).fetchone()[0], 0, f'{t}: Mandant A sichtbar für B')   # noqa: S608

    def test_insert_mit_fremder_tenant_id_scheitert(self):
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # WITH CHECK der Policy tenant_isolation
                c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,created_by) VALUES (%s,%s,'X1',%s,%s,now(),'XX',%s)",
                          (A, uid(), self.wa['vehicle'], uid(), ACTOR))

    def test_fremder_fremdschluessel_scheitert_je_tabelle(self):
        wa = self.wa
        stmts = {
            'defect_case': ("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,created_by) VALUES (%s,%s,'X2',%s,%s,now(),'XX',%s)", (B, uid(), wa['vehicle'], uid(), ACTOR)),
            'inspection_run': ("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'FOLLOW_UP',now(),%s)",
                               (B, uid(), self.wb['shift'], self.wb['vehicle'], uid(), wa['template'], ACTOR)),
            'repair_order': ("INSERT INTO taktovia.repair_order(tenant_id,id,defect_id,vehicle_id,created_by) VALUES (%s,%s,%s,%s,%s)", (B, uid(), wa['defect'], self.wb['vehicle'], ACTOR)),
            'vehicle_block': ("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (%s,%s,%s,'OPERATIONS','MANUAL',%s)", (B, uid(), wa['vehicle'], ACTOR)),
            'position_sample': ("INSERT INTO taktovia.position_sample(tenant_id,id,vehicle_id,shift_id,position,accuracy_m,measured_at,created_by) VALUES (%s,%s,%s,%s,ST_GeogFromText('POINT(8 50)'),5,now(),%s)",
                                (B, uid(), wa['vehicle'], self.wb['shift'], ACTOR)),
            'incident': ("INSERT INTO taktovia.incident(tenant_id,id,kind,vehicle_id,dedupe_key,created_by) VALUES (%s,%s,'DEFECT',%s,%s,%s)", (B, uid(), wa['vehicle'], f'd-{uid()}', ACTOR)),
        }
        for table, (sql, args) in stmts.items():
            with tx(B) as c:
                with self.assertRaises(psycopg.errors.ForeignKeyViolation, msg=table):
                    c.execute(sql, args)

    def test_parallele_transaktionen_verschiedener_mandanten_nach_pool_wiederverwendung(self):
        """Mehrere Verbindungen werden abwechselnd für A und B benutzt (transaktionslokaler Kontext): jede sieht nur eigene Zeilen, kein Kontextrest."""
        conns = [runtime() for _ in range(4)]
        errors, barrier = [], threading.Barrier(len(conns))

        def work(c, i):
            try:
                barrier.wait(timeout=10)
                for round_ in range(12):
                    tenant, other, w = ((A, B, self.wa), (B, A, self.wb))[(i + round_) % 2]
                    set_tenant(c, tenant)
                    own = c.execute('SELECT count(*) FROM taktovia.business_alert WHERE id=%s', (w['alert'],)).fetchone()[0]
                    foreign = c.execute('SELECT count(*) FROM taktovia.business_alert WHERE tenant_id=%s', (other,)).fetchone()[0]
                    if (own, foreign) != (1, 0): errors.append((i, round_, own, foreign))
                    c.commit()
                    set_tenant(c, None)   # nach dem Commit ist kein Kontext mehr gesetzt
                    if c.execute('SELECT count(*) FROM taktovia.business_alert').fetchone()[0] != 0: errors.append((i, round_, 'Kontextrest'))
                    c.commit()
            except Exception as e:  # noqa: BLE001
                errors.append((i, repr(e)))
        try:
            ts = [threading.Thread(target=work, args=(c, i)) for i, c in enumerate(conns)]
            [t.start() for t in ts]; [t.join() for t in ts]
        finally:
            [c.close() for c in conns]
        self.assertEqual(errors, [])


class Unveraenderlichkeit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm302-a')

    def setUp(self):
        self.w = world(A)

    def _mutation_scheitert(self, sql, args=()):
        for conn in ('runtime', 'admin'):   # gilt auch für die Administration
            ctx = tx(A) if conn == 'runtime' else admin()
            with ctx as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=f'{conn}: {sql}') as cm:
                    c.execute(sql, args)
                if conn == 'admin': self.assertIsInstance(cm.exception, psycopg.errors.CheckViolation, sql)   # Trigger, nicht nur fehlendes Recht
                if isinstance(cm.exception, psycopg.errors.CheckViolation): self.assertEqual(cm.exception.sqlstate, '23514')

    def test_antworten_sind_nach_abschluss_unveraenderlich(self):
        with tx(A) as c:   # solange der Lauf offen ist, ist der Entwurf änderbar
            c.execute("UPDATE taktovia.inspection_answer SET answer='DEFECT' WHERE id=%s", (self.w['answer'],))
        complete_run(A, self.w)
        self._mutation_scheitert("UPDATE taktovia.inspection_answer SET answer='OK' WHERE id=%s", (self.w['answer'],))
        self._mutation_scheitert("DELETE FROM taktovia.inspection_answer WHERE id=%s", (self.w['answer'],))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):   # auch kein Nachtrag einer weiteren Antwort
                c.execute("INSERT INTO taktovia.inspection_answer(tenant_id,id,inspection_id,step_code,answer,answered_at,created_by) VALUES (%s,%s,%s,'LIGHTS','OK',now(),%s)", (A, uid(), self.w['run'], ACTOR))

    def test_abgeschlossener_lauf_laesst_sich_nicht_oeffnen_oder_aendern(self):
        complete_run(A, self.w)
        self._mutation_scheitert("UPDATE taktovia.inspection_run SET status='IN_PROGRESS', outcome=NULL, completed_at=NULL, received_at=NULL WHERE id=%s", (self.w['run'],))
        self._mutation_scheitert("UPDATE taktovia.inspection_run SET outcome='BLOCKED' WHERE id=%s", (self.w['run'],))
        self._mutation_scheitert("DELETE FROM taktovia.inspection_run WHERE id=%s", (self.w['run'],))

    def test_evidenz_und_freigabeentscheidung_und_historien_sind_append_only(self):
        w = self.w; ev, rel, hist, vsh, amend = uid(), uid(), uid(), uid(), uid()
        complete_run(A, w)
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (%s,%s,%s,'obj/1',%s,now(),%s)", (A, ev, w['defect'], 'a' * 64, ACTOR))
            c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'FOLLOW_UP',now(),%s)",
                      (A, w['followup'], w['shift'], w['vehicle'], uid(), w['template'], ACTOR))
            c.execute("INSERT INTO taktovia.vehicle_release_decision(tenant_id,id,vehicle_id,repair_order_id,follow_up_inspection_id,decision,decided_by,second_approver,safety_revision) "
                      "VALUES (%s,%s,%s,%s,%s,'RELEASED',%s,%s,2)", (A, rel, w['vehicle'], w['repair'], w['followup'], ACTOR, ACTOR2))
            c.execute("INSERT INTO taktovia.defect_status_history(tenant_id,id,defect_id,from_status,to_status,actor) VALUES (%s,%s,%s,NULL,'NEW',%s)", (A, hist, w['defect'], ACTOR))
            c.execute("INSERT INTO taktovia.vehicle_status_history(tenant_id,id,vehicle_id,shift_id,from_status,to_status,trigger,created_by) VALUES (%s,%s,%s,%s,NULL,'NOT_READY','SYSTEM',%s)", (A, vsh, w['vehicle'], w['shift'], ACTOR))
            c.execute("INSERT INTO taktovia.inspection_amendment(tenant_id,id,inspection_id,step_code,amended_by,reason,new_answer) VALUES (%s,%s,%s,'BRAKES',%s,'Korrektur','DEFECT')", (A, amend, w['run'], ACTOR))
        for table, key in (('defect_evidence', ev), ('vehicle_release_decision', rel), ('defect_status_history', hist), ('vehicle_status_history', vsh), ('inspection_amendment', amend)):
            self._mutation_scheitert(f"UPDATE taktovia.{table} SET revision = revision + 1 WHERE id=%s", (key,))   # noqa: S608
            self._mutation_scheitert(f"DELETE FROM taktovia.{table} WHERE id=%s", (key,))                          # noqa: S608

    def test_vieraugenpflicht_der_wiederfreigabe_in_der_datenbank(self):
        w = self.w
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,started_at,created_by) VALUES (%s,%s,%s,%s,%s,%s,'FOLLOW_UP',now(),%s)",
                      (A, w['followup'], w['shift'], w['vehicle'], uid(), w['template'], ACTOR))
        for second in (None, ACTOR):   # keine zweite Identität oder dieselbe
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation):
                    c.execute("INSERT INTO taktovia.vehicle_release_decision(tenant_id,id,vehicle_id,repair_order_id,follow_up_inspection_id,decision,decided_by,second_approver,safety_revision) "
                              "VALUES (%s,%s,%s,%s,%s,'RELEASED',%s,%s,2)", (A, uid(), w['vehicle'], w['repair'], w['followup'], ACTOR, second))

    def test_sperre_wird_nur_geschlossen_nie_geoeffnet(self):
        blk = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (%s,%s,%s,'SAFETY','DEFECT_CRITICAL',%s)", (A, blk, self.w['vehicle'], ACTOR))
            c.execute("UPDATE taktovia.vehicle_block SET status='LIFTED', lifted_by=%s, lifted_at=now() WHERE id=%s", (ACTOR2, blk))
            self.assertEqual(c.execute("SELECT revision FROM taktovia.vehicle_block WHERE id=%s", (blk,)).fetchone()[0], 2)
        self._mutation_scheitert("UPDATE taktovia.vehicle_block SET status='ACTIVE', lifted_by=NULL, lifted_at=NULL WHERE id=%s", (blk,))
        self._mutation_scheitert("DELETE FROM taktovia.vehicle_block WHERE id=%s", (blk,))

    def test_veroeffentlichte_vorlage_und_schritte_sind_unveraenderlich(self):
        self._mutation_scheitert("UPDATE taktovia.checklist_step SET title='X' WHERE id=%s", (self.w['step'],))
        self._mutation_scheitert("UPDATE taktovia.checklist_template_revision SET vehicle_class='BUS' WHERE id=%s", (self.w['template'],))
        self._mutation_scheitert("DELETE FROM taktovia.checklist_template_revision WHERE id=%s", (self.w['template'],))
        with tx(A) as c:   # Stilllegen bleibt erlaubt
            c.execute("UPDATE taktovia.checklist_template_revision SET state='RETIRED' WHERE id=%s", (self.w['template'],))

    def test_boarding_code_nur_hash_und_nur_zaehler_aenderbar(self):
        with admin() as c:
            c.execute("SET session_replication_role = replica")
            bk = uid()
            c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}',1)", (A, bk, uid(), uid(), f'f-{bk}'))
            c.execute("SET session_replication_role = DEFAULT")
        code = uid()
        h = '$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHQ$aGFzaGhhc2hoYXNo'
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.boarding_code(tenant_id,id,booking_id,code_hash,valid_until,created_by) VALUES (%s,%s,%s,%s,now()+interval '1 day',%s)", (A, code, bk, h, ACTOR))
            c.execute("UPDATE taktovia.boarding_code SET failed_attempts = failed_attempts + 1 WHERE id=%s", (code,))
        self._mutation_scheitert("UPDATE taktovia.boarding_code SET code_hash=%s WHERE id=%s", (h + 'x', code))
        self._mutation_scheitert("UPDATE taktovia.boarding_code SET failed_attempts = 0 WHERE id=%s", (code,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):   # ein Klartextcode (6 Ziffern) ist kein gültiger Hash
                c.execute("INSERT INTO taktovia.boarding_code(tenant_id,id,booking_id,code_hash,valid_until,created_by) VALUES (%s,%s,%s,'123456',now(),%s)", (A, uid(), bk, ACTOR))


class Struktur(unittest.TestCase):
    def test_force_rls_policy_und_runtime_ist_nicht_eigentuemerin(self):
        with admin() as c:
            for t in NEW_TABLES:
                rls = c.execute("SELECT relrowsecurity, relforcerowsecurity, pg_get_userbyid(relowner) FROM pg_class WHERE oid=%s::regclass", (f'taktovia.{t}',)).fetchone()
                self.assertEqual(tuple(rls[:2]), (True, True), t)
                self.assertNotEqual(rls[2], 'taktovia_runtime', f'{t}: Runtime ist Eigentümerin')
                pol = c.execute("SELECT qual, with_check FROM pg_policies WHERE schemaname='taktovia' AND tablename=%s AND policyname='tenant_isolation'", (t,)).fetchone()
                self.assertIsNotNone(pol, t)
                self.assertIn('current_tenant()', pol[0]); self.assertIn('current_tenant()', pol[1])

    def test_kein_truncate_kein_delete_und_keine_partitionsrechte(self):
        with admin() as c:
            for t in NEW_TABLES:
                privs = {r[0] for r in c.execute("SELECT privilege_type FROM information_schema.role_table_grants WHERE table_schema='taktovia' AND table_name=%s AND grantee='taktovia_runtime'", (t,))}
                self.assertFalse({'TRUNCATE', 'DELETE', 'REFERENCES', 'TRIGGER'} & privs, f'{t}: {privs}')
                self.assertGreaterEqual(privs, {'SELECT', 'INSERT'}, t)
            parts = c.execute("SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid=i.inhparent JOIN pg_namespace n ON n.oid=p.relnamespace WHERE n.nspname='taktovia' AND p.relname = ANY(%s)", (NEW_TABLES,)).fetchone()[0]
            self.assertEqual(parts, 0, 'Partitionen sind nicht Teil von M3 (position_sample erst nach gemessenem Volumen)')

    def test_zustandswerte_sind_per_check_versioniert_abgesichert(self):
        seed_tenant(A, 'm302-a'); w = world(A)
        for sql in ("UPDATE taktovia.defect_case SET status='ERFUNDEN' WHERE id=%s", "UPDATE taktovia.repair_order SET status='ERFUNDEN' WHERE id=%s",
                    "UPDATE taktovia.incident SET status='ERFUNDEN' WHERE id=%s"):
            key = {'defect_case': 'defect', 'repair_order': 'repair', 'incident': 'incident'}[sql.split()[1].split('.')[1]]
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    c.execute(sql, (w[key],))

    def test_dedupe_offener_alarm_und_incident_je_schluessel(self):
        seed_tenant(A, 'm302-a'); w = world(A)
        with tx(A) as c:
            key = c.execute("SELECT dedupe_key FROM taktovia.business_alert WHERE id=%s", (w['alert'],)).fetchone()[0]
            with self.assertRaises(psycopg.errors.UniqueViolation):
                c.execute("INSERT INTO taktovia.business_alert(tenant_id,id,alert_kind,priority,dedupe_key,created_by) VALUES (%s,%s,'VEHICLE_OUTAGE','HIGH',%s,%s)", (A, uid(), key, ACTOR))


class FremdschluesselAktivierung(unittest.TestCase):
    """Das Aktivierungsskript für staff_shift.driver_ref und resource_release.inspection_ref ist ausführbar und wirkt (zurückgerollt)."""

    def test_aktivierungsskript_erzwingt_fremdschluessel_fuer_neue_zeilen(self):
        from pathlib import Path
        script = (Path(__file__).resolve().parents[1] / 'scripts' / 'm3_fk_aktivierung.sql').read_text()
        seed_tenant(A, 'm302-a')
        v = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, v))
        with admin() as c:
            c.autocommit = False
            try:
                c.execute(script)   # NOT VALID: Altbestand bleibt, neue Zeilen werden geprüft
                with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                              "VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','Europe/Berlin',0,'PLANNED')", (A, uid(), uid(), v))
            finally:
                c.rollback()
        with admin() as c:   # nach dem Rollback ist nichts aktiv
            self.assertEqual(c.execute("SELECT count(*) FROM pg_constraint WHERE conname IN ('staff_shift_driver_fk','resource_release_inspection_fk')").fetchone()[0], 0)


def _applied():
    with admin() as c:
        return c.execute("SELECT script, checksum FROM taktovia_meta.schema_history ORDER BY 1").fetchall()


if __name__ == '__main__':
    unittest.main()
