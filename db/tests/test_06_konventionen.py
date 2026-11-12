"""WP-M1-01: DDL-Konvention (Kap. 04 §4.1) und Mandantenisolation über alle Tabellen und die vier Schlüsseltabellen der Abnahme (MUL-001)."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
NO_TENANT_FK = {'tenant', 'currency', 'policy_schema_version', 'booking_status_reason'}   # globale Ziele, auf die ohne tenant_id verwiesen werden darf


class Konvention(unittest.TestCase):
    def test_pk_beginnt_mit_tenant_id_und_tenant_id_ist_not_null(self):  # MUL-001 AK 2
        with admin() as c:
            tabs = [r[0] for r in c.execute("""SELECT table_name FROM information_schema.columns WHERE table_schema='taktovia' AND column_name='tenant_id'""")]
            self.assertGreaterEqual(len(tabs), 36)   # 36 Kerntabellen; weitere Arbeitspakete dürfen Tabellen mit gleicher Konvention ergänzen
            for t in tabs:
                nullable = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s AND column_name='tenant_id'", (t,)).fetchone()[0]
                self.assertEqual(nullable, 'NO', t)
                pk = c.execute("""SELECT a.attname FROM pg_index i JOIN pg_class k ON k.oid=i.indrelid JOIN pg_namespace n ON n.oid=k.relnamespace
                                  JOIN pg_attribute a ON a.attrelid=k.oid AND a.attnum=i.indkey[0]
                                  WHERE n.nspname='taktovia' AND k.relname=%s AND i.indisprimary""", (t,)).fetchone()
                self.assertEqual(pk[0], 'tenant_id', f'{t}: PK beginnt nicht mit tenant_id')

    def test_unique_und_fremdschluessel_beginnen_mit_tenant_id(self):
        with admin() as c:
            uniq = c.execute("""SELECT k.relname, con.conname, a.attname FROM pg_constraint con JOIN pg_class k ON k.oid=con.conrelid
                                JOIN pg_namespace n ON n.oid=k.relnamespace JOIN pg_attribute a ON a.attrelid=k.oid AND a.attnum=con.conkey[1]
                                WHERE n.nspname='taktovia' AND con.contype='u' AND k.relname NOT IN ('tenant','currency')""").fetchall()
            self.assertGreater(len(uniq), 20)
            self.assertEqual([u for u in uniq if u[2] != 'tenant_id'], [])
            fks = c.execute("""SELECT k.relname, con.conname, a.attname, r.relname FROM pg_constraint con JOIN pg_class k ON k.oid=con.conrelid
                               JOIN pg_class r ON r.oid=con.confrelid JOIN pg_namespace n ON n.oid=k.relnamespace
                               JOIN pg_attribute a ON a.attrelid=k.oid AND a.attnum=con.conkey[1]
                               WHERE n.nspname='taktovia' AND con.contype='f'""").fetchall()
            self.assertGreater(len(fks), 40)
            bad = [f for f in fks if f[3] not in NO_TENANT_FK and f[2] != 'tenant_id']
            self.assertEqual(bad, [], 'Fremdschlüssel auf lokale Tabelle ohne tenant_id an erster Stelle')

    def test_policies_erzwingen_using_und_with_check_mit_current_tenant(self):  # SEC-001 AK 3 (statisch, alle Tabellen)
        with admin() as c:
            rows = c.execute("""SELECT tablename, qual, with_check FROM pg_policies WHERE schemaname='taktovia' AND policyname='tenant_isolation'""").fetchall()
            self.assertGreaterEqual(len(rows), 36)
            for t, q, w in rows:
                self.assertIn('current_tenant()', q, t); self.assertIn('current_tenant()', w, t)

    def test_keine_view_und_keine_definerfunktion(self):  # SEC-001 AK 6
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM pg_views WHERE schemaname='taktovia'").fetchone()[0], 0)
            self.assertEqual(c.execute("""SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                                          WHERE n.nspname='taktovia' AND p.prosecdef""").fetchone()[0], 0)

    def test_tenant_id_trigger_auf_allen_tabellen(self):
        with admin() as c:
            n = c.execute("""SELECT count(*) FROM pg_trigger t JOIN pg_class k ON k.oid=t.tgrelid JOIN pg_namespace s ON s.oid=k.relnamespace
                             WHERE s.nspname='taktovia' AND t.tgname='tenant_id_immutable'""").fetchone()[0]
            self.assertGreaterEqual(n, 36)

    def test_tenant_id_aenderung_wird_auch_fuer_admin_verweigert(self):
        seed_tenant(A, 'konv-a'); seed_tenant(B, 'konv-b')
        v = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, v))
        with admin() as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute('UPDATE taktovia.vehicle SET tenant_id=%s WHERE id=%s', (B, v))


def seed(c, t, tenant):
    """Je eine minimale Zeile der vier Abnahmetabellen; Fremdschlüssel per replica-Rolle umgangen (nur Testdaten, nur Admin)."""
    c.execute("SET session_replication_role = replica")
    ids = {k: uid() for k in ('booking', 'expense', 'journal', 'settlement', 'le', 'event')}
    c.execute("""INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision)
                 VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}',1)""", (tenant, ids['booking'], uid(), uid(), f'fed-{uid()}'))
    c.execute("""INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at,client_uuid)
                 VALUES (%s,%s,%s,%s,%s,'application/pdf','NEW',1,'u',now(),%s)""", (tenant, ids['expense'], ids['le'], f'k-{uid()}', 'a' * 64, ids['expense']))   # client_uuid: unter replica läuft der Vorgabe-Trigger nicht (WP-M4-26)
    c.execute("""INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state)
                 VALUES (%s,%s,%s,%s,'EUR',%s,'2026-10-15','DRAFT')""", (tenant, ids['journal'], ids['event'], ids['le'], uid()))
    c.execute("""INSERT INTO taktovia.settlement_line(tenant_id,id,event_id,leg_id,contract_version_id,creditor_ref,debtor_ref,currency,payable_minor,revision)
                 VALUES (%s,%s,%s,%s,%s,'c','d','EUR',100,1)""", (tenant, ids['settlement'], ids['event'], uid(), uid()))
    c.execute("SET session_replication_role = DEFAULT")
    return ids


