"""WP-M4-64: Modulzugänge der M4-Module (staff-time, documents-finance, analytics) unter echten Anmeldungen: nur eigene Tabellen, kein DDL, RLS und
Tenantkontext gelten auch für sie; Poolwiederverwendung überträgt keinen Kontext (Kap. 04 §4.6, Kap. 01 §1.3)."""
import queue, threading, unittest
import psycopg
from helpers import admin, module, module_tx, runtime, seed_tenant, set_tenant, tx, uid

A, B = uid(), uid()
ROLES = {'taktovia_mod_stafftime': 'staff-time', 'taktovia_mod_docsfin': 'documents-finance', 'taktovia_mod_analytics': 'analytics'}
DML = ('SELECT', 'INSERT', 'UPDATE', 'DELETE')


def seed_vehicle(tenant):
    v = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, v))
    return v


_slot = iter(range(1, 10_000))


def shift(c, tenant, veh):
    """Jede Schicht in einem eigenen Zeitfenster desselben Fahrzeugs (WP-M3-07: überlappende Fahrzeugbelegung ist verboten)."""
    sid, d = uid(), next(_slot)
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
              "VALUES (%s,%s,%s,%s,now()+make_interval(days => %s),now()+make_interval(days => %s, hours => 8),'Europe/Berlin',0,'PLANNED')", (tenant, sid, uid(), veh, d, d))
    return sid


