"""Statische Prüfung der Alarmierung kritischer Fehlerszenarien (WP-M5-04): Katalog <-> Regeln <-> Komponenten <-> Runbook <-> Routing <-> Metriken.
Nichts davon startet Prometheus oder Alertmanager (kein Docker); die Prüfung ersetzt keinen Lauf dagegen. Das Auslösen der Szenarien gegen die Regeln leistet die Nachbildung in
tests/acceptance/alerting (infra/alerting/promql_mini.py, chain.py)."""
import pathlib
import re
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "alerting"))
import check_telemetry  # noqa: E402
import promql_mini  # noqa: E402

SERVER_COMPONENTS = ("fachkern", "gateway", "routing-worker", "dokumenten-worker", "outbox-relay")
SEVERITIES = {"critical", "warning"}
RECIPIENTS = {"betrieb-bereitschaft", "betrieb-plattform", "betriebsleitung"}
LIEFERUNG_SPAETER = re.compile(r"^WP-M5-\d+$")
EXTERNAL = re.compile(r"\b(up|pg_[a-z_]+|redis_up|probe_[a-z_]+|alertmanager_[a-z_]+|prometheus_[a-z_]+)\b")
METRIC_NAMES = re.compile(r"\b([a-z_][a-z0-9_]*)\s*(?:\{|\[|\s*[<>=!]|\)|$)")
PERSON = re.compile(r"(?i)(passenger|fahrgast|e-?mail|telefon|phone|@|vorname|nachname|iban|adresse|address)")


def katalog(root):
    return yaml.safe_load((root / "alerting" / "katalog.yaml").read_text(encoding="utf-8"))


def rules(root):
    out = []
    for f in sorted((root / "observability" / "alert-rules.d").glob("*.yml")):
        for g in yaml.safe_load(f.read_text(encoding="utf-8"))["groups"]:
            out.extend(g["rules"])
    return out


def anchors(path):
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r'<a id="([a-z0-9_]+)"></a>', text))


