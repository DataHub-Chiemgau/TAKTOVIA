#!/usr/bin/env python3
"""Linter für die API-Verträge (WP-M0-04, Konventionen aus Kap. 05 §5.1).

Regeln: Pfadpräfix /v1; eindeutige operationId; security je Operation; Idempotency-Key bei mutierenden Operationen;
If-Match bei Revisionseingriffen; Fehlerantworten als application/problem+json; $ref auflösbar (OpenAPI-Validator);
Schema-Beispiele gültig; Ereignisschemas unter api/events gültiges JSON Schema mit Namensschema.
Ausnahmen sind unten namentlich begründet; neue Ausnahmen brauchen Review.
"""
import json, re, sys
from pathlib import Path
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate_spec

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bundle import API, bundle  # noqa: E402

MUTATING = {'post', 'put', 'patch', 'delete'}
# Operationen ohne Idempotency-Key: Partnerereignisse tragen Signaturheader und werden über die Inbox dedupliziert (Kap. 05 §5.6).
NO_IDEMPOTENCY_KEY = {'receivePartnerEvent', 'receiveProviderEvent'}   # receiveProviderEvent: PSP-Signatur und Inbox-Deduplikation (WP-M2-01)
# Mutierende Operationen mit Idempotency-Key, aber ohne If-Match: legen Neues an statt eine Revision zu ändern.
NO_IF_MATCH = {'planJourney', 'recordDriverEvent'}
EVENT_NAME = re.compile(r'^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+\.v\d+\.schema\.json$')   # Unterstrich erlaubt: Ereignistypen wie config.revision_created


# WP-M1-37 (LH-6.18-SEC-008): rohe Kartendaten erreichen die API nie. Kein Schema, Parameter oder Header mit Kartendatenfeld;
# `payment_method_token` und `provider_ref` sind PSP-Referenzwerte und bleiben erlaubt (Kap. 05 §5.3).
CARD_FIELD = re.compile(r'(?i)^(pan|primary_?account_?number|card_?(number|no|num)|cc_?(number|num)|cvc2?|cvv2?|cid|card_?(security_?code|verification\w*)|'
                        r'(card_?)?expir(y|ation)\w*|exp_?(month|year|date)|card_?holder\w*|cardholder_?name)$')


def card_data_violations(node, path='') -> list[str]:
    """Findet Kartendatenfelder in Schemaeigenschaften und Parameternamen (rekursiv über das gesamte Dokument)."""
    out = []
    if isinstance(node, dict):
        props = node.get('properties')
        if isinstance(props, dict):
            out += [f'{path}/properties/{k}: Kartendatenfeld' for k in props if CARD_FIELD.match(str(k))]
        if 'in' in node and isinstance(node.get('name'), str) and CARD_FIELD.match(node['name']):
            out.append(f"{path}: Parameter {node['name']} ist ein Kartendatenfeld")
        for k, v in node.items():
            out += card_data_violations(v, f'{path}/{k}')
    elif isinstance(node, list):
        for i, v in enumerate(node):
            out += card_data_violations(v, f'{path}/{i}')
    return out


def _param_names(op):
    return {p.get('$ref', '').split('/')[-1] or p.get('name') for p in op.get('parameters', [])}


POLICY_FILE = API / 'request-schema-policy.json'
KATALOG = API.parent / 'docs' / 'pflichtenheft' / 'kataloge.csv'


def load_request_policy(policy_file: Path = POLICY_FILE, katalog: Path = KATALOG) -> dict:
    """Konvention für Request-Schemas (WP-M1-26): verbotene Feldnamen (Muster und Katalogparameter) und begründete Ausnahmen."""
    import csv
    pol = json.loads(policy_file.read_text())
    params = {r['name_original'] for r in csv.DictReader(katalog.open(encoding='utf-8'), delimiter=';') if r['katalogtyp'] == 'Parameter'}
    return {'patterns': [re.compile(p) for p in pol['verbotene_feldnamen']], 'params': params,
            'allow': {(a['schema'], a['feld']) for a in pol['ausnahmen']}}


