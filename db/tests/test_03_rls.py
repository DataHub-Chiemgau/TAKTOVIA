"""WP-M0-18/19: Tenantkontext und RLS unter der echten Runtime-Rolle (PH-AT-001 Datenbankanteil)."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
VA, VB = uid(), uid()


def vehicle(c, tenant, vid, owner='x'):
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,%s,'{}')",
              (tenant, vid, owner))


class RLS(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'A'); seed_tenant(B, 'B')
        with tx(A) as c: vehicle(c, A, VA)
        with tx(B) as c: vehicle(c, B, VB)

    def count(self, tenant, where=''):
        with tx(tenant) as c:
            return c.execute(f'SELECT count(*) FROM taktovia.vehicle {where}').fetchone()[0]

    def test_ohne_kontext_keine_zeile_und_kein_schreiben(self):
        self.assertEqual(self.count(None), 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(None) as c: vehicle(c, A, uid())

    def test_fremde_zeile_unsichtbar_wie_nicht_vorhanden(self):
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle WHERE id=%s', (VB,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle WHERE id=%s', (uid(),)).fetchone()[0], 0)
            self.assertEqual(c.execute('UPDATE taktovia.vehicle SET owner_org_ref=%s WHERE id=%s', ('hack', VB)).rowcount, 0)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT owner_org_ref FROM taktovia.vehicle WHERE id=%s', (VB,)).fetchone()[0], 'x')

    def test_with_check_verhindert_fremden_mandanten(self):
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: vehicle(c, B, uid())
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # Umhängen auf B per UPDATE
            with tx(A) as c: c.execute('UPDATE taktovia.vehicle SET tenant_id=%s WHERE id=%s', (B, VA))

    def test_tenantfremde_referenz_scheitert_per_fk(self):
        """Zusammengesetzte FK: Schicht in A darf kein Fahrzeug aus B referenzieren."""
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c:
                c.execute("""INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state)
                             VALUES (%s,%s,%s,%s,now(),now()+interval '1 hour','Europe/Berlin',1,'PLANNED')""", (A, uid(), uid(), VB))

    def test_tenant_tabelle_nur_eigene_zeile(self):
        with tx(A) as c:
            ids = [r[0] for r in c.execute('SELECT id FROM taktovia.tenant').fetchall()]
            self.assertEqual(ids, [A])

    def test_kontext_ist_transaktionslokal_nach_poolwiederverwendung(self):
        c = runtime()
        try:
            set_tenant(c, A); self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle').fetchone()[0], 1)
            c.commit()  # Verbindung geht zurück in den Pool: Kontext darf nicht fortwirken
            self.assertEqual(c.execute("SELECT current_setting('app.tenant_id', true)").fetchone()[0] in (None, ''), True)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle').fetchone()[0], 0)
            c.rollback()
            set_tenant(c, A); c.rollback()  # Rollback verwirft den Kontext ebenfalls
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle').fetchone()[0], 0)
        finally:
            c.close()

    def test_ungueltiger_kontext_ist_fail_closed(self):
        c = runtime()
        try:
            c.execute("SELECT set_config('app.tenant_id','kein-uuid',true)")
            with self.assertRaises(psycopg.errors.InvalidTextRepresentation):  # Abbruch statt Zeilen
                c.execute('SELECT count(*) FROM taktovia.vehicle')
        finally:
            c.rollback(); c.close()

    def test_zwei_gleichzeitige_verbindungen_beeinflussen_sich_nicht(self):
        errs, seen = [], []
        barrier = threading.Barrier(2)

        def work(tenant, vid):
            try:
                with tx(tenant) as c:
                    barrier.wait(timeout=10)
                    for _ in range(50):
                        ids = {r[0] for r in c.execute('SELECT id FROM taktovia.vehicle').fetchall()}
                        seen.append((tenant, ids))
            except Exception as e:  # pragma: no cover
                errs.append(e)
        ts = [threading.Thread(target=work, args=(A, VA)), threading.Thread(target=work, args=(B, VB))]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(errs, [])
        for tenant, ids in seen:
            self.assertEqual(ids, {VA} if tenant == A else {VB})

    def test_sql_injection_ueber_kontext_nicht_moeglich(self):
        c = runtime()
        try:
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (f"{A}' OR '1'='1",))
            with self.assertRaises(psycopg.errors.InvalidTextRepresentation):
                c.execute('SELECT count(*) FROM taktovia.vehicle')
        finally:
            c.rollback(); c.close()

    def test_alle_36_tabellen_ohne_kontext_leer(self):
        with admin() as a:
            tabs = [r[0] for r in a.execute("""SELECT table_name FROM information_schema.columns
                                               WHERE table_schema='taktovia' AND column_name='tenant_id'""")]
        self.assertEqual(len(tabs), 36)
        c = runtime()
        try:
            for t in tabs:
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0], 0, t)
        finally:
            c.close()


if __name__ == '__main__':
    unittest.main()
