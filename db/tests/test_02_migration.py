"""WP-M0-16: Migration, Struktur, Prüfsummen, Abgleich mit dem Entwurfs-DDL."""
import re, subprocess, sys, unittest
from pathlib import Path
import psycopg
from helpers import admin, migrator

ROOT = Path(__file__).resolve().parents[2]
V1 = ROOT / 'db/migrations/V1__kernschema.sql'
DRAFT = ROOT / 'docs/pflichtenheft/schema/kernschema.sql'
EXPECTED = ('tenant currency legal_entity organization membership config_revision service service_area stop_revision '
            'partner_contract_version cooperation_grant passenger_private travel_attempt fare_quote journey journey_leg '
            'operation saga_step vehicle staff_shift resource_release vehicle_run capacity_hold booking payment_intent '
            'expense_document cost_object economic_event allocation_set allocation_line fiscal_period journal journal_line '
            'settlement_line idempotency_record outbox inbox audit_event').split()
# Bewusst globale Tabellen ohne Mandantenbezug und ohne RLS (nur per Migration gepflegt, Runtime nur lesend)
GLOBAL = {'currency', 'state_transition_rule', 'role_catalog', 'rights_matrix_rollout', 'policy_schema', 'policy_schema_version', 'config_vehicle_override_release', 'rejection_reason', 'booking_status_reason'}


