"""Kommandozeile: `python tools/stammdaten_import/cli.py --quelle stammdaten.json --api https://… --zugang-datei zugaenge.json --freigeber <Kennung> [--trockenlauf|--fortsetzen]`.

Die Zugänge (Bearer-Tokens je Rolle des Zielmandanten) stehen in einer Datei außerhalb des Repositorys: {"administrator": "...", "planer": "...", "disponent": "...", "personalplanung": "..."}.
Quelldateien mit Personendaten und Zugangsdatei gehören nur in die Zielumgebung, nicht ins Repository und nicht in Logs."""
import argparse
import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stammdaten_import import einspielung, protokoll, quelle  # noqa: E402


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--quelle", required=True)
    p.add_argument("--api", required=True, help="Basis-URL der Anwendung der Zielumgebung")
    p.add_argument("--zugang-datei", required=True)
    p.add_argument("--zustand", default="stammdaten-zustand.json", help="Zustandsdatei für Wiederaufnahme")
    p.add_argument("--protokoll", default="migrationsprotokoll.json")
    p.add_argument("--build", default="unbekannt")
    p.add_argument("--migrationsstand", default="unbekannt")
    p.add_argument("--ausfuehrender")
    p.add_argument("--freigeber")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--trockenlauf", action="store_true")
    g.add_argument("--fortsetzen", action="store_true")
    a = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    z = json.loads(Path(a.zugang_datei).read_text())
    modus = "trockenlauf" if a.trockenlauf else "fortsetzen" if a.fortsetzen else "lauf"
    try:
        prot = einspielung.einspielen(a.quelle, a.api, einspielung.Zugaenge(**z), modus=modus, zustand_pfad=a.zustand, protokoll_pfad=a.protokoll, build=a.build,
                                      migrationsstand=a.migrationsstand, ausfuehrender=a.ausfuehrender, freigeber=a.freigeber)
    except (quelle.QuelleFehler, ValueError, einspielung.MandantAbweichung) as e:
        print("FEHLER:", e, file=sys.stderr)
        return 2
    except einspielung.LaufAbgebrochen as e:
        print("ABGEBROCHEN:", e, "- mit --fortsetzen weitermachen", file=sys.stderr)
        return 3
    print(protokoll.zusammenfassung(prot))
    return 0 if prot["lauf"]["ergebnis"] in ("ABGESCHLOSSEN", "TROCKENLAUF") else 1


if __name__ == "__main__":
    sys.exit(main())
