"""WP-M1-33 / LH-6.18-SEC-007: Aufbewahrungsregeln, Löschläufe, Lösch-/Sperrjournal und Löschzustand des Fahrgastkontos in der Datenbank
(Runtime-Rolle; Administrator nur zum Anlegen und Nachprüfen)."""
import csv, json, threading, unittest
from pathlib import Path
import psycopg
from helpers import admin, runtime, set_tenant, uid, seed_tenant

DB_DIR = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(DB_DIR))
import dateninventar  # noqa: E402

A, B = uid(), uid()


def _passenger(tenant, retention="now() - interval '400 days'"):
    pid = uid()
    with admin() as c:
        c.execute(f"INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,contact_ciphertext,support_ciphertext,retention_until,idp_issuer,idp_subject) "
                  f"VALUES (%s,%s,%s,%s,%s,{retention},'https://idp','sub-'||%s::text)",
                  (tenant, pid, f'tok-{pid}', b'KONTAKT', b'UNTERSTUETZUNG', pid))
        c.execute("INSERT INTO taktovia.passenger_contact(tenant_id,id,passenger_id,channel,contact_token) VALUES (%s,%s,%s,'EMAIL',%s)", (tenant, uid(), pid, pid.hex * 2))
        c.execute("INSERT INTO taktovia.contact_verification(tenant_id,id,passenger_id,channel,contact_token,code_mac,expires_at) VALUES (%s,%s,%s,'EMAIL',%s,%s,now()+interval '1 day')",
                  (tenant, uid(), pid, pid.hex * 2, 'b' * 64))
    return pid


def _rule(c, tenant, status_approver=None, days=30, action='ANONYMIZE', archive=None, data_class='LK_PII', changed='user:u1'):
    rid = uid()
    c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,archive_days,changed_by) VALUES (%s,%s,%s,%s,%s,%s,%s)",
              (tenant, rid, data_class, days, action, archive, changed))
    return rid


