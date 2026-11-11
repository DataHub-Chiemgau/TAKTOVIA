"""Prüfung der M2-Detailverträge (WP-M2-01): Zustandsautomaten, JSON-Schemas, Ereigniskatalog, OpenAPI-Dateien.

Positiv: Beispielpayloads validieren gegen ihre Schemas; Automaten sind vollständig beschrieben. Negativ: ein absichtlich fehlerhaftes
Beispiel (und ein absichtlich fehlerhafter Automat) lässt die Prüfung fehlschlagen.
Aufruf: python -m unittest discover -s tests/contract -p 'test_m2_*.py'
"""
import json
import re
import sys
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
VERTRAEGE = ROOT / 'docs/umsetzungsplan/vertraege'
SCHEMAS = VERTRAEGE / 'schemas'
EVENTS = ROOT / 'api/events'
sys.path.insert(0, str(ROOT / 'api/tools'))

ROW = re.compile(r'^\|\s*`([A-Z_]+)`\s*\|\s*`([A-Z_]+)`\s*\|(.+)\|(.+)\|(.+)\|\s*$')


def parse_machines(text: str) -> dict:
    """Liest je Abschnitt: Zustände, Anfangs-/Endzustände und Kanten (von, nach, Auslöser, Akteur, Fehler)."""
    out, cur = {}, None
    for line in text.splitlines():
        if line.startswith('## ') or (cur is None and line.startswith('# ')):
            cur = out.setdefault(line.lstrip('# ').strip(), {'edges': []})
        m = re.match(r'^(Zustände|Anfangszustand|Endzustände):\s*(.+)$', line)
        if m:
            cur = cur if cur is not None else out.setdefault('_', {'edges': []})
            cur[m.group(1)] = re.findall(r'`([A-Z_]+)`', m.group(2))
        r = ROW.match(line)
        if r and cur is not None:
            cur['edges'].append(tuple(x.strip() for x in r.groups()))
    return {k: v for k, v in out.items() if 'Zustände' in v}


def check_machine(name: str, m: dict) -> list[str]:
    errs, states, start, ends = [], set(m['Zustände']), m['Anfangszustand'][0], set(m['Endzustände'])
    graph: dict[str, set] = {s: set() for s in states}
    for von, nach, trigger, actor, fehler in m['edges']:
        if von not in states or nach not in states: errs.append(f'{name}: Kante {von}->{nach} mit unbekanntem Zustand')
        if not trigger.strip() or not actor.strip(): errs.append(f'{name}: Kante {von}->{nach} ohne Auslöser/Akteur')
        if not re.search(r'\b[45]\d\d `[A-Z_]+`', fehler): errs.append(f'{name}: Kante {von}->{nach} ohne Fehlercode')
        graph.setdefault(von, set()).add(nach)
    for e in ends:
        if graph.get(e): errs.append(f'{name}: Endzustand {e} hat ausgehende Kanten')
    seen, todo = {start}, [start]
    while todo:
        for n in graph.get(todo.pop(), ()):
            if n not in seen: seen.add(n); todo.append(n)
    for s in states - seen: errs.append(f'{name}: Zustand {s} vom Anfangszustand nicht erreichbar')
    for s in states - ends:
        if not graph.get(s): errs.append(f'{name}: Zustand {s} ist weder Endzustand noch hat er einen Folgezustand')
    return errs


def registry() -> Registry:
    reg = Registry()
    for f in list(SCHEMAS.glob('*.json')) + list(EVENTS.glob('*.json')):
        doc = json.loads(f.read_text())
        reg = reg.with_resource(f.name, Resource.from_contents(doc))
        if '$id' in doc: reg = reg.with_resource(doc['$id'], Resource.from_contents(doc))
    return reg


def validator(schema: dict) -> Draft202012Validator:
    return Draft202012Validator(schema, registry=registry(), format_checker=FormatChecker())


