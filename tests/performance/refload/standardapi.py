"""Messung der nicht-optimierenden Standard-API je Endpunktklasse (WP-M5-11, LH-6.19-NFR-002).

Gemessen wird gegen die **echte Anwendung** (Spring-Boot-Jar, produktive Verdrahtung, Runtime-Rolle ohne Superuser/BYPASSRLS, RLS aktiv) mit **zwei synthetischen Mandanten** und dem Datenbestand
des Generators aus WP-M5-02 (`datagen`, Skala über `TAKTOVIA_PERF_STD_SCALE=small|reference`). Ein geschlossener Lastmix aus Sitzungen beider Mandanten ruft die Klassen im festgelegten Verhältnis ab
(Hintergrundlast = Messlast); jede Anfrage erzeugt genau einen Messsatz, Perzentile nach Nearest-Rank. Zusätzlich laufen Buchungsflüsse (Angebot, Hold, Confirm, Storno) in zwei weiteren Mandanten mit
Testflotte, damit `/v1/bookings` Confirm und Storno gemessen werden.

**Messpunkt:** Clientzeit des Lastgenerators auf derselben Maschine (Senden bis vollständig gelesener Antwortkörper); sie enthält die serverseitige Zeit und die CPU-Konkurrenz zwischen Generator,
Anwendung und Datenbank, ist also eine **obere Schranke** der serverseitigen Zeit (Eingang Gateway bis Antwortende; ein Gateway gibt es in dieser Umgebung nicht, OP-04). Antworten `202` zählen bis zur Annahme.
Klassen ohne Datensatz im Messaufbau (`expenses/{id}`, `operations/{id}`, `inspections/{id}`) werden über den **404-Pfad** gemessen (die RLS-Abfrage läuft, der Datensatz fehlt) und so ausgewiesen."""
from __future__ import annotations

import asyncio
import json
import os
import random
import statistics
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from . import aload
from . import world as W

CLASS_LABELS = {
    "me": "GET /v1/me", "bookings_list": "GET /v1/bookings (limit 100)", "bookings_read": "GET /v1/bookings/{id}", "operator_list": "GET /v1/operator/bookings (limit 100)",
    "operator_deep": "GET /v1/operator/bookings (limit 100, Folgeseiten)",
    "bookings_confirm": "POST /v1/bookings (Confirm)", "bookings_cancel": "POST /v1/bookings/{id}/cancel", "services": "GET /v1/services", "service_areas": "GET /v1/service-areas",
    "stops": "GET /v1/stops", "vehicles": "GET /v1/operator/vehicles", "driver_events": "POST /v1/driver/events", "driver_run": "GET /v1/driver/shifts/{id}/run",
    "inspections": "GET /v1/inspections/{id} (404-Pfad)", "defects": "GET /v1/defects", "rosters": "GET /v1/rosters", "time_events": "GET /v1/time-events",
    "expenses": "GET /v1/expenses/{id} (404-Pfad)", "config": "GET /v1/config/effective", "operations": "GET /v1/operations/{id} (404-Pfad)", "metrics": "GET /v1/analytics/metrics",
}
# Gewichte des Lesemixes (Annahme; Kap. 08 §8.6 legt keinen Endpointmix fest, OP-04): Fahrgast-/Leitstellenlesen überwiegt
MIX = {"me": 14, "operator_list": 10, "operator_deep": 4, "services": 8, "service_areas": 4, "stops": 8, "vehicles": 8, "driver_events": 6, "driver_run": 6,
       "inspections": 2, "defects": 4, "rosters": 3, "time_events": 4, "expenses": 2, "config": 5, "operations": 2, "metrics": 3}


@dataclass
class TenantCtx:
    label: str
    tenant_id: str
    seed_tokens: dict = field(default_factory=dict)          # Rolle -> Subject
    rigs: list = field(default_factory=list)                 # DriverRig der Fahrersitzungen
    booking_ids: list = field(default_factory=list)
    cursors: list = field(default_factory=list)              # Cursor der Folgeseiten von /v1/bookings


