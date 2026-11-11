#!/usr/bin/env python3
"""Erzeugt den TypeScript-API-Client des Webs aus den OpenAPI-Dateien in api/ (WP-M1-43).

  gen_ts_client.py --write    schreibt web/api-client/src/generated.ts
  gen_ts_client.py --check    Exitcode 1, wenn die Datei nicht zum aktuellen Vertrag passt (CI)
  gen_ts_client.py            Ausgabe nach stdout

Erzeugt: Typen aller components.schemas, je operationId ein Eintrag in `operationTable` (Methode, Pfad, Parameter, Scopes,
Statuscodes) und die Typkarte `Operations` (Pfad-/Query-/Header-Parameter, Body, Antwort). Die Laufzeit (Fetch, Problem-Fehler,
If-Match, Idempotency-Key) steht von Hand in web/api-client/src/runtime.ts und nutzt nur diese Tabelle.
"""
import re, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bundle  # noqa: E402

TARGET = Path(__file__).resolve().parents[2] / 'web/api-client/src/generated.ts'
METHODS = ('get', 'post', 'put', 'patch', 'delete')
HEADER = '// GENERIERT von api/tools/gen_ts_client.py aus api/*.yaml - nicht von Hand ändern (python api/tools/gen_ts_client.py --write).\n'


def ref_name(ref: str) -> str:
    return ref.rsplit('/', 1)[-1]


def lit(v) -> str:
    return "'" + str(v).replace("\\", "\\\\").replace("'", "\\'") + "'" if isinstance(v, str) else ('null' if v is None else str(v).lower())


def ts(schema: dict, indent: int = 0) -> str:
    if '$ref' in schema:
        return ref_name(schema['$ref'])
    if 'enum' in schema:
        return ' | '.join(lit(v) for v in schema['enum'])
    if 'const' in schema:
        return lit(schema['const'])
    if 'oneOf' in schema or 'anyOf' in schema:
        return ' | '.join(ts(s, indent) for s in schema.get('oneOf') or schema['anyOf'])
    if 'allOf' in schema and 'type' not in schema:
        return ' & '.join(ts(s, indent) for s in schema['allOf'])
    t = schema.get('type')
    if isinstance(t, list):
        return ' | '.join(ts({**schema, 'type': x}, indent) for x in t)
    if t == 'string':
        return 'string'
    if t in ('integer', 'number'):
        return 'number'
    if t == 'boolean':
        return 'boolean'
    if t == 'null':
        return 'null'
    if t == 'array':
        inner = ts(schema.get('items', {}), indent)
        return f'({inner})[]' if ' ' in inner else f'{inner}[]'
    if t == 'object' or 'properties' in schema:
        props = schema.get('properties', {})
        req = set(schema.get('required', []))
        pad = '  ' * (indent + 1)
        lines = []
        for name, sub in props.items():
            key = name if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name) else lit(name)
            lines.append(f"{pad}{key}{'' if name in req else '?'}: {ts(sub, indent + 1)};")
        if schema.get('additionalProperties') not in (False, None) and not props:
            return 'Record<string, unknown>'
        if not lines:
            return 'Record<string, never>' if schema.get('additionalProperties') is False else 'Record<string, unknown>'
        return '{\n' + '\n'.join(lines) + '\n' + '  ' * indent + '}'
    return 'unknown'


def resolve(spec: dict, node: dict) -> dict:
    while isinstance(node, dict) and '$ref' in node and not node['$ref'].startswith('#/components/schemas/'):
        cur = spec
        for part in node['$ref'][2:].split('/'):
            cur = cur[part]
        node = cur
    return node


def generate(spec: dict) -> str:
    out = [HEADER]
    for name, schema in spec['components']['schemas'].items():
        body = ts(schema)
        if body.startswith('{'):
            out.append(f'export interface {name} {body}\n')
        else:
            out.append(f'export type {name} = {body};\n')
    table, types = [], []
    for path, item in spec['paths'].items():
        for method in METHODS:
            op = item.get(method)
            if not op:
                continue
            oid = op['operationId']
            params = [resolve(spec, p) for p in item.get('parameters', []) + op.get('parameters', [])]
            groups = {'path': [], 'query': [], 'header': []}
            for p in params:
                groups[p['in']].append(p)
            success = [c for c in op['responses'] if c.startswith('2')]
            resp = 'undefined'
            for c in success:
                content = resolve(spec, op['responses'][c]).get('content', {}).get('application/json')
                if content:
                    resp = ts(content['schema'])
            body = op.get('requestBody', {}).get('content', {}).get('application/json')
            scopes = sorted({s for sec in op.get('security', []) for sc in sec.values() for s in sc})

            def group_type(ps):
                if not ps:
                    return 'Record<string, never>'
                return '{ ' + '; '.join(f"{lit(p['name']) if '-' in p['name'] else p['name']}{'' if p.get('required') else '?'}: {ts(p['schema'])}" for p in ps) + ' }'

            types.append(f"  {oid}: {{\n    path: {group_type(groups['path'])};\n    query: {group_type(groups['query'])};\n"
                         f"    headers: {group_type(groups['header'])};\n    body: {ts(body['schema']) if body else 'undefined'};\n    response: {resp};\n  }};")
            table.append(f"  {oid}: {{ method: '{method.upper()}', path: '{path}', pathParams: [{', '.join(lit(p['name']) for p in groups['path'])}], "
                         f"query: [{', '.join(lit(p['name']) for p in groups['query'])}], headers: [{', '.join(lit(p['name']) for p in groups['header'])}], "
                         f"hasBody: {'true' if body else 'false'}, scopes: [{', '.join(lit(s) for s in scopes)}], "
                         f"statuses: [{', '.join(c for c in op['responses'] if c.isdigit())}] }},")
    out.append('export interface Operations {\n' + '\n'.join(types) + '\n}\n')
    out.append('export type OperationId = keyof Operations;\n')
    out.append('export interface OperationInfo {\n  method: \'GET\' | \'POST\' | \'PUT\' | \'PATCH\' | \'DELETE\';\n  path: string;\n  pathParams: readonly string[];\n'
               '  query: readonly string[];\n  headers: readonly string[];\n  hasBody: boolean;\n  scopes: readonly string[];\n  statuses: readonly number[];\n}\n')
    out.append('export const operationTable: Record<OperationId, OperationInfo> = {\n' + '\n'.join(table) + '\n};\n')
    return '\n'.join(out)


if __name__ == '__main__':
    text = generate(bundle.bundle())
    if '--write' in sys.argv:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(text)
    elif '--check' in sys.argv:
        if not TARGET.is_file() or TARGET.read_text() != text:
            print('FEHLER: web/api-client/src/generated.ts passt nicht zu api/*.yaml; python api/tools/gen_ts_client.py --write ausführen')
            sys.exit(1)
        print('API-Client aktuell')
    else:
        sys.stdout.write(text)
