#!/usr/bin/env python3
"""Release-Gate Datenschutz-Folgenabschätzung (WP-M1-42, LH-6.18-SEC-013).

  check_dsfa.py                 Strukturprüfung (CI, jeder Stand): die DSFA deckt alle Bewegungs-/Mobilitätsfelder des Dateninventars ab, jede Maßnahme verweist auf
                                umsetzende Arbeitspakete, die Bewertung der Bewegungs- und Mobilitätsdaten ist vorhanden. Ein Entwurf besteht diese Prüfung.
  check_dsfa.py --release       zusätzlich: DSFA-Version FREIGEGEBEN, datiert, mit Freigabevermerk der verantwortlichen Stelle (Release-Gate vor der ersten Produktivschaltung
                                personenbezogener Daten). Ohne Freigabevermerk oder Freigabedatum schlägt die Prüfung fehl.
Optionen: --dsfa <datei>  --inventar <verzeichnis>  --wp-dir <verzeichnis>.

Bewegungs-/Mobilitätsdatum (Kennzeichnung im Dateninventar, Detailvertrag SEC-013): eine Spalte mit `personenbezogen = ja` und Schutzklasse `STANDORT_ETA`.
Das Gate meldet zusätzlich personenbezogene Spalten mit Namen, die auf Orts-/Bewegungsbezug hindeuten, aber nicht so gekennzeichnet sind.
Die rechtliche Bewertung und die Freigabe selbst liegen bei der verantwortlichen Stelle (Datenschutz des Auftraggebers, OP-32); dieses Werkzeug prüft nur die Form.
"""
import argparse
import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'db'))
import dateninventar  # noqa: E402

MOBILITY_CLASS = 'STANDORT_ETA'
HINT_TOKENS = {'position', 'lat', 'lon', 'lng', 'latitude', 'longitude', 'location', 'origin', 'destination', 'eta', 'coord', 'waypoint', 'heading', 'speed',
               'pickup', 'dropoff', 'arrival', 'departure', 'geo'}
REQUIRED_TOPICS = (('Standortdaten', 'position_sample'), ('Fahrerdaten', 'Fahrer'), ('ETA', 'ETA'))
DEFAULT_DSFA = ROOT / 'docs' / 'datenschutz' / 'dsfa.md'


def mobility_fields(inventar_dir: Path) -> tuple[set[str], list[str]]:
    """(gekennzeichnete Felder `tabelle.spalte`, Hinweise auf nicht gekennzeichnete Felder)."""
    columns, _ = dateninventar.load_inventory(inventar_dir)
    marked, unmarked = set(), []
    for (t, c), r in columns.items():
        if r['personenbezogen'] != 'ja':
            continue
        if r['schutzklasse'] == MOBILITY_CLASS:
            marked.add(f'{t}.{c}')
        elif HINT_TOKENS & set(c.lower().split('_')):
            unmarked.append(f'{t}.{c}')
    return marked, sorted(unmarked)