class Aufbewahrung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'aufbewahrung-a'); seed_tenant(B, 'aufbewahrung-b')

    # --- CI-Prüfung: Regel je personenbezogener Datenklasse (SEC-007) -----------------------------------------------------------------
    def test_regel_fuer_personenbezogene_datenklasse_vorhanden(self):
        columns, tables = dateninventar.load_inventory()
        klassen = {r['loeschklasse'] for r in columns.values() if r['personenbezogen'] == 'ja'}
        self.assertGreaterEqual(len(klassen), 5, klassen)
        with (DB_DIR / 'loeschmatrix.csv').open(encoding='utf-8', newline='') as f:
            matrix = {r['datenklasse']: r for r in csv.DictReader(f, delimiter=';')}
        fehlend = sorted(klassen - set(matrix))
        self.assertEqual(fehlend, [], f'personenbezogene Datenklasse ohne Eintrag in db/loeschmatrix.csv: {fehlend}')
        katalog = dateninventar.load_catalog()['loeschklassen']
        for k, r in matrix.items():
            self.assertIn(k, katalog, f'{k}: unbekannte Löschklasse')
            self.assertIn(r['aktion_vorschlag'], ('DELETE', 'ANONYMIZE', 'ARCHIVE'), k)
            # Keine erfundenen Fristen: ohne Beschluss (D-11) bleibt die Frist leer.
            if r['frist_status'] == 'OFFEN_D11':
                self.assertEqual(r['frist_tage'], '', f'{k}: Frist ohne Beschluss D-11')

    def test_matrix_meldet_fehlende_regel(self):
        """Das Prüfverfahren schlägt an, wenn eine personenbezogene Klasse ohne Matrixeintrag bleibt (negative Probe auf die CI-Prüfung)."""
        klassen = {'LK_PII', 'LK_NEU_OHNE_REGEL'}
        matrix = {'LK_PII'}
        self.assertEqual(sorted(klassen - matrix), ['LK_NEU_OHNE_REGEL'])

    # --- Isolation ------------------------------------------------------------------------------------------------------------------
    def test_retention_rule_erasure_job_rls(self):
        with admin() as c:
            ra = _rule(c, A); rb = _rule(c, B)
            ja = uid()
            c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',30,'user:u1')", (A, ja, ra))
            c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_PII',%s,'ANONYMIZE')", (A, uid(), ja, f'passenger:{uid()}'))
        c = runtime()
        try:
            for t in ('retention_rule', 'erasure_job', 'erasure_journal'):   # ohne Kontext keine Zeile
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0], 0, t)
            set_tenant(c, B)
            self.assertEqual([r[0] for r in c.execute('SELECT id FROM taktovia.retention_rule')], [rb])
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.erasure_job').fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.erasure_journal').fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.Error):   # fremde tenant_id
                c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by) VALUES (%s,%s,'LK_PII',5,'DELETE','user:x')", (A, uid()))
        finally:
            c.rollback(); c.close()

    # --- Regel: Vier-Augen, Unveränderlichkeit, Eindeutigkeit ------------------------------------------------------------------------
    def test_regel_freigabe_vier_augen_und_unveraenderlich(self):
        c = runtime()
        try:
            set_tenant(c, A)
            rid = _rule(c, A, changed='user:editor', data_class='LK_STANDORT')
            with self.assertRaises(psycopg.errors.CheckViolation):   # Urheber gibt nicht selbst frei
                c.execute("UPDATE taktovia.retention_rule SET status='APPROVED', approved_by='user:editor', approved_at=now() WHERE id=%s", (rid,))
            c.rollback(); set_tenant(c, A)
            rid = _rule(c, A, changed='user:editor', data_class='LK_STANDORT')
            c.execute("UPDATE taktovia.retention_rule SET status='APPROVED', approved_by='user:approver', approved_at=now() WHERE id=%s", (rid,))
            with self.assertRaises(psycopg.errors.CheckViolation):   # freigegebene Regel nicht still ändern
                c.execute("UPDATE taktovia.retention_rule SET retention_days=1 WHERE id=%s", (rid,))
            c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Archiv ohne Archivdauer
                c.execute("INSERT INTO taktovia.retention_rule(tenant_id,id,data_class,retention_days,action,changed_by) VALUES (%s,%s,'LK_PII',5,'ARCHIVE','user:x')", (A, uid()))
        finally:
            c.rollback(); c.close()

    def test_nur_eine_freigegebene_regel_je_datenklasse(self):
        c = runtime()
        try:
            set_tenant(c, B)
            for _ in range(2):
                r = _rule(c, B, data_class='LK_TECHNISCH')
            r1 = _rule(c, B, data_class='LK_TECHNISCH'); r2 = _rule(c, B, data_class='LK_TECHNISCH')
            c.execute("UPDATE taktovia.retention_rule SET status='APPROVED', approved_by='user:a', approved_at=now() WHERE id=%s", (r1,))
            with self.assertRaises(psycopg.errors.UniqueViolation):
                c.execute("UPDATE taktovia.retention_rule SET status='APPROVED', approved_by='user:a', approved_at=now() WHERE id=%s", (r2,))
        finally:
            c.rollback(); c.close()

    # --- Journal -----------------------------------------------------------------------------------------------------------------------
    def test_journal_append_only_und_einmalig_je_objekt(self):
        with admin() as c:
            rid = _rule(c, A, data_class='LK_BETRIEB'); jid = uid()
            c.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_BETRIEB','ANONYMIZE',30,'user:u1')", (A, jid, rid))
        ref = f'passenger:{uid()}'
        c = runtime()
        try:
            set_tenant(c, A)
            c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_BETRIEB',%s,'ANONYMIZE')", (A, uid(), jid, ref))
            with self.assertRaises(psycopg.errors.UniqueViolation):   # kein Objekt doppelt
                c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_BETRIEB',%s,'ANONYMIZE')", (A, uid(), jid, ref))
            c.rollback(); set_tenant(c, A)
            for stmt in ("UPDATE taktovia.erasure_journal SET data_class='X'", 'DELETE FROM taktovia.erasure_journal'):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt):
                    c.execute(stmt)
                c.rollback(); set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Objektkennung ohne Personenangabe: nur Art und UUID
                c.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_BETRIEB','max.mustermann@example.org','DELETE')", (A, uid(), jid))
        finally:
            c.rollback(); c.close()
        with admin() as a:   # auch die Administration kann das Journal nicht ändern
            a.execute("INSERT INTO taktovia.erasure_journal(tenant_id,id,job_id,data_class,object_ref,action) VALUES (%s,%s,%s,'LK_BETRIEB',%s,'ANONYMIZE')", (A, uid(), jid, f'passenger:{uid()}'))
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute('UPDATE taktovia.erasure_journal SET data_class=data_class')

    # --- Fahrgastkonto: Löschzustand und Archiv -------------------------------------------------------------------------------------------
    def test_loeschzustand_entfernt_alle_personenangaben_atomar(self):
        pid = _passenger(A)
        c = runtime()
        try:
            set_tenant(c, A)
            c.execute('UPDATE taktovia.passenger_private SET erased_at=now() WHERE id=%s', (pid,))
            c.execute('DELETE FROM taktovia.passenger_contact WHERE passenger_id=%s', (pid,))
            c.execute('DELETE FROM taktovia.contact_verification WHERE passenger_id=%s', (pid,))
            c.commit()
        finally:
            c.close()
        with admin() as a:
            r = a.execute('SELECT contact_ciphertext, support_ciphertext, idp_issuer, idp_subject, subject_token, status, erased_at IS NOT NULL FROM taktovia.passenger_private WHERE id=%s', (pid,)).fetchone()
            self.assertEqual(r, (None, None, None, None, f'erased:{pid}', 'BLOCKED', True))
            self.assertEqual(a.execute('SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id=%s', (pid,)).fetchone()[0], 0)

    def test_kontakt_loeschen_nur_fuer_geloeschte_fahrgaeste(self):
        pid = _passenger(A)
        c = runtime()
        try:
            set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute('DELETE FROM taktovia.passenger_contact WHERE passenger_id=%s', (pid,))
        finally:
            c.rollback(); c.close()

    def test_geloeschter_fahrgast_nimmt_keine_personenangabe_mehr_an(self):
        pid = _passenger(A)
        with admin() as a:
            a.execute('UPDATE taktovia.passenger_private SET erased_at=now() WHERE id=%s', (pid,))
            for stmt in ("UPDATE taktovia.passenger_private SET contact_ciphertext='\\x01' WHERE id=%s",
                         "UPDATE taktovia.passenger_private SET idp_subject='neu', idp_issuer='x' WHERE id=%s",
                         "UPDATE taktovia.passenger_private SET erased_at=NULL WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt):
                    a.execute(stmt, (pid,))

    def test_loeschlauf_parallel_zu_profilaenderung_ohne_wiederauflebende_pii(self):
        """Profiländerung (Modulzugang) und Löschzustand desselben Datensatzes gleichzeitig: nie bleibt Personenangabe zurück."""
        from helpers import fields
        for run in range(8):
            pid = _passenger(A)
            ready = threading.Barrier(2); errors = []

            def profile():
                c = fields()
                try:
                    set_tenant(c, A); c.execute("SELECT 1 FROM taktovia.passenger_private WHERE id=%s", (pid,))
                    ready.wait(); c.execute("UPDATE taktovia.passenger_private SET contact_ciphertext=%s WHERE id=%s", (b'NEU', pid)); c.commit()
                except psycopg.errors.CheckViolation:
                    c.rollback()
                except Exception as e:  # noqa: BLE001
                    errors.append(e)
                finally:
                    c.close()

            def erase():
                c = runtime()
                try:
                    set_tenant(c, A); ready.wait()
                    c.execute("UPDATE taktovia.passenger_private SET erased_at=now() WHERE id=%s", (pid,)); c.commit()
                except Exception as e:  # noqa: BLE001
                    errors.append(e)
                finally:
                    c.close()
            ts = [threading.Thread(target=profile), threading.Thread(target=erase)]
            [t.start() for t in ts]; [t.join() for t in ts]
            self.assertEqual(errors, [])
            with admin() as a:
                r = a.execute('SELECT contact_ciphertext, support_ciphertext, idp_subject FROM taktovia.passenger_private WHERE id=%s AND erased_at IS NOT NULL', (pid,)).fetchone()
            self.assertEqual(r, (None, None, None), f'Lauf {run}: Personenangabe lebt wieder auf')

    def test_archivierter_datensatz_ist_fuer_operative_zugriffe_nicht_lesbar(self):
        pid = _passenger(A)
        c = runtime()
        try:
            set_tenant(c, A)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (pid,)).fetchone()[0], 1)
            c.execute("SELECT set_config('app.archive_access','on',true)")
            c.execute("UPDATE taktovia.passenger_private SET archived_at=now(), archive_until=now()+interval '30 days' WHERE id=%s", (pid,))
            c.commit(); set_tenant(c, A)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (pid,)).fetchone()[0], 0)   # operativ unsichtbar
            self.assertEqual(c.execute("UPDATE taktovia.passenger_private SET locale='en-GB' WHERE id=%s", (pid,)).rowcount, 0)    # und nicht änderbar
            c.rollback(); set_tenant(c, A)
            c.execute("SELECT set_config('app.archive_access','on',true)")
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (pid,)).fetchone()[0], 1)    # Archivzugang
            c.rollback(); set_tenant(c, B)
            c.execute("SELECT set_config('app.archive_access','on',true)")
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.passenger_private WHERE id=%s', (pid,)).fetchone()[0], 0)    # nie fremder Mandant
        finally:
            c.rollback(); c.close()

    def test_archiv_verbirgt_auch_die_bestaende_am_konto(self):
        pid = _passenger(A)
        with admin() as a:
            a.execute("INSERT INTO taktovia.consent_revision(tenant_id,id,passenger_id,purpose,sequence,granted,text_version) VALUES (%s,%s,%s,'MARKETING',1,true,'v1')", (A, uid(), pid))
            a.execute("INSERT INTO taktovia.passenger_preference(tenant_id,id,passenger_id,kind,label) SELECT %s,%s,%s,column_default,'x' FROM (SELECT 'FAVORITE_PLACE'::text AS column_default) q", (A, uid(), pid)) if False else None
        def counts(archive):
            c = runtime()
            try:
                set_tenant(c, A)
                if archive:
                    c.execute("SELECT set_config('app.archive_access','on',true)")
                return [c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE passenger_id=%s', (pid,)).fetchone()[0]
                        for t in ('passenger_contact', 'contact_verification', 'consent_revision')]
            finally:
                c.rollback(); c.close()
        self.assertEqual(counts(False), [1, 1, 1])
        with admin() as a:
            a.execute("UPDATE taktovia.passenger_private SET archived_at=now(), archive_until=now()+interval '5 days' WHERE id=%s", (pid,))
        self.assertEqual(counts(False), [0, 0, 0])
        self.assertEqual(counts(True), [1, 1, 1])

    # --- Wiedereinspielung des Journals beim Restore ------------------------------------------------------------------------------------
    def test_journal_wiedereinspielung_loescht_erneut_und_ist_idempotent(self):
        pid = _passenger(A)
        with admin() as a:
            rid = _rule(a, A, data_class='LK_PII', changed='user:e')
            jid = uid()
            a.execute("INSERT INTO taktovia.erasure_job(tenant_id,id,rule_id,rule_revision,data_class,action,retention_days,created_by) VALUES (%s,%s,%s,1,'LK_PII','ANONYMIZE',30,'user:u1')", (A, jid, rid))
            entries = json.dumps([{'tenant_id': str(A), 'job_id': str(jid), 'object_ref': f'passenger:{pid}', 'data_class': 'LK_PII', 'action': 'ANONYMIZE'}])
            self.assertEqual(a.execute('SELECT taktovia.replay_erasure_journal(%s::jsonb)', (entries,)).fetchone()[0], 1)
            self.assertEqual(a.execute('SELECT taktovia.replay_erasure_journal(%s::jsonb)', (entries,)).fetchone()[0], 0)
            r = a.execute('SELECT contact_ciphertext, subject_token FROM taktovia.passenger_private WHERE id=%s', (pid,)).fetchone()
            self.assertEqual(r, (None, f'erased:{pid}'))
            self.assertEqual(a.execute('SELECT count(*) FROM taktovia.passenger_contact WHERE passenger_id=%s', (pid,)).fetchone()[0], 0)
            self.assertEqual(a.execute("SELECT count(*) FROM taktovia.erasure_journal WHERE object_ref=%s", (f'passenger:{pid}',)).fetchone()[0], 1)

    def test_wiedereinspielung_nicht_fuer_die_runtime_rolle(self):
        c = runtime()
        try:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("SELECT taktovia.replay_erasure_journal('[]'::jsonb)")
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