class ZustandsautomatenM2(unittest.TestCase):
    FILES = {'buchung-lokal.md': 1, 'hold.md': 1, 'fahrtenplan.md': 1, 'nachricht.md': 2, 'zahlung-ablauf.md': 1}

    def test_jeder_automat_vollstaendig_beschrieben(self):
        for f, count in self.FILES.items():
            machines = parse_machines((VERTRAEGE / 'zustaende' / f).read_text())
            self.assertEqual(len(machines), count, f)
            for name, m in machines.items():
                self.assertEqual(check_machine(f'{f}/{name}', m), [])

    def test_hold_hat_die_vier_zustaende_der_datenbank(self):
        m = next(iter(parse_machines((VERTRAEGE / 'zustaende/hold.md').read_text()).values()))
        self.assertEqual(set(m['Zustände']), {'HELD', 'CONSUMED', 'RELEASED', 'EXPIRED'})

    def test_unbestimmtes_ist_eigener_zustand(self):
        z = lambda f: {s for m in parse_machines((VERTRAEGE / 'zustaende' / f).read_text()).values() for s in m['Zustände']}
        self.assertIn('RECONCILING', z('buchung-lokal.md'))
        self.assertTrue({'CAPTURE_UNKNOWN', 'RECONCILING'} <= z('zahlung-ablauf.md'))
        self.assertIn('UNKNOWN', z('nachricht.md'))

    def test_lokale_buchung_nutzt_nur_kanten_des_registrierten_automaten(self):
        reg = parse_machines((VERTRAEGE / 'zustaende/buchung.md').read_text() + '\n## x\nZustände: `A`\n')  # Tabelle des Rahmens: von | Folgezustände
        erlaubt = {}
        for line in (VERTRAEGE / 'zustaende/buchung.md').read_text().splitlines():
            m = re.match(r'^\|\s*`([A-Z_]+)`\s*\|(.+)\|\s*$', line)
            if m: erlaubt[m.group(1)] = set(re.findall(r'`([A-Z_]+)`', m.group(2)))
        lokal = next(iter(parse_machines((VERTRAEGE / 'zustaende/buchung-lokal.md').read_text()).values()))
        for von, nach, *_ in lokal['edges']:
            self.assertIn(nach, erlaubt.get(von, set()), f'{von}->{nach} nicht im registrierten Automaten `booking`')
        self.assertIsNotNone(reg)

    def test_zahlungsablauf_deckt_sich_mit_registriertem_automaten(self):
        erlaubt = {}
        for line in (VERTRAEGE / 'zustaende/zahlung.md').read_text().splitlines():
            m = re.match(r'^\|\s*`([A-Z_]+)`\s*\|(.+)\|\s*$', line)
            if m: erlaubt[m.group(1)] = set(re.findall(r'`([A-Z_]+)`', m.group(2)))
        ablauf = next(iter(parse_machines((VERTRAEGE / 'zustaende/zahlung-ablauf.md').read_text()).values()))
        kanten = {(v, n) for v, n, *_ in ablauf['edges']}
        self.assertEqual(kanten, {(v, n) for v, ns in erlaubt.items() for n in ns})

    def test_fehlerhafter_automat_wird_erkannt(self):
        kaputt = ('# x\nZustände: `A`, `B`, `C`\nAnfangszustand: `A`\nEndzustände: `B`\n\n'
                  '| von | nach | Auslöser | Akteur | Fehler |\n|---|---|---|---|---|\n'
                  '| `A` | `B` | x | System |  |\n| `B` | `A` | y | System | 409 `X_Y` |\n')
        errs = check_machine('kaputt', next(iter(parse_machines(kaputt).values())))
        self.assertTrue(any('ohne Fehlercode' in e for e in errs), errs)
        self.assertTrue(any('Endzustand B hat ausgehende' in e for e in errs), errs)
        self.assertTrue(any('Zustand C' in e for e in errs), errs)