class Abnahme(unittest.TestCase):
    """MUL-001 AK 1: Mit Kontext A werden keine Zeilen aus booking, expense_document, journal, settlement_line von B gelesen oder geändert."""
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'konv-a'); seed_tenant(B, 'konv-b')
        with admin() as c:
            cls.ia, cls.ib = seed(c, None, A), seed(c, None, B)

    TABLES = {'booking': 'booking', 'expense_document': 'expense', 'journal': 'journal', 'settlement_line': 'settlement'}

    def test_a_sieht_nur_a(self):
        for table, key in self.TABLES.items():
            with tx(A) as c:
                ids = {r[0] for r in c.execute(f'SELECT id FROM taktovia.{table}').fetchall()}
                self.assertIn(self.ia[key], ids, table); self.assertNotIn(self.ib[key], ids, table)
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{table} WHERE id=%s', (self.ib[key],)).fetchone()[0], 0)

    def test_a_aendert_und_loescht_b_nicht(self):
        for table, key in self.TABLES.items():
            with tx(A) as c:
                n_upd = c.execute(f'UPDATE taktovia.{table} SET tenant_id=tenant_id WHERE id=%s', (self.ib[key],)).rowcount
                self.assertEqual(n_upd, 0, table)
        with tx(A) as c:
            self.assertEqual(c.execute('DELETE FROM taktovia.journal WHERE id=%s', (self.ib['journal'],)).rowcount, 0)
        with admin() as c:  # B-Zeilen unverändert vorhanden
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.journal WHERE id=%s', (self.ib['journal'],)).fetchone()[0], 1)

    def test_a_schreibt_nicht_fuer_b(self):
        for table, key in self.TABLES.items():
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                with tx(A) as c:
                    c.execute(f'INSERT INTO taktovia.{table}(tenant_id,id) VALUES (%s,%s)', (B, uid()))


if __name__ == '__main__':
    unittest.main()
