#!/usr/bin/env python3
"""Läufer des Referenzfalls als Wiederholung von AT-01 (WP-M5-23): startet die Referenzwelt, führt den Referenzfall mit Zahlung (Mandant A, B) und ohne Zahlung (kostenfreier Tarif) unter dem Wächter aus,
prüft die Kennzahlen (drei Fahrgäste, Kleingruppenschwelle) und schreibt den Evidenzdatensatz nach tests/acceptance/evidence/m5/. e2e.csv wird hier NICHT geschrieben: die Zeile AT-01 gehört nach der Rückverfolgbarkeitsregel R3 (tests/acceptance/m1_gate/traceability.py) der Suite M2 (`m2_gate/run.py --write-e2e`); der Lauf
dieser Datei ist eine zusätzliche Wiederholung in der Ersatzumgebung mit eigenem Evidenzdatensatz unter evidence/m5.

  python tests/acceptance/e2e/run.py [--out tests/acceptance/evidence/m5]

ERSATZUMGEBUNG, kein Staging (siehe referenzwelt.ERSATZ); der Evidenzdatensatz nennt die Ersatzkomponenten. Die Abnahmeentscheidung bleibt `PENDING` (Auftraggeber, D-12)."""
import argparse
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
REPO = ACC.parents[1]
sys.path.insert(0, str(ACC))
EVIDENCE_DIR = ACC / "evidence" / "m5"
TEST_ID = "AT-01"


def lauf(out_dir):
    from m1_gate.run import build_id
    os.environ["TAKTOVIA_BUILD_ID"] = build_id()
    from e2e import evidenz
    from e2e.referenzfall import durchlaufen, kennzahlen, warte, Http
    from e2e.referenzwelt import Referenzwelt
    from e2e.waechter import Waechter
    welt = Referenzwelt(dbname="taktovia_at_e2e_lauf").starten()
    wa = Waechter(welt.welt)
    try:
        wa.installieren()
        wa.start()
        faelle, pruefungen = [], []
        for i in (0, 1, 2):   # Mandant A und B mit Zahlung, dritter Mandant kostenfrei (ohne Zahlung)
            (f,), _ = durchlaufen(welt.welt, welt.mandanten[i])
            faelle.append(f)
            pruefungen.append((f"[{welt.mandanten[i].name}] Zahlungsvariante: payment_intent-Anzahl", 1 if welt.mandanten[i].preis > 0 else 0, len(f.zahlung())))
        m = welt.mandanten[3]
        fk, _ = durchlaufen(welt.welt, m, anzahl=3)
        faelle += fk
        http = Http(welt.welt)
        k, as_of = warte(lambda: (lambda r: r if all(r[0][x]["state"] == "OK" for x in ("requests", "offer_rate", "booking_conversion", "pickup_on_time", "arrival_on_time")) else None)(kennzahlen(welt.welt, m, http=http)), 60, 1.0, "Kennzahlen")
        for metric, erwartet in {"requests": (3, None), "offer_rate": (3, 3), "booking_conversion": (3, 3), "pickup_on_time": (3, 3), "arrival_on_time": (3, 3)}.items():
            pruefungen.append((f"Kennzahl {metric}: Zähler/Nenner mit Datenstand {as_of}", list(erwartet), [k[metric]["numerator"], k[metric]["denominator"]]))
        bericht = wa.ende()
        record, path = evidenz.aufzeichnen(faelle, bericht, pruefungen, out_dir)
        record["_path"] = str(path)
        return record
    finally:
        wa.entfernen()
        welt.stoppen()


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(EVIDENCE_DIR))
    a = ap.parse_args(argv)
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("AT-01-*.json"):
        old.unlink()
    record = lauf(out)
    falsch = [c["step"] for c in record["soll_ist"] if not c["ok"]]
    print(f"AT-01 (Ersatzumgebung) {record['result']} {len(record['soll_ist'])} Prüfungen" + (f"  rot: {falsch}" if falsch else ""))
    return 0 if record["result"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