def percentile_row(recs: list[aload.Rec]) -> dict:
    s = aload.summarize(recs)
    return s


async def _get(c: aload.Client, name: str, path: str):
    return await c.call(name, "GET", path)


async def prepare_cursors(app, idp, ctx: TenantCtx, pages: int = 5) -> None:
    """Liest die Auftragsübersicht `/v1/operator/bookings` seitenweise (limit 100) und merkt Buchungs-IDs und Cursor (tiefe Paginierung). Hinweis: die synthetischen Buchungen des Generators tragen keinen
    vollständigen Zusagenschnappschuss; `GET /v1/bookings` (Fahrgastsicht) und das Lesen einer Buchung werden deshalb an Buchungen der Buchungsmandanten gemessen."""
    rec = aload.Recorder()
    c = aload.Client(rec, "127.0.0.1", app.port, ctx.label, lambda: idp.token(ctx.seed_tokens["dispatcher"]))
    try:
        path = "/v1/operator/bookings?limit=100"
        for _ in range(pages):
            s, _, js = await c.call("prep", "GET", path)
            if s != 200 or not js:
                break
            ctx.booking_ids += [b["id"] for b in js.get("items", [])]
            nxt = js.get("next_cursor")
            if not nxt:
                break
            ctx.cursors.append(nxt)
            path = f"/v1/operator/bookings?limit=100&cursor={nxt}"
    finally:
        await c.close()


async def mix_session(app, idp, rec: aload.Recorder, ctx: TenantCtx, idx: int, stop: asyncio.Event, think_s: float, seed: int) -> None:
    rnd = random.Random(f"{seed}|{ctx.label}|{idx}")
    names = list(MIX)
    weights = [MIX[n] for n in names]
    clients: dict[str, aload.Client] = {}

    def client(role: str, sub: str | None = None) -> aload.Client:
        key = f"{role}|{sub}"
        if key not in clients:
            s = sub or ctx.seed_tokens[role]
            clients[key] = aload.Client(rec, "127.0.0.1", app.port, ctx.label, lambda s=s: idp.token(s))
        return clients[key]
    rig = ctx.rigs[idx % len(ctx.rigs)] if ctx.rigs else None
    try:
        await asyncio.sleep(rnd.random() * think_s)
        while not stop.is_set():
            cls = rnd.choices(names, weights)[0]
            label = CLASS_LABELS[cls]
            if cls == "me":
                await client("dispatcher").call(label, "GET", "/v1/me")
            elif cls == "operator_list":
                await client("dispatcher").call(label, "GET", "/v1/operator/bookings?limit=100")
            elif cls == "operator_deep" and ctx.cursors:
                await client("dispatcher").call(label, "GET", f"/v1/operator/bookings?limit=100&cursor={rnd.choice(ctx.cursors)}")
            elif cls == "services":
                await client("dispatcher").call(label, "GET", "/v1/services")
            elif cls == "service_areas":
                await client("dispatcher").call(label, "GET", "/v1/service-areas")
            elif cls == "stops":
                await client("dispatcher").call(label, "GET", "/v1/stops?limit=100")
            elif cls == "vehicles":
                await client("dispatcher").call(label, "GET", "/v1/operator/vehicles?limit=100")
            elif cls == "driver_events" and rig:
                now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
                body = {"event_id": str(uuid.uuid4()), "device_id": rig.device_id, "shift_id": rig.shift_id, "vehicle_id": rig.vehicle_id, "plan_revision": 1, "event_type": "BREAK_START",
                        "occurred_at": now, "device_recorded_at": now, "offline_recorded": False}
                await client("driver", rig.subject).call(label, "POST", "/v1/driver/events", body)
            elif cls == "driver_run" and rig:
                await client("driver", rig.subject).call(label, "GET", f"/v1/driver/shifts/{rig.shift_id}/run")
            elif cls == "inspections":
                await client("dispatcher").call(label, "GET", f"/v1/inspections/{uuid.uuid4()}")
            elif cls == "defects":
                await client("dispatcher").call(label, "GET", "/v1/defects?limit=100")
            elif cls == "rosters":
                await client("planner").call(label, "GET", "/v1/rosters?limit=100")
            elif cls == "time_events":
                await client("dispatcher").call(label, "GET", "/v1/time-events?limit=100")
            elif cls == "expenses":
                await client("finance").call(label, "GET", f"/v1/expenses/{uuid.uuid4()}")
            elif cls == "config":
                await client("dispatcher").call(label, "GET", "/v1/config/effective")
            elif cls == "operations":
                await client("dispatcher").call(label, "GET", f"/v1/operations/{uuid.uuid4()}")
            elif cls == "metrics":
                await client("dispatcher").call(label, "GET", "/v1/analytics/metrics?from=2026-12-01&to=2026-12-02&metric_id=requests")
            await asyncio.sleep(rnd.expovariate(1.0 / think_s) if think_s > 0 else 0)
    finally:
        for c in clients.values():
            await c.close()


