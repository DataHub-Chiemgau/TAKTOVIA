"""Kommandozeile: `probe` (ein Lauf je Bereich, Rohdaten anhängen, Probenalarm) und `bericht` (Monatsbericht aus Rohdaten).

  python -m taktovia_availability probe --config probe-config.json --rohdaten rohdaten.jsonl [--bereich kernbuchung|operator|beide] [--alarmdatei alarme.jsonl] [--metrikdatei probe.prom]
  python -m taktovia_availability bericht --rohdaten rohdaten.jsonl --monat 2026-11 [--wartung wartungskalender.json] --ausgabe berichte/
"""
import argparse
import json
import sys
from pathlib import Path

from . import bericht as B
from . import proben as P

SCHEMA = Path(__file__).resolve().parents[3] / "docs" / "umsetzungsplan" / "vertraege" / "schemas" / "verfuegbarkeitsbericht.v1.schema.json"


def _config(pfad: Path) -> P.Konfiguration:
    d = json.loads(pfad.read_text(encoding="utf-8"))
    d.pop("_hinweis", None)
    return P.Konfiguration(**d)


def cmd_probe(a) -> int:
    cfg = _config(Path(a.config))
    bereiche = P.BEREICHE if a.bereich == "beide" else (a.bereich,)
    alarme = []

    def alarm(e):
        alarme.append(e)
        print(json.dumps(e, ensure_ascii=False), file=sys.stderr)
        if a.alarmdatei:
            with open(a.alarmdatei, "a", encoding="utf-8") as f:
                f.write(json.dumps(e, ensure_ascii=False) + "\n")

    saetze = [P.probe_ausfuehren(b, cfg, alarm=alarm) for b in bereiche]
    with open(a.rohdaten, "a", encoding="utf-8") as f:
        for s in saetze:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    if a.metrikdatei:
        zeilen = ["# TYPE taktovia_probe_result gauge"]
        for s in saetze:
            for e in P.ERGEBNISSE:
                zeilen.append(f'taktovia_probe_result{{bereich="{s["bereich"]}",ergebnis="{e}"}} {1 if s["ergebnis"] == e else 0}')
        Path(a.metrikdatei).write_text("\n".join(zeilen) + "\n", encoding="utf-8")
    print(json.dumps([{k: s[k] for k in ("bereich", "ergebnis")} for s in saetze], ensure_ascii=False))
    return 0 if all(s["ergebnis"] == P.OK for s in saetze) else 2


def cmd_bericht(a) -> int:
    vertrag = B.lade_vertrag(Path(a.vertrag) if a.vertrag else None)
    wartung = B.lade_wartung(Path(a.wartung) if a.wartung else None)
    saetze, sha = B.lade_rohdaten(Path(a.rohdaten))
    von, bis = B.monat_zeitraum(a.monat) if a.monat else (B.utc(a.von), B.utc(a.bis))
    b = B.erzeuge_bericht(saetze, von, bis, vertrag, wartung, str(a.rohdaten), sha)
    try:
        import jsonschema
        jsonschema.validate(b, json.loads(SCHEMA.read_text(encoding="utf-8")))
    except ImportError:
        print("Hinweis: jsonschema nicht installiert, Schemaprüfung übersprungen", file=sys.stderr)
    out = Path(a.ausgabe)
    out.mkdir(parents=True, exist_ok=True)
    name = a.monat or f"{von:%Y%m%dT%H%M}-{bis:%Y%m%dT%H%M}"
    (out / f"verfuegbarkeit-{name}.json").write_text(json.dumps(b, indent=1, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    (out / f"verfuegbarkeit-{name}.md").write_text(B.als_markdown(b), encoding="utf-8")
    print(json.dumps({k: {"verfuegbarkeit_prozent": v["verfuegbarkeit_prozent"], "aussage": v["aussage"]} for k, v in b["bereiche"].items()}, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("probe")
    p.add_argument("--config", required=True)
    p.add_argument("--rohdaten", required=True)
    p.add_argument("--bereich", choices=[*P.BEREICHE, "beide"], default="beide")
    p.add_argument("--alarmdatei")
    p.add_argument("--metrikdatei")
    p.set_defaults(fn=cmd_probe)
    b = sub.add_parser("bericht")
    b.add_argument("--rohdaten", required=True)
    g = b.add_mutually_exclusive_group(required=True)
    g.add_argument("--monat")
    g.add_argument("--von")
    b.add_argument("--bis")
    b.add_argument("--wartung")
    b.add_argument("--vertrag")
    b.add_argument("--ausgabe", required=True)
    b.set_defaults(fn=cmd_bericht)
    a = ap.parse_args(argv)
    if a.cmd == "bericht" and a.von and not a.bis:
        ap.error("--von braucht --bis")
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
