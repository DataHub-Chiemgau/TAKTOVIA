"""Lastgenerator (festgeschriebenes Werkzeug, nur stdlib) mit tatsächlich gemessenen Perzentilen.

Geschlossene Last: `concurrency` Threads senden nacheinander Anfragen gemäß Endpointmix (gewichtet, Seed-gesteuert).
Gemessen wird je Anfrage die Zeit von Sendebeginn bis zum vollständig gelesenen Antworttext (time.perf_counter).
Anfragen in der Aufwärmphase werden in den Rohdaten markiert, aber nicht in die Kennzahlen eingerechnet.
Grenze: Ein Python-Generator belastet eine Maschine nur begrenzt; der Bericht nennt Hardware und erzielte Rate.
"""
import csv
import hashlib
import json
import random
import threading
import time
from datetime import datetime, timezone
from http.client import HTTPConnection
from pathlib import Path
from urllib.parse import urlsplit

from . import hardware
from .profile import load as load_profile
from .stats import summarize

LIMITATIONS = [
    "Perzentile sind Nearest-Rank über die tatsächlich gemessenen Latenzen der Messphase (keine Schätzung, keine Interpolation).",
    "Geschlossene Last (feste Parallelität): bei langsamer Antwort sinkt die Anfragerate (coordinated omission möglich).",
    "Referenzziele sind vorläufige Lastenheftziele, keine gemessene Leistung des Produkts (Kap. 08 §8.6).",
]


def _worker(idx, base, profile, deadline_total, t0, warmup_s, out, stop):
    load = profile["load"]
    rnd = random.Random(load.get("seed", 1) * 1000 + idx)
    mix = profile["endpoint_mix"]
    weights = [e["weight"] for e in mix]
    parts = urlsplit(base)
    timeout = load.get("request_timeout_s", 10)
    conn = None
    think = load.get("think_time_ms", 0) / 1000
    while time.perf_counter() < deadline_total and not stop.is_set():
        e = rnd.choices(mix, weights)[0]
        body = json.dumps(e["body"]).encode() if e.get("body") else None
        headers = dict(e.get("headers", {}))
        if body is not None:
            headers.setdefault("Content-Type", "application/json")
        start = time.perf_counter()
        status, err = None, None
        try:
            if conn is None:
                conn = HTTPConnection(parts.hostname, parts.port, timeout=timeout)
            conn.request(e["method"], e["path"], body=body, headers=headers)
            r = conn.getresponse()
            r.read()
            status = r.status
            if r.will_close:
                conn.close()
                conn = None
        except Exception as ex:  # noqa: BLE001 - jede Störung zählt als Fehler und wird protokolliert
            err = type(ex).__name__
            if conn is not None:
                conn.close()
            conn = None
        lat = (time.perf_counter() - start) * 1000
        ok = err is None and status in e.get("expect_status", [200])
        out.append((start - t0, e["name"], status if status is not None else err, lat, ok, (start - t0) < warmup_s))
        if think:
            time.sleep(think)
    if conn is not None:
        conn.close()


