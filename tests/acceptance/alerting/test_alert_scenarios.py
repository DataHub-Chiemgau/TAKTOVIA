"""WP-M5-04 / LH-6.19-NFR-008: Alerting für kritische Fehlerszenarien.

Was dieser Nachweis ist (und was nicht): Prometheus und Alertmanager laufen in dieser Umgebung NICHT (kein Docker). Die ECHTEN Alarmregeln (infra/observability/alert-rules.d/), die ECHTE
`alertmanager.yml` und der ECHTE Szenariokatalog (infra/alerting/katalog.yaml) werden gegen eine Nachbildung ausgeführt: Regelauswertung in der PromQL-Teilmenge (promql_mini.py), Routing nach
alertmanager.yml (chain.py) und eine ECHTE HTTPS-Zustellung an den Test-Empfänger. Die Szenarien werden durch vorgegebene Messreihen gezielt ausgelöst. Nicht belegt ist das Verhalten des echten
Prometheus/Alertmanager (Extrapolation von rate(), Notification-Log, Inhibition, Cluster); dafür liegen promtool-Fälle (infra/alerting/rules_test.yml) für die CI bei, hier NICHT ausgeführt.

Die Abnahme von NFR-008 ist erst vollständig, wenn auch die Backup-/Replikationsalarme aus WP-M5-14 ausgelöst wurden: `test_katalogabgleich_szenario_alarm_ohne_luecke` weist diese Lücke
ausdrücklich aus und bestätigt keine vollständige Abnahme.
"""
import json
import os
import re
import sys
import tempfile
import threading
import unittest
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[3]
INFRA = REPO / "infra"
sys.path.insert(0, str(INFRA / "alerting"))
sys.path.insert(0, str(INFRA))
import alertops  # noqa: E402
import chain as chainmod  # noqa: E402
import check_alerting  # noqa: E402
import check_telemetry  # noqa: E402
import deadman as deadmanmod  # noqa: E402
import promql_mini as pq  # noqa: E402
import test_receiver  # noqa: E402

RULES = INFRA / "observability" / "alert-rules.d" / "kritische-szenarien.yml"
AMCONFIG = INFRA / "alerting" / "alertmanager.yml"
KATALOG = yaml.safe_load((INFRA / "alerting" / "katalog.yaml").read_text(encoding="utf-8"))
T0 = 1_000_000.0
STEP = 15.0
PERSON = re.compile(r"(?i)(passenger|fahrgast|e-?mail|telefon|phone|@|vorname|nachname|iban|adresse|address|erika|muster)")
ALLOWED_LABELS = {"alertname", "severity", "component", "scenario", "empfaenger", "route_class", "job", "instance"}


# ----------------------------------------------------------------------------------------------------------------------- Messreihen

def gauge(s, name, labels, t0, t1, value):
    t = t0
    while t <= t1:
        s.add(name, labels, t, value)
        t += STEP


def counter(s, name, labels, t0, t1, per_s):
    t = t0
    while t <= t1:
        s.add(name, labels, t, (t - t0) * per_s)
        t += STEP


def http(s, cls, t0, t1, ok_per_s, err_per_s):
    counter(s, "taktovia_http_server_request_seconds_count", {"route_class": cls, "status_class": "2xx"}, t0, t1, ok_per_s)
    if err_per_s:
        counter(s, "taktovia_http_server_request_seconds_count", {"route_class": cls, "status_class": "5xx"}, t0, t1, err_per_s)


# Szenario-Auslöser: (Alarmname, Auslöser „Fehler“, Auslöser „knapp unterhalb der Schwelle“ oder None)
def _ratio(num, den, labels, name_num, name_den):
    def feed(s, t0, t1):
        counter(s, name_num, labels, t0, t1, num); counter(s, name_den, labels, t0, t1, den)
    return feed


def kernbuchung(share):
    return lambda s, t0, t1: http(s, "booking", t0, t1, 1.0 - share, share)


