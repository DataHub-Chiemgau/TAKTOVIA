"""Steuerbare Testzeit (WP-M0-22, gemeinsame Vereinbarung für PSP-Simulator und Sandbox-Partner).

Vereinbarung ("Testzeit-Protokoll v1"):
  * Jeder Simulator besitzt eine eigene Uhr (SimClock). Sie ist standardmäßig EINGEFROREN (frozen) und beginnt bei
    der Startzeit, die per Kommandozeile/Konfiguration oder per Steuerschnittstelle gesetzt wird. Eingefroren heißt:
    zwei Läufe mit gleicher Startzeit und gleicher Aufrufreihenfolge liefern identische Zeitstempel.
  * Steuerschnittstelle (nur Testnetz) bei jedem Simulator:
        GET  /control/clock                      -> {"now": "<ISO-8601 UTC>", "mode": "frozen|real"}
        POST /control/clock {"set": "<ISO-8601>"}            Uhr auf Zeitpunkt setzen
        POST /control/clock {"advance_seconds": <Zahl>}      Uhr vorstellen (auch negativ nicht erlaubt)
        POST /control/clock {"mode": "frozen|real"}          real = läuft ab dem gesetzten Wert in Echtzeit weiter
  * Grenze: Die Datenbankzeit now() wird NICHT verstellt. Szenarien, die Ablauf nach Datenbank-/Serverzeit prüfen
    (Hold-Ablauf nach Kap. 01 §1.4), laufen über kurze reale Fristen oder über eine in M1 zu liefernde Zeitquelle
    des Kerns (Offene Punkte 26). Die Simulatoruhr gilt nur für Zeitstempel/Fristen im Simulator selbst.
"""
import threading
import time
from datetime import datetime, timedelta, timezone

DEFAULT_START = "2026-01-01T00:00:00Z"


def parse_iso(value: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError("Zeitpunkt muss ein ISO-8601-String sein")
    v = value.strip()
    if v.endswith("Z"):
        v = v[:-1] + "+00:00"
    dt = datetime.fromisoformat(v)
    if dt.tzinfo is None:
        raise ValueError("Zeitpunkt ohne Zeitzone nicht erlaubt (UTC mit 'Z' angeben)")
    return dt.astimezone(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


class SimClock:
    def __init__(self, start: str = DEFAULT_START, mode: str = "frozen"):
        self._lock = threading.Lock()
        self._base = parse_iso(start)
        self._mark = time.monotonic()
        self._mode = mode

    def now(self) -> datetime:
        with self._lock:
            if self._mode == "real":
                return self._base + timedelta(seconds=time.monotonic() - self._mark)
            return self._base

    def now_iso(self) -> str:
        return iso(self.now())

    def epoch(self) -> int:
        return int(self.now().timestamp())

    def set(self, dt: datetime):
        with self._lock:
            self._base = dt.astimezone(timezone.utc)
            self._mark = time.monotonic()

    def advance(self, seconds: float):
        if seconds < 0:
            raise ValueError("Rückwärts stellen nicht erlaubt; Uhr mit 'set' neu setzen")
        cur = self.now()
        self.set(cur + timedelta(seconds=seconds))

    def set_mode(self, mode: str):
        if mode not in ("frozen", "real"):
            raise ValueError("mode muss frozen oder real sein")
        cur = self.now()
        with self._lock:
            self._mode = mode
        self.set(cur)

    @property
    def mode(self):
        return self._mode

    def control(self, body: dict) -> dict:
        """Gemeinsame Umsetzung von POST /control/clock."""
        if "mode" in body:
            self.set_mode(body["mode"])
        if "set" in body:
            self.set(parse_iso(body["set"]))
        if "advance_seconds" in body:
            v = body["advance_seconds"]
            if isinstance(v, bool) or not isinstance(v, (int, float)):
                raise ValueError("advance_seconds muss eine Zahl sein")
            self.advance(v)
        return self.snapshot()

    def snapshot(self) -> dict:
        return {"now": self.now_iso(), "mode": self.mode}


def set_clock(base_url: str, when: str, call) -> dict:
    """Setzt die Uhr eines Simulators; `call` ist taktovia_testkit.jsonhttp.http_call."""
    status, body, _, _ = call("POST", base_url.rstrip("/") + "/control/clock", {"set": when})
    if status != 200:
        raise RuntimeError(f"Uhr setzen fehlgeschlagen: {status} {body}")
    return body


def set_all(base_urls, when: str, call=None) -> list:
    """Setzt die Uhr von PSP-Simulator und Sandbox-Partnern in einem Schritt (Szenario-Hilfsfunktion)."""
    if call is None:
        from .jsonhttp import http_call as call
    return [set_clock(u, when, call) for u in base_urls]
