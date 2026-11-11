import copy, json, shutil, sys, tempfile, unittest
from pathlib import Path
import yaml
import bundle, lint

API = bundle.API


class ApiTools(unittest.TestCase):
    def doc(self): return copy.deepcopy(bundle.bundle())

    def test_bundle_erweitert_ausgangsstand_kompatibel(self):
        import breaking
        base = yaml.safe_load(bundle.DOCS.read_text())
        self.assertEqual((len(base['paths']), len(base['components']['schemas'])), (14, 42))
        self.assertEqual(breaking.compare(base, bundle.bundle()), [])
        d = self.doc()
        self.assertGreaterEqual(len(d['paths']), 14)
        ids = [op['operationId'] for i in d['paths'].values() for m, op in i.items() if m in ('get', 'post', 'put', 'patch', 'delete')]
        self.assertEqual(len(ids), len(set(ids)))

    def test_ausgangsstand_ist_lintfrei(self):
        self.assertEqual(lint.lint_doc(self.doc()), [])
        self.assertEqual(lint.lint_events(API / 'events'), [])

    def test_sicherheitsschemata_und_scopes_unveraendert(self):
        d = self.doc()
        self.assertEqual(set(d['components']['securitySchemes']), {'oidc', 'partnerSignature', 'providerSignature'})
        self.assertEqual(d['paths']['/v1/federation/journeys/plan']['post']['security'], [{'oidc': ['journeys:plan']}])

    def test_negativ_pfad_ohne_v1(self):
        d = self.doc(); d['paths']['/health'] = {'get': {'operationId': 'h', 'security': [{'oidc': []}], 'responses': {'200': {'description': 'x'}}}}
        self.assertTrue(any('/v1' in e for e in lint.lint_doc(d)))

    def test_negativ_mutation_ohne_idempotency_key(self):
        d = self.doc(); d['paths']['/v1/x'] = {'post': {'operationId': 'x', 'security': [{'oidc': []}], 'responses': {'200': {'description': 'x'}}}}
        self.assertTrue(any('Idempotency-Key' in e for e in lint.lint_doc(d)))

    def test_negativ_revisionseingriff_ohne_if_match(self):
        d = self.doc()
        op = d['paths']['/v1/rosters/{roster_id}/publish']['post']
        op['parameters'] = [p for p in op['parameters'] if 'IfMatch' not in p.get('$ref', '')]
        self.assertTrue(any('If-Match' in e for e in lint.lint_doc(d)))

    def test_mutation_auf_pfad_ohne_variable_braucht_kein_if_match_mit_variable_schon(self):
        d = self.doc()
        for oid, path in (('switchTenant', '/v1/me/tenant-switch'), ('createRegion', '/v1/regions')):
            self.assertEqual(d['paths'][path]['post']['operationId'], oid)
            self.assertFalse(any(oid in e for e in lint.lint_doc(d)), oid)
        op = d['paths']['/v1/memberships/{membership_id}']['delete']
        op['parameters'] = [p for p in op['parameters'] if 'IfMatch' not in p.get('$ref', '')]
        self.assertTrue(any('revokeMembership' in e and 'If-Match' in e for e in lint.lint_doc(d)))

    def test_m1_endpunkte_sind_dokumentiert_mit_scopes_und_problem_antworten(self):
        d = self.doc()
        ids = {op['operationId'] for item in d['paths'].values() for m, op in item.items() if m in ('get', 'post', 'put', 'patch', 'delete')}
        for must in ('getMe', 'getEffectiveConfig', 'listConfigRevisions', 'getConfigRevision', 'validateConfigRevision', 'requestConfigImpact', 'publishConfig',
                     'getOperation', 'listPassengers', 'createWebhookSubscription', 'listServices', 'listAuditEvents', 'updateCompanyProfile', 'createDriverClearance', 'switchTenant', 'revokeSession'):
            self.assertIn(must, ids)
        scopes = set(d['components']['securitySchemes']['oidc']['flows']['authorizationCode']['scopes'])
        for item in d['paths'].values():
            for m, op in item.items():
                if m in ('get', 'post', 'put', 'patch', 'delete') and op['operationId'] not in ('receivePartnerEvent', 'receiveProviderEvent'):
                    self.assertTrue(set(op['security'][0]['oidc']) <= scopes, op['operationId'])
        self.assertEqual(lint.lint_doc(d), [])

    def test_neue_moduldatei_wird_ohne_aenderung_der_werkzeuge_gebuendelt(self):
        names = bundle.module_names()
        self.assertEqual(names[:len(bundle.ORDER)], bundle.ORDER)
        for n in ('identity', 'registry', 'passengers', 'webhooks', 'services', 'compliance', 'fleet'): self.assertIn(n, names)

    def test_negativ_doppelte_operation_id(self):
        d = self.doc(); d['paths']['/v1/operations/{operation_id}']['get']['operationId'] = 'planJourney'
        self.assertTrue(any('doppelt' in e for e in lint.lint_doc(d)))

    def test_negativ_operation_ohne_sicherheit(self):
        d = self.doc(); del d['paths']['/v1/operations/{operation_id}']['get']['security']
        self.assertTrue(any('security' in e for e in lint.lint_doc(d)))

    def test_negativ_nicht_aufloesbarer_ref(self):
        d = self.doc(); d['paths']['/v1/operations/{operation_id}']['get']['responses']['200']['content']['application/json']['schema'] = {'$ref': '#/components/schemas/Gibtsnicht'}
        self.assertTrue(any('ungültig' in e for e in lint.lint_doc(d)))

    def test_negativ_ungueltiges_schema_beispiel(self):
        d = self.doc()
        name = next(n for n, s in d['components']['schemas'].items() if s.get('examples'))
        d['components']['schemas'][name]['examples'] = [12345]
        self.assertTrue(any('Beispiel' in e for e in lint.lint_doc(d)))

    def test_negativ_fehlerantwort_ohne_problem_json(self):
        d = self.doc(); d['paths']['/v1/operations/{operation_id}']['get']['responses']['404'] = {'description': 'x', 'content': {'application/json': {}}}
        self.assertTrue(any('problem+json' in e for e in lint.lint_doc(d)))

    def test_negativ_ereignisschema(self):
        t = Path(tempfile.mkdtemp())
        (t / 'journey.confirmed.v1.schema.json').write_text(json.dumps({'type': 'object'}))
        self.assertEqual(lint.lint_events(t), [])
        (t / 'kaputt.json').write_text(json.dumps({'type': 'ungueltig'}))
        errs = lint.lint_events(t)
        self.assertTrue(any('Name' in e for e in errs) and any('JSON Schema' in e for e in errs))

    def test_negativ_abweichung_zum_ausgangsstand_wird_erkannt(self):
        t = Path(tempfile.mkdtemp()) / 'api'; shutil.copytree(API, t, ignore=shutil.ignore_patterns('tools'))
        f = t / 'config.yaml'; f.write_text(f.read_text().replace('Konfiguration', 'Konfig', 1) if 'Konfiguration' in f.read_text() else f.read_text() + '\n# x\n')
        d = yaml.safe_load(f.read_text()); d['paths']['/v1/config/revisions/{revision_id}/publish']['post']['summary'] = 'geändert'
        f.write_text(yaml.safe_dump(d, sort_keys=False, allow_unicode=True))
        import breaking
        self.assertTrue(any('geändert' in c or 'entfernt' in c or 'Pflicht' in c for c in breaking.compare(yaml.safe_load(bundle.DOCS.read_text()), bundle.bundle(t))) or bundle.bundle(t) != yaml.safe_load(bundle.DOCS.read_text()))

    def test_negativ_doppelter_pfad_bricht_buendeln(self):
        t = Path(tempfile.mkdtemp()) / 'api'; shutil.copytree(API, t, ignore=shutil.ignore_patterns('tools'))
        d = yaml.safe_load((t / 'operations.yaml').read_text()); d['paths']['/v1/config/revisions/{revision_id}/publish'] = {}
        (t / 'operations.yaml').write_text(yaml.safe_dump(d))
        with self.assertRaises(SystemExit): bundle.bundle(t)



