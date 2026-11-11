#!/usr/bin/env python3
"""Nachverfolgungsprüfung (WP-M0-32): arbeitspakete.csv, Detailverträge, e2e.csv.

Prüft: 672 Zeilen mit eindeutigem source_key; Phase und Priorität unverändert gegenüber anforderungen.csv; Statuswerte
(vertragsprozess.md); Definition of Ready je Status; Detailvertrag existiert und trägt den unveränderten Originaltext;
Testfälle existieren (Katalog-ID oder pfad::name); WP-Verweise; e2e.csv nur mit Build-ID gefüllt.
Aufruf: python docs/umsetzungsplan/check_tracking.py [Wurzelverzeichnis]
"""
import csv, re, sys
from pathlib import Path

STATUS = ['Geplant', 'Vertrag in Arbeit', 'Bereit', 'In Umsetzung', 'Umgesetzt', 'Abgenommen', 'Abweichung freigegeben']
NEEDS_CONTRACT = {'Vertrag in Arbeit', 'Bereit', 'In Umsetzung', 'Umgesetzt', 'Abgenommen', 'Abweichung freigegeben'}
NEEDS_TEST = {'Bereit', 'In Umsetzung', 'Umgesetzt', 'Abgenommen'}
NEEDS_REAL_TEST = {'In Umsetzung', 'Umgesetzt', 'Abgenommen'}
CATALOG_ID = re.compile(r'^(AT-\d{2}|E2E-\d{3}|PH-AT-\d{3})$')


def read(p: Path):
    with p.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f, delimiter=';'))


def norm(s: str) -> str:
    return re.sub(r'\s+', '', s)


def check(root: Path) -> list[str]:
    errs = []
    um = root / 'docs/umsetzungsplan'
    ph = root / 'docs/pflichtenheft'
    reqs = {r['source_key']: r for r in read(ph / 'anforderungen.csv')}
    rows = read(um / 'arbeitspakete.csv')
    keys = [r['source_key'] for r in rows]
    if len(rows) != 672: errs.append(f'{len(rows)} statt 672 Zeilen')
    if len(set(keys)) != len(keys): errs.append('source_key nicht eindeutig')
    if set(keys) != set(reqs): errs.append('source_keys weichen von anforderungen.csv ab')
    catalog = {r['test_id_original'] for r in read(ph / 'e2e.csv')} | {f'PH-AT-{i:03d}' for i in range(1, 23)}
    for r in rows:
        k = r['source_key']; src = reqs.get(k)
        if not src: continue
        if (r['phase_original'], r['prioritaet_original']) != (src['phase_original'], src['prioritaet_original']):
            errs.append(f'{k}: Phase/Priorität weicht von anforderungen.csv ab')
        st = r['status']
        if st not in STATUS: errs.append(f'{k}: unbekannter Status „{st}“'); continue
        if st == 'Abweichung freigegeben' and src['prioritaet_original'] == 'MUSS':
            errs.append(f'{k}: MUSS darf nicht „Abweichung freigegeben“ sein')
        contracts = [c for c in r['detailvertrag'].split(';') if c]
        tests = [t for t in r['testfall'].split(';') if t]
        if st in NEEDS_CONTRACT and not contracts: errs.append(f'{k}: Status {st} ohne detailvertrag')
        if st in NEEDS_TEST and not tests: errs.append(f'{k}: Status {st} ohne testfall')
        for c in contracts:
            f = root / c.split('#')[0]
            if not f.is_file(): errs.append(f'{k}: Detailvertrag {c} fehlt'); continue
            if f.name == f'{k}.md':
                text = f.read_text(encoding='utf-8')
                if k not in text.splitlines()[0]: errs.append(f'{k}: Vertragskopf nennt source_key nicht')
                body = norm(src['beschreibung_original']); acc = norm(src['akzeptanz_original'])
                if body not in norm(text): errs.append(f'{k}: Originaltext im Detailvertrag verändert')
                if acc not in norm(text): errs.append(f'{k}: Originalabnahme im Detailvertrag verändert')
                m = re.search(r'Phase / Priorität[^:]*:\*\*\s*(P\d) / (\w+)', text)
                if m and (m.group(1), m.group(2)) != (src['phase_original'], src['prioritaet_original']):
                    errs.append(f'{k}: Phase/Priorität im Detailvertrag verändert')
        real = 0
        for t in tests:
            if CATALOG_ID.match(t):
                if t not in catalog: errs.append(f'{k}: Testfall {t} nicht im Testkatalog')
            elif t.startswith('geplant:'):
                if '::' not in t: errs.append(f'{k}: geplanter Testfall {t} ohne pfad::name')
            elif '::' in t:
                path, name = t.split('::', 1)
                f = root / path
                if not f.is_file() or name not in f.read_text(encoding='utf-8'): errs.append(f'{k}: Testfall {t} nicht gefunden')
                else: real += 1
            else:
                errs.append(f'{k}: Testfall „{t}“ weder Katalog-ID noch pfad::name')
        if st in NEEDS_REAL_TEST and real == 0: errs.append(f'{k}: Status {st} ohne ausführbaren Testfall (pfad::name)')
        wpdir = um / 'wp'
        wf = wpdir / r['wp_datei'].removeprefix('wp/')
        if not wf.is_file() or f"### {r['arbeitspaket']} " not in wf.read_text(encoding='utf-8'):
            errs.append(f'{k}: Arbeitspaket {r["arbeitspaket"]} nicht in {r["wp_datei"]}')
    errs += check_e2e(root)
    return errs


def check_e2e(root: Path) -> list[str]:
    errs = []
    rows = read(root / 'docs/pflichtenheft/e2e.csv')
    if len(rows) != 106: errs.append(f'e2e.csv: {len(rows)} statt 106 Zeilen')
    for r in rows:
        if r['status'] != 'Nicht ausgeführt':
            if not r['build'].strip(): errs.append(f'e2e.csv {r["test_id_original"]}: Status {r["status"]} ohne Build-ID')
            if not r['nachweis'].strip(): errs.append(f'e2e.csv {r["test_id_original"]}: Status ohne Nachweis')
    return errs


if __name__ == '__main__':
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    errs = check(root)
    for e in errs: print('FEHLER', e)
    print('Nachverfolgung:', 'FEHLER' if errs else 'PASS')
    sys.exit(1 if errs else 0)