class SchemasUndBeispiele(unittest.TestCase):
    NEU = ['resource-vector.v1', 'fare-quote-snapshot.v1', 'stop-accessibility.v1']
    PARAMS = ['max_wait_min', 'max_detour_pct', 'min_booking_lead_min', 'max_booking_horizon_days', 'pickup_window_min', 'arrival_grace_min',
              'stop_walk_max_m', 'pooling_allowed', 'min_capacity_reserve', 'wheelchair_boarding_sec', 'stop_dwell_sec', 'customer_notify_before_pickup_min']

    def test_beispiele_validieren(self):
        for n in self.NEU:
            schema = json.loads((SCHEMAS / f'{n}.schema.json').read_text())
            Draft202012Validator.check_schema(schema)
            self.assertTrue(schema['examples'])
            for ex in schema['examples']: validator(schema).validate(ex)

    def test_ressourcenvektor_negativ(self):
        v = validator(json.loads((SCHEMAS / 'resource-vector.v1.schema.json').read_text()))
        for bad in ({'seats': 1, 'rollstuhl': 1}, {'seats': -1}, {'wheelchair': 'eins'}, {'equipment': ['DIAGNOSIS_X']}, {'equipment': ['RAMP', 'RAMP']}, {'schema_version': '2.0'}):
            with self.assertRaises(ValidationError, msg=str(bad)): v.validate(bad)

    def test_alle_zwoelf_parameter_schemas_vorhanden(self):
        for p in self.PARAMS:
            s = json.loads((SCHEMAS / f'{p}.v1.schema.json').read_text())
            Draft202012Validator.check_schema(s)
            self.assertIn('x-unit', s, p); self.assertIn('x-levels', s, p)

    def test_stop_barrierefreiheit_ungeprueft_ist_gueltig_aber_unbekannter_wert_nicht(self):
        v = validator(json.loads((SCHEMAS / 'stop-accessibility.v1.schema.json').read_text()))
        v.validate({'schema_version': '1.0', 'step_free': 'UNVERIFIED', 'wheelchair_boarding': 'UNVERIFIED'})
        with self.assertRaises(ValidationError): v.validate({'schema_version': '1.0', 'step_free': 'MAYBE', 'wheelchair_boarding': 'YES'})


class EreigniskatalogM2(unittest.TestCase):
    TYPEN = ['booking.attempt_recorded', 'booking.offer_created', 'booking.hold_placed', 'booking.hold_released', 'booking.hold_expired', 'booking.confirmed',
             'booking.cancelled', 'dispatch.run_revised', 'payment.refund_recorded', 'communication.notification_sent', 'communication.notification_failed']

    def test_jedes_ereignis_hat_schema_beispiel_und_katalogzeile(self):
        katalog = (VERTRAEGE / 'm2-ereigniskatalog.md').read_text()
        for t in self.TYPEN:
            f = EVENTS / f'{t}.v1.schema.json'
            schema = json.loads(f.read_text())
            Draft202012Validator.check_schema(schema)
            self.assertIn(f'`{t}`', katalog)
            for ex in schema['examples']: validator(schema).validate(ex)

    def test_keine_adressen_oder_kontaktdaten_im_payload(self):
        verboten = re.compile(r'(?i)(address|adresse|street|strasse|email|e_mail|phone|telefon|name$|latitude|longitude|point)')
        for t in self.TYPEN:
            schema = json.loads((EVENTS / f'{t}.v1.schema.json').read_text())
            for prop in schema['properties']:
                self.assertIsNone(verboten.search(prop), f'{t}: Feld {prop}')
            self.assertFalse(schema.get('additionalProperties', True), t)

    def test_fehlerhaftes_beispiel_wird_abgewiesen(self):
        schema = json.loads((EVENTS / 'booking.hold_placed.v1.schema.json').read_text())
        bad = dict(schema['examples'][0], capacity_vector={'seats': 1, 'rollstuhl': 2})
        with self.assertRaises(ValidationError): validator(schema).validate(bad)
        bad2 = dict(schema['examples'][0], exakte_adresse='Musterstr. 1')
        with self.assertRaises(ValidationError): validator(schema).validate(bad2)

    def test_ablehnung_traegt_primaergrund(self):
        schema = json.loads((EVENTS / 'booking.attempt_recorded.v1.schema.json').read_text())
        self.assertIn('EQUIPMENT_MISSING', schema['properties']['primary_reason']['enum'])