async def booking_sessions(app, idp, admin_factory, rec: aload.Recorder, w: W.TenantWorld, stop: asyncio.Event, think_s: float, seed: int) -> None:
    """Angebot, Hold, Confirm, Lesen und Storno im Wechsel (Mandant mit Testflotte); Confirm und Storno zählen als eigene Klassen."""
    rnd = random.Random(f"{seed}|{w.label}|bk")
    c = aload.Client(rec, "127.0.0.1", app.port, "B" + w.label, lambda: idp.token(w.passengers[0]))
    try:
        while not stop.is_set():
            s, _, off = await c.call("POST /v1/ride-offers", "POST", "/v1/ride-offers", W.offer_body(w.service_id, w.days, 1, 0))
            if s == 200 and off and off.get("offers"):
                offer = off["offers"][0]
                run, seg = w.next_run_segment()
                await asyncio.to_thread(_bind, admin_factory, w.tenant_id, offer["id"], run, seg)
                s, hd, hold = await c.call("POST /v1/ride-offers/{id}/hold", "POST", f"/v1/ride-offers/{offer['id']}/hold", {"max_gross": offer["gross"]}, if_match=str(offer["revision"]))
                if s == 201:
                    s, hd, bk = await c.call(CLASS_LABELS["bookings_confirm"], "POST", "/v1/bookings", {"hold_id": hold["id"], "max_gross": offer["gross"]}, if_match=hd.get("etag", "1"))
                    if s == 201:
                        await c.call(CLASS_LABELS["bookings_list"], "GET", "/v1/bookings?limit=100")
                        s, hd, _ = await c.call(CLASS_LABELS["bookings_read"], "GET", f"/v1/bookings/{bk['id']}")
                        if s == 200:
                            await c.call(CLASS_LABELS["bookings_cancel"], "POST", f"/v1/bookings/{bk['id']}/cancel", {"reason": "PASSENGER_REQUEST"}, if_match=hd.get("etag", "1"))
            await asyncio.sleep(rnd.expovariate(1.0 / think_s))
    finally:
        await c.close()


def _bind(admin_factory, tenant, offer_id, run, seg):
    a = admin_factory()
    try:
        W.bind_offer(a, tenant, offer_id, run, seg)
    finally:
        a.close()


async def run_mix(app, idp, admin_factory, ctxs: list[TenantCtx], bworlds: list[W.TenantWorld], *, duration_s: float, warmup_s: float, sessions_per_tenant: int, think_s: float, seed: int) -> dict:
    rec = aload.Recorder()
    rec.warmup_s = warmup_s
    stop = asyncio.Event()
    tasks = [asyncio.create_task(mix_session(app, idp, rec, c, i, stop, think_s, seed)) for c in ctxs for i in range(sessions_per_tenant)]
    tasks += [asyncio.create_task(booking_sessions(app, idp, admin_factory, rec, w, stop, max(think_s, 0.5), seed)) for w in bworlds for _ in range(2)]
    await asyncio.sleep(warmup_s + duration_s)
    stop.set()
    await asyncio.wait(tasks, timeout=60)
    for t in tasks:
        t.cancel()
    return {"rec": rec, "duration_s": duration_s, "warmup_s": warmup_s}


