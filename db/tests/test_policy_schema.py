"""WP-M1-19 / LH-I.7-CFG-002, LH-I.7-CFG-004: `policy_schema` (Parameterkatalog und Systemgrenzen) und die Prüfung von `config_revision.values_json`
in der Datenbank. Der Katalog ist global (kein `tenant_id`, wie `currency` und `state_transition_rule`), für die Runtime-Rolle nur lesbar und
nur per Migration änderbar. Geprüft wird unter der echten Runtime-Rolle."""
import csv
import json
import unittest
from pathlib import Path

import psycopg
from helpers import admin, migrator, runtime, seed_tenant, tx, uid

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'docs/umsetzungsplan/vertraege/schemas'
KATALOG = ROOT / 'docs/pflichtenheft/kataloge.csv'
A, B = uid(), uid()
START = ['max_wait_min', 'max_detour_pct', 'min_booking_lead_min', 'max_booking_horizon_days', 'pickup_window_min', 'arrival_grace_min',
         'no_show_grace_min', 'stop_walk_max_m', 'advance_arrival_buffer_min', 'pooling_allowed', 'max_reassignments',
         'protected_stop_horizon_min', 'min_capacity_reserve', 'transfer_min_time_min', 'wheelchair_boarding_sec', 'stop_dwell_sec',
         'vehicle_readiness_require_checks', 'customer_notify_before_pickup_min', 'reporting_timezone', 'notification_quiet_hours']


