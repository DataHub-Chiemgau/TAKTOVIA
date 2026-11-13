"""Statische Prüfung des Telemetrie-Ausbaus (WP-M5-03): Scrape-Ziele je Komponente, Collector-Pipelines mit Maskierung, Dashboards nur mit
tatsächlich im Code definierten Metriken, Aufbewahrung, Betriebszugang, Gateway-Zugriffslog ohne Query und Kopfzeilen.
Nichts davon startet Dienste (kein Docker); die Prüfung ersetzt keinen Lauf gegen Prometheus, Tempo, Loki oder Grafana."""
import json
import pathlib
import re
import sys

import yaml

REQUIRED_JOBS = {"backend-app": "fachkern", "routing-worker": "routing-worker", "documents-worker": "documents-worker",
                 "rabbitmq": "rabbitmq", "redis": "redis", "postgres": "postgres"}
FORBIDDEN_LOG_VARS = ("$request ", "$request\"", "$request'", "$query_string", "$args", "$is_args", "$http_authorization", "$http_cookie", "$http_referer",
                      "$http_user_agent", "$remote_user", "$request_body", "$request_uri")
EXTERNAL_PREFIXES = ("hikaricp_", "rabbitmq_", "redis_", "pg_", "jvm_", "http_server_")
METRIC = re.compile(r"\b(taktovia_[a-z0-9_]+)")
PREFIXED_EXTERNAL = re.compile(r"\b(" + "|".join(EXTERNAL_PREFIXES) + r")[a-z0-9_]+")


def defined_metrics(repo):
    names = set()
    for base, glob in ((repo / "backend", "**/src/main/**/*.kt"), (repo / "workers", "**/src/**/*.py")):
        for f in base.glob(glob):
            names |= set(re.findall(r'"(taktovia_[a-z0-9_]+)"', f.read_text(encoding="utf-8")))
    for f in (repo / "infra" / "backup").glob("taktovia_backup/*.py"):    # Betriebsmetriken der Datensicherung (WP-M5-14, Textfile-Collector)
        names |= set(re.findall(r'"(taktovia_(?:backup|wal|standby)_[a-z0-9_]+)"', f.read_text(encoding="utf-8")))
    return names


