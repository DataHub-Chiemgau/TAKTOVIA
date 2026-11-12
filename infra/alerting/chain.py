"""Nachbildung der Alarmierungskette für Tests (WP-M5-04): Regelauswertung -> Alertmanager-Routing -> Zustellung an den Empfängerkanal.

WICHTIG: Das ist NICHT Alertmanager. Es gibt hier weder Docker noch Alertmanager/Prometheus. Die Nachbildung liest die ECHTE `alertmanager.yml` (Routenbaum, group_by, group_wait,
group_interval, repeat_interval, matchers, continue, send_resolved) und bildet die Gruppierung, die Wiederholung und Silences nach; die Zustellung ist ein ECHTER HTTPS-Aufruf an den
Test-Empfänger (infra/alerting/test_receiver.py) mit der Nutzlast im Alertmanager-Webhook-Format (Version 4). Nicht abgebildet: Inhibition, Zeitintervalle, Cluster, Notification-Log.
Die simulierte Uhr (`now`) steuert Auswertung und Routing; Zeitstempel der Zustellung sind echte Uhrzeiten (für das Protokoll Auslösezeit/Zustellzeit).
"""
from __future__ import annotations

import json
import re
import ssl
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from promql_mini import Alert, RuleEvaluator, Store, parse_duration

MATCHER = re.compile(r'^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(=~|!~|!=|=)\s*"(.*)"\s*$')


def parse_matcher(text: str):
    m = MATCHER.match(text)
    if not m:
        raise ValueError(f"Matcher nicht lesbar: {text!r}")
    return m.group(1), m.group(2), m.group(3)


def matches(labels: dict, matchers) -> bool:
    for k, op, v in matchers:
        actual = labels.get(k, "")
        ok = {"=": actual == v, "!=": actual != v, "=~": re.fullmatch(v, actual) is not None, "!~": re.fullmatch(v, actual) is None}[op]
        if not ok:
            return False
    return True


@dataclass
class Silence:
    matchers: list
    start: float
    end: float
    created_by: str
    comment: str


@dataclass
class Group:
    route_path: str
    receiver: str
    key: tuple
    group_labels: dict
    wait: float
    interval: float
    repeat: float
    send_resolved: bool
    alerts: dict = field(default_factory=dict)        # fingerprint -> Alert (aktuell feuernd)
    sent: dict = field(default_factory=dict)          # fingerprint -> Zeit der letzten Benachrichtigung
    first_seen: float | None = None
    last_flush: float | None = None
    pending_resolved: list = field(default_factory=list)


@dataclass
class Notification:
    receiver: str
    status: str
    group_labels: dict
    alerts: list
    at_sim: float
    delivered_wall: float | None = None
    delivered_ok: bool = False
    error: str | None = None


