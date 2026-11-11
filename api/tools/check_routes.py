#!/usr/bin/env python3
"""CI-Pflichtschritt „Route ohne Vertrag bricht den Build“ (WP-M1-03, LH-6.17-INT-001).

  check_routes.py <routes.json> [--pending api/contract-pending.txt]

`routes.json` schreibt `ContractCoverageTest` aus den tatsächlich registrierten Routen der laufenden Anwendung
(`backend/app/build/contract/routes.json`). Geprüft wird unabhängig vom Backend gegen die gebündelten OpenAPI-Dateien:
  - jede Route unter /v1 hat genau eine operationId (Methode + Pfad, Variablennamen egal),
  - jede operationId hat höchstens eine Route,
  - jede dokumentierte, nicht geroutete Operation steht in contract-pending.txt (und umgekehrt keine geroutete),
  - Routen außerhalb /v1 sind nur als Betriebsendpunkte (/actuator, /error) erlaubt.
Exitcode 1 bei jedem Verstoß.
"""
import json, re, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bundle import API, bundle  # noqa: E402

NON_API = ('/actuator', '/error')
METHODS = ('get', 'post', 'put', 'patch', 'delete')


def norm(path: str) -> str:
    return re.sub(r'\{[^/}]+\}', '{}', path)


def read_pending(path: Path) -> set[str]:
    return {l.split('#')[0].strip() for l in path.read_text().splitlines() if l.split('#')[0].strip()}


def verify(routes: list[dict], doc: dict, pending: set[str]) -> list[str]:
    ops: dict[tuple[str, str], list[str]] = {}
    for path, item in doc['paths'].items():
        for m in METHODS:
            if m in item: ops.setdefault((m.upper(), norm(path)), []).append(item[m]['operationId'])
    errs, routed = [], {}
    for r in routes:
        method, path = r['method'].upper(), r['path']
        if any(path.startswith(p) for p in NON_API): continue
        if not path.startswith('/v1/'): errs.append(f'Route {method} {path}: außerhalb von /v1 und kein erlaubter Betriebsendpunkt'); continue
        ids = ops.get((method, norm(path)), [])
        if not ids: errs.append(f'Route {method} {path}: keine operationId im Vertrag (api/*.yaml)')
        elif len(ids) > 1: errs.append(f'Route {method} {path}: mehrere operationIds {ids}')
        elif ids[0] in routed: errs.append(f'operationId {ids[0]} doppelt geroutet: {routed[ids[0]]} und {path}')
        else: routed[ids[0]] = path
    documented = {i for ids in ops.values() for i in ids}
    errs += [f'contract-pending.txt nennt unbekannte operationId {i}' for i in sorted(pending - documented)]
    errs += [f'operationId {i} ist gebaut, steht aber noch in contract-pending.txt' for i in sorted(set(routed) & pending)]
    errs += [f'operationId {i} ist dokumentiert, hat keine Route und steht nicht in contract-pending.txt' for i in sorted(documented - set(routed) - pending)]
    return errs


def main(argv: list[str]) -> int:
    args = [a for a in argv if not a.startswith('--')]
    pending_path = Path(argv[argv.index('--pending') + 1]) if '--pending' in argv else API / 'contract-pending.txt'
    if '--pending' in argv: args.remove(argv[argv.index('--pending') + 1])
    if len(args) != 1:
        print(__doc__); return 2
    routes = json.loads(Path(args[0]).read_text())
    errs = verify(routes, bundle(), read_pending(pending_path))
    for e in errs: print('FEHLER', e)
    print('Routen-Vertragsabgleich:', 'FEHLER' if errs else f'PASS ({len(routes)} Routen)')
    return 1 if errs else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
