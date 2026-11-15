"""WP-M5-13 / LH-6.19-NFR-004: Messharness „Commit der Fahrer-Planrevision bis Darstellung beim Client“ gegen die **echte Anwendung** (Spring-Boot-Jar, Runtime-Rolle, RLS, Live-Kanal, Fahrerabruf).

Kette (Revisionsnummer als Korrelation):
  Commit (`vehicle_run.revision`, `route_stop`, `plan_change`, `outbox` + Projektion `live_event` in EINER Transaktion)
  -> Live-Kanal (Server-Sent Events, `GET /v1/driver/live/events`, Abruf der Datenbank je Abonnement)
  -> Empfang im Gerät -> Nachladen des Plans (`GET /v1/driver/shifts/{id}/run`) -> Darstellung (`on_displayed`).

Was echt ist: Anwendung, Live-Kanal, Fahrerabruf, Berechtigung, Datenbank und Trigger (`outbox_live`), Runtime-Rolle. Was **nicht** echt ist (steht in jedem Protokoll unter „Grenzen“):
- Der Planschreiber: die Anwendung hat keinen Weg, 250 Umläufe je Mandant ohne Buchungen zu ändern. Das Schreiben der Revision bildet `JdbcRunPlans.appendIn` Anweisung für Anweisung nach
  (Zeilenlock, Revision +1, Stopps, `plan_change`, Outbox-Ereignis `dispatch.run_revised`) und läuft unter der Runtime-Rolle; Plan-Validator und Diff je Buchung entfallen (keine Buchungen).
- Das Gerät: `DeviceSim` ist eine Gerätesimulation (asyncio), keine Flutter-App. `on_displayed` ersetzt den Testmodus-Meldepunkt von UI-10 (`mobile/driver/lib/run/display_probe.dart`, nicht
  ausgeführt: Flutter nicht verfügbar). Die Aussage gilt damit für das **simulierte** Gerät (OP-27: Zielgeräte und Instrumentierung nicht festgelegt).
- Der Relay (Outbox -> Broker) ist in der Anwendung nicht verdrahtet; der Live-Kanal liest `live_event`, das der Trigger in der Commit-Transaktion schreibt. Outbox-Alter und Relay-Verzug werden
  deshalb als „nicht veröffentlicht“ ausgewiesen, nicht erfunden. Der Verteilweg wird stattdessen real angehalten (offene Transaktion hält den Snapshot-Horizont, siehe [stall])."""
from __future__ import annotations

import asyncio
import json
import math
import random
import threading
import time
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

ZIEL_S = 10.0
STATUS_ZIELWERT = "vorläufig, D-05 offen"


# --- reine Logik (ohne Anwendung testbar) -----------------------------------------------------------------------------------------------------

def percentile(values: list[float], p: float) -> float:
    """Nearest-Rank wie `perfkit.stats`."""
    s = sorted(values)
    return s[max(0, math.ceil(p / 100 * len(s)) - 1)]


def summarize(values: list[float]) -> dict:
    if not values:
        return {"n": 0}
    return {"n": len(values), "min_s": round(min(values), 4), "p50_s": round(percentile(values, 50), 4), "p90_s": round(percentile(values, 90), 4), "p95_s": round(percentile(values, 95), 4),
            "p99_s": round(percentile(values, 99), 4), "max_s": round(max(values), 4), "mean_s": round(sum(values) / len(values), 4)}


def histogram(values: list[float], edges=(0.25, 0.5, 1.0, 2.0, 5.0, 10.0)) -> dict:
    out: dict[str, int] = {}
    lo = 0.0
    for e in edges:
        out[f"{lo:g}-{e:g}s"] = sum(1 for v in values if lo <= v < e)
        lo = e
    out[f">={lo:g}s"] = sum(1 for v in values if v >= lo)
    return out