def parse_frontmatter(text: str) -> dict:
    m = re.match(r'---\n(.*?)\n---\n', text, re.S)
    if not m:
        return {}
    out = {}
    for line in m.group(1).splitlines():
        if ':' in line and not line.lstrip().startswith('#'):
            k, v = line.split(':', 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def section(text: str, heading_re: str) -> str:
    """Text eines Abschnitts `## <heading>` bis zur nächsten `## `-Überschrift."""
    m = re.search(rf'^## {heading_re}.*?$(.*?)(?=^## |\Z)', text, re.S | re.M)
    return m.group(1) if m else ''


def known_wps(wp_dir: Path) -> set[str]:
    out = set()
    for f in wp_dir.glob('*.md'):
        out |= set(re.findall(r'^### (WP-M\d+[a-z]?-\d+)', f.read_text(encoding='utf-8'), re.M))
    return out


def check(dsfa: Path, inventar_dir: Path, wp_dir: Path, release: bool) -> list[str]:
    errors = []
    if not dsfa.is_file():
        return [f'DSFA-Dokument fehlt: {dsfa}']
    text = dsfa.read_text(encoding='utf-8')
    fm = parse_frontmatter(text)
    if not re.fullmatch(r'[1-9]\d*', fm.get('dsfa_version', '')):
        errors.append('DSFA-Version fehlt oder ist keine positive ganze Zahl (Kopfzeile dsfa_version)')
    try:
        doc_date = date.fromisoformat(fm.get('datum', ''))
    except ValueError:
        doc_date = None
        errors.append('DSFA ohne gültiges Datum (Kopfzeile datum, JJJJ-MM-TT)')
    status = fm.get('status', '')
    if status not in ('ENTWURF', 'FREIGEGEBEN'):
        errors.append('Status der DSFA muss ENTWURF oder FREIGEGEBEN sein')

    # 1. jedes Bewegungs-/Mobilitätsfeld des Inventars ist referenziert
    fields, unmarked = mobility_fields(inventar_dir)
    refs = set(re.findall(r'\|\s*`?([a-z][a-z0-9_]*\.[a-z][a-z0-9_]*)`?\s*\|', section(text, r'6\.')))
    for f in sorted(fields - refs):
        errors.append(f'Bewegungs-/Mobilitätsfeld ohne DSFA-Referenz: {f}')
    for f in sorted(refs - fields):
        errors.append(f'DSFA verweist auf ein Feld, das im Inventar nicht als Bewegungs-/Mobilitätsdatum gekennzeichnet ist: {f}')
    for f in unmarked:
        errors.append(f'personenbezogenes Feld mit Ortsbezug im Namen, im Inventar nicht als Bewegungs-/Mobilitätsdatum gekennzeichnet (Schutzklasse {MOBILITY_CLASS}) oder bewusst auszunehmen: {f}')

    # 2. Maßnahmen mit Verweis auf umsetzende Arbeitspakete
    wps = known_wps(wp_dir)
    measures = [l for l in section(text, r'5\.').splitlines() if re.match(r'\|\s*M-\d+', l)]
    if not measures:
        errors.append('keine Maßnahmen in der DSFA (Abschnitt 5)')
    for l in measures:
        cells = [c.strip() for c in l.strip().strip('|').split('|')]
        found = re.findall(r'WP-M\d+[a-z]?-\d+', cells[2] if len(cells) > 2 else '')
        if not found:
            errors.append(f'Maßnahme ohne Verweis auf ein umsetzendes Arbeitspaket: {cells[0]}')
        for w in found:
            if wps and w not in wps:
                errors.append(f'Maßnahme {cells[0]} verweist auf unbekanntes Arbeitspaket {w}')

    # 3. Bewertung der Bewegungs- und Mobilitätsdaten einschließlich der geplanten Standort-, Fahrer- und ETA-Daten
    ev = section(text, r'4\.')
    if len(ev.strip()) < 400:
        errors.append('Bewertung der Bewegungs- und Mobilitätsdaten fehlt oder ist zu knapp (Abschnitt 4)')
    for topic, keyword in REQUIRED_TOPICS:
        sub = re.search(rf'^### [^\n]*{topic}[^\n]*$(.*?)(?=^### |\Z)', ev, re.S | re.M | re.I)
        if not sub or len(sub.group(1).strip()) < 150 or keyword.lower() not in sub.group(1).lower():
            errors.append(f'Bewertung ohne ausreichenden Abschnitt zu {topic}-Daten (geplante Verarbeitung laut Pflichtenheft)')

    # 4. Freigabe (nur Release-Gate)
    if release:
        if status != 'FREIGEGEBEN':
            errors.append('DSFA nicht freigegeben (Status ENTWURF): ohne Freigabevermerk der verantwortlichen Stelle besteht das Release-Gate nicht')
        for k, label in (('freigabe_stelle', 'verantwortliche Stelle'), ('freigabe_name', 'freigebende Person'), ('freigabe_vermerk', 'Freigabevermerk')):
            if not fm.get(k):
                errors.append(f'Freigabevermerk unvollständig: {label} fehlt (Kopfzeile {k})')
        try:
            fd = date.fromisoformat(fm.get('freigabe_datum', ''))
            if doc_date and fd < doc_date:
                errors.append('Freigabedatum liegt vor dem Datum der DSFA-Version')
        except ValueError:
            errors.append('Freigabedatum fehlt oder ist ungültig (Kopfzeile freigabe_datum)')
    return errors


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--release', action='store_true')
    ap.add_argument('--dsfa', default=str(DEFAULT_DSFA))
    ap.add_argument('--inventar', default=str(dateninventar.INVENTORY_DIR))
    ap.add_argument('--wp-dir', default=str(ROOT / 'docs' / 'umsetzungsplan' / 'wp'))
    a = ap.parse_args(argv)
    errs = check(Path(a.dsfa), Path(a.inventar), Path(a.wp_dir), a.release)
    for e in errs:
        print('FEHLER', e)
    print('DSFA-Gate' + (' (Release)' if a.release else ' (Struktur)') + ':', 'FEHLER' if errs else 'PASS')
    return 1 if errs else 0


if __name__ == '__main__':
    sys.exit(main())
