#!/usr/bin/env python3
"""Bündelt api/*.yaml zu einer OpenAPI-3.1-Gesamtdatei (WP-M0-04).

  bundle.py                  Gesamtdatei nach stdout
  bundle.py --check          api/ muss zum Ausgangsstand docs/pflichtenheft/api/openapi.yaml abwärtskompatibel sein
                             (nur Erweiterungen; Entfernen/Ändern bestehender Verträge ist ein Fehler), Exitcode 1 sonst
  bundle.py --check-equal    wie --check, aber verlangt semantische Gleichheit (galt bis zur ersten Erweiterung)
  bundle.py --write-docs     schreibt die Pflichtenheft-Datei aus api/ neu (nur nach bewusster Entscheidung)

Führend ist api/. docs/pflichtenheft/api/openapi.yaml bleibt der eingefrorene Ausgangsstand des Pflichtenhefts (Stand
25.10.2026); neue Endpunkte entstehen nur in api/.
"""
import re, sys
from pathlib import Path
import yaml

API = Path(__file__).resolve().parents[1]
DOCS = API.parent / 'docs/pflichtenheft/api/openapi.yaml'
ORDER = ['common', 'federation', 'operations', 'finance', 'config', 'rosters', 'driver']
EXTERNAL = re.compile(r'^[A-Za-z0-9_.-]+\.yaml(#/.*)$')


def _rewrite(o):
    if isinstance(o, dict):
        return {k: (_ext(v) if k == '$ref' and isinstance(v, str) else _rewrite(v)) for k, v in o.items()}
    if isinstance(o, list):
        return [_rewrite(v) for v in o]
    return o


def _ext(v):
    m = EXTERNAL.match(v)
    return m.group(1) if m else v


def module_names(api: Path = API) -> list[str]:
    """Feste Reihenfolge der ersten Dateien, danach alle weiteren api/<modul>.yaml alphabetisch (neue Module brauchen keine Änderung hier)."""
    return ORDER + sorted(p.stem for p in api.glob('*.yaml') if p.stem not in ORDER)


def bundle(api: Path = API) -> dict:
    out = {}
    for name in module_names(api):
        doc = _rewrite(yaml.safe_load((api / f'{name}.yaml').read_text()))
        for k in ('openapi', 'jsonSchemaDialect', 'info', 'servers'):
            if k in doc: out[k] = doc[k]
        for p, item in doc.get('paths', {}).items():
            if p in out.setdefault('paths', {}): raise SystemExit(f'Pfad doppelt: {p}')
            out['paths'][p] = item
        for kind, entries in doc.get('components', {}).items():
            target = out.setdefault('components', {}).setdefault(kind, {})
            for n, v in entries.items():
                if n in target: raise SystemExit(f'Komponente doppelt: {kind}/{n}')
                target[n] = v
    return {k: out[k] for k in ('openapi', 'jsonSchemaDialect', 'info', 'servers', 'paths', 'components')}


if __name__ == '__main__':
    b = bundle()
    if '--check-equal' in sys.argv:
        base = yaml.safe_load(DOCS.read_text())
        if b != base:
            print('FEHLER: gebündelte api/ weicht vom Pflichtenheft-Stand ab.')
            sys.exit(1)
        print(f'api/ == Pflichtenheft-Stand ({len(b["paths"])} Pfade, {len(b["components"]["schemas"])} Schemas)')
    elif '--check' in sys.argv:
        from breaking import compare
        base = yaml.safe_load(DOCS.read_text())
        changes = compare(base, b)
        for c in changes: print('FEHLER gegenüber dem Ausgangsstand inkompatibel:', c)
        if changes: sys.exit(1)
        print(f'api/ erweitert den Pflichtenheft-Stand kompatibel ({len(b["paths"])} Pfade, {len(b["components"]["schemas"])} Schemas)')
    elif '--write-docs' in sys.argv:
        DOCS.write_text(yaml.safe_dump(b, sort_keys=False, allow_unicode=True, width=120))
        print('geschrieben:', DOCS)
    else:
        print(yaml.safe_dump(b, sort_keys=False, allow_unicode=True, width=120))
