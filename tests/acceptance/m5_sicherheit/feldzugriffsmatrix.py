"""WP-M5-17: Feldzugriffsmatrix Rolle × Feldklasse (PII, Unterstützungsmerkmale, HR, Finanzen), erzeugt aus den führenden Quellen statt von Hand gepflegt.

Ebenen:
  API       Rechtematrix (`rechtematrix.csv`): je Anwendungsrolle und Datenklasse PII/HR/FINANZ die Zahl erlaubter von allen Operationen dieser Klasse.
  FELDRECHT Feldrechte (`feldrechte.json`, WP-M1-31): je Anwendungsrolle die Feldklassen (PII, SUPPORT, PAYMENT_TOKEN) mit Zweck; alles andere ist verweigert (fail-closed).
  DB        Spaltenrechte der Datenbankrollen (Systemkatalog, `has_column_privilege`) auf die Spalten des Dateninventars mit Schutzklasse, je Schutzklasse lesbare von allen Spalten.
Aufruf: python feldzugriffsmatrix.py (schreibt feldzugriffsmatrix.csv neu; braucht Datenbankzugang wie db/db.py). Der Test vergleicht die Zugriffsmuster.
"""
import csv
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MATRIX = REPO / "backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv"
FELDRECHTE = REPO / "backend/platform/src/main/resources/fieldprotection/feldrechte.json"
INVENTAR = REPO / "db/dateninventar.d"
OUT = Path(__file__).resolve().parent / "feldzugriffsmatrix.csv"
DB_ROLLEN = ["taktovia_runtime", "taktovia_fields", "taktovia_mod_stafftime", "taktovia_mod_docsfin", "taktovia_mod_analytics"]
KOPF = ["ebene", "rolle", "feldklasse", "erlaubt", "gesamt"]


def api_zeilen():
    lines = [l for l in MATRIX.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]
    header = lines[0].split(";")
    rows = [dict(zip(header, l.split(";"))) for l in lines[1:]]
    rollen = header[4:]
    out = []
    for klasse in ("PII", "HR", "FINANZ"):
        ops = [r for r in rows if r["datenklasse"] == klasse]
        for rolle in rollen:
            out.append(["API", rolle, klasse, sum(r[rolle] == "J" for r in ops), len(ops)])
    return out


def feldrecht_zeilen():
    doc = json.loads(FELDRECHTE.read_text(encoding="utf-8"))
    rollen = sorted({g.get("role", "<dienstkonto>") for f in doc["fields"] for g in f["grants"]} | {"tenant_admin", "passenger", "driver", "finance", "callcenter", "support", "dispatcher"})
    out = []
    for klasse in sorted({f["class"] for f in doc["fields"]}):
        felder = [f for f in doc["fields"] if f["class"] == klasse]
        for rolle in rollen:
            n = sum(any(g.get("role") == rolle for g in f["grants"]) for f in felder)
            out.append(["FELDRECHT", rolle, klasse, n, len(felder)])
    return out


def inventar_spalten():
    """(tabelle, spalte, schutzklasse) aller Inventarzeilen mit Schutzklasse."""
    out = []
    for p in sorted(INVENTAR.glob("*.csv")):
        if p.name.endswith(".tabellen.csv"):
            continue
        with p.open(encoding="utf-8-sig", newline="") as f:
            for r in csv.DictReader(f, delimiter=";"):
                if r.get("schutzklasse"):
                    out.append((r["tabelle"], r["spalte"], r["schutzklasse"]))
    return out


def db_zeilen(conn):
    spalten = inventar_spalten()
    vorhanden = {(t, c) for t, c in conn.execute("SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='taktovia'").fetchall()}
    spalten = [s for s in spalten if (s[0], s[1]) in vorhanden]
    out = []
    for klasse in sorted({s[2] for s in spalten}):
        cols = [s for s in spalten if s[2] == klasse]
        for rolle in DB_ROLLEN:
            n = sum(conn.execute("SELECT has_column_privilege(%s, %s::regclass, %s, 'SELECT')", (rolle, f"taktovia.{t}", c)).fetchone()[0] for t, c, _ in cols)
            out.append(["DB", rolle, klasse, n, len(cols)])
    return out


def alle_zeilen(conn=None):
    z = api_zeilen() + feldrecht_zeilen()
    return z + (db_zeilen(conn) if conn is not None else [])


def lies(path=OUT):
    with path.open(encoding="utf-8", newline="") as f:
        return [[r[k] if k in ("ebene", "rolle", "feldklasse") else int(r[k]) for k in KOPF] for r in csv.DictReader(f, delimiter=";")]


def schreibe(zeilen, path=OUT):
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";", lineterminator="\n")
        w.writerow(KOPF)
        w.writerows(zeilen)


if __name__ == "__main__":
    sys.path.insert(0, str(REPO / "db"))
    import os
    import db as dbtool
    with dbtool.admin(os.environ.get("TAKTOVIA_DB_NAME", "taktovia")) as c:
        zeilen = alle_zeilen(c)
    schreibe(zeilen)
    print(f"{len(zeilen)} Zeilen nach {OUT}")
