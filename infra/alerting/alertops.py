#!/usr/bin/env python3
"""Betriebswerkzeug für Alarmregeln, Stummschaltung und Quittierung (WP-M5-04, Kriterium 6).

Anlegen, Ändern, Stummschalten und Quittieren ist nur Betriebsrollen möglich und wird je Vorgang protokolliert (Audit, JSON-Zeilen, anhängend). Andere Rollen werden abgewiesen; auch die
Abweisung wird protokolliert. Die Quittierung ist je Alarm genau einmal wirksam (Eindeutigkeit in SQLite, auch bei parallelen Betriebsrollen). Regeländerungen validieren die Datei vollständig
(YAML, Pflichtetiketten, Ausdrücke in der auswertbaren Teilmenge) und schreiben atomar. Alarmregeln liegen zusätzlich unter Quellverwaltung mit Code-Owner-Pflicht (.github/CODEOWNERS).

STATUS: Python-Werkzeug mit Tests (tests/acceptance/alerting); es steuert den echten Alertmanager NICHT an (Silences gegen dessen API sind nicht ausgeführt). Die Rollenprüfung ist die
Ausführungsschicht dieses Werkzeugs, keine Authentisierung: sie vertraut der übergebenen Betriebsidentität (in Betrieb: Betriebs-Realm, WP-M5-03).
"""
from __future__ import annotations

import contextlib
import json
import os
import sqlite3
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import yaml

import promql_mini

OPS_ROLES = frozenset({"betrieb", "plattform"})
MAX_SILENCE_H = 8
REQUIRED_LABELS = {"severity", "component", "scenario", "empfaenger"}


@dataclass(frozen=True)
class Actor:
    name: str
    roles: frozenset

    @property
    def is_ops(self) -> bool:
        return bool(self.roles & OPS_ROLES)


