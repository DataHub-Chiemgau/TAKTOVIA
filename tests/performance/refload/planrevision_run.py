"""Ausführung des Messlaufs für WP-M5-13 (siehe `planrevision.py`): Anwendung starten, zwei Mandantenwelten mit je 250 Fahrern anlegen, 500 Gerätesimulationen mit offenem Live-Kanal halten,
im Hintergrund die Positionsmeldungen der Fahrer (alle 15 s) senden und in vier Phasen Planrevisionen committen:

  A `serie`         gleichmäßige Serie über beide Mandanten unter Grundlast (p95-Nachweis, Zielmenge = bestehende Verbindung)
  B `konkurrenz`    zwei Revisionen kurz nacheinander je Umlauf; Fahrerereignis (Bestätigung) mit alter Revision parallel zur Planänderung
  C `unterbrochen`  kurze Unterbrechung der Verbindung eines Teils der Geräte um den Commit (zählt nicht zur Zielmenge, getrennt ausgewiesen)
  D `stall`         Verteilweg angehalten (offene Transaktion hält den Snapshot-Horizont des Live-Kanals) und wieder freigegeben: Zustellung erst danach = Ausreißer, nicht Erfolg

Ergebnis: Protokoll (`dict`), Rohdaten je Revision als CSV."""
from __future__ import annotations

import asyncio
import csv
import json
import os
import random
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from . import aload
from . import planrevision as P
from . import world as W
from .appproc import App
from .idp import TestIdp
from .sampler import Sampler

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "db"))

VEHICLES = 250
TENANTS = 2


def _scale() -> float:
    return float(os.environ.get("TAKTOVIA_PERF_PLANREV_SCALE", "1.0"))


class Run:
    def __init__(self, app, idp, admin_factory, worlds, rigs, runs_by_tenant, db_name: str) -> None:
        self.app, self.idp, self.admin_factory, self.worlds, self.rigs, self.db_name = app, idp, admin_factory, worlds, rigs, db_name
        self.runs = runs_by_tenant
        self.samples: dict[tuple[str, int], P.Sample] = {}
        self.devices: dict[str, P.DeviceSim] = {}            # run_id -> Gerät
        self.locks: dict[str, threading.Lock] = {}
        self.writers: list = []
        self.writer_lock = threading.Lock()
        self.pool = ThreadPoolExecutor(12)
        self.displayed: list[tuple[str, int, int]] = []
        self.t_phase: dict[str, tuple[int, int]] = {}
        self.rec = aload.Recorder()

    # -- Schreiber (Runtime-Rolle)
    def writer(self):
        import db as dbtool
        with self.writer_lock:
            if self.writers:
                return self.writers.pop()
        return dbtool.conn("taktovia_runtime", os.environ["TAKTOVIA_DB_RUNTIME_PASSWORD"], self.db_name)

    def release(self, c) -> None:
        with self.writer_lock:
            self.writers.append(c)

    def commit(self, ref: P.RunRef, phase: str, shift_min: int) -> P.Sample:
        dev = self.devices[ref.run_id]
        lock = self.locks.setdefault(ref.run_id, threading.Lock())
        conn = self.writer()
        try:
            with lock:
                rev = ref.revision + 1
                s = P.Sample(ref.tenant, ref.vehicle_id, rev, 0, 0, phase, connected_at_commit=dev.connected, cid=dev.conn_id)
                self.samples[(ref.run_id, rev)] = s
                try:
                    _, t0, t1 = P.commit_revision(conn, ref, shift_min)
                except Exception:
                    self.samples.pop((ref.run_id, rev), None)
                    raise
                s.t_commit_start, s.t_commit_end = t0, t1
            return s
        finally:
            self.release(conn)

    async def acommit(self, ref, phase, shift_min=15):
        return await asyncio.get_running_loop().run_in_executor(self.pool, self.commit, ref, phase, shift_min)

    def all_refs(self) -> list[P.RunRef]:
        return [r for refs in self.runs.values() for r in refs]

    async def settle(self, timeout: float, phase: str) -> None:
        """Wartet, bis alle Samples der Phase dargestellt sind (oder [timeout])."""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if all(s.t_shown is not None for s in self.samples.values() if s.phase == phase):
                return
            await asyncio.sleep(0.25)