class Modulzugaenge(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm464-a'); seed_tenant(B, 'm464-b')
        cls.va, cls.vb = seed_vehicle(A), seed_vehicle(B)

    def test_attribute_kein_eigentum_keine_mitgliedschaft(self):  # AK 2
        with admin() as c:
            for role in ROLES:
                r = c.execute('SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolcanlogin, rolinherit FROM pg_roles WHERE rolname=%s', (role,)).fetchone()
                self.assertEqual(tuple(r), (False, False, False, False, True, False), role)
                owned = c.execute("""SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles o ON o.oid=c.relowner
                                     WHERE n.nspname IN ('taktovia','taktovia_meta','public') AND o.rolname=%s""", (role,)).fetchone()[0]
                self.assertEqual(owned, 0, f'{role} besitzt Objekte')
                member = c.execute("SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=%s", (role,)).fetchone()[0]
                self.assertEqual(member, 0, f'{role} ist Mitglied anderer Rollen')

    def test_rechte_genau_auf_die_tabellen_des_eigenen_moduls(self):  # AK 2
        with admin() as c:
            owners = dict(c.execute('SELECT tabelle, modul FROM taktovia_meta.table_owner').fetchall())
            tables = [r[0] for r in c.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='taktovia' AND c.relkind='r'")]
            checked = 0
            for role, modul in ROLES.items():
                for t in tables:
                    q = f'taktovia.{t}'
                    runtime_privs = {p for p in DML if c.execute('SELECT has_table_privilege(%s,%s,%s)', ('taktovia_runtime', q, p)).fetchone()[0]}
                    got = {p for p in DML if c.execute('SELECT has_table_privilege(%s,%s,%s)', (role, q, p)).fetchone()[0]}
                    expect = runtime_privs if owners.get(t) == modul else set()
                    self.assertEqual(got, expect, f'{role} auf {t}')
                    if owners.get(t) != modul:
                        self.assertFalse(c.execute('SELECT has_any_column_privilege(%s,%s,%s)', (role, q, 'SELECT')).fetchone()[0], f'{role}: Spaltenrecht auf {t}')
                    checked += 1
            self.assertGreater(checked, 100)
            for role in ROLES:   # nie Eigentümerrechte
                for priv in ('TRUNCATE', 'REFERENCES', 'TRIGGER'):
                    self.assertEqual([t for t in tables if c.execute('SELECT has_table_privilege(%s,%s,%s)', (role, f'taktovia.{t}', priv)).fetchone()[0]], [], f'{role} {priv}')

    def test_ddl_truncate_setrole_scheitern(self):
        for role in ROLES:
            for stmt in ('CREATE TABLE taktovia.x(a int)', 'ALTER TABLE taktovia.staff_shift ADD COLUMN x int', 'TRUNCATE taktovia.staff_shift',
                         'SET ROLE taktovia_migrator', 'CREATE TABLE public.x(a int)', 'CREATE SCHEMA evil', 'DROP TABLE taktovia.staff_shift'):
                c = module(role)
                try:
                    set_tenant(c, A)
                    with self.assertRaises(psycopg.errors.Error, msg=f'{role}: {stmt}'):
                        c.execute(stmt)
                finally:
                    c.rollback(); c.close()

    def test_modulfremder_schreibversuch_scheitert_eigenes_modul_schreibt(self):  # AK 2
        with module_tx('taktovia_mod_stafftime', A) as c:
            shift(c, A, self.va)   # eigenes Modul: erlaubt
            self.assertGreaterEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift').fetchone()[0], 1)
        foreign = [
            ('taktovia_mod_stafftime', "UPDATE taktovia.expense_document SET state='X'"),
            ('taktovia_mod_stafftime', 'DELETE FROM taktovia.cost_object'),
            ('taktovia_mod_stafftime', 'SELECT count(*) FROM taktovia.journal'),
            ('taktovia_mod_docsfin', "UPDATE taktovia.staff_shift SET state='X'"),
            ('taktovia_mod_docsfin', 'DELETE FROM taktovia.driver_event'),
            ('taktovia_mod_analytics', "UPDATE taktovia.staff_shift SET state='X'"),
            ('taktovia_mod_analytics', 'DELETE FROM taktovia.expense_document'),
            ('taktovia_mod_analytics', 'SELECT count(*) FROM taktovia.vehicle'),
            ('taktovia_mod_stafftime', 'SELECT count(*) FROM taktovia.audit_event'),
        ]
        for role, stmt in foreign:
            c = module(role)
            try:
                set_tenant(c, A)
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=f'{role}: {stmt}'):
                    c.execute(stmt)
            finally:
                c.rollback(); c.close()

    def test_rls_gilt_auch_fuer_modulzugaenge(self):  # AK 1
        with module_tx('taktovia_mod_stafftime', A) as c: s = shift(c, A, self.va)
        with module_tx('taktovia_mod_stafftime', B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE id=%s', (s,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # WITH CHECK: fremde tenant_id
                shift(c, A, self.va)
        with module_tx('taktovia_mod_stafftime', None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift').fetchone()[0], 0, 'ohne Mandantenkontext keine Zeile')
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                shift(c, A, self.va)
        with module_tx('taktovia_mod_stafftime', B) as c:   # keine lokale Cross-Tenant-Referenz: Fahrzeug von A in Mandant B
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                shift(c, B, self.va)

    def test_poolwiederverwendung_mit_abbruch_ueberträgt_keinen_kontext(self):  # konkurrierend, Kap. 04 §4.6
        with module_tx('taktovia_mod_stafftime', A) as c: shift(c, A, self.va)
        with module_tx('taktovia_mod_stafftime', B) as c: shift(c, B, self.vb); shift(c, B, self.vb)
        pool = queue.Queue()
        for _ in range(2): pool.put(module('taktovia_mod_stafftime'))
        errors, counts = [], {A: set(), B: set()}

        def worker(tenant, n):
            for i in range(n):
                c = pool.get()
                try:
                    try:
                        set_tenant(c, tenant)
                        counts[tenant].add(c.execute('SELECT count(*) FROM taktovia.staff_shift').fetchone()[0])
                        if i % 3 == 0:   # Transaktionsabbruch mitten im Kontext
                            c.execute('SELECT 1/0')
                        c.commit()
                    except psycopg.errors.DivisionByZero:
                        c.rollback()
                    ctx = c.execute("SELECT current_setting('app.tenant_id', true)").fetchone()[0]
                    if ctx not in (None, ''): errors.append(f'Kontextrest {ctx}')
                    c.commit()
                except Exception as ex:  # noqa: BLE001
                    errors.append(repr(ex)); c.rollback()
                finally:
                    pool.put(c)
        threads = [threading.Thread(target=worker, args=(t, 30)) for t in (A, B, A, B)]
        [t.start() for t in threads]; [t.join() for t in threads]
        while not pool.empty(): pool.get().close()
        self.assertEqual(errors, [])
        with admin() as c:
            na = c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=%s', (A,)).fetchone()[0]
            nb = c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=%s', (B,)).fetchone()[0]
        self.assertEqual(counts[A], {na}, 'Mandant A sieht stets nur die eigenen Schichten')
        self.assertEqual(counts[B], {nb}, 'Mandant B sieht stets nur die eigenen Schichten')


if __name__ == '__main__':
    unittest.main()