def per_class(rec: aload.Rec | None, recs: list[aload.Rec]) -> dict:
    out = {}
    for label, rs in sorted(aload.group(recs, lambda r: r.endpoint).items()):
        if label == "prep":
            continue
        out[label] = aload.summarize(rs)
        out[label]["status"] = {str(k): sum(1 for r in rs if r.status == k) for k in sorted({r.status for r in rs})}
    return out


async def timing_side_channel(app, idp, a: TenantCtx, b: TenantCtx, n: int = 300) -> dict:
    """Abfrage auf die UUID eines **fremden** Mandanten (existiert, gehört B) gegen eine **eigene nicht vorhandene** UUID: beide 404; die Antwortzeiten dürfen nicht nennenswert abweichen."""
    rec = aload.Recorder()
    c = aload.Client(rec, "127.0.0.1", app.port, a.label, lambda: idp.token(a.seed_tokens["dispatcher"]))
    try:
        for _ in range(20):                                                   # Aufwärmen
            await c.call("w", "GET", f"/v1/bookings/{uuid.uuid4()}")
        for i in range(n):
            await c.call("fremd", "GET", f"/v1/bookings/{b.booking_ids[i % len(b.booking_ids)]}")
            await c.call("eigen_unbekannt", "GET", f"/v1/bookings/{uuid.uuid4()}")
    finally:
        await c.close()
    f = [r for r in rec.recs if r.endpoint == "fremd"]
    e = [r for r in rec.recs if r.endpoint == "eigen_unbekannt"]
    fm, em = statistics.median(r.ms for r in f), statistics.median(r.ms for r in e)
    sf, se = aload.summarize(f), aload.summarize(e)
    return {"n_je_art": n, "status_fremd": sorted({r.status for r in f}), "status_eigen": sorted({r.status for r in e}), "fremd": sf, "eigen_unbekannt": se, "median_diff_ms": round(abs(fm - em), 3),
            "median_fremd_ms": round(fm, 3), "median_eigen_ms": round(em, 3)}


def cache_probe(app, idp, a: TenantCtx, b: TenantCtx, foreign_ids: dict[str, set[str]], rounds: int = 60) -> dict:
    """Identische Pfade, parallele Anfragen beider Mandanten: keine Antwort enthält Kennungen des anderen Mandanten (Cache-Schlüssel tragen den Mandanten)."""
    import threading
    from .world import call
    paths = ["/v1/services", "/v1/service-areas", "/v1/stops?limit=100", "/v1/operator/vehicles?limit=100", "/v1/config/effective", "/v1/me", "/v1/operator/bookings?limit=100"]
    leaks, checked, statuses = [], [0], {}
    lock = threading.Lock()

    def worker(me: TenantCtx, other: TenantCtx):
        tok = idp.token(me.seed_tokens["dispatcher"])
        for _ in range(rounds):
            for p in paths:
                s, body, _ = call(app.base, "GET", p, tok)
                raw = json.dumps(body) if body is not None else ""
                with lock:
                    checked[0] += 1
                    statuses[s] = statuses.get(s, 0) + 1
                    hit = [i for i in foreign_ids[other.label] if i in raw]
                    if hit:
                        leaks.append({"tenant": me.label, "path": p, "fremde_ids": hit[:3]})
    ts = [threading.Thread(target=worker, args=(a, b)), threading.Thread(target=worker, args=(b, a)), threading.Thread(target=worker, args=(a, b)), threading.Thread(target=worker, args=(b, a))]
    [t.start() for t in ts]; [t.join() for t in ts]
    return {"pfade": paths, "anfragen": checked[0], "status": {str(k): v for k, v in statuses.items()}, "fremde_ids_in_antworten": len(leaks), "beispiele": leaks[:3]}