def request_schema_violations(doc: dict, policy: dict | None = None) -> list[str]:
    """WP-M1-26 / LH-6.1-SYS-011: Request-Schemas haben an jedem Objekt mit Eigenschaften additionalProperties:false und enthalten kein vom Client
    setzbares Regel-, Preis-, Revisions- oder Statusfeld (siehe api/request-schema-policy.json; Ausnahmen sind dort begründet)."""
    policy = policy or load_request_policy()
    schemas, errs = doc['components']['schemas'], []

    def walk(node, where, named, seen):
        if not isinstance(node, dict): return
        if '$ref' in node:
            name = node['$ref'].split('/')[-1]
            if name in seen or name not in schemas: return
            return walk(schemas[name], where, name, seen | {name})
        props = node.get('properties')
        if props is not None and node.get('additionalProperties') is not False:
            errs.append(f'{where}: Request-Schema {named} ohne additionalProperties:false')
        for field, sub in (props or {}).items():
            if (named, field) not in policy['allow'] and (any(p.match(field) for p in policy['patterns']) or field in policy['params']):
                errs.append(f'{where}: Request-Schema {named} enthält das vom Client setzbare Regel-, Preis- oder Statusfeld "{field}"')
            walk(sub, where, named, seen)
        if 'items' in node: walk(node['items'], where, named, seen)
        for key in ('allOf', 'oneOf', 'anyOf'):
            for sub in node.get(key, []): walk(sub, where, named, seen)

    for path, item in doc['paths'].items():
        for method, op in item.items():
            body = op.get('requestBody') if method in {'post', 'put', 'patch', 'delete'} else None
            schema = (body or {}).get('content', {}).get('application/json', {}).get('schema')
            if schema: walk(schema, f'{method.upper()} {path} ({op.get("operationId")})', None, frozenset())
    return errs


def lint_doc(doc: dict) -> list[str]:
    errs, ids = [], []
    for path, item in doc['paths'].items():
        if not path.startswith('/v1/'): errs.append(f'{path}: Pfad ohne /v1-Präfix')
        for method, op in item.items():
            if method not in {'get', 'post', 'put', 'patch', 'delete'}: continue
            oid = op.get('operationId')
            where = f'{method.upper()} {path} ({oid})'
            if not oid: errs.append(f'{where}: operationId fehlt')
            ids.append(oid)
            if not op.get('security'): errs.append(f'{where}: security fehlt')
            names = _param_names(op)
            if method in MUTATING:
                if oid not in NO_IDEMPOTENCY_KEY and 'IdempotencyKey' not in names:
                    errs.append(f'{where}: mutierende Operation ohne Idempotency-Key')
                # Mutation auf einem Pfad ohne Ressourcenvariable (Sammelpfad, /v1/me/…) legt Neues an oder wirkt auf den Kontext:
                # es gibt keine Revision, auf die sich If-Match beziehen könnte.
                if oid not in NO_IDEMPOTENCY_KEY and oid not in NO_IF_MATCH and '{' in path and 'IfMatch' not in names:
                    errs.append(f'{where}: Revisionseingriff ohne If-Match')
            if oid in NO_IF_MATCH and 'IfMatch' in names: errs.append(f'{where}: Ausnahmeliste veraltet (trägt If-Match)')
            for code, resp in op.get('responses', {}).items():
                if str(code)[0] in '45' and '$ref' not in resp:
                    if 'application/problem+json' not in resp.get('content', {}):
                        errs.append(f'{where}: Fehlerantwort {code} nicht als application/problem+json')
    errs += [f'SEC-008: {v}' for v in card_data_violations(doc)]
    errs += [f'SYS-011: {v}' for v in request_schema_violations(doc)]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup: errs.append(f'operationId doppelt: {sorted(dup)}')
    try:
        validate_spec(doc)
    except Exception as e:  # noqa: BLE001
        errs.append(f'OpenAPI ungültig: {str(e).splitlines()[0]}')
        return errs
    for name, schema in doc['components']['schemas'].items():
        v = Draft202012Validator({**schema, 'components': doc['components']}, format_checker=FormatChecker())
        for i, ex in enumerate(schema.get('examples', [])):
            try: v.validate(ex)
            except Exception as e:  # noqa: BLE001
                errs.append(f'Schema {name}: Beispiel {i} ungültig ({str(e).splitlines()[0]})')
    return errs


def lint_events(events_dir: Path) -> list[str]:
    errs = []
    for f in sorted(events_dir.glob('*.json')):
        if not EVENT_NAME.match(f.name): errs.append(f'{f.name}: Name muss <ereignis.typ>.v<N>.schema.json folgen')
        try:
            Draft202012Validator.check_schema(json.loads(f.read_text()))
        except Exception as e:  # noqa: BLE001
            errs.append(f'{f.name}: kein gültiges JSON Schema ({str(e).splitlines()[0]})')
    return errs


def main(api: Path = API) -> int:
    errs = lint_doc(bundle(api)) + lint_events(api / 'events')
    for e in errs: print('FEHLER', e)
    print('API-Lint:', 'FEHLER' if errs else 'PASS')
    return 1 if errs else 0


if __name__ == '__main__':
    sys.exit(main())
