"""WP-M4-56 / LH-6.14-ANA-002, LH-I.6-KPI-013: Datenbankebene der Kilometerkennzahlen unter der Runtime-Rolle: Besetzung je Lauf (CHECK, Überlappung, Summeninvariante gegen die PASSENGER-Abschnitte,
append-only), Mandantentrennung (RLS), Kapazität im Lauf, Seed der ausführbaren Definitionen (Version 2, wiederholbar, Version 1 unverändert) und Zerlegung im Snapshot."""
import datetime
import json
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
DAY = datetime.date(2026, 6, 1)
T0 = datetime.datetime(2026, 6, 1, 8, 0, tzinfo=datetime.timezone.utc)
MIN = datetime.timedelta(minutes=1)
IDS = {'vehicle_km', 'operating_hours', 'empty_km_rate', 'productive_km', 'passenger_km', 'seat_time_utilization', 'capacity_utilization_distance_weighted'}


def vehicle(tenant):
    vid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org',%s::jsonb)", (tenant, vid, '{"seats":4,"wheelchair":1}'))
    return vid


def run(c, tenant, vehicle_id, total, seats=4):
    rid = uid()
    c.execute("INSERT INTO taktovia.vehicle_km_run(tenant_id,id,vehicle_id,service_day,input_sha256,data_as_of,rule_version,position_count,events_complete,total_km_thousandths,capacity_seats,capacity_wheelchair) "
              "VALUES (%s,%s,%s,%s,%s,%s,1,3,true,%s,%s,1)", (tenant, rid, vehicle_id, DAY, uid().hex + uid().hex, T0, total, seats))
    return rid


def seg(c, tenant, run_id, vehicle_id, a, b, category, km):
    c.execute("INSERT INTO taktovia.vehicle_km_segment(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,category,km_thousandths,source,rule_version) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'POSITION',1)", (tenant, uid(), run_id, vehicle_id, DAY, T0 + a * MIN, T0 + b * MIN, category, km))


def occ(c, tenant, run_id, vehicle_id, a, b, passengers, km):
    c.execute("INSERT INTO taktovia.vehicle_km_occupancy(tenant_id,id,run_id,vehicle_id,service_day,period_from,period_to,passengers,km_thousandths,rule_version) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,1)",
              (tenant, uid(), run_id, vehicle_id, DAY, T0 + a * MIN, T0 + b * MIN, passengers, km))