def insert_revision(c, tenant, values, scope='TENANT', scope_id=None, version='1', revision=1):
    """Entwurf wie die Anwendung ihn anlegt; liefert die Zeilen-ID."""
    rid = uid()
    c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason)
                 VALUES (%s,%s,%s,%s,%s,%s,%s::jsonb,now(),'user:t','Test')""",
              (tenant, rid, scope, scope_id or tenant, revision, version, json.dumps(values)))
    return rid


def count(tenant, scope_id):
    with admin() as c:
        return c.execute("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=%s AND scope_id=%s", (tenant, scope_id)).fetchone()[0]


class Katalog(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'policy-a'); seed_tenant(B, 'policy-b')

    def test_katalog_enthaelt_startmenge_mit_name_typ_bereich_einheit_beschreibung(self):  # CFG-002 AK 1
        with tx(A) as c:
            rows = {r[0]: r for r in c.execute("""SELECT parameter, value_type, unit, min_value, max_value, description FROM taktovia.policy_schema
                                                  WHERE schema_version = taktovia.current_policy_version()""")}
        for name in START:
            self.assertIn(name, rows)
            _, typ, unit, _, _, desc = rows[name]
            self.assertTrue(typ and unit and desc, name)
        self.assertEqual((rows['max_wait_min'][3], rows['max_wait_min'][4], rows['max_wait_min'][2]), (0, 240, 'MINUTES'))
        self.assertIn('service_config', rows)

    def test_namen_stimmen_mit_kataloge_csv_ueberein(self):
        with open(KATALOG, encoding='utf-8', newline='') as f:
            catalog = {r['name_original'] for r in csv.DictReader(f, delimiter=';') if r['katalogtyp'] == 'Parameter'}
        with admin() as c:
            names = {r[0] for r in c.execute("SELECT parameter FROM taktovia.policy_schema WHERE parameter <> 'service_config'")}
        # Ausnahme: die vier Gewichte der Mehrzielbewertung (WP-M2-20, OP-18) stehen bewusst nicht im Pflichtenheft-Katalog
        ohne_katalog = {'opt_weight_wait', 'opt_weight_detour', 'opt_weight_empty', 'opt_weight_quality', 'cancellation_deadline_min', 'cancellation_reasons'}   # WP-M2-26: Stornoparameter (OP-23)
        self.assertEqual(names - catalog - ohne_katalog, set(), 'Parameter im Schema, die kataloge.csv nicht kennt')
        self.assertTrue(set(START) <= names)

    def test_gewichte_der_mehrzielbewertung_sind_registriert_und_begrenzt(self):  # WP-M2-20 / OPT-008
        with admin() as c:
            rows = {r[0]: r[1:] for r in c.execute("SELECT parameter, value_type, min_value, max_value, default_value FROM taktovia.policy_schema WHERE parameter LIKE 'opt_weight_%'")}
        self.assertEqual(set(rows), {'opt_weight_wait', 'opt_weight_detour', 'opt_weight_empty', 'opt_weight_quality'})
        for typ, lo, hi, _ in rows.values():
            self.assertEqual((typ, lo, hi), ('integer', 0, 100))

    def test_spalten_folgen_den_schemadateien(self):
        with admin() as c:
            rows = {r[0]: r[1] for r in c.execute("SELECT parameter, json_schema FROM taktovia.policy_schema WHERE schema_version = '1'")}
        for name, schema in rows.items():
            fn = 'service-config.v1.schema.json' if name == 'service_config' else f'{name}.v1.schema.json'
            self.assertEqual(json.loads((SCHEMAS / fn).read_text(encoding='utf-8')), schema, name)

    def test_aktuelle_version_und_unbekannte_version(self):
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT taktovia.current_policy_version()").fetchone()[0], '1')
            self.assertEqual(c.execute("SELECT taktovia.current_policy_version(now() - interval '100 years')").fetchone()[0], None)
        with self.assertRaises(psycopg.errors.CheckViolation):   # Trigger prüft vor dem Fremdschlüssel; unbekannte Version = keine Parameter bekannt
            with tx(A) as c:
                insert_revision(c, A, {'max_wait_min': 5}, version='999')


class Systemgrenzen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'policy-a'); seed_tenant(B, 'policy-b')

    def test_ungueltiger_wert_erzeugt_keine_zeile_in_config_revision(self):  # CFG-002 Abnahme
        scope = uid()
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                insert_revision(c, A, {'max_wait_min': -5}, scope='TENANT', scope_id=scope)
        self.assertEqual(count(A, scope), 0)
        # Gegenprobe: derselbe Aufruf mit gültigem Wert erzeugt genau eine Zeile
        with tx(A) as c:
            insert_revision(c, A, {'max_wait_min': 5}, scope_id=scope)
        self.assertEqual(count(A, scope), 1)

    def test_wert_ueber_systemmaximum_wird_abgelehnt(self):  # CFG-004 Abnahme
        for bad in (241, 100000):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=bad):
                with tx(A) as c:
                    insert_revision(c, A, {'max_wait_min': bad}, revision=50)

    def test_sicherheitsinvariante_kann_nicht_abgeschaltet_werden(self):  # CFG-004 AK 4
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                insert_revision(c, A, {'vehicle_readiness_require_checks': False}, revision=51)
        with tx(A) as c:   # true bleibt zulässig
            insert_revision(c, A, {'vehicle_readiness_require_checks': True}, revision=52)

    def test_falscher_typ_unbekannter_name_unzulaessige_ebene(self):
        cases = [({'max_wait_min': 'fünf'}, 'TENANT'), ({'max_wait_min': 5.5}, 'TENANT'), ({'max_wait_min': True}, 'TENANT'),
                 ({'pooling_allowed': 1}, 'TENANT'), ({'reporting_timezone': 5}, 'TENANT'), ({'reporting_timezone': 'Mars/Olympus'}, 'TENANT'),
                 ({'erfundener_parameter': 1}, 'TENANT'),
                 ({'reporting_timezone': 'Europe/Berlin'}, 'REGION'),         # Ebene Mandant
                 ({'service_config': {}}, 'TENANT'),                           # nur Ebene Dienst
                 ({'notification_quiet_hours': {'from': '22:00'}}, 'TENANT'),  # Pflichtfeld fehlt
                 ({'notification_quiet_hours': {'from': '22:00', 'to': '06:00', 'x': 1}}, 'TENANT'),
                 ([1, 2], 'TENANT')]
        for n, (values, scope) in enumerate(cases):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(values)):
                with tx(A) as c:
                    insert_revision(c, A, values, scope=scope, scope_id=uid(), revision=60 + n)

    def test_dienstkonfiguration_braucht_alle_fuenf_bestandteile(self):  # SYS-002
        good = {'service_area_id': str(uid()), 'traffic_type': 'AREA_ON_DEMAND', 'operator_id': str(uid()),
                'operating_hours': [{'weekdays': ['MON'], 'from': '06:00', 'to': '22:00'}], 'tariff_id': str(uid())}
        with tx(A) as c:
            insert_revision(c, A, {'service_config': good}, scope='SERVICE', scope_id=uid())
        for broken in ({k: v for k, v in good.items() if k != 'tariff_id'}, {**good, 'unbekannt': 1}):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c:
                    insert_revision(c, A, {'service_config': broken}, scope='SERVICE', scope_id=uid())

    def test_werte_an_den_bereichsgrenzen_sind_zulaessig(self):  # positiv: Startparameter an Min/Max
        with admin() as c:
            rows = c.execute("""SELECT parameter, value_type, min_value, max_value, const_value FROM taktovia.policy_schema
                                WHERE schema_version = '1' AND parameter = ANY(%s) AND value_type = 'integer'""", (START,)).fetchall()
        self.assertGreaterEqual(len(rows), 14)
        with admin() as c:   # Systemgrenzen oberhalb des Schema-Minimums (policy_system_floor, WP-M4-62: stop_dwell_sec)
            floors = dict(c.execute("SELECT parameter, minimum FROM taktovia.policy_system_floor()").fetchall())
        rev = 100
        for name, _, mn, mx, _ in rows:
            mn = max(mn, floors.get(name, mn))
            for v in (int(mn), int(mx)):
                rev += 1
                with tx(A) as c:
                    insert_revision(c, A, {name: v}, scope_id=uid(), revision=rev)
            for v in (int(mn) - 1, int(mx) + 1):
                rev += 1
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{name}={v}'):
                    with tx(A) as c:
                        insert_revision(c, A, {name: v}, scope_id=uid(), revision=rev)
        with tx(A) as c:
            insert_revision(c, A, {'pooling_allowed': False, 'reporting_timezone': 'Europe/Berlin',
                                   'notification_quiet_hours': {'from': '22:00', 'to': '06:00'}}, revision=300)


class Schreibschutz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'policy-a')

    def test_runtime_rolle_kann_katalog_nicht_aendern(self):  # CFG-004 AK 6 (SQL unter Runtime-Rolle)
        for sql in ("UPDATE taktovia.policy_schema SET json_schema = json_schema WHERE parameter = 'max_wait_min'",
                    "DELETE FROM taktovia.policy_schema",
                    "INSERT INTO taktovia.policy_schema(schema_version, parameter, json_schema) VALUES ('1','x_param','{}')",
                    "UPDATE taktovia.policy_schema_version SET valid_from = now()",
                    "INSERT INTO taktovia.policy_schema_version(schema_version, valid_from, description) VALUES ('2', now(), 'Mandant hebt an')"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c:
                    c.execute(sql)

    def test_freigegebene_schemaversion_ist_auch_fuer_den_migrator_unveraenderlich(self):
        with migrator() as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.policy_schema SET json_schema = json_schema || '{\"maximum\": 999}' WHERE parameter = 'max_wait_min'")
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("DELETE FROM taktovia.policy_schema WHERE parameter = 'max_wait_min'")

    def test_katalog_ist_fuer_jeden_mandanten_lesbar(self):
        for t in (A, B):
            seed_tenant(t, 'policy-' + str(t)[:4])
            with tx(t) as c:
                self.assertGreaterEqual(c.execute("SELECT count(*) FROM taktovia.policy_schema").fetchone()[0], 21)

    def test_katalog_hat_kein_tenant_id_und_keinen_mandantenbezug(self):
        with admin() as c:
            cols = {r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='policy_schema'")}
        self.assertNotIn('tenant_id', cols)


if __name__ == '__main__':
    unittest.main()
