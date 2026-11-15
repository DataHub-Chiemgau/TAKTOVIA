#!/usr/bin/env python3
"""Regressionslauf aller Gate-Suiten auf EINEM festgelegten Build (WP-M5-31, Akzeptanzkriterium 7: kein Mischen von Builds).

  python tests/acceptance/m5_gate/run.py ...   (einzelne Suite)
  python tests/acceptance/m5_gate/regression.py --build <Commit> [--write-e2e] [--skip m2,m4] [--prefix taktovia_w18]

Führt nacheinander die Suiten M1, M2, M3, M4, AT-23 (WP-M5-15), AT-59 (WP-M5-20) und AT-25/-53/-54/-61 (dieses Paket) aus. Jede Suite erzeugt Evidenz nach Kap. 08 §8.4 und, mit --write-e2e,
die Einträge in docs/pflichtenheft/e2e.csv ausschließlich aus ihrem Lauf. Damit alle Evidenz denselben Build trägt, wird die Build-Kennung aller Suiten auf `--build` festgelegt (sonst würde jede
Suite `+lokal` anhängen, sobald die vorige Evidenz schreibt). Der Build ist der geprüfte CODE-Stand (Commit); Evidenz und e2e.csv werden danach in einem eigenen Commit abgelegt.
Exit 0 nur, wenn alle ausgeführten Suiten grün sind. Rote Suiten werden ausgewiesen, nicht verschwiegen."""
import argparse
import importlib
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
sys.path.insert(0, str(ACC))

SUITEN = [("m1", "m1_gate.run"), ("m2", "m2_gate.run"), ("m3", "m3_gate.run"), ("m4", "m4_gate.run"), ("at23", "m5_at23.run"), ("at59", "m5_at59.run"), ("m5gate", "m5_gate.run")]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--build", required=True, help="Commit des geprüften Codestands (wird in jede Evidenz eingetragen)")
    ap.add_argument("--write-e2e", action="store_true")
    ap.add_argument("--skip", default="")
    ap.add_argument("--prefix", default="taktovia_at_reg")
    a = ap.parse_args(argv)
    skip = {s for s in a.skip.split(",") if s}
    import m1_gate.run as m1
    for _, mod in SUITEN:
        m = importlib.import_module(mod)
        m.build_id = lambda: a.build            # feste Build-Kennung für alle Suiten
        if mod != "m1_gate.run":
            m1.build_id = lambda: a.build
    ergebnis = {}
    for name, mod in SUITEN:
        if name in skip:
            ergebnis[name] = "übersprungen"
            continue
        m = importlib.import_module(mod)
        args = ["--db", f"{a.prefix}_{name}"] + (["--write-e2e"] if a.write_e2e else [])
        t0 = time.time()
        print(f"=== Suite {name} ({mod}) auf Build {a.build[:12]} ===", flush=True)
        try:
            rc = m.main(args)
        except SystemExit as e:
            rc = e.code or 0
        except Exception as e:  # eine fehlerhafte Suite darf die übrigen nicht verdecken
            print(f"Suite {name} abgebrochen: {type(e).__name__}: {e}", flush=True)
            rc = 99
        ergebnis[name] = f"{'GRÜN' if rc == 0 else 'ROT'} (Exit {rc}, {int(time.time() - t0)} s)"
    print("\n=== Zusammenfassung Regressionslauf ===")
    for k, v in ergebnis.items():
        print(f"{k:8} {v}")
    return 0 if all(v.startswith(("GRÜN", "übersprungen")) for v in ergebnis.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