def check(root, repo, errs, notes):
    prom = yaml.safe_load((root / "observability" / "prometheus.yml").read_text(encoding="utf-8"))
    jobs = {j["job_name"]: j for j in prom["scrape_configs"]}
    for job, comp in REQUIRED_JOBS.items():
        if job not in jobs:
            errs.append(f"prometheus: Scrape-Ziel {job} fehlt")
            continue
        labels = jobs[job]["static_configs"][0].get("labels", {})
        if labels.get("component") != comp:
            errs.append(f"prometheus: {job} ohne component-Label '{comp}'")
    if ":8080" in json.dumps(jobs.get("backend-app", {})):
        errs.append("prometheus: Fachkern wird auf dem API-Port gescrapt (Verwaltungsport verlangt)")

    otel = yaml.safe_load((root / "observability" / "otel-collector-config.yaml").read_text(encoding="utf-8"))
    pl = otel["service"]["pipelines"]
    for name in ("traces", "metrics", "logs"):
        if name not in pl:
            errs.append(f"otel: Pipeline {name} fehlt")
    if "transform/log_masking" not in pl.get("logs", {}).get("processors", []):
        errs.append("otel: Log-Pipeline ohne Maskierung (transform/log_masking)")
    if not any(e.startswith("otlp") for e in pl.get("traces", {}).get("exporters", [])):
        errs.append("otel: Trace-Pipeline ohne Exporter in die Betriebsplattform")
    if not any("loki" in e for e in pl.get("logs", {}).get("exporters", [])):
        errs.append("otel: Log-Pipeline ohne Exporter in die zentrale Logablage")

    dash_dir = root / "observability" / "dashboards"
    known = defined_metrics(repo)
    used = set()
    files = sorted(dash_dir.glob("*.json"))
    if not files:
        errs.append("dashboards: keine Dashboards")
    for f in files:
        d = json.loads(f.read_text(encoding="utf-8"))
        if not d.get("panels"):
            errs.append(f"dashboards: {f.name} ohne Panels")
        for p in d.get("panels", []):
            for t in p.get("targets", []):
                for m in METRIC.findall(t["expr"]):
                    base = re.sub(r"_(bucket|sum|count)$", "", m)
                    used.add(base)
                    if base not in known:
                        errs.append(f"dashboards: {f.name} verwendet im Code nicht definierte Metrik {m}")
    for comp_metric in ("taktovia_http_server_request_seconds", "taktovia_outbox_depth", "taktovia_outbox_publish_delay_seconds", "taktovia_inbox_lag_seconds",
                        "taktovia_operation_open", "taktovia_operation_lease_overdue", "taktovia_payment_intent_indeterminate", "taktovia_db_lock_wait_seconds",
                        "taktovia_routing_solver_seconds", "taktovia_documents_task_seconds"):
        if comp_metric not in used:
            errs.append(f"dashboards: Pflichtmetrik {comp_metric} wird nirgends dargestellt")

    comp = yaml.safe_load((root / "compose" / "docker-compose.telemetry.yml").read_text(encoding="utf-8"))
    graf = comp["services"]["grafana"]["environment"]
    if str(graf.get("GF_AUTH_ANONYMOUS_ENABLED")).lower() != "false":
        errs.append("grafana: anonymer Zugang nicht ausgeschlossen (Betriebszugang getrennt von Mandantenrollen)")
    for svc, conf in comp["services"].items():
        for k, v in (conf.get("environment") or {}).items():
            if re.search(r"(?i)(password|secret|token|dsn)$", k) and v and not str(v).startswith("${"):
                errs.append(f"compose: {svc}.{k} trägt einen Wert statt einer Secret-Referenz")
    loki = yaml.safe_load((root / "observability" / "loki.yaml").read_text(encoding="utf-8"))
    if not loki.get("limits_config", {}).get("retention_period"):
        errs.append("loki: Aufbewahrung nicht festgelegt")
    tempo = yaml.safe_load((root / "observability" / "tempo.yaml").read_text(encoding="utf-8"))
    if not tempo.get("compactor", {}).get("compaction", {}).get("block_retention"):
        errs.append("tempo: Aufbewahrung nicht festgelegt")
    if not any("retention" in str(c) for c in yaml.safe_load((root / "compose" / "docker-compose.yml").read_text(encoding="utf-8"))["services"]["prometheus"]["command"]):
        errs.append("prometheus: Aufbewahrung nicht festgelegt")

    gw = (root / "gateway" / "nginx.conf.template").read_text(encoding="utf-8")
    m = re.search(r"log_format\s+taktovia_masked[^;]*;", gw, re.S)
    if not m:
        errs.append("gateway: log_format taktovia_masked fehlt")
    else:
        for bad in FORBIDDEN_LOG_VARS:
            if bad in m.group(0):
                errs.append(f"gateway: Zugriffslog enthält {bad.strip()} (Query/Kopfzeilen im Klartext)")
    if "access_log /dev/stdout taktovia_masked" not in gw:
        errs.append("gateway: access_log nutzt nicht taktovia_masked")
    notes.append("telemetrie: Scrape-Ziele, Pipelines, Dashboards (nur im Code definierte Metriken), Aufbewahrung, Gateway-Log statisch geprüft (Dienste NICHT gestartet)")


def run(root, repo=None):
    root = pathlib.Path(root)
    repo = pathlib.Path(repo) if repo else root.parent
    errs, notes = [], []
    try:
        check(root, repo, errs, notes)
    except Exception as e:  # fehlende Datei = Fehler, kein Absturz
        errs.append(f"check_telemetry: {type(e).__name__}: {e}")
    return errs, notes


if __name__ == "__main__":
    e, n = run(pathlib.Path(__file__).resolve().parent)
    for x in n:
        print("ok   ", x)
    for x in e:
        print("FEHLER", x)
    print("ERGEBNIS:", "ROT" if e else "GRUEN")
    sys.exit(1 if e else 0)
