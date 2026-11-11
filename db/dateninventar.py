#!/usr/bin/env python3
"""Dateninventar mit Zweck und Rechtsgrundlage (WP-M1-32, LH-6.18-SEC-004).

  dateninventar.py check              gleicht das Schema `taktovia` der Datenbank (information_schema.columns) mit dem Inventar ab;
                                      Exitcode 1 bei unklassifizierter Spalte, Eintrag ohne Spalte, unbekanntem Zweck, unbekannter
                                      Rechtsgrundlage, personenbezogenem Feld ohne Rechtsgrundlage/Schutzklasse/Löschklasse
  dateninventar.py export [--write]   lesbarer Export (JSON) für UI-20; mit --write nach web/operator/src/datenschutz/dateninventar.json
  dateninventar.py csv                lesbarer Export als CSV nach stdout

Inventar: db/dateninventar.d/<arbeitspaket>.csv (Zeile je Spalte) und <arbeitspaket>.tabellen.csv (Zeile je Tabelle mit Löschklasse).
Wer eine Migration mit neuer Tabelle oder Spalte schreibt, ergänzt im selben Zug seine Datei (eine Datei je Arbeitspaket gegen
Merge-Konflikte). Katalog: db/dateninventar_katalog.json (vorläufig, nicht freigegeben, OP-24).
Verbindung wie db.py über TAKTOVIA_DB_*-Umgebungsvariablen (Administrator, nur lesend).
"""
import csv
import io
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
INVENTORY_DIR = HERE / 'dateninventar.d'
CATALOG = HERE / 'dateninventar_katalog.json'
UI_EXPORT = HERE.parent / 'web/operator/src/datenschutz/dateninventar.json'
SCHEMA = 'taktovia'

COLUMN_FIELDS = ['tabelle', 'spalte', 'personenbezogen', 'zweck', 'rechtsgrundlage', 'schutzklasse', 'loeschklasse', 'bemerkung']
TABLE_FIELDS = ['tabelle', 'aggregat', 'loeschklasse', 'bemerkung']


class InventoryError(Exception):
    pass


def load_catalog(path: Path = CATALOG) -> dict:
    return json.loads(path.read_text(encoding='utf-8'))


def _read(path: Path, fields: list[str]) -> list[dict]:
    with path.open(encoding='utf-8', newline='') as f:
        reader = csv.DictReader(f, delimiter=';')
        if reader.fieldnames != fields:
            raise InventoryError(f'{path.name}: Kopfzeile muss {";".join(fields)} lauten, ist {";".join(reader.fieldnames or [])}')
        rows = []
        for n, row in enumerate(reader, start=2):
            if None in row or any(v is None for v in row.values()):
                raise InventoryError(f'{path.name}:{n}: falsche Spaltenzahl')
            rows.append({k: v.strip() for k, v in row.items()})
        return rows


def load_inventory(directory: Path = INVENTORY_DIR):
    """-> (spalten[(tabelle, spalte)] -> Zeile, tabellen[tabelle] -> Zeile). Doppelte Einträge sind ein Fehler."""
    columns, tables = {}, {}
    for path in sorted(directory.glob('*.csv')):
        if path.name.endswith('.tabellen.csv'):
            for row in _read(path, TABLE_FIELDS):
                if row['tabelle'] in tables:
                    raise InventoryError(f'{path.name}: Tabelle {row["tabelle"]} doppelt im Inventar')
                tables[row['tabelle']] = row
        else:
            for row in _read(path, COLUMN_FIELDS):
                key = (row['tabelle'], row['spalte'])
                if key in columns:
                    raise InventoryError(f'{path.name}: {row["tabelle"]}.{row["spalte"]} doppelt im Inventar')
                columns[key] = row
    return columns, tables


def schema_columns(conn) -> list[tuple[str, str]]:
    rows = conn.execute('SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = %s '
                        'ORDER BY table_name, ordinal_position', (SCHEMA,)).fetchall()
    return [(t, c) for t, c in rows]


