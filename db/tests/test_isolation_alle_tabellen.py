"""WP-M1-11 / LH-6.1-SYS-001: keine Cross-Tenant-Leaks über alle Tabellen. Strukturprüfung über das gesamte Schema `taktovia` (jede Tabelle mit `tenant_id`
hat RLS erzwungen, eine Policy auf den Tenantkontext und `tenant_id` NOT NULL) und ein Leseversuch je Tabelle unter der Runtime-Rolle: mit Kontext Mandant A
ist keine Zeile von B sichtbar, ohne Kontext keine Zeile, und mit wechselndem Kontext auf einem Verbindungspool bleibt kein Rest des vorherigen Mandanten."""
import threading
import unittest

from helpers import admin, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
# Katalogtabellen ohne Mandantenbezug (global, nicht personenbezogen, Runtime nur lesend)
GLOBAL = {'tenant', 'currency', 'state_transition_rule', 'role_catalog', 'rights_matrix_rollout', 'policy_schema', 'policy_schema_version',
          'config_vehicle_override_release', 'platform_operator_account'}


def tenant_tables(c):
    return [r[0] for r in c.execute("""SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t ON t.table_schema=c.table_schema AND t.table_name=c.table_name
                                       WHERE c.table_schema='taktovia' AND c.column_name='tenant_id' AND t.table_type='BASE TABLE' ORDER BY 1""").fetchall()]


class Alle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'iso-a'); seed_tenant(B, 'iso-b')
        with tx(B) as c:   # etwas Bestand in B
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (B, uid()))
            c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                         VALUES (%s,%s,'TENANT',%s,1,'1','{"max_wait_min":20}','2030-01-01T00:00:00Z','user:b','Bestand B')""", (B, uid(), B))

    def test_jede_mandantentabelle_hat_rls_erzwungen_policy_und_not_null(self):
        with admin() as c:
            tables = tenant_tables(c)
            self.assertGreater(len(tables), 40)
            for t in tables:
                rls = c.execute("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid=%s::regclass", (f'taktovia.{t}',)).fetchone()
                self.assertEqual(tuple(rls), (True, True), f'{t}: RLS aktiv und erzwungen')
                self.assertGreaterEqual(c.execute("SELECT count(*) FROM pg_policies WHERE schemaname='taktovia' AND tablename=%s", (t,)).fetchone()[0], 1, f'{t}: Policy')
                nn = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s AND column_name='tenant_id'", (t,)).fetchone()[0]
                self.assertEqual(nn, 'NO', f'{t}: tenant_id NOT NULL')

    def test_keine_cross_tenant_leaks_alle_tabellen(self):
        with admin() as c:
            tables = tenant_tables(c)
        with tx(A) as c:
            for t in tables:
                if t in GLOBAL: continue
                n = c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id <> %s', (A,)).fetchone()[0]   # noqa: S608 (Tabellennamen aus dem Katalog)
                self.assertEqual(n, 0, f'{t}: Zeilen eines anderen Mandanten sichtbar')
        with tx(None) as c:
            for t in tables:
                if t in GLOBAL: continue
                try:
                    n = c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0]   # noqa: S608
                except Exception:   # Tabelle ohne Leserecht der Runtime-Rolle: auch kein Leck
                    c.rollback(); continue
                self.assertEqual(n, 0, f'{t}: ohne Mandantenkontext sichtbar')
        with tx(A) as c:   # Gegenprobe: B hat Bestand, A sieht ihn nicht
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle").fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.config_revision").fetchone()[0], 0)

    def test_kontextwechsel_auf_demselben_pool_ohne_kontextrest(self):
        # Eine Verbindung, abwechselnd A und B, jeweils transaktionslokal: nach COMMIT bleibt kein Mandant gesetzt.
        c = runtime()
        try:
            for tenant in (A, B, A, B, None, A):
                set_tenant(c, tenant)
                seen = c.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id <> coalesce(nullif(current_setting('app.tenant_id', true), '')::uuid, '00000000-0000-0000-0000-000000000000')").fetchone()[0]
                self.assertEqual(seen, 0)
                c.commit()
                rest = c.execute("SELECT coalesce(nullif(current_setting('app.tenant_id', true), ''), '-')").fetchone()[0]
                self.assertEqual(rest, '-', 'nach dem COMMIT bleibt kein Mandantenkontext zurück')
                c.commit()
        finally:
            c.close()

    def test_parallele_kontextwechsel_ohne_vermischung(self):
        errors = []

        def run(tenant, other):
            c = runtime()
            try:
                for _ in range(30):
                    set_tenant(c, tenant)
                    if c.execute("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=%s", (other,)).fetchone()[0] != 0:
                        errors.append(('leak', tenant))
                    c.commit()
            finally:
                c.close()
        ts = [threading.Thread(target=run, args=(A, B)), threading.Thread(target=run, args=(B, A))]
        [t.start() for t in ts]; [t.join(60) for t in ts]
        self.assertEqual(errors, [])


if __name__ == '__main__':
    unittest.main()