class Kilometerkennzahlen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm456-a'); seed_tenant(B, 'm456-b')
        cls.va, cls.vb = vehicle(A), vehicle(B)

    def test_besetzung_check_ueberlappung_und_summeninvariante(self):
        with tx(A) as c:   # gültig: zwei Fahrgäste auf gemeinsamer Teilstrecke, Meter = PASSENGER-Abschnitt
            r = run(c, A, self.va, 3000)
            seg(c, A, r, self.va, 0, 3, 'PASSENGER', 3000)
            occ(c, A, r, self.va, 0, 1, 1, 1000); occ(c, A, r, self.va, 1, 2, 2, 1000); occ(c, A, r, self.va, 2, 3, 1, 1000)
        for bad in (0, 100):   # Fahrgäste mindestens 1 und höchstens 99
            with tx(A) as c:
                r = run(c, A, self.va, 1000); seg(c, A, r, self.va, 0, 1, 'PASSENGER', 1000)
                with self.assertRaises(psycopg.errors.CheckViolation): occ(c, A, r, self.va, 0, 1, bad, 1000)
        with tx(A) as c:   # Überlappung
            r = run(c, A, self.va, 2000); seg(c, A, r, self.va, 0, 2, 'PASSENGER', 2000)
            occ(c, A, r, self.va, 0, 1, 1, 1000)
            with self.assertRaises(psycopg.errors.ExclusionViolation): occ(c, A, r, self.va, 0, 2, 2, 1000)
        with self.assertRaises(psycopg.errors.CheckViolation):   # Besetzungsabschnitte ungleich PASSENGER-Abschnitte: Fehler am Ende der Transaktion
            with tx(A) as c:
                r = run(c, A, self.va, 3000); seg(c, A, r, self.va, 0, 3, 'PASSENGER', 3000)
                occ(c, A, r, self.va, 0, 1, 1, 1000)
        with tx(A) as c:   # Lauf ohne Besetzungsabschnitte (Besetzung unbekannt) ist kein Widerspruch
            r = run(c, A, self.va, 3000); seg(c, A, r, self.va, 0, 3, 'PASSENGER', 3000)

    def test_besetzung_ist_append_only(self):
        with tx(A) as c:
            r = run(c, A, self.va, 1000); seg(c, A, r, self.va, 0, 1, 'PASSENGER', 1000); occ(c, A, r, self.va, 0, 1, 1, 1000)
        for stmt in ("UPDATE taktovia.vehicle_km_occupancy SET passengers = 2 WHERE run_id = %s", "DELETE FROM taktovia.vehicle_km_occupancy WHERE run_id = %s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute(stmt, (r,))

    def test_fremder_mandant_sieht_keine_kilometerfakten(self):
        with tx(A) as c:
            r = run(c, A, self.va, 1000); seg(c, A, r, self.va, 0, 1, 'PASSENGER', 1000); occ(c, A, r, self.va, 0, 1, 1, 1000)
        with tx(B) as c:
            for t in ('vehicle_km_run', 'vehicle_km_segment', 'vehicle_km_occupancy'):
                self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t} WHERE run_id = %s" if t != 'vehicle_km_run' else "SELECT count(*) FROM taktovia.vehicle_km_run WHERE id = %s", (r,)).fetchone()[0], 0, t)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # fremder Mandant: Besetzung für einen Lauf des Mandanten A (RLS-WITH-CHECK)
                occ(c, A, r, self.va, 0, 1, 1, 1000)
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle_km_occupancy WHERE run_id = %s', (r,)).fetchone()[0], 1)

    def test_seed_ausfuehrbar_version_2_wiederholbar_je_mandant(self):
        for t in (A, B):
            with tx(t) as c:
                c.execute('SELECT taktovia.seed_metric_definitions()')
                self.assertGreaterEqual(c.execute('SELECT taktovia.seed_km_metric_definitions()').fetchone()[0], 5)
                self.assertEqual(c.execute('SELECT taktovia.seed_km_metric_definitions()').fetchone()[0], 0, 'wiederholbar')
                self.assertEqual(c.execute('SELECT taktovia.seed_utilization_metric_definitions()').fetchone()[0], 2, 'WP-M4-57: beide Auslastungskennzahlen in Version 2')
                self.assertEqual(c.execute('SELECT taktovia.seed_utilization_metric_definitions()').fetchone()[0], 0, 'wiederholbar')
                rows = {r[0]: r[1:] for r in c.execute("SELECT metric_id, version, executable, filter_dimensions FROM taktovia.metric_definition_revision WHERE metric_id = ANY(%s) AND version = (SELECT max(m.version) FROM taktovia.metric_definition_revision m WHERE m.tenant_id = metric_definition_revision.tenant_id AND m.metric_id = metric_definition_revision.metric_id)", (list(IDS),)).fetchall()}
                self.assertEqual(set(rows), IDS)
                for k, (version, executable, dims) in rows.items():
                    self.assertTrue(executable, k); self.assertEqual(dims, ['vehicle_id'], k)
                    self.assertEqual(version, 1 if k in ('vehicle_km', 'operating_hours') else 2, k)
                self.assertFalse(c.execute("SELECT executable FROM taktovia.metric_definition_revision WHERE metric_id = 'empty_km_rate' AND version = 1").fetchone()[0], 'Version 1 bleibt unverändert')

    def test_snapshot_traegt_zerlegung_als_objekt(self):
        with tx(A) as c:
            c.execute('SELECT taktovia.seed_metric_definitions()'); c.execute('SELECT taktovia.seed_km_metric_definitions()')
            c.execute("""INSERT INTO taktovia.aggregate_snapshot(tenant_id,id,metric_id,definition_version,value_kind,filter,filter_hash,period_from,period_to,reporting_timezone,state,value,numerator,coverage,data_as_of,source_events,components)
                         VALUES (%s,%s,'vehicle_km',1,'SUM','{}'::jsonb,%s,'2026-05-31T22:00Z','2026-06-01T22:00Z','Europe/Berlin','OK',1,1,NULL,NULL,1,%s::jsonb)""", (A, uid(), 'a' * 64, json.dumps({'PASSENGER': '1.000'})))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("""INSERT INTO taktovia.aggregate_snapshot(tenant_id,id,metric_id,definition_version,value_kind,filter,filter_hash,period_from,period_to,reporting_timezone,state,value,numerator,coverage,data_as_of,source_events,components)
                             VALUES (%s,%s,'vehicle_km',1,'SUM','{}'::jsonb,%s,'2026-05-31T22:00Z','2026-06-01T22:00Z','Europe/Berlin','OK',1,1,NULL,NULL,1,'[]'::jsonb)""", (A, uid(), 'b' * 64))


if __name__ == '__main__':
    unittest.main()