def check(schema: list[tuple[str, str]], columns: dict, tables: dict, catalog: dict) -> list[str]:
    """Liefert die Befunde (leer = in Ordnung)."""
    problems = []
    existing = set(schema)
    for t, c in schema:
        if (t, c) not in columns:
            problems.append(f'{t}.{c}: Spalte ohne Inventareintrag')
    for t in sorted({t for t, _ in schema}):
        if t not in tables:
            problems.append(f'Tabelle {t} ohne Löschklasse im Inventar')
    for t, row in tables.items():
        if t not in {x for x, _ in schema}:
            problems.append(f'Tabelle {t}: nicht im Schema, Inventareintrag entfernen')
        elif row['loeschklasse'] not in catalog['loeschklassen']:
            problems.append(f'Tabelle {t}: unbekannte Löschklasse {row["loeschklasse"]!r}')
    for (t, c), row in columns.items():
        where = f'{t}.{c}'
        if (t, c) not in existing:
            problems.append(f'{where}: Inventareintrag, aber Spalte nicht im Schema')
            continue
        if row['personenbezogen'] not in ('ja', 'nein'):
            problems.append(f'{where}: personenbezogen muss ja oder nein sein, ist {row["personenbezogen"]!r}')
            continue
        if not row['zweck']:
            problems.append(f'{where}: Feld ohne Zweck')
        elif row['zweck'] not in catalog['zwecke']:
            problems.append(f'{where}: unbekannter Zweck {row["zweck"]!r}')
        for feld, katalog, name in (('rechtsgrundlage', 'rechtsgrundlagen', 'Rechtsgrundlage'),
                                    ('schutzklasse', 'schutzklassen', 'Schutzklasse'),
                                    ('loeschklasse', 'loeschklassen', 'Löschklasse')):
            value = row[feld]
            if row['personenbezogen'] == 'ja' and not value:
                problems.append(f'{where}: personenbezogen ohne {name}')
            elif value and value not in catalog[katalog]:
                problems.append(f'{where}: unbekannte {name} {value!r}')
    return problems


def export_document(columns: dict, tables: dict, catalog: dict) -> dict:
    """Maschinen- und menschenlesbarer Export (Format: docs/umsetzungsplan/vertraege/schemas/dateninventar.v1.schema.json)."""
    out = []
    for t in sorted(tables):
        out.append({
            'tabelle': t, 'aggregat': tables[t]['aggregat'], 'loeschklasse': tables[t]['loeschklasse'], 'bemerkung': tables[t]['bemerkung'],
            'spalten': [{k: row[k] for k in COLUMN_FIELDS[1:]} for (tt, _), row in sorted(columns.items()) if tt == t],
        })
    for t in sorted({t for t, _ in columns} - set(tables)):  # nur bei fehlerhaftem Inventar; der Export bleibt vollständig lesbar
        out.append({'tabelle': t, 'aggregat': '', 'loeschklasse': '', 'bemerkung': '',
                    'spalten': [{k: row[k] for k in COLUMN_FIELDS[1:]} for (tt, _), row in sorted(columns.items()) if tt == t]})
    return {
        'format': 'dateninventar.v1',
        'freigegeben': catalog['freigegeben'],
        'hinweis': catalog['hinweis'],
        'kataloge': {k: catalog[k] for k in ('zwecke', 'rechtsgrundlagen', 'schutzklassen', 'loeschklassen')},
        'tabellen': out,
    }


def export_csv(document: dict) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=';', lineterminator='\n')
    w.writerow(['tabelle', 'aggregat_loeschklasse'] + COLUMN_FIELDS[1:])
    for t in document['tabellen']:
        for s in t['spalten']:
            w.writerow([t['tabelle'], t['loeschklasse']] + [s[k] for k in COLUMN_FIELDS[1:]])
    return buf.getvalue()


def json_text(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + '\n'


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else 'check'
    columns, tables = load_inventory()
    catalog = load_catalog()
    if cmd == 'check':
        sys.path.insert(0, str(HERE))
        import db as dbtool
        with dbtool.admin(dbtool.env('TAKTOVIA_DB_NAME', 'taktovia')) as c:
            schema = schema_columns(c)
        problems = check(schema, columns, tables, catalog)
        for p in problems:
            print('FEHLER', p)
        print(f'Dateninventar: {len(schema)} Spalten in {len({t for t, _ in schema})} Tabellen, {len(problems)} Befunde, '
              f'{sum(1 for p in problems if "ohne Inventareintrag" in p)} unklassifiziert, Katalog '
              f'{"freigegeben" if catalog["freigegeben"] else "NICHT freigegeben (OP-24)"}')
        return 1 if problems else 0
    doc = export_document(columns, tables, catalog)
    if cmd == 'export':
        if '--write' in argv:
            UI_EXPORT.parent.mkdir(parents=True, exist_ok=True)
            UI_EXPORT.write_text(json_text(doc), encoding='utf-8')
        else:
            sys.stdout.write(json_text(doc))
        return 0
    if cmd == 'csv':
        sys.stdout.write(export_csv(doc))
        return 0
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
