#!/usr/bin/env python3
"""Repository-Prüfung (WP-M0-01): Zielstruktur, READMEs, CODEOWNERS-Abdeckung, Versionsmatrix, Lockfiles.

Aufruf: python tools/check_repo.py [Wurzelverzeichnis]
"""
import re, sys
from pathlib import Path

DIRS = ['backend', 'db', 'api', 'workers/routing', 'workers/documents', 'web', 'mobile/driver', 'mobile/passenger',
        'infra', 'tests/acceptance', 'tests/performance']
MODULES = ['identity', 'config', 'service-geo', 'booking-dispatch', 'journey-federation', 'fare-payment', 'fleet-safety',
           'staff-time', 'documents-finance', 'communication-support', 'analytics']
ADAPTERS = ['psp', 'partner-tomp', 'partner-osdm', 'timetable-gtfs', 'erp-export', 'ocr', 'notification']


def check(root: Path) -> list[str]:
    errors = []
    for d in DIRS:
        if not (root / d).is_dir(): errors.append(f'Verzeichnis fehlt: {d}')
        elif not (root / d / 'README.md').is_file(): errors.append(f'README fehlt: {d}/README.md')
    projects = ['backend/platform', 'backend/app'] + [f'backend/modules/{m}' for m in MODULES] + [f'backend/adapters/{a}' for a in ADAPTERS]
    for p in projects:
        if not (root / p / 'build.gradle.kts').is_file(): errors.append(f'Teilprojekt fehlt: {p}')
        if not (root / p / 'gradle.lockfile').is_file(): errors.append(f'Lockfile fehlt: {p}/gradle.lockfile')
    for p in projects[2:]:
        if not (root / p / 'README.md').is_file(): errors.append(f'README fehlt: {p}/README.md')
    co = root / '.github/CODEOWNERS'
    if not co.is_file():
        errors.append('CODEOWNERS fehlt')
    else:
        patterns = [l.split()[0] for l in co.read_text().splitlines() if l.strip() and not l.startswith('#') and l.split()[0] != '*']
        for target in DIRS + projects + ['docs', '.github', 'tools']:
            if not any(('/' + target + '/').startswith(p if p.endswith('/') else p + '/') or p.rstrip('/') == '/' + target for p in patterns):
                errors.append(f'CODEOWNERS deckt {target} nicht ausdrücklich ab')
    v = root / 'docs/umsetzungsplan/versionen.md'
    if not v.is_file():
        errors.append('versionen.md fehlt')
    else:
        rows = [l for l in v.read_text().splitlines() if l.startswith('| ') and not l.startswith('| Baustein') and '---' not in l]
        if len(rows) < 15: errors.append('Versionsmatrix unvollständig')
        for r in rows:
            cells = [c.strip() for c in r.strip('|').split('|')]
            if re.search(r'\blatest\b', cells[1], re.I): errors.append(f'Version „latest“: {cells[0]}')
            if not (cells[4].startswith('verifiziert') or cells[4].startswith('offen')): errors.append(f'Status unklar: {cells[0]}')
            if cells[4].startswith('verifiziert') and cells[1] in ('', '–'): errors.append(f'verifiziert ohne Version: {cells[0]}')
    return errors


if __name__ == '__main__':
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    errs = check(root)
    for e in errs: print('FEHLER', e)
    print('Repository-Prüfung:', 'FEHLER' if errs else 'PASS')
    sys.exit(1 if errs else 0)
