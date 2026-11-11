"""WP-M1-18: JSON-Schema der Zeittypen (docs/umsetzungsplan/vertraege/schemas/zeit.v1.schema.json) und seine Übereinstimmung mit api/common.yaml."""
import json
import sys
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker

REPO = Path(__file__).resolve().parents[3]
SCHEMA = json.loads((REPO / 'docs/umsetzungsplan/vertraege/schemas/zeit.v1.schema.json').read_text())
COMMON = yaml.safe_load((REPO / 'api/common.yaml').read_text())['components']['schemas']


def validator(name):
    return Draft202012Validator({'$ref': f'#/$defs/{name}', '$defs': SCHEMA['$defs']}, format_checker=FormatChecker())


class ZeitSchema(unittest.TestCase):
    def test_schema_ist_gueltig_und_beispiele_validieren(self):
        Draft202012Validator.check_schema(SCHEMA)
        for name, d in SCHEMA['$defs'].items():
            for ex in d['examples']:
                self.assertEqual(list(validator(name).iter_errors(ex)), [], (name, ex))

    def test_ohne_offset_und_unbekannte_felder_werden_abgewiesen(self):
        self.assertTrue(list(validator('Timestamp').iter_errors('2026-10-25T02:30:00')))
        self.assertTrue(list(validator('Timestamp').iter_errors('2026-10-25 02:30:00+02:00')))
        self.assertTrue(list(validator('TimeZone').iter_errors('CET')))
        self.assertTrue(list(validator('TimeZone').iter_errors('+01:00')))
        self.assertTrue(list(validator('ZonedMoment').iter_errors({'desired_at': '2026-10-25T02:30:00+01:00', 'time_zone': 'Europe/Berlin', 'extra': 1})))
        self.assertTrue(list(validator('ZonedMoment').iter_errors({'desired_at': '2026-10-25T02:30:00+01:00'})))
        self.assertTrue(list(validator('ValidityInterval').iter_errors({'valid_from': '2026-12-01T00:00:00'})))

    def test_api_komponenten_verwenden_dieselben_muster_und_beispiele(self):
        for name in ('Timestamp', 'TimeZone', 'ZonedMoment', 'LocalDateTimeInZone', 'ValidityInterval'):
            self.assertIn(name, COMMON)
            self.assertIn('x-time', COMMON[name], name)
        for name in ('ZonedMoment', 'LocalDateTimeInZone', 'ValidityInterval'):
            for ex in COMMON[name]['examples']:
                self.assertEqual(list(validator(name).iter_errors(ex)), [], (name, ex))
        self.assertEqual(COMMON['TimeZone']['x-time'], 'zone')
        self.assertIn('Europe/Berlin', COMMON['TimeZone']['examples'])


if __name__ == '__main__':
    unittest.main()