class Breaking(unittest.TestCase):
    def setUp(self):
        import breaking
        self.b = breaking
        self.old = copy.deepcopy(bundle.bundle())

    def test_unveraendert_keine_aenderung(self):
        self.assertEqual(self.b.compare(self.old, copy.deepcopy(self.old)), [])

    def test_entferntes_pflichtfeld_wird_gemeldet(self):
        new = copy.deepcopy(self.old)
        name, schema = next((n, s) for n, s in new['components']['schemas'].items() if s.get('required'))
        feld = schema['required'][0]
        del schema['properties'][feld]; schema['required'].remove(feld)
        self.assertTrue(any('Pflichtfeld' in c and feld in c for c in self.b.compare(self.old, new)))

    def test_entfernte_operation_antwort_und_pfad(self):
        new = copy.deepcopy(self.old)
        del new['paths']['/v1/operations/{operation_id}']
        self.assertTrue(any('Pfad entfernt' in c for c in self.b.compare(self.old, new)))
        new = copy.deepcopy(self.old); del new['paths']['/v1/driver/events']['post']['responses']['400']
        self.assertTrue(any('Antwort 400' in c for c in self.b.compare(self.old, new)))

    def test_neues_pflichtfeld_und_pflichtparameter(self):
        new = copy.deepcopy(self.old)
        name = next(n for n, s in new['components']['schemas'].items() if s.get('properties'))
        new['components']['schemas'][name].setdefault('required', []).append('neu')
        self.assertTrue(any('neues Pflichtfeld' in c for c in self.b.compare(self.old, new)))
        new = copy.deepcopy(self.old)
        new['paths']['/v1/operations/{operation_id}']['get']['parameters'].append({'name': 'x', 'in': 'query', 'required': True, 'schema': {'type': 'string'}})
        self.assertTrue(any('neuer Pflichtparameter' in c for c in self.b.compare(self.old, new)))