class OpenApiM2(unittest.TestCase):
    OPS = {'booking': ['createRideOffer', 'getRideOffer', 'holdRideOffer', 'releaseHold', 'confirmBooking', 'getBooking', 'listBookings', 'cancelBooking'],
           'geo': ['listServiceAreas', 'createServiceArea', 'listStops', 'createStop'],
           'payments': ['receiveProviderEvent'], 'notifications': ['listNotifications', 'getNotification']}

    def test_operationen_vorhanden_und_nach_konvention(self):
        from lint import lint_doc
        from bundle import bundle
        doc = bundle()
        self.assertEqual(lint_doc(doc), [])
        ids = {o['operationId']: (p, m, o) for p, it in doc['paths'].items() for m, o in it.items() if isinstance(o, dict) and 'operationId' in o}
        for mod, ops in self.OPS.items():
            for oid in ops:
                self.assertIn(oid, ids, f'{mod}: {oid}')
        pfade = {p for p, _, _ in ids.values()}
        for p in ['/v1/ride-offers', '/v1/bookings', '/v1/service-areas', '/v1/stops', '/v1/payments/provider-events/{provider}', '/v1/notifications']:
            self.assertIn(p, pfade)

    def test_fehlercodes_und_konflikte_dokumentiert(self):
        doc = yaml.safe_load((ROOT / 'api/booking.yaml').read_text())
        hold = doc['paths']['/v1/ride-offers/{ride_offer_id}/hold']['post']
        self.assertTrue({'409', '412', '428', '404'} <= set(hold['responses']))
        self.assertIn('OFFER_EXPIRED', hold['description']); self.assertIn('CAPACITY_UNAVAILABLE', hold['description'])
        confirm = doc['paths']['/v1/bookings']['post']
        self.assertIn('HOLD_EXPIRED', confirm['description']); self.assertIn('REQUOTE_REQUIRED', confirm['description'])
        common = yaml.safe_load((ROOT / 'api/common.yaml').read_text())
        codes = common['components']['schemas']['ErrorCode']['enum']
        for c in ['OFFER_EXPIRED', 'HOLD_EXPIRED', 'REQUOTE_REQUIRED', 'CAPACITY_UNAVAILABLE', 'OPERATION_IN_PROGRESS', 'REVISION_MISMATCH', 'PRECONDITION_REQUIRED', 'NOT_FOUND', 'PROVIDER_UNAVAILABLE', 'IDEMPOTENCY_CONFLICT']:
            self.assertIn(c, codes)

    def test_unterstuetzungsbedarf_kennt_keine_diagnosefelder(self):
        doc = yaml.safe_load((ROOT / 'api/booking.yaml').read_text())
        needs = doc['components']['schemas']['RideSupportNeeds']
        self.assertFalse(needs['additionalProperties'])
        self.assertFalse([k for k in needs['properties'] if re.search(r'(?i)diagn|krank|disease|condition', k)])

    def test_fehlerhaftes_beispiel_laesst_lint_fehlschlagen(self):
        from lint import lint_doc
        from bundle import bundle
        doc = bundle()
        doc['components']['schemas']['RideCancelRequest']['examples'] = [{'reason': 'KEIN_GRUND'}]
        self.assertTrue(any('RideCancelRequest' in e and 'Beispiel' in e for e in lint_doc(doc)))


if __name__ == '__main__':
    unittest.main()
