"""WP-M1-18 (LH-6.1-SYS-006): Zeitpunkte sind timestamptz; Dienst- und Schichtzonen sind IANA-Zonen."""
import unittest
import uuid
import psycopg
from helpers import admin

LINT = """SELECT table_schema, table_name, column_name FROM information_schema.columns
          WHERE table_schema = %s AND (data_type = 'timestamp without time zone' OR udt_name = '_timestamp')
          ORDER BY 1, 2, 3"""


class ZeitLint(unittest.TestCase):
    def test_keine_timestamp_without_time_zone_spalte(self):
        with admin() as c:
            # Lint über das Produktschema: kein timestamp ohne Zeitzone, auch nicht als Array
            self.assertEqual(c.execute(LINT, ('taktovia',)).fetchall(), [])
            # Der Lint findet den Verstoß wirklich: absichtlich verstoßende Tabelle in einem Hilfsschema
            c.execute('CREATE SCHEMA zeitlint_selftest')
            try:
                c.execute('CREATE TABLE zeitlint_selftest.t (id int, naiv timestamp, mehrere timestamp[], ok timestamptz)')
                found = c.execute(LINT, ('zeitlint_selftest',)).fetchall()
                self.assertEqual(found, [('zeitlint_selftest', 't', 'mehrere'), ('zeitlint_selftest', 't', 'naiv')])
            finally:
                c.execute('DROP SCHEMA zeitlint_selftest CASCADE')

    def test_alle_zeitspalten_des_kernschemas_sind_timestamptz(self):
        with admin() as c:
            kinds = {r[0] for r in c.execute("""SELECT DISTINCT data_type FROM information_schema.columns
                       WHERE table_schema='taktovia' AND (column_name LIKE '%\\_at' OR column_name LIKE 'valid\\_%' OR column_name LIKE 'effective\\_%')""")}
            self.assertEqual(kinds, {'timestamp with time zone'})


SERVICE = """INSERT INTO taktovia.service(tenant_id, id, operator_id, name, timezone, config_revision_id) VALUES (%s, %s, %s, 'Rufbus', %s, %s)"""
SHIFT = """INSERT INTO taktovia.staff_shift(tenant_id, id, driver_ref, vehicle_id, starts_at, ends_at, timezone, roster_revision, state)
           VALUES (%s, %s, %s, %s, '2026-10-25T00:00:00Z', '2026-10-25T08:00:00Z', %s, 1, 'PLANNED')"""


class IanaZone(unittest.TestCase):
    def check(self, sql, zone):
        """Fügt eine Zeile ein (Fremdschlüssel-Trigger ausgeschaltet; CHECK-Constraints bleiben wirksam) und rollt immer zurück."""
        with admin() as c:
            c.execute('SET session_replication_role = replica')
            ids = [uuid.uuid4() for _ in range(4)]
            args = (ids[0], ids[1], ids[2], zone, ids[3]) if 'service' in sql.split('(')[0] else (ids[0], ids[1], ids[2], ids[3], zone)
            try:
                c.execute(sql, args)
                return None
            except psycopg.Error as e:
                return e.sqlstate
            finally:
                c.rollback()

    def test_service_und_staff_shift_zone_wird_geprueft(self):
        for sql in (SERVICE, SHIFT):
            for ok in ('Europe/Berlin', 'America/New_York', 'UTC', 'Asia/Kolkata', 'America/Argentina/Buenos_Aires'):
                self.assertIsNone(self.check(sql, ok), (sql[:40], ok))
            for bad in ('Mars/Olympus', 'Europe/Berlino', 'CET', 'EST5EDT', '+01:00', 'UTC+1', 'Etc/GMT-1', 'europe/berlin', '', 'Europe/Berlin ', 'Europe//Berlin'):
                self.assertIn(self.check(sql, bad), ('23514', '22023'), (sql[:40], bad))

    def test_constraints_sind_im_schema_vorhanden(self):
        with admin() as c:
            names = {r[0] for r in c.execute("""SELECT conname FROM pg_constraint con JOIN pg_namespace n ON n.oid=con.connamespace
                                                WHERE n.nspname='taktovia' AND contype='c' AND conname LIKE '%\\_timezone\\_iana'""")}
        self.assertEqual(names, {'service_timezone_iana', 'staff_shift_timezone_iana', 'time_event_timezone_iana'})


if __name__ == '__main__':
    unittest.main()