def run_profile(profile, base_url=None, out_dir=None, title=None):
    """Führt ein (bereits geprüftes) Profil aus und liefert den Bericht (dict); schreibt Rohdaten/Bericht nach out_dir."""
    profile = load_profile(profile)
    base = base_url or profile["target"]["base_url"]
    load = profile["load"]
    warmup = load.get("warmup_s", 0)
    started = datetime.now(timezone.utc)
    t0 = time.perf_counter()
    deadline = t0 + warmup + load["duration_s"]
    stop = threading.Event()
    buckets = [[] for _ in range(load["concurrency"])]
    ts = [threading.Thread(target=_worker, args=(i, base, profile, deadline, t0, warmup, buckets[i], stop), daemon=True)
          for i in range(load["concurrency"])]
    [t.start() for t in ts]
    [t.join() for t in ts]
    rows = sorted((r for b in buckets for r in b), key=lambda r: r[0])
    measured = [r for r in rows if not r[5]]
    raw_path = None
    raw_sha = hashlib.sha256(b"").hexdigest()
    if out_dir:
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        raw_path = out_dir / "rohdaten.csv"
        with raw_path.open("w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["offset_s", "endpoint", "status_or_error", "latency_ms", "ok", "warmup"])
            for r in rows:
                w.writerow([f"{r[0]:.6f}", r[1], r[2], f"{r[3]:.3f}", int(r[4]), int(r[5])])
        raw_sha = hashlib.sha256(raw_path.read_bytes()).hexdigest()
    span = max(1e-9, (measured[-1][0] - measured[0][0])) if len(measured) > 1 else load["duration_s"]
    per_second = {}
    for r in measured:
        per_second[int(r[0])] = per_second.get(int(r[0]), 0) + 1
    errors = [r for r in measured if not r[4]]
    per_ep = {}
    for name in sorted({r[1] for r in measured}):
        lat = [r[3] for r in measured if r[1] == name]
        per_ep[name] = dict(summarize(lat), errors=sum(1 for r in measured if r[1] == name and not r[4]))
    results = {"overall": summarize([r[3] for r in measured]), "per_endpoint": per_ep,
               "error_count": len(errors), "error_rate": (len(errors) / len(measured)) if measured else 1.0,
               "throughput_rps": round(len(measured) / span, 2) if measured else 0.0,
               "peak_rps_1s_bucket": max(per_second.values()) if per_second else 0,
               "status_counts": _counts(r[2] for r in measured)}
    ref = profile["reference_targets"]
    is_example = profile["mode"] == "example"
    evaluation = []
    for e in profile["endpoint_mix"]:
        cls = e.get("target_class", "none")
        key = {"standard_api": "standard_api_p95_s", "offer": "offer_p95_s", "driver_plan": "driver_plan_p95_s"}.get(cls)
        if key and e["name"] in per_ep and per_ep[e["name"]].get("n"):
            m = per_ep[e["name"]]["p95_ms"] / 1000
            evaluation.append({"criterion": f"p95 {e['name']} ({cls})", "target": f"<= {ref[key]} s (vorläufiges Lastenheftziel)",
                               "measured": f"{m:.4f} s", "verdict": "nicht bewertet" if is_example else ("erfüllt" if m <= ref[key] else "verfehlt")})
    evaluation.append({"criterion": "Fehlerrate", "target": f"<= {profile['max_error_rate']}", "measured": f"{results['error_rate']:.4f}",
                       "verdict": "nicht bewertet" if is_example else ("erfüllt" if results["error_rate"] <= profile["max_error_rate"] else "verfehlt")})
    open_ok = all(o["value"] is not None for o in profile["open_parameters"].values())
    report = {
        "report_version": "1.0", "kind": "load-run", "title": title or f"Lastlauf {profile['name']}",
        "started_at": started.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "header": {"hardware": hardware.collect(), "dataset": profile["dataset"],
                   "sample": {"n": len(measured), "n_warmup_excluded": len(rows) - len(measured),
                              "rule": "alle Anfragen der Messphase nach der Aufwärmphase"},
                   "raw_data": {"path": str(raw_path) if raw_path else "nicht geschrieben", "sha256": raw_sha},
                   "parameters": {"profile": profile["name"], "mode": profile["mode"], "target": base, "load": load,
                                  "endpoint_mix": [{"name": e["name"], "weight": e["weight"]} for e in profile["endpoint_mix"]],
                                  "candidate_fleet": profile["candidate_fleet"], "partner_share": profile["partner_share"]}},
        "results": results, "evaluation": evaluation, "open_parameters": profile["open_parameters"],
        "valid_for_acceptance": (not is_example) and open_ok,
        "limitations": LIMITATIONS + (["Beispielprofil: Werkzeugnachweis, keine Abnahmeaussage."] if is_example else [])
                       + ([] if open_ok else ["Offene Pflichtparameter (Peak-Requests/s, aktive Mandanten) nicht vereinbart: kein Abnahmenachweis."]),
    }
    if out_dir:
        (out_dir / "bericht.json").write_text(json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n")
        from .report import render_markdown
        (out_dir / "bericht.md").write_text(render_markdown(report))
    return report


def _counts(it):
    out = {}
    for x in it:
        out[str(x)] = out.get(str(x), 0) + 1
    return out
