#!/usr/bin/env python3
"""Runner für Lastprofile (WP-M0-25).

  python tests/performance/run.py --profile tests/performance/profiles/health-example.json [--target URL | --stub-target]
                                  [--out DIR]

--stub-target startet einen lokalen Platzhalter-Server mit /actuator/health (Werkzeugnachweis ohne Backend).
Exit-Codes: 0 Lauf erzeugt Bericht; 3 Profil abgewiesen (Pflichtparameter fehlen); 1 Fehlerrate über Schwelle (nur measurement).
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from perfkit import PERF_DIR  # noqa: E402
from perfkit import profile as pf, report, runner  # noqa: E402


def stub_server():
    from taktovia_testkit.jsonhttp import App, Response
    app = App()
    app.route("GET", r"/actuator/health")(lambda req: Response(200, {"status": "UP"}))
    app.start()
    return app


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--target")
    ap.add_argument("--stub-target", action="store_true")
    ap.add_argument("--out", default=str(PERF_DIR / "out"))
    a = ap.parse_args(argv)
    try:
        prof = pf.load(a.profile)
    except pf.ProfileError as e:
        print("Profil abgewiesen:", *e.problems, sep="\n  ", file=sys.stderr)
        return 3
    app = stub_server() if a.stub_target else None
    try:
        target = app.url if app else a.target
        out = Path(a.out) / (prof["name"] + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        rep = runner.run_profile(prof, target, out, title=f"Lastlauf {prof['name']}" + (" (Platzhalter-Server, kein Backend)" if app else ""))
        report.validate(rep)
    finally:
        if app:
            app.stop()
    o = rep["results"]["overall"]
    print(f"{prof['name']}: n={o['n']} p50={o['p50_ms']} ms p95={o['p95_ms']} ms p99={o['p99_ms']} ms "
          f"Fehlerrate={rep['results']['error_rate']:.4f} gültig für Abnahme: {rep['valid_for_acceptance']}")
    print(f"Bericht: {out / 'bericht.md'}")
    if prof["mode"] == "measurement" and rep["results"]["error_rate"] > prof["max_error_rate"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
