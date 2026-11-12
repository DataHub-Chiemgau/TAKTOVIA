"""Prüfung der M3-Detailverträge (WP-M3-01): Zustandsautomaten, Ereignisschemas, OpenAPI-Verträge, Offene Punkte, DDL-Entwurf.

Positiv: Beispiele validieren, Automaten sind vollständig, jede der 48 Anforderungen hat einen Detailvertrag. Negativ: je Endpunkt mit
Anfragekörper wird eine bewusst fehlerhafte Nutzlast (zusätzliches Feld `tenant_id`, fehlendes Pflichtfeld, unzulässiger Wert) vom Schema
abgewiesen; ein absichtlich fehlerhafter Automat und ein fehlerhaftes Ereignisbeispiel lassen die Prüfung fehlschlagen.
Aufruf: python -m unittest discover -s tests/contract -p 'test_m3_*.py'
"""
import csv
import json
import os
import re
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'api/tools'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from bundle import bundle  # noqa: E402
from test_m2_vertraege import check_machine, parse_machines, validator  # noqa: E402

VERTRAEGE = ROOT / 'docs/umsetzungsplan/vertraege'
EVENTS = ROOT / 'api/events'
M3_FILES = {'inspection_run.md': 1, 'defect_case.md': 1, 'fahrzeug_dienststatus.md': 1, 'fahrzeug_sperre.md': 1, 'einsatzfreigabe.md': 1,
            'reparaturauftrag.md': 1, 'incident.md': 1}
M3_EVENTS = ['fleet.vehicle_blocked', 'fleet.vehicle_block_lifted', 'fleet.release_issued', 'fleet.release_revoked', 'inspection.completed', 'defect.reported',
             'defect.triaged', 'repair.order_created', 'driver.event_recorded', 'driver.run_acknowledged', 'vehicle.service_status_changed', 'incident.opened', 'alert.raised']
FORBIDDEN_PAYLOAD_FIELDS = re.compile(r'(?i)(description|diagnos|note|text|name|email|phone|contact|address|photo|image|latitude|longitude|position|passenger|comment)')

U1, U2, U3 = '11111111-2222-4333-8444-555555555555', '66666666-7777-4888-8999-aaaaaaaaaaaa', 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff'
T0 = '2026-11-12T08:00:00Z'