class Router:
    """Alertmanager-Routing aus `alertmanager.yml`."""

    def __init__(self, config_path: Path, urls: dict, cafile: str | None = None, now_wall=time.time):
        self.cfg = yaml.safe_load(Path(config_path).read_text(encoding="utf-8"))
        self.urls = urls                      # Empfängername -> https-URL (der Test-Empfänger); in echt: url_file
        self.cafile = cafile
        self.now_wall = now_wall
        self.silences: list[Silence] = []
        self.groups: dict[tuple, Group] = {}
        self.notifications: list[Notification] = []
        self.suppressed: list[dict] = []      # stummgeschaltete Alarme: bleiben protokolliert
        self.failed_deliveries = 0
        self.receivers = {r["name"]: r for r in self.cfg["receivers"]}

    # --- Routenbaum ---
    def _route_of(self, route: dict, labels: dict, path: str, inherited: dict):
        eff = {**inherited, **{k: route[k] for k in ("receiver", "group_by", "group_wait", "group_interval", "repeat_interval") if k in route}}
        matched = []
        for i, child in enumerate(route.get("routes", [])):
            ms = [parse_matcher(x) for x in child.get("matchers", [])]
            if matches(labels, ms):
                matched.extend(self._route_of(child, labels, f"{path}/{i}", eff))
                if not child.get("continue"):
                    return matched
        return matched or [(path, eff)]

    def targets(self, labels: dict):
        return self._route_of(self.cfg["route"], labels, "", {})

    def silenced(self, labels: dict, now: float) -> Silence | None:
        for s in self.silences:
            if s.start <= now < s.end and matches(labels, s.matchers):
                return s
        return None

    # --- Verarbeitung ---
    def process(self, firing: list[Alert], resolved: list[Alert], now: float, all_active: dict) -> list[Notification]:
        """Ein Schritt: Gruppen aktualisieren und fällige Benachrichtigungen senden."""
        for a in firing:
            self._add(a, now)
        for a in resolved:
            self._resolve(a, now)
        out: list[Notification] = []
        for g in list(self.groups.values()):
            out.extend(self._flush(g, now))
        return out

    def _add(self, a: Alert, now: float):
        for path, eff in self.targets(a.labels):
            labels = {k: a.labels.get(k, "") for k in eff.get("group_by", [])}
            key = (path, tuple(sorted(labels.items())))
            g = self.groups.get(key)
            if g is None:
                recv = eff["receiver"]
                wc = self.receivers[recv].get("webhook_configs", [{}])[0]
                g = Group(path, recv, key, labels, parse_duration(eff.get("group_wait", "30s")), parse_duration(eff.get("group_interval", "5m")), parse_duration(eff.get("repeat_interval", "4h")),
                          bool(wc.get("send_resolved", True)))
                self.groups[key] = g
            if g.first_seen is None:
                g.first_seen = now
            g.alerts[a.fingerprint] = a

    def _resolve(self, a: Alert, now: float):
        for g in self.groups.values():
            if a.fingerprint in g.alerts:
                g.alerts.pop(a.fingerprint)
                if a.fingerprint in g.sent and g.send_resolved:
                    g.pending_resolved.append(a)
                g.sent.pop(a.fingerprint, None)

    def _flush(self, g: Group, now: float) -> list[Notification]:
        out: list[Notification] = []
        due = []
        for fp, a in g.alerts.items():
            last = g.sent.get(fp)
            if last is None:
                # neuer Alarm: erste Benachrichtigung nach group_wait (ab Gruppenbeginn), weitere neue Alarme der Gruppe frühestens nach group_interval
                first_ok = now - (g.first_seen or now) >= g.wait and (g.last_flush is None or now - g.last_flush >= g.interval or not g.sent)
                if first_ok:
                    due.append(a)
            elif now - last >= g.repeat:
                due.append(a)
        live = []
        for a in due:
            s = self.silenced(a.labels, now)
            if s:
                self.suppressed.append({"fingerprint": a.fingerprint, "alertname": a.alertname, "at": now, "silence_by": s.created_by, "comment": s.comment})
            else:
                live.append(a)
        if live:
            out.append(self._send(g, "firing", live, now))
            for a in live:
                g.sent[a.fingerprint] = now
            g.last_flush = now
        if g.pending_resolved:
            res = list(g.pending_resolved); g.pending_resolved.clear()
            out.append(self._send(g, "resolved", res, now))
        return out

    def _send(self, g: Group, status: str, alerts: list[Alert], now: float) -> Notification:
        payload = {
            "version": "4", "groupKey": json.dumps(g.key), "truncatedAlerts": 0, "status": status, "receiver": g.receiver, "groupLabels": g.group_labels,
            "commonLabels": _common([a.labels for a in alerts]), "commonAnnotations": _common([a.annotations for a in alerts]), "externalURL": "http://alertmanager.invalid",
            "alerts": [{"status": status, "labels": a.labels, "annotations": a.annotations, "startsAt": a.fired_at or a.active_at, "endsAt": None, "fingerprint": a.fingerprint} for a in alerts],
        }
        n = Notification(g.receiver, status, g.group_labels, payload["alerts"], now)
        url = self.urls.get(g.receiver)
        try:
            if url is None:
                raise OSError("kein Zustellweg für den Empfänger")
            ctx = ssl.create_default_context(cafile=self.cafile)
            req = urllib.request.Request(url, json.dumps(payload).encode(), {"Content-Type": "application/json"})
            urllib.request.urlopen(req, context=ctx, timeout=5).read()
            n.delivered_ok = True
            n.delivered_wall = self.now_wall()
        except Exception as e:  # noqa: BLE001 - jeder Zustellfehler zählt
            n.error = type(e).__name__
            self.failed_deliveries += 1
        self.notifications.append(n)
        return n


def _common(dicts: list[dict]) -> dict:
    if not dicts:
        return {}
    common = dict(dicts[0])
    for d in dicts[1:]:
        common = {k: v for k, v in common.items() if d.get(k) == v}
    return common


class Chain:
    """Regelauswertung + Routing mit gemeinsamer simulierter Uhr."""

    def __init__(self, rule_files: list[Path], router: Router, store: Store):
        groups = []
        for f in rule_files:
            groups.extend(yaml.safe_load(Path(f).read_text(encoding="utf-8"))["groups"])
        self.evaluator = RuleEvaluator(groups, store)
        self.router = router
        self.store = store
        self.trigger_wall: dict[str, float] = {}     # Fingerprint -> echte Uhrzeit der Auslösung (feuernd)

    def step(self, t: float) -> list[Notification]:
        firing, resolved = self.evaluator.step(t)
        wall = time.time()
        for a in firing:
            self.trigger_wall.setdefault(a.fingerprint, wall)
        sent = self.router.process(firing, resolved, t, self.evaluator.active)
        # Wiederholungen und group_wait laufen auch ohne neue Ereignisse
        return sent

    def run(self, start: float, end: float, step: float = 15.0) -> list[Notification]:
        out, t = [], start
        while t <= end:
            out.extend(self.step(t))
            t += step
        return out
