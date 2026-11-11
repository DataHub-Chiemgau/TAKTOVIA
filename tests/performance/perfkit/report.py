"""Berichtsformat: Prüfung gegen das Schema und Markdown-Darstellung."""
import json

import jsonschema

from . import PERF_DIR

SCHEMA = PERF_DIR / "schema" / "report.schema.json"


def validate(report):
    jsonschema.validate(report, json.loads(SCHEMA.read_text()))
    return report


def render_markdown(r):
    h = r["header"]
    L = [f"# {r['title']}", "", f"Beginn: {r['started_at']} · Art: {r['kind']} · gültig für Abnahme: **{'ja' if r['valid_for_acceptance'] else 'nein'}**", "",
         "## Kopf", "",
         f"- Hardware: {h['hardware'].get('cpu_model')} · {h['hardware'].get('logical_cpus')} logische CPUs · {h['hardware'].get('memory_total_mb', '?')} MB RAM · {h['hardware'].get('os')} · Python {h['hardware'].get('python')} · virtualisiert: {h['hardware'].get('virtualized')}",
         f"- Datenstand: {h['dataset'].get('description')} ({h['dataset'].get('volume')})",
         f"- Stichprobe: n = {h['sample'].get('n')} ({h['sample'].get('rule', '')})",
         f"- Rohdaten: `{h['raw_data']['path']}` (SHA-256 {h['raw_data']['sha256'][:16]}…)", ""]
    if "parameters" in h:
        L += ["- Parameter: `" + json.dumps(h["parameters"], ensure_ascii=False, sort_keys=True) + "`", ""]
    if r["kind"] == "load-run":
        res = r["results"]
        o = res["overall"]
        L += ["## Ergebnis", "", f"Durchsatz {res['throughput_rps']} req/s (Spitze je 1-s-Fenster: {res['peak_rps_1s_bucket']}), Fehlerrate {res['error_rate']:.4f} ({res['error_count']} Fehler)", "",
              "| Endpunkt | n | p50 ms | p90 ms | p95 ms | p99 ms | max ms | Fehler |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for name, s in {"(gesamt)": o, **res["per_endpoint"]}.items():
            if s.get("n"):
                L.append(f"| {name} | {s['n']} | {s['p50_ms']} | {s['p90_ms']} | {s['p95_ms']} | {s['p99_ms']} | {s['max_ms']} | {s.get('error_count', s.get('errors', res['error_count']))} |")
        L.append("")
    L += ["## Bewertung gegen vorläufige Lastenheftziele", "", "| Kriterium | Ziel | gemessen | Urteil |", "|---|---|---|---|"]
    L += [f"| {e['criterion']} | {e['target']} | {e['measured']} | {e['verdict']} |" for e in r["evaluation"]]
    L += ["", "## Offene Pflichtparameter", ""]
    L += [f"- {k}: {v['value'] if v['value'] is not None else 'OFFEN'} – {v['status']}" for k, v in r["open_parameters"].items()]
    L += ["", "## Grenzen", ""] + [f"- {x}" for x in r["limitations"]]
    return "\n".join(L) + "\n"
