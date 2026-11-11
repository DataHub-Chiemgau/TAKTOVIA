#!/usr/bin/env python3
"""Erzeugt die API-Dokumentation (Entwicklerportal-Seite) aus den OpenAPI-Dateien (WP-M1-03, LH-6.17-INT-001 AK 3).

  gen_docs.py --write     schreibt docs/api/index.html
  gen_docs.py --check     Exitcode 1, wenn docs/api/index.html nicht dem Vertrag entspricht oder Pflichtangaben fehlen

Je Endpunkt stehen Version, Sicherheits-Scopes und Fehlercodes in der Seite. Die Fehlercodes folgen aus den im Vertrag
dokumentierten Fehlerstatus und dem Katalog `ErrorCode` (`x-http-status`) in common.yaml. Die Seite ist statisch und ohne
Skripte oder externe Ressourcen (Content-Security-Policy-tauglich).
"""
import html, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from bundle import API, bundle, module_names  # noqa: E402
import yaml

TARGET = API.parent / 'docs/api/index.html'
METHODS = ('get', 'post', 'put', 'patch', 'delete')
# Codes, die erst mit M2 entstehen und nur an Föderationsendpunkten vorkommen.
FEDERATION_ONLY = {'OFFER_EXPIRED', 'HOLD_EXPIRED', 'REQUOTE_REQUIRED', 'CAPACITY_UNAVAILABLE', 'COOPERATION_NOT_ALLOWED', 'PROVIDER_UNAVAILABLE'}


def error_codes(doc: dict, path: str, op: dict) -> dict[str, list[str]]:
    """Status → mögliche Fehlercodes dieser Operation."""
    catalog = doc['components']['schemas']['ErrorCode']['x-http-status']
    params = {p.get('$ref', '').rsplit('/', 1)[-1] or p.get('name') for p in op.get('parameters', [])}
    federation = path.startswith('/v1/federation/')
    out = {}
    for status in sorted(op['responses']):
        if not status.isdigit() or int(status) < 400: continue
        codes = [c for c, s in catalog.items() if str(s) == status and (federation or c not in FEDERATION_ONLY)]
        if 'IdempotencyKey' not in params: codes = [c for c in codes if c != 'IDEMPOTENCY_CONFLICT']
        if status == '400' and 'Cursor' not in params: codes = [c for c in codes if c != 'INVALID_CURSOR']
        if status == '409' and op.get('operationId') != 'getOperation' and not federation: codes = [c for c in codes if c != 'OPERATION_IN_PROGRESS' or 'IdempotencyKey' in params]
        out[status] = codes
    return out


def operations(doc: dict):
    """(Modul, Pfad, Methode, Operation) in Vertragsreihenfolge."""
    for name in module_names():
        d = yaml.safe_load((API / f'{name}.yaml').read_text())
        for path, item in d.get('paths', {}).items():
            for m in METHODS:
                if m in item: yield name, path, m, doc['paths'][path][m]


def render(doc: dict) -> str:
    version = doc['info']['version']
    e = html.escape
    out = ['<!doctype html>', '<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">',
           f'<title>TAKTOVIA API {e(version)}</title>',
           '<style>body{font:16px/1.5 system-ui,sans-serif;max-width:60rem;margin:0 auto;padding:1rem 16px;color:#1a1a1a;background:#fff}'
           'h2{margin-top:2.5rem;border-bottom:1px solid #ccc}article{border:1px solid #d0d0d0;border-radius:6px;padding:.5rem 1rem;margin:1rem 0}'
           'code{background:#f2f2f2;padding:0 .25rem;border-radius:3px}.m{font-weight:700}dt{font-weight:600;margin-top:.5rem}'
           '@media (prefers-color-scheme:dark){body{background:#121212;color:#e8e8e8}article{border-color:#444}code{background:#2a2a2a}}</style></head><body>',
           f'<h1>TAKTOVIA API</h1><p>Vertragsversion <strong>{e(version)}</strong>, Pfadpräfix <code>/v1</code>. Erzeugt aus <code>api/*.yaml</code> '
           '(<code>python api/tools/gen_docs.py --write</code>); nicht von Hand ändern.</p>',
           '<p>Fehler: <code>application/problem+json</code> mit stabilem <code>code</code> und <code>correlation_id</code>; Feldfehler (422) tragen '
           '<code>violations[].pointer</code> und <code>violations[].code</code>.</p>']
    current = None
    for name, path, m, op in operations(doc):
        if name != current:
            out.append(f'<h2 id="{e(name)}">{e(name)}.yaml</h2>'); current = name
        scopes = [s for sec in op.get('security', []) for ss in sec.values() for s in ss] or ['(Partnersignatur)']
        out.append(f'<article id="{e(op["operationId"])}"><h3><span class="m">{m.upper()}</span> <code>{e(path)}</code></h3>'
                   f'<p>{e(op.get("summary", ""))}</p><dl><dt>operationId</dt><dd><code>{e(op["operationId"])}</code></dd>'
                   f'<dt>Version</dt><dd>{e(version)} (/v1)</dd>'
                   f'<dt>Sicherheits-Scopes</dt><dd>{", ".join(f"<code>{e(s)}</code>" for s in scopes)}</dd><dt>Fehlercodes</dt><dd><ul>')
        for status, codes in error_codes(doc, path, op).items():
            out.append(f'<li>{e(status)}: {", ".join(f"<code>{e(c)}</code>" for c in codes) or "–"}</li>')
        out.append('</ul></dd></dl></article>')
    out.append('</body></html>')
    return '\n'.join(out) + '\n'


def check_doc(text: str, doc: dict) -> list[str]:
    """Pflichtangaben je Endpunkt: Version, Scopes, Fehlercodes (maschinell gegen die OpenAPI-Dateien)."""
    errs = []
    version = doc['info']['version']
    if f'Vertragsversion <strong>{version}</strong>' not in text: errs.append(f'Version {version} fehlt in der Dokumentation')
    for _, path, m, op in operations(doc):
        oid = op['operationId']
        start = text.find(f'<article id="{oid}">')
        if start < 0: errs.append(f'{oid}: Endpunkt fehlt in der Dokumentation'); continue
        block = text[start:text.find('</article>', start)]
        if f'<dt>Version</dt><dd>{version}' not in block: errs.append(f'{oid}: Version fehlt')
        for sec in op.get('security', []):
            for s in [s for ss in sec.values() for s in ss]:
                if f'<code>{s}</code>' not in block: errs.append(f'{oid}: Scope {s} fehlt')
        for status, codes in error_codes(doc, path, op).items():
            if f'<li>{status}:' not in block: errs.append(f'{oid}: Fehlerstatus {status} fehlt')
            for c in codes:
                if f'<code>{c}</code>' not in block: errs.append(f'{oid}: Fehlercode {c} fehlt')
    return errs


def main(argv):
    doc = bundle()
    text = render(doc)
    if '--write' in argv:
        TARGET.parent.mkdir(parents=True, exist_ok=True); TARGET.write_text(text); print('geschrieben:', TARGET); return 0
    if '--check' in argv:
        errs = [] if TARGET.exists() and TARGET.read_text() == text else ['docs/api/index.html entspricht nicht dem Vertrag (gen_docs.py --write)']
        if TARGET.exists(): errs += check_doc(TARGET.read_text(), doc)
        for e in errs: print('FEHLER', e)
        print('API-Dokumentation:', 'FEHLER' if errs else 'PASS')
        return 1 if errs else 0
    print(__doc__); return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