TRIGGERS = [
    ("KernbuchungAusfall", kernbuchung(0.66), kernbuchung(0.49)),
    ("FachkernNichtErreichbar", lambda s, t0, t1: gauge(s, "up", {"job": "backend-app"}, t0, t1, 0), lambda s, t0, t1: gauge(s, "up", {"job": "backend-app"}, t0, t0 + 60, 0) or gauge(s, "up", {"job": "backend-app"}, t0 + 75, t1, 1)),
    ("DatenbankNichtErreichbar", lambda s, t0, t1: gauge(s, "pg_up", {"job": "postgres"}, t0, t1, 0), lambda s, t0, t1: gauge(s, "pg_up", {"job": "postgres"}, t0, t0 + 45, 0) or gauge(s, "pg_up", {"job": "postgres"}, t0 + 60, t1, 1)),
    ("DatenbankReplikationsverzug", lambda s, t0, t1: gauge(s, "pg_replication_lag_seconds", {"job": "postgres"}, t0, t1, 45), lambda s, t0, t1: gauge(s, "pg_replication_lag_seconds", {"job": "postgres"}, t0, t1, 30)),
    ("OutboxStau", lambda s, t0, t1: gauge(s, "taktovia_outbox_depth", {"tenant_id": "00000000-0000-4000-8000-0000000000a1"}, t0, t1, 1500),
     lambda s, t0, t1: gauge(s, "taktovia_outbox_depth", {"tenant_id": "00000000-0000-4000-8000-0000000000a1"}, t0, t1, 1000)
     or gauge(s, "taktovia_outbox_oldest_age_seconds", {"tenant_id": "00000000-0000-4000-8000-0000000000a1"}, t0, t1, 300)),
    ("MessagingNichtErreichbar", lambda s, t0, t1: gauge(s, "up", {"job": "rabbitmq"}, t0, t1, 0), lambda s, t0, t1: gauge(s, "up", {"job": "rabbitmq"}, t0, t0 + 90, 0) or gauge(s, "up", {"job": "rabbitmq"}, t0 + 105, t1, 1)),
    ("RoutingWorkerNichtErreichbar", lambda s, t0, t1: gauge(s, "up", {"job": "routing-worker"}, t0, t1, 0), None),
    ("SolverTimeoutsHoch", _ratio(0.3, 1.0, {"kind": "solver_budget"}, "taktovia_routing_timeouts_total", "taktovia_routing_requests_total"),
     _ratio(0.19, 1.0, {"kind": "solver_budget"}, "taktovia_routing_timeouts_total", "taktovia_routing_requests_total")),
    ("ZahlungUnbestimmt", lambda s, t0, t1: gauge(s, "taktovia_payment_intent_indeterminate", {"tenant_id": "00000000-0000-4000-8000-0000000000a1"}, t0, t1, 3),
     lambda s, t0, t1: gauge(s, "taktovia_payment_intent_indeterminate", {"tenant_id": "00000000-0000-4000-8000-0000000000a1"}, t0, t1, 0)),
    ("OperationLeaseUeberfaellig", lambda s, t0, t1: gauge(s, "taktovia_operation_lease_overdue", {"tenant_id": "00000000-0000-4000-8000-0000000000a1"}, t0, t1, 2), None),
    ("RedisAusfall", lambda s, t0, t1: gauge(s, "redis_up", {"job": "redis"}, t0, t1, 0), None),
    ("ZertifikatLaeuftAb", lambda s, t0, t1: gauge_cert(s, t0, t1, 10 * 86400), lambda s, t0, t1: gauge_cert(s, t0, t1, 14 * 86400 + 3600)),
    ("ZertifikatLaeuftBaldAb", lambda s, t0, t1: gauge_cert(s, t0, t1, 2 * 86400), lambda s, t0, t1: gauge_cert(s, t0, t1, 3 * 86400 + 3600)),
    ("FehlerrateEndpunktklasse", lambda s, t0, t1: http(s, "operator", t0, t1, 0.9, 0.1), lambda s, t0, t1: http(s, "operator", t0, t1, 0.951, 0.049)),
    ("GatewayNichtErreichbar", lambda s, t0, t1: gauge(s, "probe_success", {"job": "gateway-probe"}, t0, t1, 0), None),
    ("DokumentenWorkerNichtErreichbar", lambda s, t0, t1: gauge(s, "up", {"job": "documents-worker"}, t0, t1, 0), None),
    ("DokumentenAufgabenScheitern", _ratio(0.4, 1.0, {"result": "error"}, "taktovia_documents_tasks_total", "taktovia_documents_tasks_total"),
     None),
    ("AlertmanagerZustellungFehlgeschlagen", lambda s, t0, t1: counter(s, "alertmanager_notifications_failed_total", {"job": "alertmanager"}, t0, t1, 0.05), None),
    ("PrometheusRegelauswertungFehlgeschlagen", lambda s, t0, t1: counter(s, "prometheus_rule_evaluation_failures_total", {"job": "prometheus"}, t0, t1, 0.05), None),
    ("Watchdog", lambda s, t0, t1: None, None),
]


def gauge_cert(s, t0, t1, remaining):
    t = t0
    while t <= t1:
        s.add("probe_ssl_earliest_cert_expiry", {"job": "gateway-probe"}, t, t + remaining)   # Ablaufzeitpunkt, relativ zur Messzeit gleichbleibend
        t += STEP


def documents_fix(s, t0, t1):
    """Dokumentenaufgaben: 40 % Fehler und 60 % Erfolg (zwei Reihen derselben Metrik)."""
    counter(s, "taktovia_documents_tasks_total", {"kind": "ocr", "result": "error"}, t0, t1, 0.4)
    counter(s, "taktovia_documents_tasks_total", {"kind": "ocr", "result": "ok"}, t0, t1, 0.6)


