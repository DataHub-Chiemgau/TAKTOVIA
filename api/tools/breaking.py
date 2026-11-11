#!/usr/bin/env python3
"""Erkennung inkompatibler API-Änderungen (WP-M0-13, Kap. 05 §5.1: Breaking Changes brauchen eine neue Major-Version).

Inkompatibel (für bestehende Clients) ist:
  - entfernter Pfad oder entfernte Operation,
  - entfernte Antwort (Statuscode) einer Operation,
  - entferntes Feld (Property) eines Schemas, insbesondere eines Pflichtfelds,
  - neues Pflichtfeld in einem Schema oder neuer Pflichtparameter einer Operation,
  - entfernter Enum-Wert,
  - geänderter Typ eines Feldes.
Ohne Hochsetzen der Major-Version in info.version ist jede solche Änderung ein Fehler.

Aufruf: breaking.py [Basisreferenz]   (Standard: HEAD; Vergleichsstand = docs/pflichtenheft/api/openapi.yaml dieser Referenz
gegen die aus api/ gebündelte Datei im Arbeitsbaum)
"""
import subprocess, sys
from pathlib import Path
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bundle import API, bundle  # noqa: E402

METHODS = {'get', 'post', 'put', 'patch', 'delete'}


def major(doc): return str(doc['info']['version']).split('.')[0].lstrip('vV')


def compare(old: dict, new: dict) -> list[str]:
    out = []
    for path, item in old['paths'].items():
        if path not in new['paths']: out.append(f'Pfad entfernt: {path}'); continue
        for m, op in item.items():
            if m not in METHODS: continue
            n = new['paths'][path].get(m)
            if n is None: out.append(f'Operation entfernt: {m.upper()} {path}'); continue
            for code in op.get('responses', {}):
                if code not in n.get('responses', {}): out.append(f'Antwort {code} entfernt: {m.upper()} {path}')
            old_req = {p.get('$ref') or p.get('name') for p in op.get('parameters', []) if p.get('required')}
            for p in n.get('parameters', []):
                key = p.get('$ref') or p.get('name')
                if (p.get('required') or _param_required(new, p)) and key not in old_req and key not in {q.get('$ref') or q.get('name') for q in op.get('parameters', [])}:
                    out.append(f'neuer Pflichtparameter {key}: {m.upper()} {path}')
    for name, s in old['components']['schemas'].items():
        if name not in new['components']['schemas']: out.append(f'Schema entfernt: {name}'); continue
        out += _schema(name, s, new['components']['schemas'][name])
    return out


def _param_required(doc, p):
    ref = p.get('$ref')
    return bool(ref and doc['components']['parameters'].get(ref.split('/')[-1], {}).get('required'))


def _schema(name, o, n):
    out = []
    for prop, ps in o.get('properties', {}).items():
        if prop not in n.get('properties', {}):
            out.append(f'Feld entfernt: {name}.{prop}' + (' (Pflichtfeld)' if prop in o.get('required', []) else ''))
        else:
            np = n['properties'][prop]
            if 'type' in ps and 'type' in np and ps['type'] != np['type']: out.append(f'Typ geändert: {name}.{prop}')
            if 'enum' in ps and 'enum' in np and not set(ps['enum']) <= set(np['enum']): out.append(f'Enum-Wert entfernt: {name}.{prop}')
    for req in set(n.get('required', [])) - set(o.get('required', [])):
        out.append(f'neues Pflichtfeld: {name}.{req}')
    return out


def baseline(ref: str) -> dict:
    root = API.parent
    txt = subprocess.run(['git', '-C', str(root), 'show', f'{ref}:docs/pflichtenheft/api/openapi.yaml'], capture_output=True, text=True, check=True).stdout
    return yaml.safe_load(txt)


if __name__ == '__main__':
    old, new = baseline(sys.argv[1] if len(sys.argv) > 1 else 'HEAD'), bundle()
    changes = compare(old, new)
    if changes and major(new) <= major(old):
        for c in changes: print('FEHLER inkompatibel ohne neue Major-Version:', c)
        sys.exit(1)
    print(f'inkompatible Änderungen: {len(changes)}', '(Major-Version erhöht)' if changes else '')