@dataclass
class Sample:
    """Eine Planrevision bei einem Gerät. Zeiten in Nanosekunden der Wanduhr des Messrechners (`time.time_ns()`), Server und Gerät laufen auf derselben Maschine."""
    tenant: str
    vehicle: str
    revision: int
    t_commit_start: int
    t_commit_end: int
    phase: str
    t_recv: int | None = None
    t_shown: int | None = None
    connected_at_commit: bool = True      # Stream stand beim Commit (und blieb bis zur Darstellung bestehen)
    via_catchup: bool = False             # Zustellung erst nach Wiederverbindung (Cursor) bzw. Nachladen
    stalled: bool = False                 # Verteilweg war angehalten
    cid: int = 0                          # Verbindungsnummer des Geräts beim Commit

    @property
    def latency_s(self) -> float | None:
        return None if self.t_shown is None else max(0.0, (self.t_shown - self.t_commit_start) / 1e9)

    @property
    def stage_delivery_s(self) -> float | None:
        return None if self.t_recv is None else max(0.0, (self.t_recv - self.t_commit_start) / 1e9)

    @property
    def stage_display_s(self) -> float | None:
        return None if self.t_shown is None or self.t_recv is None else max(0.0, (self.t_shown - self.t_recv) / 1e9)


def classify(s: Sample) -> str:
    """`ziel` (bestehende Verbindung, Zustellung über den Live-Kanal), `unterbrochen` (Verbindung beim Commit oder bis zur Darstellung nicht durchgehend), `nachgeladen` (Zustellung erst durch Wiederverbindung
    oder Abruf, Ausreißer), `nicht_dargestellt`. Verzögerung über dem Ziel bei bestehender Verbindung bleibt `ziel` (zählt gegen das Ziel)."""
    if not s.connected_at_commit:
        return "unterbrochen"
    if s.t_shown is None:
        return "nicht_dargestellt"
    if s.via_catchup:
        return "nachgeladen"
    return "ziel"


def evaluate(samples: list[Sample], target_s: float = ZIEL_S) -> dict:
    """Auswertung der Zielmenge: p95 über **alle** Revisionen bei bestehender Verbindung; nachgeladene und nicht dargestellte zählen als Ausreißer und gegen das Ziel (nie als Erfolg)."""
    by: dict[str, list[Sample]] = defaultdict(list)
    for s in samples:
        by[classify(s)].append(s)
    target = by["ziel"]
    lat = [s.latency_s for s in target if s.latency_s is not None]
    # Zielmenge = bestehende Verbindung; Ausreißer (nachgeladen/nicht dargestellt) gehören dazu und werden als Fehlschlag gewertet
    pop = len(target) + len(by["nachgeladen"]) + len(by["nicht_dargestellt"])
    fail = sum(1 for v in lat if v > target_s) + len(by["nachgeladen"]) + len(by["nicht_dargestellt"])
    # p95 der Zielmenge mit Ausreißern als unendliche Latenz
    all_lat = lat + [math.inf] * (len(by["nachgeladen"]) + len(by["nicht_dargestellt"]))
    p95 = percentile(all_lat, 95) if all_lat else None
    return {
        "zielmenge_n": pop, "latenz": summarize(lat), "p95_zielmenge_inkl_ausreisser_s": None if p95 is None else (round(p95, 4) if p95 != math.inf else "unendlich"),
        "anteil_innerhalb_ziel": round(1 - fail / pop, 4) if pop else None, "ueber_ziel": sum(1 for v in lat if v > target_s), "verteilung": histogram(lat),
        "ausreisser_nachgeladen": len(by["nachgeladen"]), "nicht_dargestellt": len(by["nicht_dargestellt"]),
        "unterbrochen_getrennt": {"n": len(by["unterbrochen"]), "nicht_dargestellt": sum(1 for s in by["unterbrochen"] if s.t_shown is None), "latenz": summarize([s.latency_s for s in by["unterbrochen"] if s.latency_s is not None])},
        "teilstrecken": {"commit_bis_empfang": summarize([s.stage_delivery_s for s in target if s.stage_delivery_s is not None]), "empfang_bis_darstellung": summarize([s.stage_display_s for s in target if s.stage_display_s is not None])},
        "ziel_s": target_s, "ziel_erfuellt": p95 is not None and p95 != math.inf and p95 <= target_s,
    }


def parse_sse(buffer: str) -> tuple[list[tuple[str, str, str]], str]:
    """Zerlegt einen SSE-Puffer in (Ereignisname, id, data)-Tripel und den Rest; Kommentarzeilen (Herzschlag) werden verworfen."""
    out, parts = [], buffer.split("\n\n")
    rest = parts.pop()
    for block in parts:
        name, ident, data = "message", "", []
        for line in block.split("\n"):
            if line.startswith(":") or not line:
                continue
            k, _, v = line.partition(":")
            v = v[1:] if v.startswith(" ") else v
            if k == "event":
                name = v
            elif k == "id":
                ident = v
            elif k == "data":
                data.append(v)
        if data or name != "message":
            out.append((name, ident, "\n".join(data)))
    return out, rest