# gültige Beispielkörper je Request-Schema (Name im gebündelten Vertrag)
VALID = {
    'VehicleBlockCreate': {'reason_code': 'MANUAL', 'comment': 'Fahrzeug steht'},
    'VehicleBlockLift': {'comment': 'wieder fahrbereit'},
    'InspectionCreate': {'shift_id': U1, 'vehicle_id': U2, 'template_revision_id': U3, 'kind': 'PRE_SHIFT', 'client_started_at': T0},
    'InspectionDraft': {'answers': [{'step_code': 'TIRES', 'answer': 'OK', 'answered_at': T0}]},
    'InspectionSubmit': {'answers': [{'step_code': 'TIRES', 'answer': 'OK', 'answered_at': T0}], 'client_submitted_at': T0},
    'DefectCreate': {'vehicle_id': U1, 'occurred_at': T0, 'category_code': 'BRAKES', 'description': 'Bremse zieht'},
    'DefectTriage': {'severity': 'SAFETY_CRITICAL', 'decision': 'REVIEWED'},
    'DefectTransition': {'to': 'REVIEWED'},
    'DefectAmendment': {'change_reason': 'Beschreibung präzisiert', 'description': 'Kratzer links'},
    'RepairOrderCreate': {'defect_id': U1},
    'VehicleReleaseCreate': {'repair_order_id': U1, 'follow_up_inspection_id': U2, 'reason': 'Reparatur nachgewiesen'},
    'IncidentTransition': {'event': 'START_REPLACEMENT_SEARCH'},
    'RunRevisionCreate': {'changes': [{'route_stop_id': U1, 'change': 'MOVE', 'new_sequence': 2}], 'reason': 'Disposition', 'base_plan_revision': 3},
    'DriverEvent': {'event_id': U1, 'shift_id': U2, 'vehicle_id': U3, 'plan_revision': 4, 'event_type': 'CHECK_IN', 'booking_id': U1, 'route_stop_id': U2,
                    'occurred_at': T0, 'device_recorded_at': T0, 'offline_recorded': True},
    'DriverPositionBatch': {'shift_id': U1, 'vehicle_id': U2, 'device_id': U3,
                            'samples': [{'position': {'latitude': 52.5, 'longitude': 13.4}, 'accuracy_meters': 8.0, 'measured_at': T0}]},
    'DriverRunAck': {'plan_revision': 4, 'acknowledged_at': T0},
    'DriverDeviceRegister': {'device_id': U1, 'platform': 'ANDROID', 'app_version': '1.0.0'},
    # WP-M3-03/-04: Fahrzeugstamm und Kapazitätskonfiguration
    'VehicleMasterWrite': {'vehicle_label': 'KA-100', 'vehicle_type': 'PKW', 'operator_organization_id': U1, 'capacity': {'seats': 4, 'wheelchair': 1}, 'equipment': ['RAMP'], 'active': True},
    'CapacityPatch': {'combinations': [{'code': 'STANDARD', 'wheelchair_places': 0}], 'active_combination': 'STANDARD'},
    'VehicleServiceAssignmentCreate': {'service_id': U1, 'service_area_id': None, 'valid_from': '2026-12-01T00:00:00Z', 'valid_to': None},
    'VehicleServiceAssignmentEnd': {'valid_to': '2027-01-01T00:00:00Z'},
}
# Je Schema: Mutation, die ein Pflichtfeld entfernt, bzw. einen unzulässigen Wert setzt
BROKEN = {
    'VehicleBlockCreate': ('reason_code', 'SAFETY'),            # Quelle SAFETY darf kein Client setzen
    'VehicleBlockLift': ('comment', 'x' * 501),
    'InspectionCreate': ('kind', 'ANYTIME'),
    'InspectionDraft': ('answers', 'kein-array'),
    'InspectionSubmit': ('answers', []),
    'DefectCreate': ('vehicle_id', 'kein-uuid'),
    'DefectTriage': ('severity', 'S9'),
    'DefectAmendment': ('change_reason', ''),                   # Änderungsgrund ist Pflicht
    'DefectTransition': ('to', 'NEW'),                          # Rücksprung nach NEW ist kein Ziel
    'RepairOrderCreate': ('defect_id', None),
    'VehicleReleaseCreate': ('reason', ''),
    'IncidentTransition': ('event', 'CLOSED'),                  # Zustand statt Ereignis
    'RunRevisionCreate': ('changes', []),
    'DriverEvent': ('event_type', 'TELEPORT'),
    'DriverPositionBatch': ('samples', []),
    'DriverRunAck': ('plan_revision', 0),
    'DriverDeviceRegister': ('platform', 'WINDOWS'),
    'VehicleMasterWrite': ('capacity', {'seats': -1, 'wheelchair': 1}),
    'CapacityPatch': ('active_combination', 'klein'),
    'VehicleServiceAssignmentCreate': ('valid_from', 'gestern'),
    'VehicleServiceAssignmentEnd': ('valid_to', 'morgen'),
}


def schema_validator(doc, name):
    return Draft202012Validator({'$ref': f'#/components/schemas/{name}', 'components': doc['components']}, format_checker=FormatChecker())


class ZustandsautomatenM3(unittest.TestCase):
    def test_jeder_automat_vollstaendig_beschrieben(self):
        for f, count in M3_FILES.items():
            machines = parse_machines((VERTRAEGE / 'zustaende' / f).read_text())
            self.assertEqual(len(machines), count, f)
            for name, m in machines.items():
                self.assertEqual(check_machine(f'{f}/{name}', m), [])

    def test_jeder_automat_nennt_rolle_je_uebergang_und_ein_negativbeispiel(self):
        for f in M3_FILES:
            text = (VERTRAEGE / 'zustaende' / f).read_text()
            m = next(iter(parse_machines(text).values()))
            for von, nach, trigger, actor, _ in m['edges']:
                self.assertTrue(re.search(r'[A-Za-zÄÖÜäöüß]{4,}', actor), f'{f}: {von}->{nach} ohne Rolle')
            self.assertIn('## Negativbeispiele', text, f)
            neg = re.findall(r'`([A-Z_]+)` -> `([A-Z_]+)`', text.split('## Negativbeispiele')[1])
            self.assertTrue(neg, f'{f}: kein unzulässiger Übergang als Negativbeispiel')
            edges = {(v, n) for v, n, *_ in m['edges']}
            for pair in neg:
                self.assertNotIn(pair, edges, f'{f}: Negativbeispiel {pair} ist ein zulässiger Übergang')

    def test_schadenakte_hat_die_sechs_statuswerte_des_pflichtenhefts(self):
        m = next(iter(parse_machines((VERTRAEGE / 'zustaende/defect_case.md').read_text()).values()))
        self.assertEqual(set(m['Zustände']), {'NEW', 'REVIEWED', 'IN_PROGRESS', 'REPAIR', 'CLOSED', 'DISMISSED'})
        self.assertNotIn(('NEW', 'CLOSED'), {(v, n) for v, n, *_ in m['edges']})

    def test_dienststatus_hat_die_sechs_zustaende_des_pflichtenhefts(self):
        m = next(iter(parse_machines((VERTRAEGE / 'zustaende/fahrzeug_dienststatus.md').read_text()).values()))
        self.assertTrue({'AVAILABLE', 'EN_ROUTE', 'WAITING', 'BREAK', 'DISRUPTED', 'ENDED'} <= set(m['Zustände']))
        # ohne Freigabe kein direkter Weg in die Disposition: einziger Weg nach AVAILABLE aus NOT_READY ist die Bereitmeldung
        self.assertEqual([e for e in m['edges'] if e[1] == 'AVAILABLE' and e[0] == 'NOT_READY'][0][2].startswith('Bereitmeldung nach serverbestätigter'), True)

    def test_fehlerhafter_automat_wird_erkannt(self):
        kaputt = ('# x\nZustände: `A`, `B`, `C`\nAnfangszustand: `A`\nEndzustände: `B`\n\n'
                  '| von | nach | Auslöser | Akteur | Fehler |\n|---|---|---|---|---|\n'
                  '| `A` | `B` | x | System |  |\n| `B` | `A` | y | System | 409 `X_Y` |\n')
        errs = check_machine('kaputt', next(iter(parse_machines(kaputt).values())))
        self.assertTrue(any('ohne Fehlercode' in e for e in errs), errs)
        self.assertTrue(any('Endzustand B hat ausgehende Kanten' in e for e in errs), errs)


