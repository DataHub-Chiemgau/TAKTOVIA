#!/usr/bin/env python3
"""Bündelt api/*.yaml zu einer OpenAPI-3.1-Gesamtdatei (WP-M0-04).

  bundle.py                  Gesamtdatei nach stdout
  bundle.py --check          vergleicht semantisch mit docs/pflichtenheft/api/openapi.yaml (Ausgangsstand), Exitcode 1 bei Abweichung
  bundle.py --write-docs     schreibt die Pflichtenheft-Datei aus api/ neu (nur nach bewusster Änderung der Verträge)

Führend ist api/. docs/pflichtenheft/api/openapi.yaml ist der Ausgangsstand des Pflichtenhefts und wird nur aus
api/ erzeugt, nicht von Hand gepflegt.
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


def bundle(api: Path = API) -> dict:
    out = {}
    for name in ORDER:
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
    if '--check' in sys.argv:
        base = yaml.safe_load(DOCS.read_text())
        if b != base:
            print('FEHLER: gebündelte api/ weicht vom Pflichtenheft-Stand ab. Absicht? Dann: bundle.py --write-docs')
            sys.exit(1)
        print(f'api/ == Pflichtenheft-Stand ({len(b["paths"])} Pfade, {len(b["components"]["schemas"])} Schemas)')
    elif '--write-docs' in sys.argv:
        DOCS.write_text(yaml.safe_dump(b, sort_keys=False, allow_unicode=True, width=120))
        print('geschrieben:', DOCS)
    else:
        print(yaml.safe_dump(b, sort_keys=False, allow_unicode=True, width=120))
