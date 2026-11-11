#!/usr/bin/env python3
"""Legt das Gerüst eines Detailvertrags an (WP-M0-31): Originaltext und -abnahme werden wortgleich aus
docs/pflichtenheft/anforderungen.csv übernommen, WP und Phase aus arbeitspakete.csv.

Aufruf: python tools/new_contract.py LH-6.1-SYS-002 [...]   (überschreibt nie eine vorhandene Datei)
"""
import csv, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(p):
    with p.open(encoding='utf-8-sig', newline='') as f:
        return {r['source_key']: r for r in csv.DictReader(f, delimiter=';')}


def main(keys):
    req = read(ROOT / 'docs/pflichtenheft/anforderungen.csv')
    wp = read(ROOT / 'docs/umsetzungsplan/arbeitspakete.csv')
    for k in keys:
        out = ROOT / f'docs/umsetzungsplan/vertraege/{k}.md'
        if out.exists(): print('vorhanden:', out.name); continue
        r, w = req[k], wp[k]
        tpl = (ROOT / 'docs/umsetzungsplan/vertraege/_vorlage.md').read_text(encoding='utf-8')
        text = (tpl.replace('<source_key> – <Kurzbezeichnung>', f'{k} – {w["wp_titel"]}')
                   .replace('- **Arbeitspaket:** WP-…', f'- **Arbeitspaket:** {w["arbeitspaket"]}')
                   .replace('- **Status:** Vertrag in Arbeit | Bereit | …  (Werte siehe vertragsprozess.md)', '- **Status:** Vertrag in Arbeit')
                   .replace('P? / MUSS|SOLL|KANN', f'{r["phase_original"]} / {r["prioritaet_original"]}')
                   .replace('> <beschreibung_original aus anforderungen.csv>', '> ' + r['beschreibung_original'].strip())
                   .replace('<akzeptanz_original>', r['akzeptanz_original'].strip()))
        out.write_text(text, encoding='utf-8'); print('angelegt:', out.name)


if __name__ == '__main__':
    main(sys.argv[1:])