class AlertOps:
    def __init__(self, db_path: Path, audit_path: Path, now=time.time):
        self.db_path = Path(db_path)
        self.audit_path = Path(audit_path)
        self.now = now
        self._audit_lock = threading.Lock()
        with contextlib.closing(self._db()) as c:
            c.execute("CREATE TABLE IF NOT EXISTS ack (fingerprint TEXT PRIMARY KEY, actor TEXT NOT NULL, at REAL NOT NULL, comment TEXT NOT NULL)")
            c.execute("CREATE TABLE IF NOT EXISTS silence (id INTEGER PRIMARY KEY AUTOINCREMENT, matchers TEXT NOT NULL, start REAL NOT NULL, end REAL NOT NULL, actor TEXT NOT NULL, comment TEXT NOT NULL)")

    def _db(self) -> sqlite3.Connection:
        c = sqlite3.connect(self.db_path, timeout=30, isolation_level=None)
        c.execute("PRAGMA journal_mode=WAL")
        return c

    def _audit(self, actor: Actor, action: str, result: str, **details) -> None:
        line = json.dumps({"at": self.now(), "actor": actor.name, "roles": sorted(actor.roles), "action": action, "result": result, **details}, ensure_ascii=False)
        with self._audit_lock, open(self.audit_path, "a", encoding="utf-8") as f:
            f.write(line + "\n")

    def _require_ops(self, actor: Actor, action: str, **details) -> None:
        if not actor.is_ops:
            self._audit(actor, action, "denied", **details)
            raise PermissionError(f"{action}: nur Betriebsrollen")

    # --- Stummschalten ---
    def silence(self, actor: Actor, matchers: list[str], minutes: int, comment: str) -> int:
        self._require_ops(actor, "silence", matchers=matchers)
        if not comment.strip():
            raise ValueError("Pflichtkommentar fehlt")
        if not (1 <= minutes <= MAX_SILENCE_H * 60):
            raise ValueError(f"Dauer außerhalb 1..{MAX_SILENCE_H * 60} Minuten")
        if not matchers:
            raise ValueError("ein Matcher ist Pflicht (keine Pauschal-Stummschaltung)")
        t = self.now()
        with contextlib.closing(self._db()) as c:
            cur = c.execute("INSERT INTO silence(matchers, start, end, actor, comment) VALUES (?,?,?,?,?)", (json.dumps(matchers), t, t + minutes * 60, actor.name, comment))
        self._audit(actor, "silence", "ok", matchers=matchers, minutes=minutes, comment=comment, id=cur.lastrowid)
        return int(cur.lastrowid)

    def active_silences(self) -> list[dict]:
        t = self.now()
        with contextlib.closing(self._db()) as c:
            rows = c.execute("SELECT id, matchers, start, end, actor, comment FROM silence WHERE start <= ? AND end > ?", (t, t)).fetchall()
        return [{"id": r[0], "matchers": json.loads(r[1]), "start": r[2], "end": r[3], "actor": r[4], "comment": r[5]} for r in rows]

    # --- Quittieren ---
    def acknowledge(self, actor: Actor, fingerprint: str, comment: str) -> bool:
        """`True`, wenn DIESER Aufruf die Quittierung wirksam gemacht hat; bei bereits quittiertem Alarm `False` (kein zweiter Eintrag)."""
        self._require_ops(actor, "acknowledge", fingerprint=fingerprint)
        if not comment.strip():
            raise ValueError("Pflichtkommentar fehlt")
        with contextlib.closing(self._db()) as c:
            cur = c.execute("INSERT OR IGNORE INTO ack(fingerprint, actor, at, comment) VALUES (?,?,?,?)", (fingerprint, actor.name, self.now(), comment))
            effective = cur.rowcount == 1
        self._audit(actor, "acknowledge", "ok" if effective else "already_acknowledged", fingerprint=fingerprint, comment=comment)
        return effective

    def acknowledgements(self, fingerprint: str) -> list[tuple]:
        with contextlib.closing(self._db()) as c:
            return c.execute("SELECT actor, at, comment FROM ack WHERE fingerprint = ?", (fingerprint,)).fetchall()

    # --- Regeln ---
    def change_rules(self, actor: Actor, path: Path, new_text: str, reason: str) -> None:
        """Ersetzt eine Regeldatei, nachdem sie vollständig geprüft wurde; atomar und protokolliert. Fehlerhafte Dateien ändern nichts."""
        self._require_ops(actor, "change_rules", path=str(path))
        if not reason.strip():
            raise ValueError("Pflichtbegründung fehlt")
        problems = validate_rules_text(new_text)
        if problems:
            self._audit(actor, "change_rules", "rejected", path=str(path), problems=problems)
            raise ValueError("; ".join(problems))
        path = Path(path)
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".rules-")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(new_text)
        os.replace(tmp, path)
        self._audit(actor, "change_rules", "ok", path=str(path), reason=reason)


def validate_rules_text(text: str) -> list[str]:
    """Prüft eine Regeldatei: gültiges YAML, je Regel Pflichtetiketten und Anmerkungen ohne Vorlagenplatzhalter, Ausdruck in der auswertbaren Teilmenge."""
    try:
        doc = yaml.safe_load(text)
        groups = doc["groups"]
    except Exception as e:  # noqa: BLE001
        return [f"Regeldatei nicht lesbar: {type(e).__name__}"]
    problems = []
    for g in groups:
        for r in g.get("rules", []):
            name = r.get("alert", "?")
            missing = REQUIRED_LABELS - set(r.get("labels", {}))
            if missing:
                problems.append(f"{name}: Etiketten fehlen {sorted(missing)}")
            for k, v in r.get("annotations", {}).items():
                if "{{" in str(v):
                    problems.append(f"{name}: Anmerkung {k} enthält Vorlagenplatzhalter (Etikettenwerte gehören nicht in Alarmtexte)")
            try:
                promql_mini.parse(str(r.get("expr", "")))
            except promql_mini.PromqlError as e:
                problems.append(f"{name}: Ausdruck nicht auswertbar ({e})")
    return problems