def check(root, repo, errs, notes):
    k = katalog(root)
    rs = rules(root)
    by_name = {r["alert"]: r for r in rs}
    ids = [s["id"] for s in k["szenarien"]]
    if len(ids) != len(set(ids)):
        errs.append("alarmkatalog: Szenario-IDs nicht eindeutig")
    runbook = repo / "docs" / "betrieb" / "alarm-handlungsschritte.md"
    anc = anchors(runbook) if runbook.exists() else set()
    for s in k["szenarien"]:
        sid = s["id"]
        for key in ("titel", "komponente", "regeln", "signal", "schwelle", "schweregrad", "empfaenger", "eskalation_nach_min", "runbook", "lieferung"):
            if key not in s:
                errs.append(f"alarmkatalog: {sid}: Feld {key} fehlt")
        if s.get("schweregrad") not in SEVERITIES:
            errs.append(f"alarmkatalog: {sid}: Schweregrad {s.get('schweregrad')!r}")
        if s.get("empfaenger") not in RECIPIENTS:
            errs.append(f"alarmkatalog: {sid}: Empfaenger {s.get('empfaenger')!r} nicht definiert")
        if s.get("komponente") not in k["komponenten"]:
            errs.append(f"alarmkatalog: {sid}: Komponente {s.get('komponente')!r} unbekannt")
        m = re.search(r"#([a-z0-9_]+)$", s.get("runbook", ""))
        if not m or m.group(1) != sid.lower() or m.group(1) not in anc:
            errs.append(f"alarmkatalog: {sid}: Runbook-Verweis fehlt oder zeigt auf keinen Handlungsschritt in docs/betrieb/alarm-handlungsschritte.md")
        aktiv = s.get("lieferung") == "aktiv"
        if aktiv and not s.get("regeln"):
            errs.append(f"alarmkatalog: {sid}: aktives Szenario ohne Alarmregel")
        if not aktiv and (s.get("regeln") or not LIEFERUNG_SPAETER.match(str(s.get("lieferung")))):
            errs.append(f"alarmkatalog: {sid}: nicht aktives Szenario muss 'WP-M5-<nn>' als Lieferung nennen und darf noch keine Regel haben")
        for name in s.get("regeln", []):
            r = by_name.get(name)
            if r is None:
                errs.append(f"alarmkatalog: {sid}: Regel {name} fehlt in alert-rules.d")
                continue
            lab = r.get("labels", {})
            if lab.get("scenario") != sid:
                errs.append(f"alarmkatalog: Regel {name}: Etikett scenario {lab.get('scenario')!r} statt {sid}")
            if lab.get("component") != s["komponente"] and sid != "ALARMKETTE_AUSGEFALLEN":
                errs.append(f"alarmkatalog: Regel {name}: Komponente {lab.get('component')!r} statt {s['komponente']}")
            if r.get("annotations", {}).get("runbook") != s["runbook"]:
                errs.append(f"alarmkatalog: Regel {name}: Runbook-Verweis weicht vom Katalog ab")
    cataloged = {n for s in k["szenarien"] for n in s.get("regeln", [])}
    for r in rs:
        if r["alert"] not in cataloged:
            errs.append(f"alarmkatalog: Regel {r['alert']} gehört zu keinem Katalogszenario")
        lab = r.get("labels", {})
        for need in ("severity", "component", "scenario", "empfaenger"):
            if need not in lab:
                errs.append(f"alarmkatalog: Regel {r['alert']}: Etikett {need} fehlt")
        if lab.get("severity") not in SEVERITIES | {"none"}:
            errs.append(f"alarmkatalog: Regel {r['alert']}: Schweregrad {lab.get('severity')!r}")
        for key, value in r.get("annotations", {}).items():
            if "{{" in str(value):
                errs.append(f"alarmkatalog: Regel {r['alert']}: Anmerkung {key} nutzt Vorlagenwerte (Alarmtexte tragen keine Etikettenwerte)")
            if key != "runbook" and PERSON.search(str(value)):
                errs.append(f"alarmkatalog: Regel {r['alert']}: Anmerkung {key} enthaelt personenbezogene Begriffe")
        try:
            promql_mini.parse(str(r["expr"]))
        except promql_mini.PromqlError as e:
            errs.append(f"alarmkatalog: Regel {r['alert']}: Ausdruck nicht auswertbar ({e})")
    # je Serverkomponente mindestens eine aktive Regel
    active_components = {r["labels"]["component"] for r in rs}
    for c in SERVER_COMPONENTS:
        if c not in active_components:
            errs.append(f"alarmkatalog: Serverkomponente {c} ohne aktive Alarmregel")
    # Metriken der Regeln: eigene im Code definiert, fremde aus den erlaubten Exportern
    known = check_telemetry.defined_metrics(repo)
    for r in rs:
        for name in re.findall(r"\b(taktovia_[a-z0-9_]+)", str(r["expr"])):
            base = re.sub(r"_(count|sum|bucket)$", "", name)
            if name not in known and base not in known:
                errs.append(f"alarmkatalog: Regel {r['alert']} nutzt Metrik {name}, die im Code nicht definiert ist")
    # Scrape-Ziele der Regeln, Regeldateien und Alertmanager in prometheus.yml
    prom = yaml.safe_load((root / "observability" / "prometheus.yml").read_text(encoding="utf-8"))
    jobs = {j["job_name"] for j in prom["scrape_configs"]}
    for r in rs:
        for job in re.findall(r'job="([a-z-]+)"', str(r["expr"])):
            if job not in jobs:
                errs.append(f"alarmkatalog: Regel {r['alert']} nutzt Scrape-Ziel {job}, das in prometheus.yml fehlt")
    if not any("alert-rules.d" in f for f in prom.get("rule_files", [])):
        errs.append("alarmkatalog: prometheus.yml bindet alert-rules.d nicht ein")
    if not prom.get("alerting", {}).get("alertmanagers"):
        errs.append("alarmkatalog: prometheus.yml ohne Alertmanager-Ziel")
    # Routing: jedes Etikett `empfaenger` hat einen Weg; Watchdog geht an die Totmannprüfung
    am = yaml.safe_load((root / "alerting" / "alertmanager.yml").read_text(encoding="utf-8"))
    names = {r["name"] for r in am["receivers"]}
    if "totmann" not in names:
        errs.append("alarmkatalog: Empfaenger totmann (Herzschlag) fehlt")
    wd = [x for x in am["route"].get("routes", []) if 'alertname="Watchdog"' in x.get("matchers", [])]
    if not wd or wd[0].get("receiver") != "totmann":
        errs.append("alarmkatalog: Watchdog wird nicht an die Totmannpruefung geleitet")
    if not any(x.get("receiver") == am["route"]["receiver"] and any("scenario" in m for m in x.get("matchers", [])) for x in am["route"].get("routes", [])):
        errs.append("alarmkatalog: keine Route fuer die Szenarioalarme")
    # Code-Owner: Alarmregeln nur durch Betrieb/Plattform
    owners = (repo / ".github" / "CODEOWNERS").read_text(encoding="utf-8")
    for p in ("/infra/alerting/", "/infra/observability/alert-rules.d/"):
        if not re.search(rf"^{re.escape(p)}\s+@\S+", owners, re.M):
            errs.append(f"alarmkatalog: CODEOWNERS ohne Eintrag fuer {p}")
    notes.append(f"alarmkatalog: {len(ids)} Szenarien, {len(rs)} Regeln statisch geprueft (Prometheus/Alertmanager NICHT gestartet)")


def main():
    root = pathlib.Path(__file__).resolve().parent
    errs, notes = [], []
    check(root, root.parent, errs, notes)
    for n in notes:
        print("ok   ", n)
    for e in errs:
        print("FEHLER", e)
    print("ERGEBNIS:", "ROT" if errs else "GRUEN")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