class TsClient(unittest.TestCase):
    def setUp(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import bundle, gen_ts_client
        self.gen = gen_ts_client
        self.spec = bundle.bundle()

    def test_generierter_client_passt_zum_vertrag(self):
        self.assertEqual(self.gen.TARGET.read_text(), self.gen.generate(self.spec))

    def test_jede_operation_steht_in_der_tabelle(self):
        text = self.gen.generate(self.spec)
        for item in self.spec['paths'].values():
            for m in ('get', 'post', 'put', 'patch', 'delete'):
                if m in item: self.assertIn(f"  {item[m]['operationId']}: {{ method:", text)

    def test_negativ_neue_operation_macht_die_alte_datei_ungueltig(self):
        spec = copy.deepcopy(self.spec)
        spec['paths']['/v1/neu'] = {'get': {'operationId': 'getNeu', 'responses': {'200': {'description': 'x'}}}}
        self.assertNotEqual(self.gen.TARGET.read_text(), self.gen.generate(spec))

class Dateninventar(unittest.TestCase):
    def test_ui_export_entspricht_dem_schema_der_vertraege(self):
        import jsonschema
        root = API.parent
        schema = json.loads((root / 'docs/umsetzungsplan/vertraege/schemas/dateninventar.v1.schema.json').read_text())
        doc = json.loads((root / 'web/operator/src/datenschutz/dateninventar.json').read_text())
        jsonschema.validate(doc, schema)

    def test_negativ_personenbezogenes_feld_ohne_rechtsgrundlage_verletzt_das_schema(self):
        import jsonschema
        root = API.parent
        schema = json.loads((root / 'docs/umsetzungsplan/vertraege/schemas/dateninventar.v1.schema.json').read_text())
        doc = json.loads((root / 'web/operator/src/datenschutz/dateninventar.json').read_text())
        feld = next(sp for t in doc['tabellen'] for sp in t['spalten'] if sp['personenbezogen'] == 'ja')
        feld['rechtsgrundlage'] = ''
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(doc, schema)

if __name__ == '__main__':
    unittest.main()