def now_ns() -> int:
    return time.time_ns()


# --- Planschreiber (Nachbildung von JdbcRunPlans.appendIn unter der Runtime-Rolle) ---------------------------------------------------------------

@dataclass
class RunRef:
    tenant: str
    run_id: str
    vehicle_id: str
    shift_id: str
    stops: list[str]
    revision: int = 1


def prepare_runs(admin, tenant: str, rigs, stop_ids: list[str]) -> list[RunRef]:
    """Je Fahrer ein Umlauf (Revision 1, zwei Stopps), die Einsatzfreigabe der Schicht und der Plan der Ausgangsrevision (Testaufbau über den Administratorzugang)."""
    admin.execute("SELECT set_config('app.tenant_id', %s, false)", (tenant,))
    now = datetime.now(timezone.utc)
    runs, vr, rel, st = [], [], [], []
    for i, r in enumerate(rigs):
        run = str(uuid.uuid4())
        runs.append(RunRef(tenant, run, r.vehicle_id, r.shift_id, stop_ids))
        vr.append((tenant, run, r.vehicle_id, r.shift_id, 1, "ACTIVE"))
        rel.append((tenant, str(uuid.uuid4()), r.vehicle_id, r.shift_id, 1, str(uuid.uuid4()), 1, now + timedelta(hours=6)))
        for k, sid in enumerate(stop_ids):
            st.append((tenant, str(uuid.uuid4()), run, 1, k, sid, now + timedelta(minutes=30 + 10 * k)))
    with admin.cursor() as cur:
        cur.execute("UPDATE taktovia.vehicle SET active_block = false WHERE tenant_id = %s AND id = ANY(%s::uuid[])", (tenant, [r.vehicle_id for r in rigs]))   # Fahrzeug ohne operativen Block (Testaufbau)
        cur.executemany("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,%s,%s)", vr)
        cur.executemany("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)", rel)
        cur.executemany("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at) VALUES (%s,%s,%s,%s,%s,%s,%s)", st)
    return runs


def commit_revision(conn, ref: RunRef, shift_minutes: int) -> tuple[int, int, int]:
    """Eine Planrevision wie `appendIn`: Zeilenlock auf dem Umlauf, Revision +1, Stopps der neuen Revision (Zeiten um [shift_minutes] verschoben: bestätigungspflichtig), `plan_change`, Outbox-Ereignis.
    Liefert (Revision, Start, Ende des Commits) in Nanosekunden der Wanduhr; der Start liegt vor dem Senden des ersten Befehls (konservativ: der Zeitraum enthält die Schreibdauer)."""
    t0 = now_ns()
    with conn.transaction():
        conn.execute("SELECT set_config('app.tenant_id', %s, true)", (ref.tenant,))
        cur = conn.execute("SELECT revision FROM taktovia.vehicle_run WHERE id = %s FOR UPDATE", (ref.run_id,)).fetchone()
        prev = cur[0]
        nxt = prev + 1
        conn.execute("UPDATE taktovia.vehicle_run SET revision = %s WHERE id = %s", (nxt, ref.run_id))
        base = datetime.now(timezone.utc) + timedelta(minutes=shift_minutes)
        for k, sid in enumerate(ref.stops):
            conn.execute("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at,leg_duration_sec,leg_distance_m) VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,%s)",
                         (uuid.uuid4(), ref.run_id, nxt, k, sid, base + timedelta(minutes=10 * k), 240 if k < len(ref.stops) - 1 else None, 1800 if k < len(ref.stops) - 1 else None))
        conn.execute("INSERT INTO taktovia.plan_change(tenant_id,id,run_id,run_revision,previous_revision,cause,created_via) VALUES (taktovia.current_tenant(),%s,%s,%s,%s,'MANUAL_CHANGE','SYSTEM')", (uuid.uuid4(), ref.run_id, nxt, prev))
        payload = {"run_id": ref.run_id, "previous_revision": prev, "revision": nxt, "cause": "MANUAL_CHANGE", "affected_booking_ids": [], "booking_changes": []}
        conn.execute("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload,recipient_tenant_id,correlation_id) "
                     "VALUES (taktovia.current_tenant(),%s,%s,%s,'dispatch.run_revised','1.0',clock_timestamp(),%s::jsonb,taktovia.current_tenant(),%s)",
                     (uuid.uuid4(), ref.run_id, nxt, json.dumps(payload), uuid.uuid4()))
    t1 = now_ns()
    ref.revision = nxt
    return nxt, t0, t1