async def _positions(run: Run, rig: W.DriverRig, w: W.TenantWorld, idx: int, stop: asyncio.Event) -> None:
    c = aload.Client(run.rec, "127.0.0.1", run.app.port, w.label, lambda: run.idp.token(rig.subject, sid=rig.sid))
    rnd = random.Random(f"pos|{w.label}|{idx}")
    try:
        await asyncio.sleep(rnd.random() * 15)
        while not stop.is_set():
            now = datetime.now(timezone.utc)
            body = {"shift_id": rig.shift_id, "vehicle_id": rig.vehicle_id, "device_id": rig.device_id,
                    "samples": [{"position": {"latitude": round(51.04 + rnd.random() * 0.05, 6), "longitude": round(13.72 + rnd.random() * 0.06, 6)}, "accuracy_meters": 5.0, "measured_at": now.isoformat()}]}
            await c.call("POST /v1/driver/positions", "POST", "/v1/driver/positions", body)
            try:
                await asyncio.wait_for(stop.wait(), 15)
            except asyncio.TimeoutError:
                pass
    finally:
        await c.close()


async def phase_serie(run: Run, rate: float, seconds: float) -> dict:
    refs = run.all_refs(); random.Random(1313).shuffle(refs)
    t_start = P.now_ns()
    n = int(rate * seconds); gap = 1.0 / rate
    t0 = time.monotonic(); tasks = []
    for i in range(n):
        ref = refs[i % len(refs)]
        tasks.append(asyncio.create_task(run.acommit(ref, "serie", 15 + 15 * (i // len(refs) + 1))))
        await asyncio.sleep(max(0.0, t0 + (i + 1) * gap - time.monotonic()))
    await asyncio.gather(*tasks)
    await run.settle(30, "serie")
    run.t_phase["serie"] = (t_start, P.now_ns())
    return {"commits": n, "rate_je_s": rate, "dauer_s": round(time.monotonic() - t0, 1)}


async def _ack(run: Run, rig: W.DriverRig, shift: str, revision: int, if_match: str) -> tuple[int, dict | None, dict]:
    c = aload.Client(run.rec, "127.0.0.1", run.app.port, "ack", lambda: run.idp.token(rig.subject, sid=rig.sid))
    try:
        return await c.call("POST /v1/driver/shifts/{id}/run/acknowledgements", "POST", f"/v1/driver/shifts/{shift}/run/acknowledgements",
                            {"plan_revision": revision, "acknowledged_at": datetime.now(timezone.utc).isoformat()}, if_match=if_match)
    finally:
        await c.close()


async def phase_konkurrenz(run: Run, per_tenant: int = 10) -> dict:
    """Zwei Revisionen kurz nacheinander; Bestätigung der alten Revision parallel zur Planänderung."""
    out = {"doppelt": [], "ack_parallel": []}
    rigs_by_run = {r.run_id: r for rs in run.rigs.values() for r in rs if False}
    refs = []
    for w in run.worlds:
        refs += run.runs[w.tenant_id][-per_tenant:]
    rig_of = {ref.run_id: rig for w in run.worlds for ref, rig in zip(run.runs[w.tenant_id], run.rigs[w.label])}
    t_start = P.now_ns()

    async def double(ref):
        a = await run.acommit(ref, "konkurrenz", 60)
        b = await run.acommit(ref, "konkurrenz", 75)
        return ref, a.revision, b.revision

    res = await asyncio.gather(*[double(r) for r in refs])
    await run.settle(30, "konkurrenz")
    await asyncio.sleep(1.0)
    for ref, ra, rb in res:
        dev = run.devices[ref.run_id]
        out["doppelt"].append({"run": ref.run_id, "rev_a": ra, "rev_b": rb, "dargestellt": dev.shown_revision, "rueckschritte": dev.regressions})
    # Fahrerereignis mit alter Revision parallel zur Planänderung (andere Umläufe als oben)
    acks = []
    for w in run.worlds:
        for ref in run.runs[w.tenant_id][-2 * per_tenant:-per_tenant]:
            acks.append((ref, rig_of[ref.run_id]))

    async def race(ref, rig):
        old = ref.revision
        # Kontrolle: die Bestätigung der aktuellen Revision wirkt (200), bevor eine Planänderung dazwischenkommt
        pre, _, pre_body = await _ack(run, rig, ref.shift_id, old, f'"{old}"')
        # Bestätigung der gelesenen Revision und Planänderung gleichzeitig
        a_task = asyncio.create_task(_ack(run, rig, ref.shift_id, old, f'"{old}"'))
        c_task = asyncio.create_task(run.acommit(ref, "konkurrenz", 90))
        (st, hd, body), s = await asyncio.gather(a_task, c_task)
        # nach dem Commit ist die alte Revision veraltet: definierte Konfliktantwort
        st2, hd2, body2 = await _ack(run, rig, ref.shift_id, old, f'"{old}"')
        return {"run": ref.run_id, "vorab_status": pre, "alte_revision": old, "neue_revision": s.revision, "parallel_status": st, "parallel_code": (body or {}).get("code"),
                "nach_commit_status": st2, "nach_commit_code": (body2 or {}).get("code"), "etag_nach_commit": hd2.get("etag")}
    out["ack_parallel"] = list(await asyncio.gather(*[race(r, g) for r, g in acks]))
    await run.settle(30, "konkurrenz")
    run.t_phase["konkurrenz"] = (t_start, P.now_ns())
    return out


async def phase_unterbrochen(run: Run, per_tenant: int = 25, down_s: float = 3.0) -> dict:
    refs = []
    for w in run.worlds:
        refs += run.runs[w.tenant_id][:per_tenant]
    t_start = P.now_ns()
    for ref in refs:
        await run.devices[ref.run_id].drop(down_s)
    await asyncio.sleep(0.8)
    await asyncio.gather(*[run.acommit(ref, "unterbrochen", 120) for ref in refs])
    await run.settle(down_s + 30, "unterbrochen")
    run.t_phase["unterbrochen"] = (t_start, P.now_ns())
    return {"geraete": len(refs), "unterbrechung_s": down_s}


async def phase_stall(run: Run, hold_s: float = 13.0, commits: int = 40) -> dict:
    """Verteilweg anhalten: eine offene Transaktion mit vergebener Transaktionsnummer hält `pg_snapshot_xmin`; der Live-Kanal liefert nur Zeilen darunter. Commits währenddessen sind sofort
    committet, aber erst nach dem Ende der Haltetransaktion zustellbar (Ersatz für „Relay gestoppt und wieder gestartet“; der Relay ist nicht verdrahtet)."""
    import db as dbtool
    holder = dbtool.admin(run.db_name); holder.autocommit = False
    holder.execute("SELECT pg_current_xact_id()")
    t_hold = time.monotonic()
    refs = run.all_refs()[-commits:] if False else [r for rs in run.runs.values() for r in rs[100:100 + commits // 2]]
    t_start = P.now_ns()
    tasks = []
    for i, ref in enumerate(refs):
        tasks.append(asyncio.create_task(run.acommit(ref, "stall", 150)))
        await asyncio.sleep(max(0.0, hold_s * 0.7 / len(refs)))
    done = await asyncio.gather(*tasks)
    await asyncio.sleep(max(0.0, hold_s - (time.monotonic() - t_hold)))
    t_release = P.now_ns()
    holder.rollback(); holder.close()
    await run.settle(40, "stall")
    for s in done:
        s.stalled = True
        if s.t_shown is not None and s.t_shown > t_release and s.latency_s is not None and s.latency_s > P.ZIEL_S - 0.0:
            s.via_catchup = True
    run.t_phase["stall"] = (t_start, P.now_ns())
    return {"haltedauer_s": round(hold_s, 1), "commits": len(done)}


def _negativ(run: Run) -> dict:
    """Fremder Fahrtenplan: ein Fahrer des Mandanten A ruft die Schicht des Mandanten B (und eines anderen Fahrers von A) ab: 404 wie bei unbekannter Schicht."""
    a, b = run.worlds[0], run.worlds[1]
    ra, rb = run.rigs[a.label], run.rigs[b.label]
    out = {"fremder_mandant": [], "anderer_fahrer": []}
    for i in range(5):
        tok = run.idp.token(ra[i].subject, sid=ra[i].sid)
        s, _, _ = W.call(run.app.base, "GET", f"/v1/driver/shifts/{rb[i].shift_id}/run", tok); out["fremder_mandant"].append(s)
        s, _, _ = W.call(run.app.base, "GET", f"/v1/driver/shifts/{ra[i + 10].shift_id}/run", tok); out["anderer_fahrer"].append(s)
    return out


def _chain_db(admin, tenants: list[str]) -> dict:
    """Serverseitige Kettenglieder aus der Datenbank: Commit der Revision (Outbox `occurred_at`) -> Projektion `live_event` (`created_at`); Outbox-Alter und Veröffentlichungsstand (Relay nicht verdrahtet)."""
    row = admin.execute("""SELECT count(*), coalesce(percentile_disc(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM l.created_at - o.occurred_at)), 0),
                                  coalesce(max(extract(epoch FROM l.created_at - o.occurred_at)), 0)
                             FROM taktovia.outbox o JOIN taktovia.live_event l ON l.tenant_id = o.tenant_id AND l.event_id = o.event_id
                            WHERE o.tenant_id = ANY(%s::uuid[]) AND o.event_type = 'dispatch.run_revised'""", (tenants,)).fetchone()
    unpub = admin.execute("SELECT count(*), coalesce(max(extract(epoch FROM now() - occurred_at)), 0) FROM taktovia.outbox WHERE tenant_id = ANY(%s::uuid[]) AND published_at IS NULL", (tenants,)).fetchone()
    return {"ereignisse_run_revised": row[0], "outbox_bis_live_event_p95_s": round(float(row[1]), 4), "outbox_bis_live_event_max_s": round(float(row[2]), 4),
            "unveroeffentlicht": unpub[0], "aeltester_unveroeffentlichter_eintrag_s": round(float(unpub[1]), 1),
            "relay": "nicht verdrahtet: `published_at` bleibt leer, Relay-Verzug nicht messbar (die Zustellung an Fahrer läuft über `live_event`, geschrieben vom Trigger in der Commit-Transaktion)"}


def execute(out_dir: Path) -> dict:
    import db as dbtool
    db_name = os.environ["TAKTOVIA_DB_NAME"]
    out_dir.mkdir(parents=True, exist_ok=True)
    idp = TestIdp()
    app = App(idp, db_name=db_name, rate_limit=100_000_000, log=out_dir / "anwendung.log",
              extra=["--server.tomcat.max-connections=40000", "--server.tomcat.accept-count=2000", "--server.tomcat.threads.max=1500", "--taktovia.live.max-connections=900"])
    admin_factory = lambda: dbtool.admin(db_name)  # noqa: E731
    admin = admin_factory()
    started = datetime.now(timezone.utc)
    sc = _scale()
    try:
        app.start(300)
        t_setup = time.monotonic()
        worlds = [W.build_tenant(app, idp, admin, chr(ord("A") + i), 2, 1, name=f"Mandant {chr(ord('A') + i)} (Planrevision)") for i in range(TENANTS)]
        rigs = {w.label: W.provision_drivers(app, idp, admin, w, VEHICLES) for w in worlds}
        runs: dict[str, list[P.RunRef]] = {}
        for w in worlds:
            admin.execute("SELECT set_config('app.tenant_id', %s, false)", (w.tenant_id,))
            stops = [r[0] for r in admin.execute("SELECT id::text FROM taktovia.stop_revision WHERE tenant_id = %s ORDER BY name", (w.tenant_id,)).fetchall()]
            runs[w.tenant_id] = P.prepare_runs(admin, w.tenant_id, rigs[w.label], stops)
        setup_s = time.monotonic() - t_setup
        offset0 = P.clock_offset(admin)
        run = Run(app, idp, admin_factory, worlds, rigs, runs, db_name)
        sampler = Sampler(app.proc.pid, admin_factory, db_name, [w.tenant_id for w in worlds]); sampler.start()
        result = asyncio.run(_main(run, sc))
        samples = sampler.stop()
        result["negativ"] = _negativ(run)
        result["chain_db"] = _chain_db(admin, [w.tenant_id for w in worlds])
        offset1 = P.clock_offset(admin)
        rows = sorted(run.samples.items(), key=lambda kv: (kv[1].t_commit_start, kv[0]))
        _write_csv(out_dir / "rohdaten.csv", rows)
        sat = [s for s in samples if "error" not in s]
        result["saettigung"] = {"proben": len(sat), "app_cpu_pct_von_einem_kern_max": max((s["app_cpu_pct_of_one_core"] for s in sat), default=None), "maschine_cpu_pct_max": max((s["machine_cpu_pct"] for s in sat), default=None),
                                "app_rss_mb_max": max((s["app_rss_mb"] for s in sat), default=None), "db_verbindungen_max": max((s["db_runtime_connections"] for s in sat), default=None),
                                "sperrwartende_max": max((s["db_lock_waiting"] for s in sat), default=None), "outbox_wartend_max": max((s["outbox_pending"] for s in sat), default=None)}
        result["uhrenabgleich"] = {"anfang": offset0, "ende": offset1}
        result["aufbau_s"] = round(setup_s, 1)
        result["gestartet"] = started.isoformat()
        result["status_zielwert"] = P.STATUS_ZIELWERT
        result["parameter"] = {"mandanten": TENANTS, "fahrzeuge_je_mandant": VEHICLES, "geraete_gesamt": TENANTS * VEHICLES, "hintergrundlast": "Positionsmeldung je Fahrer alle 15 s (POST /v1/driver/positions)", "skala": sc}
        return result
    finally:
        try:
            app.stop()
        finally:
            idp.close(); admin.close()


async def _main(run: Run, sc: float) -> dict:
    loop_tasks, stop_pos = [], asyncio.Event()
    for w in run.worlds:
        for ref, rig in zip(run.runs[w.tenant_id], run.rigs[w.label]):
            dev = P.DeviceSim("127.0.0.1", run.app.port, (lambda rg=rig: run.idp.token(rg.subject, sid=rg.sid)), w.tenant_id, ref, run.samples, on_displayed=lambda r, rev, t: run.displayed.append((r, rev, t)))
            run.devices[ref.run_id] = dev
            loop_tasks.append(asyncio.create_task(dev.run()))
    # Anfangszustand: jedes Gerät lädt seinen Plan einmal (Revision 1) und hält den Strom offen
    await asyncio.wait_for(asyncio.gather(*[d.ready.wait() for d in run.devices.values()]), 120)
    first = await asyncio.gather(*[d._get_run() for d in list(run.devices.values())[:20]])
    erstabruf = sorted({(s, (b or {}).get("release_state"), (b or {}).get("plan_revision"), len((b or {}).get("stops") or [])) for s, b in first}, key=str)
    pos = [asyncio.create_task(_positions(run, rig, w, i, stop_pos)) for w in run.worlds for i, rig in enumerate(run.rigs[w.label])]
    verbunden = {w.label: sum(1 for ref in run.runs[w.tenant_id] if run.devices[ref.run_id].connected) for w in run.worlds}
    await asyncio.sleep(20 * sc if sc >= 0.5 else 3)                    # Grundlast einschwingen lassen
    out: dict = {"erstabruf_20_geraete": [list(map(str, x)) for x in erstabruf], "verbunden_zu_beginn": verbunden}
    out["serie_info"] = await phase_serie(run, rate=6.0, seconds=max(20.0, 100.0 * sc))
    out["konkurrenz_info"] = await phase_konkurrenz(run)
    out["unterbrochen_info"] = await phase_unterbrochen(run)
    out["stall_info"] = await phase_stall(run)
    await asyncio.sleep(2)
    stop_pos.set(); await asyncio.gather(*pos, return_exceptions=True)
    for d in run.devices.values():
        await d.close()
    for t in loop_tasks:
        t.cancel()
    await asyncio.gather(*loop_tasks, return_exceptions=True)
    by_phase: dict[str, list[P.Sample]] = {}
    for s in run.samples.values():
        by_phase.setdefault(s.phase, []).append(s)
    out["phasen"] = {ph: P.evaluate(ss) for ph, ss in by_phase.items()}
    out["stichprobe"] = {ph: len(ss) for ph, ss in by_phase.items()}
    label = {w.tenant_id: w.label for w in run.worlds}
    out["stichprobe_je_mandant"] = {}
    for s in run.samples.values():
        out["stichprobe_je_mandant"][label[s.tenant]] = out["stichprobe_je_mandant"].get(label[s.tenant], 0) + 1
    out["stall_latenz"] = P.summarize([s.latency_s for s in by_phase.get("stall", []) if s.latency_s is not None])
    out["unterbrochen_latenz"] = P.summarize([s.latency_s for s in by_phase.get("unterbrochen", []) if s.latency_s is not None])
    out["fremdzustellung"] = {"fremde_ereignisse_gesamt": sum(d.foreign_events for d in run.devices.values()), "rueckschritte_gesamt": sum(d.regressions for d in run.devices.values()),
                              "ereignisse_gesamt": sum(d.events for d in run.devices.values()), "geraete": len(run.devices), "geraete_fehler": sorted({e for d in run.devices.values() for e in d.errors})[:10],
                              "wiederverbindungen_gesamt": sum(d.reconnects for d in run.devices.values()), "http_wiederholungen_veraltete_verbindung": sum(d.retries for d in run.devices.values())}
    pr = [r for r in run.rec.recs if r.endpoint == "POST /v1/driver/positions"]
    out["hintergrundlast"] = aload.summarize(pr)
    return out


def _write_csv(path: Path, rows) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["run_id", "tenant", "revision", "phase", "klasse", "commit_start_ns", "commit_ende_ns", "empfang_ns", "dargestellt_ns", "latenz_s", "verbunden_beim_commit", "nachgeladen", "angehalten"])
        for (run, rev), s in rows:
            w.writerow([run, s.tenant, rev, s.phase, P.classify(s), s.t_commit_start, s.t_commit_end, s.t_recv, s.t_shown, "" if s.latency_s is None else round(s.latency_s, 4), int(s.connected_at_commit), int(s.via_catchup), int(s.stalled)])


def render_md(p: dict) -> str:
    """Messprotokoll (Kap. 08 §8.6): Zeitstempel der Kette, p50/p95/p99, Stichprobe, Verteilung, Ausreißer, Uhrenabgleich, Grenzen."""
    L = ["# Fahrer-Planrevision: Commit bis Darstellung (WP-M5-13, LH-6.19-NFR-004)", "",
         f"- Zielwert: p95 <= {P.ZIEL_S:g} s, Status **{p['status_zielwert']}**; Urteil dieses Laufs (Messumgebung, keine Abnahme): **{p.get('urteil', '?')}**",
         f"- Last: {p['parameter']['mandanten']} Mandanten x {p['parameter']['fahrzeuge_je_mandant']} verbundene Geräte (Live-Kanal offen), Grundlast: {p['parameter']['hintergrundlast']}",
         f"- gestartet {p['gestartet']}, Aufbau {p['aufbau_s']} s; Uhrenabgleich: {json.dumps(p['uhrenabgleich'], ensure_ascii=False)}", ""]
    for ph, label in (("serie", "A Serie unter Referenzlast (Zielmenge)"), ("konkurrenz", "B Konkurrenz (zwei Revisionen kurz nacheinander, Bestätigung parallel)"), ("unterbrochen", "C Verbindung kurz unterbrochen (getrennt ausgewiesen)"),
                      ("stall", "D Verteilweg angehalten und freigegeben (Ausreißer)")):
        e = p["phasen"].get(ph)
        if not e:
            continue
        L += [f"## {label}", "", f"Stichprobe {p['stichprobe'].get(ph)}; Zielmenge {e['zielmenge_n']}; Latenz {json.dumps(e['latenz'], ensure_ascii=False)}", f"- Verteilung: {json.dumps(e['verteilung'])}",
              f"- über Ziel: {e['ueber_ziel']}, nachgeladen (Ausreißer): {e['ausreisser_nachgeladen']}, nicht dargestellt: {e['nicht_dargestellt']}, Anteil innerhalb Ziel: {e['anteil_innerhalb_ziel']}, p95 inkl. Ausreißer: {e['p95_zielmenge_inkl_ausreisser_s']}",
              f"- unterbrochen (getrennt): {json.dumps(e['unterbrochen_getrennt'], ensure_ascii=False)}", f"- Teilstrecken: {json.dumps(e['teilstrecken'], ensure_ascii=False)}", ""]
    L += ["## Serverseitige Kette (Datenbank)", "", "```", json.dumps(p["chain_db"], indent=1, ensure_ascii=False), "```", "", "## Sättigung", "", "```", json.dumps(p["saettigung"], ensure_ascii=False), "```", "",
          "## Zustellung und Datentrennung", "", "```", json.dumps({"fremdzustellung": p["fremdzustellung"], "negativ": p["negativ"], "stichprobe_je_mandant": p["stichprobe_je_mandant"]}, ensure_ascii=False), "```", "",
          "## Grenzen", ""]
    L += [f"- {x}" for x in LIMITS]
    return "\n".join(L) + "\n"


LIMITS = [
    "Gerätesimulation statt Flutter-App und Zielgerät (OP-27): `on_displayed` ersetzt den Testmodus-Meldepunkt von UI-10 (`display_probe.dart`, nicht ausgeführt). Die Aussage gilt für das simulierte Gerät.",
    "Planschreiber: Nachbildung von `JdbcRunPlans.appendIn` unter der Runtime-Rolle (Zeilenlock, Revision +1, Stopps, `plan_change`, Outbox `dispatch.run_revised`); Plan-Validator und Buchungsdiff entfallen, die Pläne enthalten keine Buchungen.",
    "Der Relay (Outbox -> Broker) ist in der Anwendung nicht verdrahtet (`published_at` bleibt leer); der Live-Kanal liest `live_event`, das der Trigger `outbox_live` in der Commit-Transaktion schreibt. Relay-Verzug ist daher nicht messbar, Outbox-Alter = Alter unveröffentlichter Zeilen.",
    "Phase D hält den Verteilweg real an (offene Transaktion hält `pg_snapshot_xmin`, der Live-Kanal liefert nur Zeilen darunter); das ist ein Ersatz für „Relay gestoppt und wieder gestartet“, kein Test eines Relays.",
    "Push-Weckruf (geschlossene App, OP-29) und Offline-Sync sind nicht Teil der Messung (Nicht im Umfang); „bestehende Verbindung“ = Live-Kanal-Strom offen (SSE), „bestätigte Planrevision“ = committete Revision des Umlaufs (OP-06 offen).",
    "Zeit: Server, Datenbank und Gerätesimulation laufen auf derselben Maschine, alle Zeitstempel stammen aus der Wanduhr des Messprozesses (Uhrenabgleich gegen die Datenbankuhr protokolliert). Der Start der Messung liegt vor dem ersten Befehl des Commits (konservativ).",
    "Geteilte Maschine (Generator, Anwendung, PostgreSQL, Issuer); Zielhardware und Spitzenlast offen (D-05). Grundlast nur Fahrerpositionen (15 s je Gerät); keine gleichzeitige Buchungs-, Lese- und Leitstellenlast.",
    "Authentifizierung über lokalen Ersatz-Issuer (JWKS, RS256) statt Keycloak; Umgebung `local` ohne TLS.",
]
