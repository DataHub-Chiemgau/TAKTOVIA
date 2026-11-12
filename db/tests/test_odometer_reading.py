"""WP-M4-41 (FCO-003): Kilometerstandsregister auf Datenbankebene unter der Runtime-Rolle: append-only (UPDATE/DELETE scheitern), Einmaligkeit je Quellobjekt, Mandantentrennung (RLS,
zusammengesetzter Fremdschlüssel auf das Fahrzeug), Konflikte und Auflösungen unveränderlich, höchstens eine Auflösung je Konflikt, gewählter Stand gehört zum Konflikt."""
import datetime
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
T0 = datetime.datetime(2026, 6, 1, 8, 0, tzinfo=datetime.timezone.utc)


def vehicle(tenant):
    vid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org',%s::jsonb)", (tenant, vid, '{}'))
    return vid


def reading(c, tenant, vehicle_id, km, source='DRIVER_CHECK', ref=None, minutes=0):
    rid = uid()
    c.execute("INSERT INTO taktovia.odometer_reading(tenant_id,id,vehicle_id,reading_km,source,source_ref,measured_at,recorded_by) VALUES (%s,%s,%s,%s,%s,%s,%s,'service:test')",
              (tenant, rid, vehicle_id, km, source, ref or str(rid), T0 + datetime.timedelta(minutes=minutes)))
    return rid


def conflict(c, tenant, vehicle_id, a, b, kind='DECREASING'):
    cid = uid()
    c.execute("INSERT INTO taktovia.odometer_conflict(tenant_id,id,vehicle_id,kind,earlier_reading_id,later_reading_id,tolerance_km) VALUES (%s,%s,%s,%s,%s,%s,5)", (tenant, cid, vehicle_id, kind, a, b))
    return cid


def resolution(c, tenant, cid, decision='DISCARDED', chosen=None, reason='geprueft und verworfen'):
    c.execute("INSERT INTO taktovia.odometer_conflict_resolution(tenant_id,id,conflict_id,decision,chosen_reading_id,reason,resolved_by) VALUES (%s,%s,%s,%s,%s,%s,'user:t')",
              (tenant, uid(), cid, decision, chosen, reason))


class Kilometerstand(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm441-a'); seed_tenant(B, 'm441-b')
        cls.va, cls.vb = vehicle(A), vehicle(B)

    def test_append_only_und_mandantentrennung(self):
        with tx(A) as c:
            r = reading(c, A, self.va, 1000)
        for sql in ("UPDATE taktovia.odometer_reading SET reading_km = 1 WHERE id = %s", "DELETE FROM taktovia.odometer_reading WHERE id = %s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.RaiseException, psycopg.errors.InvalidParameterValue)):
                    c.execute(sql, (r,))
        # auch mit dem Recht ausgestattet würde der Trigger ablehnen: Administratorweg
        with admin() as c:
            with self.assertRaises(Exception): c.execute("UPDATE taktovia.odometer_reading SET reading_km = 1 WHERE id = %s", (r,))
            with self.assertRaises(Exception): c.execute("DELETE FROM taktovia.odometer_reading WHERE id = %s", (r,))
        # Mandant B sieht den Stand nicht; Stand für fremdes Fahrzeug scheitert am zusammengesetzten Fremdschlüssel
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.odometer_reading WHERE id = %s", (r,)).fetchone()[0], 0)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): reading(c, A, self.vb, 5)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): reading(c, B, self.va, 5)   # gefälschter Mandantenschlüssel
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): reading(c, A, self.va, -1)

    def test_quellobjekt_nur_einmal_je_quelle(self):
        va = vehicle(A)
        with tx(A) as c:
            reading(c, A, va, 10, 'FUEL_RECEIPT', 'doc-1')
            reading(c, A, va, 10, 'DRIVER_CHECK', 'doc-1')   # andere Quelle, gleicher Bezug: erlaubt
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): reading(c, A, va, 11, 'FUEL_RECEIPT', 'doc-1')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): reading(c, A, va, 11, 'ODOMETER', 'x')
        # gleiche Quelle und Bezug in anderem Mandanten sind unabhängig
        with tx(B) as c:
            reading(c, B, self.vb, 10, 'FUEL_RECEIPT', 'doc-1')

    def test_konflikt_und_aufloesung_unveraenderlich_hoechstens_eine_aufloesung(self):
        va = vehicle(A)
        with tx(A) as c:
            r1 = reading(c, A, va, 2000, minutes=0); r2 = reading(c, A, va, 1500, 'FUEL_RECEIPT', None, 10); r3 = reading(c, A, va, 9, 'WORKSHOP', None, 20)
            k = conflict(c, A, va, r1, r2)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): conflict(c, A, va, r1, r2)   # derselbe Konflikt nur einmal
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): resolution(c, A, k, 'DISCARDED', None, reason='ab')   # Grund zu kurz
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): resolution(c, A, k, 'CHOSEN', None)   # Wahl ohne Stand
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): resolution(c, A, k, 'CHOSEN', r3)   # Stand gehört nicht zum Konflikt
        with tx(A) as c:
            resolution(c, A, k, 'CHOSEN', r1)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): resolution(c, A, k, 'DISCARDED')
        for sql in ("UPDATE taktovia.odometer_conflict_resolution SET reason = 'neu' WHERE conflict_id = %s", "DELETE FROM taktovia.odometer_conflict_resolution WHERE conflict_id = %s",
                    "UPDATE taktovia.odometer_conflict SET kind = 'SOURCE_MISMATCH' WHERE id = %s", "DELETE FROM taktovia.odometer_conflict WHERE id = %s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute(sql, (k,))

    def test_parallele_aufloesung_ergibt_genau_einen_eintrag(self):
        va = vehicle(A)
        with tx(A) as c:
            r1 = reading(c, A, va, 2000); r2 = reading(c, A, va, 1500, 'FUEL_RECEIPT', None, 10); k = conflict(c, A, va, r1, r2)
        barrier = threading.Barrier(4); results = []
        def run():
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10); resolution(c, A, k); results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=run) for _ in range(4)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['dup', 'dup', 'dup', 'ok'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.odometer_conflict_resolution WHERE conflict_id = %s", (k,)).fetchone()[0], 1)

    def test_policy_je_mandant_mit_grenzen(self):
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.odometer_policy(tenant_id,tolerance_km,window_minutes,updated_by) VALUES (%s,10,30,'u') ON CONFLICT (tenant_id) DO UPDATE SET tolerance_km = 10", (A,))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.odometer_policy").fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("INSERT INTO taktovia.odometer_policy(tenant_id,tolerance_km,updated_by) VALUES (%s,-1,'u')", (B,))


if __name__ == '__main__':
    unittest.main()