# --- Gerätesimulation ---------------------------------------------------------------------------------------------------------------------------

class DeviceSim:
    """Ein Fahrergerät: Live-Kanal offen halten (SSE, Cursor, Wiederverbindung), bei `dispatch.run_revised` den Plan nachladen und „darstellen“. Eine ältere Revision überschreibt nie eine neuere."""

    def __init__(self, host: str, port: int, token_fn, tenant: str, ref: RunRef, samples: dict, on_displayed=None) -> None:
        self.host, self.port, self.token_fn, self.tenant, self.ref = host, port, token_fn, tenant, ref
        self.samples = samples                       # (run_id, revision) -> Sample
        self.on_displayed = on_displayed
        self.shown_revision = 1
        self.regressions = 0                        # Zahl der Fälle, in denen eine ältere Revision eine neuere überschrieben hätte
        self.foreign_events = 0                     # Ereignisse mit fremdem Umlauf/Schicht
        self.events = 0
        self.cursor: str | None = None
        self.connected = False
        self.conn_id = 0
        self.ready = asyncio.Event()
        self.stop = False
        self.hold_until = 0.0                       # bis dahin nicht (wieder) verbinden (simulierte Unterbrechung)
        self._writer: asyncio.StreamWriter | None = None
        self.reconnects = 0
        self.catchup_marked = False
        self._http: tuple | None = None
        self.errors: list[str] = []
        self.retries = 0

    # -- Stromverbindung
    async def run(self) -> None:
        while not self.stop:
            if time.monotonic() < self.hold_until:
                await asyncio.sleep(0.05); continue
            try:
                await self._stream_once()
            except (asyncio.CancelledError, GeneratorExit):
                raise
            except Exception as e:  # noqa: BLE001 - Verbindungsabbruch: neu verbinden
                self.errors.append(type(e).__name__)
            self.connected = False
            if not self.stop:
                self.reconnects += 1
                await asyncio.sleep(0.05)

    async def _stream_once(self) -> None:
        r, w = await asyncio.open_connection(self.host, self.port)
        self._writer = w
        path = "/v1/driver/live/events?wait_seconds=55" + (f"&after={self.cursor}" if self.cursor else "")
        w.write((f"GET {path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\nAuthorization: Bearer {self.token_fn()}\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n").encode())
        await w.drain()
        status = int((await r.readline()).split()[1])
        headers = {}
        while True:
            line = await r.readline()
            if line in (b"\r\n", b""):
                break
            k, _, v = line.decode("latin-1").partition(":"); headers[k.strip().lower()] = v.strip()
        if status != 200:
            w.close()
            raise ConnectionError(f"HTTP {status}")
        chunked = headers.get("transfer-encoding", "").lower() == "chunked"
        buf = ""
        after_reconnect = self.cursor is not None
        self.conn_id += 1
        cid = self.conn_id
        try:
            while True:
                if chunked:
                    size_line = await r.readline()
                    if not size_line:
                        return
                    size = int(size_line.split(b";")[0].strip() or b"0", 16)
                    if size == 0:
                        return
                    data = await r.readexactly(size); await r.readline()
                else:
                    data = await r.read(65536)
                    if not data:
                        return
                buf += data.decode("utf-8", "replace")
                frames, buf = parse_sse(buf)
                for name, ident, payload in frames:
                    if name == "ready":
                        self.connected = True
                        self.ready.set()
                        if self.cursor is None:
                            self.cursor = json.loads(payload)["cursor"]
                    elif name == "live":
                        t = now_ns()
                        if ident:
                            self.cursor = ident
                        await self._on_live(json.loads(payload), t, after_reconnect, cid)
                    elif name == "end":
                        d = json.loads(payload)
                        self.cursor = d.get("cursor", self.cursor)
                        return
        finally:
            self.connected = False
            w.close()

    async def drop(self, seconds: float) -> None:
        """Simulierte kurze Unterbrechung der Verbindung (Funkloch): Socket schließen, nach [seconds] mit dem letzten Cursor neu verbinden."""
        self.hold_until = time.monotonic() + seconds
        if self._writer is not None:
            self._writer.close()
        self.connected = False

    async def _on_live(self, ev: dict, t_recv: int, after_reconnect: bool, cid: int) -> None:
        self.events += 1
        if ev.get("type") != "dispatch.run_revised":
            return
        if ev.get("aggregate_id") != self.ref.run_id or ev.get("shift_id") != self.ref.shift_id:
            self.foreign_events += 1
            return
        rev = int(ev["aggregate_revision"])
        s = self.samples.get((self.ref.run_id, rev))
        if s is not None and s.t_recv is None:
            s.t_recv = t_recv
            # Verbindung ununterbrochen seit dem Commit? (gleiche Verbindungsnummer wie beim Commit)
            if s.connected_at_commit and s.cid != cid:
                s.connected_at_commit = False
        await self._load_and_show(rev, cid)

    async def _load_and_show(self, rev: int, cid: int) -> None:
        if self.shown_revision >= rev:
            return
        status, body = await self._get_run()
        if status == 0:
            # veraltete Keep-Alive-Verbindung (der Server schließt untätige Verbindungen): wie jeder HTTP-Client einmal auf frischer Verbindung wiederholen, gezählt
            self.retries += 1
            status, body = await self._get_run()
        if status != 200 or body is None:
            self.errors.append(f"run_{status}")
            return
        if body.get("release_state") != "RELEASED" or not body.get("stops"):
            self.errors.append("plan_nicht_darstellbar")           # ohne Freigabe/Stopps gibt es nichts darzustellen
            return
        got = int(body["plan_revision"])
        if got < self.shown_revision:
            self.regressions += 1
            return
        if got < rev:
            return                                       # Plan noch nicht auf dem Stand des Ereignisses (kommt nicht vor: Ereignis entsteht im Commit)
        t = now_ns()
        prev, self.shown_revision = self.shown_revision, got
        for r in range(prev + 1, got + 1):                # eine höhere Revision deckt die dazwischenliegenden ab
            s = self.samples.get((self.ref.run_id, r))
            if s is not None and s.t_shown is None:
                s.t_shown = t
        if self.on_displayed:
            self.on_displayed(self.ref.run_id, got, t)

    async def _get_run(self) -> tuple[int, dict | None]:
        if self._http is None:
            self._http = await asyncio.open_connection(self.host, self.port)
        r, w = self._http
        try:
            w.write((f"GET /v1/driver/shifts/{self.ref.shift_id}/run HTTP/1.1\r\nHost: {self.host}:{self.port}\r\nAuthorization: Bearer {self.token_fn()}\r\nAccept: application/json\r\nConnection: keep-alive\r\n\r\n").encode())
            await w.drain()
            status = int((await r.readline()).split()[1]); headers = {}
            while True:
                line = await r.readline()
                if line in (b"\r\n", b""):
                    break
                k, _, v = line.decode("latin-1").partition(":"); headers[k.strip().lower()] = v.strip()
            if headers.get("transfer-encoding", "").lower() == "chunked":
                chunks = []
                while True:
                    size = int((await r.readline()).split(b";")[0].strip() or b"0", 16)
                    if size == 0:
                        await r.readline(); break
                    chunks.append(await r.readexactly(size)); await r.readline()
                raw = b"".join(chunks)
            else:
                n = int(headers.get("content-length", "0")); raw = await r.readexactly(n) if n else b""
            return status, (json.loads(raw) if raw else None)
        except Exception:  # noqa: BLE001
            w.close(); self._http = None
            return 0, None

    async def close(self) -> None:
        self.stop = True
        if self._writer is not None:
            self._writer.close()
        if self._http is not None:
            self._http[1].close()


# --- Uhrenabgleich ------------------------------------------------------------------------------------------------------------------------------

def clock_offset(conn, rounds: int = 25) -> dict:
    """Versatz Datenbankuhr gegen Messuhr: kleinste Hin-/Rückzeit gewinnt (NTP-Prinzip). Server (Anwendung, Datenbank) und Gerätesimulation laufen auf derselben Maschine, der Versatz ist eine Gegenprobe."""
    best = None
    for _ in range(rounds):
        a = now_ns(); (db,) = conn.execute("SELECT (extract(epoch from clock_timestamp()) * 1e9)::bigint").fetchone(); b = now_ns()
        rtt = b - a
        if best is None or rtt < best[0]:
            best = (rtt, db - (a + b) // 2)
    return {"versatz_ms": round(best[1] / 1e6, 3), "hin_rueck_ms": round(best[0] / 1e6, 3), "runden": rounds, "bemerkung": "gleiche Maschine; Messzeiten stammen alle aus der Wanduhr des Messprozesses"}