class EreignisschemasM3(unittest.TestCase):
    def schema(self, name):
        return json.loads((EVENTS / f'{name}.v1.schema.json').read_text())

    def test_beispiele_gueltig_und_schemata_geschlossen(self):
        for name in M3_EVENTS:
            s = self.schema(name)
            self.assertIs(s['additionalProperties'], False, name)
            self.assertTrue(s['examples'], name)
            for ex in s['examples']:
                validator(s).validate(ex)
            self.assertEqual(set(s['required']), set(s['properties']), f'{name}: alle Felder Pflicht (feste Nutzlast)')

    def test_minimierung_keine_diagnosen_namen_oder_ortsdaten(self):
        for name in M3_EVENTS:
            for field in self.schema(name)['properties']:
                self.assertIsNone(FORBIDDEN_PAYLOAD_FIELDS.search(field), f'{name}: Feld {field} verletzt die Minimierungsregel')

    def test_fehlerhafte_nutzlast_wird_abgewiesen(self):
        for name in M3_EVENTS:
            s = self.schema(name)
            ex = s['examples'][0]
            v = validator(s)
            self.assertFalse(v.is_valid({**ex, 'description': 'Bremse defekt'}), f'{name}: Zusatzfeld muss abgewiesen werden')
            self.assertFalse(v.is_valid({k: x for k, x in ex.items() if k != next(iter(ex))}), f'{name}: fehlendes Pflichtfeld')
        s = self.schema('defect.triaged')
        self.assertFalse(validator(s).is_valid({**s['examples'][0], 'severity': 'S9'}))

    def test_katalog_nennt_jedes_ereignis(self):
        katalog = (VERTRAEGE / 'm3-ereigniskatalog.md').read_text()
        for name in M3_EVENTS:
            self.assertIn(f'`{name}`', katalog)