class Migration(unittest.TestCase):
    def test_v1_entspricht_entwurf(self):
        """V1 ist unverändert der Entwurf; Härtungen stehen in V2+. Einzige Abweichungen: BEGIN/COMMIT (Migrationswerkzeug öffnet die Transaktion) und CREATE EXTENSION (Administrator)."""
        draft = DRAFT.read_text().splitlines()
        v1 = V1.read_text().splitlines()
        removed = [l for l in draft if l not in v1]
        self.assertEqual(sorted(removed), sorted(['BEGIN;', 'CREATE EXTENSION IF NOT EXISTS postgis;', 'COMMIT;']))
        self.assertEqual([l for l in draft if l in v1], v1)

    def test_genau_38_tabellen(self):
        """Die 38 Kerntabellen bleiben unverändert; weitere Tabellen sind nur zulässig, wenn ein Arbeitspaket sie in db/table_owners.d einträgt."""
        declared = {l.split(';')[0] for p in (ROOT / 'db/table_owners.d').glob('*.csv') for l in p.read_text().splitlines()[1:] if l.strip()}
        with admin() as c:
            tabs = [r[0] for r in c.execute("SELECT tablename FROM pg_tables WHERE schemaname='taktovia'")]
        self.assertEqual(sorted(t for t in tabs if t in EXPECTED), sorted(EXPECTED))
        with admin() as c:
            meta = {r[0] for r in c.execute("SELECT tablename FROM pg_tables WHERE schemaname='taktovia_meta'")}
        self.assertEqual(sorted(t for t in tabs if t not in EXPECTED), sorted(declared - set(EXPECTED) - meta))
        self.assertGreaterEqual(len(tabs), 38)

    def test_rls_erzwungen(self):
        with admin() as c:
            rows = c.execute("""SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                                  EXISTS(SELECT 1 FROM information_schema.columns k WHERE k.table_schema='taktovia'
                                         AND k.table_name=c.relname AND k.column_name='tenant_id')
                                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                                WHERE n.nspname='taktovia' AND c.relkind='r'""").fetchall()
            with_tid = [r for r in rows if r[3] and r[0] in EXPECTED]
            self.assertEqual(len(with_tid), 36)
            for name, rls, force, _ in rows:
                if name in GLOBAL:
                    self.assertFalse(rls)  # bewusst global
                else:
                    self.assertTrue(rls and force, name)
            # Mehrere Policies je Tabelle sind zulässig (z. B. draft_visibility): je Tabelle zählt, ob tenant_isolation vorhanden ist, nicht welche Policy zuletzt gelesen wird
            pairs = c.execute("SELECT tablename, policyname FROM pg_policies WHERE schemaname='taktovia'").fetchall()
            self.assertEqual(len({k for k, v in pairs if v == 'tenant_isolation' and k in EXPECTED}), 36)
            self.assertEqual({v for k, v in pairs if k == 'tenant'}, {'own_tenant'})

    def test_funktionen_trigger_indizes_geometrie(self):
        with admin() as c:
            fn = {r[0] for r in c.execute("""SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                                             WHERE n.nspname='taktovia'""")}
            self.assertTrue({'current_tenant', 'reject_mutation', 'protect_journal_line', 'guard_journal', 'guard_fiscal_period', 'reject_tenant_change'} <= fn)
            trg = {r[0] for r in c.execute("""SELECT tgname FROM pg_trigger t JOIN pg_class c ON c.oid=tgrelid
                                              JOIN pg_namespace n ON n.oid=c.relnamespace
                                              WHERE n.nspname='taktovia' AND NOT tgisinternal""")}
            self.assertTrue({'economic_immutable', 'audit_immutable', 'contract_immutable', 'quote_immutable',
                             'journal_line_guard', 'journal_guard', 'fiscal_period_guard', 'tenant_id_immutable'} <= trg)
            cfg = dict(c.execute("""SELECT proname, proconfig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                                    WHERE n.nspname='taktovia' AND proname<>'current_tenant'""").fetchall())
            for name, conf in cfg.items():
                self.assertEqual(conf, ['search_path=taktovia, pg_catalog'], name)
            idx = {r[0] for r in c.execute("SELECT indexname FROM pg_indexes WHERE schemaname='taktovia'")}
            self.assertTrue({'service_area_geo', 'stop_geo', 'expense_hash', 'outbox_unpublished'} <= idx)
            geo = dict(((r[0], r[1]), r[2]) for r in c.execute(
                "SELECT f_table_name, f_geometry_column, type || ':' || srid FROM public.geometry_columns WHERE f_table_schema='taktovia'"))
            self.assertEqual(geo[('service_area', 'area')], 'MULTIPOLYGON:4326')
            self.assertEqual(geo[('stop_revision', 'point')], 'POINT:4326')

    def test_geld_ohne_gleitkomma(self):
        """Leitlinie 5: alle *_minor-Spalten sind bigint; im Schema gibt es keinen float/real/double-Typ."""
        with admin() as c:
            minor = c.execute("""SELECT table_name, column_name, data_type FROM information_schema.columns
                                  WHERE table_schema='taktovia' AND column_name LIKE '%\\_minor' ESCAPE '\\'""").fetchall()
            self.assertGreaterEqual(len(minor), 10)
            self.assertEqual([r for r in minor if r[2] != 'bigint'], [])
            fl = c.execute("""SELECT table_name, column_name FROM information_schema.columns
                               WHERE table_schema='taktovia' AND data_type IN ('real','double precision')""").fetchall()
            self.assertEqual(fl, [])

    def test_eigentuemer_ist_migrator(self):
        with admin() as c:
            owners = {r[0] for r in c.execute("""SELECT o.rolname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                                                 JOIN pg_roles o ON o.oid=c.relowner WHERE n.nspname='taktovia' AND c.relkind='r'""")}
            self.assertEqual(owners, {'taktovia_migrator'})

    def test_zweiter_lauf_ist_noop(self):
        out = subprocess.run([sys.executable, str(ROOT / 'db/db.py'), 'migrate'], capture_output=True, text=True, check=True).stdout
        self.assertIn('nichts zu tun', out)

    def test_geaenderte_migration_wird_abgewiesen(self):
        with migrator() as c:
            old = c.execute("SELECT checksum FROM taktovia_meta.schema_history WHERE script='V1__kernschema.sql'").fetchone()[0]
            c.execute("UPDATE taktovia_meta.schema_history SET checksum=checksum+1 WHERE script='V1__kernschema.sql'")
        try:
            r = subprocess.run([sys.executable, str(ROOT / 'db/db.py'), 'migrate'], capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('Prüfsummenfehler', r.stderr + r.stdout)
        finally:
            with migrator() as c:
                c.execute("UPDATE taktovia_meta.schema_history SET checksum=%s WHERE script='V1__kernschema.sql'", (old,))

    def test_migration_unter_runtime_schlaegt_fehl(self):
        from helpers import runtime
        c = runtime()
        try:
            with self.assertRaises(psycopg.errors.Error):
                c.execute('CREATE SCHEMA taktovia2')
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