def documents_edge(s, t0, t1):
    counter(s, "taktovia_documents_tasks_total", {"kind": "ocr", "result": "error"}, t0, t1, 0.29)
    counter(s, "taktovia_documents_tasks_total", {"kind": "ocr", "result": "ok"}, t0, t1, 0.71)


TRIGGERS = [(n, documents_fix, documents_edge) if n == "DokumentenAufgabenScheitern" else (n, f, e) for n, f, e in TRIGGERS]
RULE_DEFS = {r["alert"]: r for g in yaml.safe_load(RULES.read_text(encoding="utf-8"))["groups"] for r in g["rules"]}


def hold_seconds(alertname):
    r = RULE_DEFS[alertname]
    return pq.parse_duration(str(r["for"])) if r.get("for") else 0.0


# ----------------------------------------------------------------------------------------------------------------------- Aufbau

class World:
    """Echter Test-Empfänger (HTTPS) für den Alarmkanal, Totmann-Empfänger und Protokolldatei."""

    def __init__(self):
        self.srv, self.received, self.cert = test_receiver.serve()
        self.srv2, self.received_dm, self.cert2 = test_receiver.serve()
        self.tmp = tempfile.TemporaryDirectory()
        self.protocol = Path(self.tmp.name) / "alarmprotokoll.jsonl"
        self.urls = {"betreiber": f"https://127.0.0.1:{self.srv.server_address[1]}/alert", "totmann": f"https://127.0.0.1:{self.srv2.server_address[1]}/alert"}

    def chain(self, store, urls=None, rule_files=None):
        # beide Test-Empfänger nutzen eigene Zertifikate: ein gemeinsames Bündel vertraut beiden
        bundle = Path(self.tmp.name) / f"ca-{id(store)}.pem"
        bundle.write_text(Path(self.cert).read_text() + Path(self.cert2).read_text())
        router = chainmod.Router(AMCONFIG, urls if urls is not None else self.urls, cafile=str(bundle))
        return chainmod.Chain(rule_files or [RULES], router, store)

    def close(self):
        self.srv.shutdown(); self.srv2.shutdown(); self.tmp.cleanup()

    def log(self, **entry):
        with open(self.protocol, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def run_trigger(world, alertname, feed, horizon_extra=120.0):
    """Löst einen Alarm gezielt aus und liefert (Kette, firing-Alarm oder None)."""
    s = pq.Store()
    t_end = T0 + hold_seconds(alertname) + horizon_extra
    feed(s, T0, t_end)
    ch = world.chain(s)
    ch.run(T0, t_end, STEP)
    firing = [a for a in ch.evaluator.active.values() if a.alertname == alertname and a.state == "firing"]
    return ch, (firing[0] if firing else None)


# ----------------------------------------------------------------------------------------------------------------------- Tests

class AlertScenarios(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.world = World()
        cls.results = {}      # Alarmname -> dict(Szenario, Alarm feuernd?, zugestellt?, Protokoll)
        for name, fail, _edge in TRIGGERS:
            ch, alert = run_trigger(cls.world, name, fail)
            delivered = [n for n in ch.router.notifications if n.delivered_ok and any(a["labels"]["alertname"] == name for a in n.alerts)]
            entry = {"firing": alert is not None, "delivered": bool(delivered)}
            if alert is not None and delivered:
                n = delivered[0]
                fp = alert.fingerprint
                entry.update(scenario=alert.labels["scenario"], receiver=n.receiver, empfaenger=alert.labels["empfaenger"], severity=alert.labels["severity"], triggered_at=ch.trigger_wall[fp],
                             delivered_at=n.delivered_wall, labels=alert.labels, annotations=alert.annotations, payload_alerts=n.alerts)
                cls.world.log(scenario=entry["scenario"], alert=name, empfaenger=entry["empfaenger"], receiver=n.receiver, triggered_at=entry["triggered_at"], delivered_at=entry["delivered_at"])
            cls.results[name] = entry

    @classmethod
    def tearDownClass(cls):
        cls.world.close()

    # --- Kriterium 2 ---
    def test_je_szenario_alarm_zugestellt(self):
        for name, _f, _e in TRIGGERS:
            r = self.results[name]
            self.assertTrue(r["firing"], f"{name}: Alarm wurde nicht ausgelöst")
            self.assertTrue(r["delivered"], f"{name}: Alarm wurde nicht zugestellt")
            self.assertEqual(r["receiver"], "totmann" if name == "Watchdog" else "betreiber", name)
            self.assertEqual(r["empfaenger"], RULE_DEFS[name]["labels"]["empfaenger"], name)
            # Empfängerrolle aus dem Katalog (bei gleichem Schweregrad wie das Szenario)
            sc = next(s for s in KATALOG["szenarien"] if s["id"] == r["scenario"])
            if r["severity"] == sc["schweregrad"]:
                self.assertEqual(r["empfaenger"], sc["empfaenger"], f"{name}: Empfänger weicht vom Katalog ab")
            # Auslöse- und Zustellzeit sind protokolliert und stimmig
            self.assertGreaterEqual(r["delivered_at"], r["triggered_at"], name)
        protocol = [json.loads(line) for line in self.world.protocol.read_text(encoding="utf-8").splitlines()]
        self.assertEqual({p["alert"] for p in protocol}, {n for n, _f, _e in TRIGGERS}, "je Alarm ein Protokolleintrag mit Auslöse- und Zustellzeit")
        self.assertTrue(all(isinstance(p["triggered_at"], float) and isinstance(p["delivered_at"], float) for p in protocol))
        # der Empfänger hat tatsächlich Alarme über HTTPS erhalten (nicht nur die Nachbildung gezählt)
        got = {a["labels"]["alertname"] for body in self.world.received for a in body["alerts"]}
        self.assertTrue({n for n, _f, _e in TRIGGERS if n != "Watchdog"} <= got, sorted(got))
        self.assertIn("Watchdog", {a["labels"]["alertname"] for body in self.world.received_dm for a in body["alerts"]}, "Herzschlag nur an den Totmann-Empfänger")
        self.assertNotIn("Watchdog", got, "der Herzschlag erreicht nie den Alarmkanal")

    def test_katalogabgleich_szenario_alarm_ohne_luecke(self):
        luecken, ausstehend = abgleich(KATALOG, self.results)
        self.assertEqual(luecken, [], f"aktive Szenarien ohne ausgelösten Alarm: {luecken}")
        self.assertEqual(sorted(ausstehend), ["ARCHIVIERUNGS_REPLIKATIONSVERZUG", "BACKUP_FEHLGESCHLAGEN"], "die Alarme aus WP-M5-14 stehen im Katalog, sind aber noch nicht ausgelöst")
        # Die Abnahme von NFR-008 ist damit NICHT vollständig: ausdrücklich ausgewiesen
        self.assertFalse(nfr_008_vollstaendig(ausstehend), "NFR-008 gilt erst mit den Alarmen aus WP-M5-14 als vollständig abgenommen")
        # Negativ: ein Katalogszenario ohne ausgelösten Alarm (oder ohne Regel) lässt den Abgleich fehlschlagen
        kat = json.loads(json.dumps(KATALOG))
        kat["szenarien"].append({"id": "NEU_OHNE_ALARM", "lieferung": "aktiv", "regeln": ["GibtEsNicht"]})
        self.assertEqual(abgleich(kat, self.results)[0], ["NEU_OHNE_ALARM"])
        ergebnisse = {k: dict(v) for k, v in self.results.items()}
        ergebnisse["RedisAusfall"]["firing"] = False
        self.assertEqual(abgleich(KATALOG, ergebnisse)[0], ["REDIS_AUSFALL"])
        # Negativ: Regel aus der Regeldatei entfernt -> der gezielte Auslöser erzeugt keinen Alarm
        gekuerzt = {"groups": [{"name": "x", "rules": [r for g in yaml.safe_load(RULES.read_text(encoding="utf-8"))["groups"] for r in g["rules"] if r["alert"] != "RedisAusfall"]}]}
        f = Path(self.world.tmp.name) / "ohne-redis.yml"; f.write_text(yaml.safe_dump(gekuerzt))
        s = pq.Store(); gauge(s, "redis_up", {"job": "redis"}, T0, T0 + 300, 0)
        ch = self.world.chain(s, rule_files=[f]); ch.run(T0, T0 + 300, STEP)
        self.assertFalse(any(a.alertname == "RedisAusfall" for a in ch.evaluator.active.values()))
        # statische Entsprechung: Katalog <-> Regeln lückenlos (infra/check_alerting.py)
        errs, notes = [], []
        check_alerting.check(INFRA, REPO, errs, notes)
        self.assertEqual(errs, [])

    # --- Kriterium 1 ---
    def test_je_komponente_alarmregel_und_telemetrie(self):
        prom = yaml.safe_load((INFRA / "observability" / "prometheus.yml").read_text(encoding="utf-8"))
        jobs = {j["job_name"]: j["static_configs"][0].get("labels", {}).get("component") for j in prom["scrape_configs"]}
        otel = yaml.safe_load((INFRA / "observability" / "otel-collector-config.yaml").read_text(encoding="utf-8"))
        pipelines = otel["service"]["pipelines"]
        known = check_telemetry.defined_metrics(REPO)
        gateway_conf = (INFRA / "gateway" / "nginx.conf.template").read_text(encoding="utf-8")
        regeln_je_komponente = {}
        for r in RULE_DEFS.values():
            regeln_je_komponente.setdefault(r["labels"]["component"], []).append(r["alert"])
        belege = {
            # Komponente -> (Scrape-Ziel mit component-Label, im Code definierte Metrikpräfixe)
            "fachkern": ("backend-app", ("taktovia_http_server_request_seconds", "taktovia_operation_lease_overdue")),
            "outbox-relay": ("backend-app", ("taktovia_outbox_depth", "taktovia_outbox_oldest_age_seconds", "taktovia_outbox_relay_events_total")),
            "routing-worker": ("routing-worker", ("taktovia_routing_requests_total", "taktovia_routing_timeouts_total")),
            "dokumenten-worker": ("documents-worker", ("taktovia_documents_tasks_total",)),
            "gateway": ("gateway-probe", ()),
        }
        for comp in check_alerting.SERVER_COMPONENTS:
            self.assertTrue(regeln_je_komponente.get(comp), f"{comp}: keine aktive Alarmregel")
            job, metrics = belege[comp]
            self.assertIn(job, jobs, f"{comp}: Scrape-Ziel {job} fehlt (Metriken)")
            for m in metrics:
                self.assertIn(m, known, f"{comp}: Metrik {m} im Code nicht definiert")
        # Traces und Logs sind für den Verbund konfiguriert (Collector) bzw. für das Gateway im Zugriffslog (maskiert)
        self.assertIn("traces", pipelines); self.assertIn("logs", pipelines); self.assertIn("metrics", pipelines)
        self.assertIn("taktovia_masked", gateway_conf)
        # Negativ: fehlen die Regeln einer Komponente, schlägt die Prüfung an
        ohne = {c: alerts for c, alerts in regeln_je_komponente.items() if c != "routing-worker"}
        self.assertEqual([c for c in check_alerting.SERVER_COMPONENTS if not ohne.get(c)], ["routing-worker"])
        with tempfile.TemporaryDirectory() as tmp:
            shutil_infra = Path(tmp) / "infra"
            import shutil
            shutil.copytree(INFRA, shutil_infra, ignore=shutil.ignore_patterns("__pycache__", "terraform", "compose", "keycloak", "gateway"))
            rules_file = shutil_infra / "observability" / "alert-rules.d" / "kritische-szenarien.yml"
            doc = yaml.safe_load(rules_file.read_text(encoding="utf-8"))
            for g in doc["groups"]:
                g["rules"] = [r for r in g["rules"] if r["labels"]["component"] != "routing-worker"]
            rules_file.write_text(yaml.safe_dump(doc), encoding="utf-8")
            errs, _notes = [], []
            check_alerting.check(shutil_infra, REPO, errs, _notes)
            self.assertTrue(any("routing-worker" in e and "ohne aktive Alarmregel" in e for e in errs), errs)

    # --- Negativ: Schwellen ---
    def test_schwelle_knapp_unterschritten_kein_alarm(self):
        geprueft = 0
        for name, _fail, edge in TRIGGERS:
            if edge is None:
                continue
            ch, alert = run_trigger(self.world, name, edge)
            self.assertIsNone(alert, f"{name}: knapp unterhalb der Schwelle darf kein Alarm entstehen")
            self.assertFalse([n for n in ch.router.notifications if any(a["labels"]["alertname"] == name for a in n.alerts)], name)
            geprueft += 1
        self.assertGreaterEqual(geprueft, 12)

    # --- Konkurrierend ---
    def test_gleichzeitige_ausfaelle_zwei_alarme(self):
        s = pq.Store()
        gauge(s, "pg_up", {"job": "postgres"}, T0, T0 + 300, 0); gauge(s, "up", {"job": "rabbitmq"}, T0, T0 + 300, 0)
        ch = self.world.chain(s)
        before = len(self.world.received)
        ch.run(T0, T0 + 300, STEP)
        firing = {a.alertname: a for a in ch.evaluator.active.values() if a.state == "firing" and a.alertname != "Watchdog"}
        self.assertEqual(set(firing), {"DatenbankNichtErreichbar", "MessagingNichtErreichbar"})
        self.assertNotEqual(firing["DatenbankNichtErreichbar"].fingerprint, firing["MessagingNichtErreichbar"].fingerprint, "zwei unterscheidbare Alarme")
        notes = [n for n in ch.router.notifications if n.receiver == "betreiber" and n.status == "firing" and n.delivered_ok]
        self.assertEqual(len(notes), 2, "je Ausfall eine eigene Benachrichtigung (verschiedene Gruppen)")
        self.assertEqual({tuple(sorted(n.group_labels.items())) for n in notes}, {(("alertname", "DatenbankNichtErreichbar"), ("component", "postgres")), (("alertname", "MessagingNichtErreichbar"), ("component", "rabbitmq"))})
        self.assertEqual(len(self.world.received) - before, 2)

    # --- Kriterium 3 ---
    def test_anhaltender_fehler_genau_ein_offener_alarm_schliesst_nach_behebung(self):
        s = pq.Store()
        down_until = T0 + 3 * 3600
        gauge(s, "pg_up", {"job": "postgres"}, T0, down_until, 0)
        gauge(s, "pg_up", {"job": "postgres"}, down_until + 60, down_until + 600, 1)
        ch = self.world.chain(s)
        t = T0
        while t <= down_until:
            ch.step(t); t += 60.0   # grobere Auswertung, damit drei Stunden schnell laufen
        offen = [a for a in ch.evaluator.active.values() if a.alertname == "DatenbankNichtErreichbar"]
        self.assertEqual(len(offen), 1, "ein anhaltender Fehler: genau ein offener Alarm")
        gesendet = [n for n in ch.router.notifications if n.receiver == "betreiber" and n.status == "firing"]
        self.assertGreaterEqual(len(gesendet), 1)
        self.assertLessEqual(len(gesendet), 4, f"keine Alarmflut: {len(gesendet)} Benachrichtigungen in drei Stunden (kritisch: Wiederholung höchstens stündlich)")
        self.assertEqual(len({n.group_labels.get("alertname") for n in gesendet}), 1)
        # Behebung: Alarm schließt, eine Auflösungsmeldung geht hinaus
        while t <= down_until + 600:
            ch.step(t); t += 60.0
        self.assertFalse([a for a in ch.evaluator.active.values() if a.alertname == "DatenbankNichtErreichbar"], "nach Behebung kein offener Alarm")
        self.assertTrue([n for n in ch.router.notifications if n.status == "resolved" and n.delivered_ok], "Auflösungsmeldung zugestellt")

    # --- Kriterium 6 (Stummschaltung) ---
    def test_stummschaltung_kein_ruf_aber_protokoll(self):
        clock = {"now": T0}
        ops = alertops.AlertOps(Path(self.world.tmp.name) / "ops1.db", Path(self.world.tmp.name) / "audit1.jsonl", now=lambda: clock["now"])
        s = pq.Store(); gauge(s, "pg_up", {"job": "postgres"}, T0, T0 + 4 * 3600, 0)
        ch = self.world.chain(s)
        betrieb = alertops.Actor("betrieb-1", frozenset({"betrieb"}))
        ops.silence(betrieb, ['alertname="DatenbankNichtErreichbar"'], 60, "Wartung Primärknoten")
        ch.router.silences = [chainmod.Silence([chainmod.parse_matcher(m) for m in x["matchers"]], x["start"], x["end"], x["actor"], x["comment"]) for x in ops.active_silences()]
        before = len([n for n in ch.router.notifications if n.status == "firing" and n.receiver == "betreiber"])
        t = T0
        while t <= T0 + 3000:                       # innerhalb der Stummschaltung (60 min)
            clock["now"] = t; ch.step(t); t += 30.0
        self.assertEqual(len([n for n in ch.router.notifications if n.status == "firing" and n.receiver == "betreiber"]), before, "stummgeschaltet: kein Ruf")
        self.assertTrue(ch.router.suppressed, "der unterdrückte Ruf bleibt protokolliert")
        self.assertTrue(any(a.alertname == "DatenbankNichtErreichbar" and a.state == "firing" for a in ch.evaluator.active.values()), "der Alarm ist weiter sichtbar")
        audit = [json.loads(line) for line in (Path(self.world.tmp.name) / "audit1.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual([a["action"] for a in audit], ["silence"]); self.assertEqual(audit[0]["result"], "ok")
        # nach Ablauf der Stummschaltung ruft der noch bestehende Alarm
        while t <= T0 + 3 * 3600 + 600:
            clock["now"] = t; ch.step(t); t += 30.0
        self.assertTrue([n for n in ch.router.notifications if n.status == "firing" and n.receiver == "betreiber" and n.delivered_ok], "nach Ablauf wird gerufen")
        # Grenzen der Stummschaltung
        with self.assertRaises(ValueError):
            ops.silence(betrieb, ['alertname="X"'], 24 * 60, "zu lange")
        with self.assertRaises(ValueError):
            ops.silence(betrieb, [], 10, "ohne Matcher")
        with self.assertRaises(ValueError):
            ops.silence(betrieb, ['alertname="X"'], 10, "  ")

    # --- Kriterium 4 ---
    def test_alarmtext_ohne_fahrgast_und_personaldaten(self):
        for name, r in RULE_DEFS.items():
            for key, value in r["annotations"].items():
                if key == "runbook":
                    continue
                self.assertIsNone(PERSON.search(str(value)), f"{name}.{key}: {value}")
                self.assertNotIn("{{", str(value), f"{name}.{key}: Vorlagenwerte sind verboten")
        for name, r in self.results.items():
            for key in r["labels"]:
                self.assertIn(key, ALLOWED_LABELS, f"{name}: Etikett {key} gehört nicht zur Positivliste technischer Etiketten")
            self.assertIsNone(PERSON.search(json.dumps(r["payload_alerts"], ensure_ascii=False)), f"{name}: zugestellte Nutzlast enthält Personenbezug")
            # die Mandanten-ID einer aggregierten Metrik gelangt nie in den Alarm
            self.assertNotIn("tenant_id", r["labels"])
        # Negativ: eine Regel mit Vorlagenwert oder Personenbezug wird von der Regelprüfung abgewiesen
        schlecht = yaml.safe_dump({"groups": [{"name": "x", "rules": [{"alert": "X", "expr": "up == 0", "labels": {"severity": "warning", "component": "c", "scenario": "S", "empfaenger": "betrieb-plattform"},
                                                                        "annotations": {"summary": "Fahrer {{ $labels.driver }} fehlt"}}]}]})
        self.assertTrue(alertops.validate_rules_text(schlecht))

    # --- Kriterium 6 (Rollen) ---
    def test_nicht_betriebsrolle_darf_regel_nicht_aendern_stummschalten_quittieren(self):
        d = Path(self.world.tmp.name)
        rules_copy = d / "regeln.yml"; rules_copy.write_text(RULES.read_text(encoding="utf-8"), encoding="utf-8")
        original = rules_copy.read_text(encoding="utf-8")
        ops = alertops.AlertOps(d / "ops2.db", d / "audit2.jsonl")
        fremd = alertops.Actor("mandanten-admin", frozenset({"tenant_admin"})); fahrer = alertops.Actor("fahrer-1", frozenset({"driver"}))
        betrieb = alertops.Actor("betrieb-1", frozenset({"plattform"}))
        for actor in (fremd, fahrer):
            with self.assertRaises(PermissionError):
                ops.silence(actor, ['alertname="X"'], 30, "ohne Recht")
            with self.assertRaises(PermissionError):
                ops.acknowledge(actor, "fp-1", "ohne Recht")
            with self.assertRaises(PermissionError):
                ops.change_rules(actor, rules_copy, "groups: []", "ohne Recht")
        self.assertEqual(rules_copy.read_text(encoding="utf-8"), original, "Regeldatei unverändert")
        self.assertEqual(ops.active_silences(), []); self.assertEqual(ops.acknowledgements("fp-1"), [])
        audit = [json.loads(line) for line in (d / "audit2.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(audit), 6); self.assertTrue(all(a["result"] == "denied" for a in audit), "auch die Abweisung ist protokolliert")
        # Betriebsrolle: Änderung mit gültiger Datei wirkt und ist protokolliert; fehlerhafte Datei wird abgewiesen
        neu = original.replace("for: 2m\n        labels: { severity: warning, component: redis", "for: 3m\n        labels: { severity: warning, component: redis")
        ops.change_rules(betrieb, rules_copy, neu, "Schwelle Redis angepasst")
        self.assertIn("for: 3m", rules_copy.read_text(encoding="utf-8"))
        kaputt = original.replace('severity: warning, component: redis, scenario: REDIS_AUSFALL, empfaenger: betrieb-plattform', 'severity: warning, component: redis')
        with self.assertRaises(ValueError):
            ops.change_rules(betrieb, rules_copy, kaputt, "fehlerhaft")
        self.assertIn("for: 3m", rules_copy.read_text(encoding="utf-8"), "fehlerhafte Datei ändert nichts")
        actions = [(a["action"], a["result"]) for a in map(json.loads, (d / "audit2.jsonl").read_text(encoding="utf-8").splitlines())][6:]
        self.assertEqual(actions, [("change_rules", "ok"), ("change_rules", "rejected")])
        # Code-Owner-Pflicht für die Regeldateien
        owners = (REPO / ".github" / "CODEOWNERS").read_text(encoding="utf-8")
        self.assertRegex(owners, r"(?m)^/infra/observability/alert-rules\.d/\s+@\S+")
        self.assertRegex(owners, r"(?m)^/infra/alerting/\s+@\S+")

    def test_paralleles_quittieren_genau_eine_wirksame(self):
        d = Path(self.world.tmp.name)
        ops = alertops.AlertOps(d / "ops3.db", d / "audit3.jsonl")
        actors = [alertops.Actor(f"betrieb-{i}", frozenset({"betrieb"})) for i in range(16)]
        start = threading.Barrier(16); results = []

        def go(a):
            start.wait()
            results.append(ops.acknowledge(a, "fp-parallel", "übernommen"))
        ts = [threading.Thread(target=go, args=(a,)) for a in actors]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(results.count(True), 1, "genau eine wirksame Quittierung")
        self.assertEqual(len(ops.acknowledgements("fp-parallel")), 1)
        audit = [json.loads(line) for line in (d / "audit3.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(sum(1 for a in audit if a["result"] == "ok"), 1); self.assertEqual(sum(1 for a in audit if a["result"] == "already_acknowledged"), 15)

    # --- Kriterium 5 ---
    def test_totmannpruefung_alarmkette(self):
        clock = {"now": 0.0}
        gemeldet = []
        own_srv, own_received, own_cert = test_receiver.serve()          # EIGENER Meldeweg der Totmannprüfung
        self.addCleanup(own_srv.shutdown)
        url_own = f"https://127.0.0.1:{own_srv.server_address[1]}/alert"
        srv, dm, cert, stop = deadmanmod.serve(0, timeout_s=300, notify=lambda p: (gemeldet.append(p), deadmanmod.post_json(url_own, p, str(own_cert))), clock=lambda: clock["now"], poll_s=3600)
        self.addCleanup(stop)
        urls = {"betreiber": self.world.urls["betreiber"], "totmann": f"https://127.0.0.1:{srv.server_address[1]}/hb"}
        bundle = Path(self.world.tmp.name) / "ca-totmann.pem"
        bundle.write_text(Path(self.world.cert).read_text() + Path(cert).read_text())
        s = pq.Store()
        ch = chainmod.Chain([RULES], chainmod.Router(AMCONFIG, urls, cafile=str(bundle)), s)
        # 1. gesunde Kette: der Herzschlag kommt minütlich an, die Prüfung bleibt still
        t = T0
        while t <= T0 + 600:
            clock["now"] = t - T0; ch.step(t); t += 15.0
            dm.check()
        self.assertGreaterEqual(dm.beats, 9, "Herzschlag minütlich")
        self.assertEqual(gemeldet, [], "gesunde Kette: keine Meldung")
        self.assertFalse(dm.down)
        # 2. Sammler/Auswertung gestoppt (kein ch.step mehr): nach dem Zeitlimit meldet die unabhängige Prüfung den Ausfall über ihren eigenen Weg
        clock["now"] += 299; dm.check(); self.assertEqual(gemeldet, [], "noch innerhalb des Limits")
        clock["now"] += 2; dm.check()
        self.assertEqual(len(gemeldet), 1); self.assertEqual(gemeldet[0]["scenario"], "ALARMKETTE_AUSGEFALLEN"); self.assertEqual(gemeldet[0]["empfaenger"], "betrieb-bereitschaft")
        dm.check(); self.assertEqual(len(gemeldet), 1, "einmal je Ausfallphase")
        self.assertEqual(len(own_received), 1, "über den eigenen Meldeweg zugestellt")
        # Rückkehr des Herzschlags
        clock["now"] += 10; t += 60; ch.step(t); dm.check()
        self.assertEqual([g["event"] for g in gemeldet], ["alarmkette_ausgefallen", "recovered"])
        # 3. Zustellweg zur Totmannprüfung gestoppt (z. B. Netz/Zustellung): auch das fällt auf, und der Zustellfehler wird in der Kette sichtbar
        bruch = pq.Store()
        ch2 = chainmod.Chain([RULES], chainmod.Router(AMCONFIG, {"betreiber": self.world.urls["betreiber"], "totmann": "https://127.0.0.1:9/hb"}, cafile=str(bundle)), bruch)
        dm2_gemeldet = []
        clock2 = {"now": 0.0}
        srv2, dm2, _c, stop2 = deadmanmod.serve(0, timeout_s=300, notify=dm2_gemeldet.append, clock=lambda: clock2["now"], poll_s=3600)
        self.addCleanup(stop2)
        t2 = T0
        while t2 <= T0 + 300:
            ch2.step(t2); t2 += 15.0
        self.assertGreater(ch2.router.failed_deliveries, 0, "die Kette bemerkt den unzustellbaren Herzschlag")
        clock2["now"] = 301; dm2.check()
        self.assertEqual(len(dm2_gemeldet), 1, "kein Herzschlag angekommen: Totmannprüfung schlägt an")
        # der Zustellfehlerzähler der Kette löst die Regel AlertmanagerZustellungFehlgeschlagen aus (Auswertung gegen die Messreihe, die ein Alertmanager-Scrape liefern würde)
        counter(bruch, "alertmanager_notifications_failed_total", {"job": "alertmanager"}, T0, T0 + 900, 0.05)
        ch3 = chainmod.Chain([RULES], chainmod.Router(AMCONFIG, self.world.urls, cafile=str(Path(self.world.tmp.name) / "ca-totmann.pem")), bruch)
        ch3.run(T0, T0 + 900, STEP)
        self.assertTrue([a for a in ch3.evaluator.active.values() if a.alertname == "AlertmanagerZustellungFehlgeschlagen" and a.state == "firing"])


def abgleich(katalog, ergebnisse):
    """Katalogabgleich „Szenario <-> ausgelöster Alarm“: Lücken = aktive Szenarien, deren Regeln nicht alle ausgelöst und zugestellt wurden; ausstehend = Szenarien, die erst mit einem späteren WP geliefert werden."""
    luecken, ausstehend = [], []
    for s in katalog["szenarien"]:
        if s.get("lieferung") != "aktiv":
            ausstehend.append(s["id"])
            continue
        namen = s.get("regeln") or []
        if not namen or not all(ergebnisse.get(n, {}).get("firing") and ergebnisse.get(n, {}).get("delivered") for n in namen):
            luecken.append(s["id"])
    return luecken, ausstehend


def nfr_008_vollstaendig(ausstehend):
    return not ausstehend


if __name__ == "__main__":
    unittest.main()