class OpenApiM3(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = bundle()
        cls.ops = {o['operationId']: (m, p, o) for p, it in cls.doc['paths'].items() for m, o in it.items() if isinstance(o, dict) and 'operationId' in o}

    def test_alle_neuen_operationen_vorhanden_mit_sicherheit_und_problem(self):
        erwartet = ['listOperatorVehicles', 'createVehicleBlock', 'liftVehicleBlock', 'listChecklistTemplates', 'createInspection', 'getInspection', 'saveInspectionDraft',
                    'submitInspection', 'listDefects', 'createDefect', 'getDefect', 'triageDefect', 'listRepairOrders', 'createRepairOrder', 'createVehicleRelease',
                    'listOperatorIncidents', 'getOperatorIncident', 'transitionOperatorIncident', 'listRunRevisions', 'createRunRevision', 'recordDriverEvent',
                    'recordDriverPosition', 'getDriverRun', 'acknowledgeDriverRunRevision', 'registerDriverDevice', 'revokeDriverDevice']
        for oid in erwartet:
            self.assertIn(oid, self.ops, oid)
            _, path, op = self.ops[oid]
            self.assertTrue(op['security'], oid)
            self.assertIn('404', op['responses'] if '{' in path or oid.startswith('create') else {'404': 1}, oid)

    def test_kap_5_7_pfade_vorhanden(self):
        for pfad in ['/v1/operator/vehicles', '/v1/operator/incidents', '/v1/runs/{run_id}/revisions', '/v1/driver/shifts/{shift_id}/run', '/v1/inspections',
                     '/v1/inspections/{inspection_id}/submit', '/v1/defects', '/v1/defects/{defect_id}/triage', '/v1/repair-orders', '/v1/vehicles/{vehicle_id}/release', '/v1/driver/events']:
            self.assertIn(pfad, self.doc['paths'], pfad)

    def test_mutierende_operationen_idempotenz_und_if_match(self):
        for oid in ['createVehicleBlock', 'liftVehicleBlock', 'createInspection', 'saveInspectionDraft', 'submitInspection', 'createDefect', 'triageDefect', 'createRepairOrder',
                    'createVehicleRelease', 'transitionOperatorIncident', 'createRunRevision', 'recordDriverPosition', 'acknowledgeDriverRunRevision', 'registerDriverDevice', 'revokeDriverDevice']:
            m, path, op = self.ops[oid]
            names = str(op['parameters'])
            self.assertIn('IdempotencyKey', names, oid)
            if '{' in path: self.assertIn('IfMatch', names, oid)
            if '{' in path: self.assertIn('412', op['responses'], oid)
        for oid in ['getInspection', 'getDefect', 'getOperatorIncident', 'getDriverRun']:
            self.assertIn('ETag', str(self.ops[oid][2]['responses']['200']['headers']), oid)

    def test_listen_haben_die_listenform(self):
        for oid in ['listOperatorVehicles', 'listChecklistTemplates', 'listDefects', 'listRepairOrders', 'listOperatorIncidents', 'listRunRevisions']:
            schema = self.ops[oid][2]['responses']['200']['content']['application/json']['schema']['$ref'].split('/')[-1]
            self.assertEqual(set(self.doc['components']['schemas'][schema]['required']), {'items', 'next_cursor', 'as_of'}, oid)

    def test_gueltige_nutzlast_je_endpunkt_wird_angenommen(self):
        for name, body in VALID.items():
            schema_validator(self.doc, name).validate(body)

    def test_fehlerhafte_nutzlast_je_endpunkt_wird_abgewiesen(self):
        mit_body = {oid: op['requestBody']['content']['application/json']['schema']['$ref'].split('/')[-1] for oid, (m, p, op) in self.ops.items()
                    if 'requestBody' in op and p.startswith(('/v1/operator', '/v1/inspections', '/v1/defects', '/v1/repair', '/v1/vehicles', '/v1/runs', '/v1/driver/'))}
        self.assertEqual(set(mit_body.values()), set(VALID), 'jedes Request-Schema der M3-Endpunkte hat ein gültiges und ein fehlerhaftes Beispiel')
        for oid, name in mit_body.items():
            v = schema_validator(self.doc, name)
            self.assertTrue(v.is_valid(VALID[name]), f'{oid}: gültiges Beispiel')
            self.assertFalse(v.is_valid({**VALID[name], 'tenant_id': U1}), f'{oid}: tenant_id im Body muss abgewiesen werden')
            self.assertFalse(v.is_valid({**VALID[name], 'unbekannt': 1}), f'{oid}: unbekanntes Feld')
            feld, wert = BROKEN[name]
            self.assertFalse(v.is_valid({**VALID[name], feld: wert}), f'{oid}: unzulässiger Wert für {feld}')
            self.assertFalse(v.is_valid({}), f'{oid}: leerer Körper') if self.doc['components']['schemas'][name].get('required') else None

    def test_driver_event_erweiterung_ist_additiv(self):
        v = schema_validator(self.doc, 'DriverEvent')
        basis = dict(VALID['DriverEvent'])
        arrival = {k: x for k, x in basis.items() if k not in ('booking_id', 'route_stop_id')} | {'event_type': 'ARRIVAL'}
        self.assertTrue(v.is_valid(arrival), 'ARRIVAL braucht keine Buchung')
        self.assertTrue(v.is_valid(basis | {'event_type': 'BREAK_START', 'booking_id': None, 'route_stop_id': None}))
        # Pflicht von booking_id/route_stop_id für CHECK_IN/OUT/NO_SHOW prüft der Server (DriverSyncTest.checkin_ohne_buchung_wird_abgewiesen)
        self.assertIn('booking_id', self.doc['components']['schemas']['DriverEvent']['properties'])

    def test_fahrergeraet_und_status_sind_keine_clientgesetzten_zustaende(self):
        # Antworten tragen Zustände, Anfragen nie ein state/status-Feld (Request-Schema-Politik SYS-011)
        for name in VALID:
            props = self.doc['components']['schemas'][name].get('properties', {})
            self.assertFalse({'state', 'status'} & set(props), name)


class VertragsabdeckungM3(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with (ROOT / 'docs/umsetzungsplan/arbeitspakete.csv').open(encoding='utf-8') as f:
            cls.rows = [r for r in csv.DictReader(f, delimiter=';') if r['meilenstein'] == 'M3']

    def test_alle_48_anforderungen_haben_detailvertrag(self):
        self.assertEqual(len(self.rows), 48)
        for r in self.rows:
            pfad = ROOT / r['detailvertrag'].split(';')[0].split('#')[0]
            self.assertTrue(pfad.is_file(), f'{r["source_key"]}: Detailvertrag fehlt')
            self.assertIn(r['source_key'], pfad.read_text().splitlines()[0], r['source_key'])

    def test_offene_punkte_01_bis_34_haben_entscheidung_und_verantwortlichen(self):
        text = (VERTRAEGE / 'm3-detailvertraege.md').read_text()
        for n in range(1, 35):
            m = re.search(rf'^\| {n:02d} \| (.+?) \| (.+?) \| (.+?) \|$', text, re.M)
            self.assertIsNotNone(m, f'OP-{n:02d} fehlt')
            self.assertGreater(len(m.group(1)), 20, f'OP-{n:02d}: Entscheidung fehlt')
            self.assertTrue(m.group(2).strip(), f'OP-{n:02d}: Verantwortlicher fehlt')

    def test_rechtematrix_hat_zeile_je_operation_mit_passendem_scope(self):
        rows = [l for l in (ROOT / 'backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv').read_text().splitlines() if l and not l.startswith('#')]
        matrix = {r.split(';')[0]: r.split(';')[1] for r in rows[1:]}
        doc = bundle()
        for p, it in doc['paths'].items():
            for m, o in it.items():
                if isinstance(o, dict) and o.get('operationId') in matrix and 'oidc' in o['security'][0]:
                    self.assertEqual(matrix[o['operationId']], o['security'][0]['oidc'][0], o['operationId'])


@unittest.skipUnless(os.environ.get('TAKTOVIA_DB_NAME') and os.environ.get('TAKTOVIA_DB_ADMIN_USER'), 'keine Datenbank konfiguriert')
class DdlEntwurfM3(unittest.TestCase):
    def test_ddl_entwurf_ist_ausfuehrbar_und_rls_ist_aktiv(self):
        """Führt den Entwurf gegen den Stand nach M2 aus und macht alles rückgängig (nichts bleibt bestehen). Ist der Entwurf bereits migriert (WP-M3-02, V3020), prüft der Test
        stattdessen, dass jede Tabelle des Entwurfs im migrierten Schema existiert und RLS erzwungen ist (der Entwurf ist dann nicht mehr ausführbar: die Tabellen bestehen)."""
        sys.path.insert(0, str(ROOT / 'db'))
        import db as dbtool
        sql = (VERTRAEGE / 'm3-ddl-entwurf.sql').read_text()
        with dbtool.admin(os.environ['TAKTOVIA_DB_NAME']) as c:
            migriert = c.execute("SELECT to_regclass('taktovia.vehicle_block')").fetchone()[0] is not None
            if migriert:
                tabellen = re.findall(r'^CREATE TABLE (\w+) \(', sql, flags=re.M)
                self.assertGreaterEqual(len(tabellen), 18)
                rows = dict((r[0], (r[1], r[2])) for r in c.execute("SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                                                                    "WHERE n.nspname = 'taktovia' AND c.relname = ANY(%s)", (tabellen,)).fetchall())
                self.assertEqual(set(rows), set(tabellen), 'jede Tabelle des Entwurfs ist migriert')
                self.assertTrue(all(v == (True, True) for v in rows.values()), rows)
                return
            c.autocommit = False
            try:
                c.execute(sql)
                rows = c.execute("SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                                 "WHERE n.nspname = 'taktovia' AND c.relname = ANY(%s)", (['vehicle_block', 'inspection_run', 'defect_case', 'incident', 'booking_change', 'position_sample'],)).fetchall()
                self.assertEqual(len(rows), 6)
                self.assertTrue(all(r[1] and r[2] for r in rows), rows)
            finally:
                c.rollback()
            existiert = c.execute("SELECT to_regclass('taktovia.vehicle_block')").fetchone()[0]
            self.assertIsNone(existiert, 'der Entwurf darf nichts hinterlassen')

if __name__ == '__main__':
    unittest.main()
