"""WP-M4-42 (FCO-005): Kilometerklassifikation auf Datenbankebene unter der Runtime-Rolle: CHECK-Regeln (negative Kilometer, unbekannte Kategorie), überlappende Abschnitte desselben Laufs,
Summeninvariante (Gesamtkilometer = Summe der Abschnitte inkl. UNASSIGNED), append-only, Mandantentrennung (RLS, zusammengesetzter Fremdschlüssel)."""
import datetime
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
DAY = datetime.date(2026, 6, 1)
T0 = datetime.datetime(2026, 6, 1, 8, 0, tzinfo=datetime.timezone.utc)


def vehicle(tenant):
    vid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org',%s::jsonb)", (tenant, vid, '{}'))
    return vid


def run(c, tenant, vehicle_id, total, sha=None):
    rid = uid()
    c.execute("INSERT INTO taktovia.vehicle_km_run(tenant_id,id,vehicle_id,service_day,input_sha256,data_as_of,rule_version,position_count,events_complete,total_km_thousandths) "
              "VALUES (%s,%s,%s,%s,%s,%s,1,3,true,%s)", (tenant, rid, vehicle_id, DAY, sha or uid().hex + uid().hex, T0, total))
    return rid


def seg(c, tenant, run_id, vehicle_id, start_min, end_min, category, km, source='POSITION'):
    c.execute("INSERT INTO taktovia.vehicle_km_segment(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,category,km_thousandths,source,rule_version) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1)", (tenant, uid(), run_id, vehicle_id, DAY, T0 + datetime.timedelta(minutes=start_min), T0 + datetime.timedelta(minutes=end_min), category, km, source))


class Kilometerabschnitt(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm442-a'); seed_tenant(B, 'm442-b')
        cls.va, cls.vb = vehicle(A), vehicle(B)

    def test_check_und_ueberlappung_werden_abgewiesen(self):
        # gültiger Lauf: Summe der Abschnitte (inkl. UNASSIGNED) = Gesamtkilometer
        with tx(A) as c:
            r = run(c, A, self.va, 5000)
            seg(c, A, r, self.va, 0, 10, 'PASSENGER', 3000); seg(c, A, r, self.va, 10, 20, 'UNASSIGNED', 2000)
        # negative Kilometer, unbekannte Kategorie, unbekannte Quelle, Zeitraum ohne Länge
        for args in ((0, 10, 'EMPTY', -1), (0, 10, 'SCHROTT', 1), (0, 0, 'EMPTY', 1), (10, 5, 'EMPTY', 1)):
            with tx(A) as c:
                r = run(c, A, self.va, 0)
                with self.assertRaises(psycopg.errors.CheckViolation): seg(c, A, r, self.va, *args)
        with tx(A) as c:
            r = run(c, A, self.va, 0)
            with self.assertRaises(psycopg.errors.CheckViolation): seg(c, A, r, self.va, 0, 10, 'EMPTY', 0, source='GUESS')
        # überlappende Abschnitte desselben Laufs (Fahrzeugs); angrenzende sind erlaubt
        with tx(A) as c:
            r = run(c, A, self.va, 3000)
            seg(c, A, r, self.va, 0, 10, 'EMPTY', 1000)
            seg(c, A, r, self.va, 10, 20, 'EMPTY', 1000)
            with self.assertRaises(psycopg.errors.ExclusionViolation): seg(c, A, r, self.va, 15, 25, 'PASSENGER', 1000)
        # ein Abschnitt außerhalb des Betriebstags wird abgewiesen
        with tx(A) as c:
            r = run(c, A, self.va, 0)
            with self.assertRaises(psycopg.errors.CheckViolation): seg(c, A, r, self.va, -9 * 60, -8 * 60, 'EMPTY', 0)

    def test_summeninvariante_gesamt_gleich_summe_der_abschnitte(self):
        # Gesamtkilometer größer als die Summe der Abschnitte: Fehler am Ende der Transaktion (UNASSIGNED muss den Rest ausweisen)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                r = run(c, A, self.va, 5000)
                seg(c, A, r, self.va, 0, 10, 'PASSENGER', 3000)
        # Lauf ohne Strecke ist gültig, Lauf mit Gesamtstrecke aber ohne Abschnitte nicht
        with tx(A) as c:
            run(c, A, self.va, 0)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                run(c, A, self.va, 1)

    def test_derselbe_eingangszustand_ergibt_keinen_zweiten_lauf_und_append_only(self):
        sha = uid().hex + uid().hex
        with tx(A) as c:
            r = run(c, A, self.va, 0, sha)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c:
                run(c, A, self.va, 0, sha)
        for sql in ("UPDATE taktovia.vehicle_km_run SET total_km_thousandths = 1 WHERE id = %s", "DELETE FROM taktovia.vehicle_km_run WHERE id = %s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.RaiseException, psycopg.errors.InvalidParameterValue)):
                    c.execute(sql, (r,))

    def test_rls_trennt_mandanten(self):
        with tx(A) as c:
            r = run(c, A, self.va, 1000)
            seg(c, A, r, self.va, 0, 10, 'EMPTY', 1000)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle_km_run WHERE id = %s", (r,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle_km_segment WHERE run_id = %s", (r,)).fetchone()[0], 0)
        # Lauf für das Fahrzeug eines anderen Mandanten scheitert am zusammengesetzten Fremdschlüssel; fremder Mandant im Kontext an der Policy
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): run(c, A, self.vb, 0)
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): run(c, A, self.va, 0)


if __name__ == '__main__':
    unittest.main()
