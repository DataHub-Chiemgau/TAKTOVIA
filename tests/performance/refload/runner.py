"""Lastszenarien und Lauf (WP-M2-48, WP-M5-10, WP-M5-12) gegen die echte Anwendung über HTTP.

Sitzungsarten je Mandant: Fahrgast-Buchungssitzungen (offene Ankunftsrate: Suche `/v1/ride-offers`, Hold, Confirm, Buchungsstatus, Storno), Fahrgast-Lesesitzungen (Think-Time: Buchungsliste, `/v1/me`),
Leitstellensitzungen (Listen), Fahrersitzungen je Fahrzeug (Positionsmeldung, Planabruf). Skalierungsstufen als Faktoren auf Sitzungszahl und Buchungsrate. Hintergrundlast (Outbox, Benachrichtigungen,
Kennzahlprojektion) erzeugt die Anwendung selbst aus dem Fachverkehr; gemessen wird ihre Warteschlangentiefe (Sampler)."""
from __future__ import annotations

import asyncio
import hashlib
import json
import random
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import aload, invariants
from .aload import Client, Rec, Recorder
from .sampler import Sampler
from . import world as W


@dataclass
class Stage:
    name: str
    factor: float
    duration_s: float
    burst_actors: int = 0


@dataclass
class Profile:
    name: str
    tenants: int = 2
    vehicles_per_tenant: int = 250
    passengers_per_tenant: int = 400
    bookings_per_day: int = 30000
    peak_factor: float = 3.0
    sessions: int = 5000
    sessions_scope: str = "pro_mandant"           # Annahme OP-01: 5.000 Sitzungen je Mandant (Kap. 01 §1.8)
    session_think_s: float = 30.0
    offers_per_booking: float = 2.0               # Suchen je Buchung (Annahme)
    cancel_share: float = 0.1
    wheelchair_share: float = 0.05
    operators_per_tenant: int = 10
    operator_think_s: float = 5.0
    driver_interval_s: float = 15.0               # Positionsmeldung je Fahrzeug
    stages: list[Stage] = field(default_factory=lambda: [Stage("100 %", 1.0, 30)])
    warmup_s: float = 5.0
    status: str = "vorläufig, D-05 offen"
    seed: int = 20261201
    rate_limit_per_min: int = 600                 # Quote je Akteur (Standard der Plattform)
    max_inflight_bookings: int = 400


def profile_from_json(path: Path) -> Profile:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    stages = [Stage(**s) for s in d.pop("stages")]
    return Profile(stages=stages, **{k: v for k, v in d.items() if k in Profile.__dataclass_fields__ and not k.startswith("_")})


class State:
    def __init__(self, profile: Profile) -> None:
        self.p = profile
        self.factor = 0.0
        self.stop = False
        self.burst = 0
        self.t0 = time.monotonic()
        self.inflight = 0
        self.counters: dict[str, int] = {}

    def count(self, k: str, n: int = 1) -> None:
        self.counters[k] = self.counters.get(k, 0) + n


_tls = threading.local()


def _bind(admin_factory, tenant, offer_id, run, seg):
    c = getattr(_tls, "admin", None)
    if c is None:
        c = _tls.admin = admin_factory()
    W.bind_offer(c, tenant, offer_id, run, seg)


# --- Sitzungen ---------------------------------------------------------------------------------------------------------------------------------

async def booking_flow(st: State, rec: Recorder, app, idp, admin_factory, w: W.TenantWorld, rnd: random.Random, convert: bool) -> None:
    """Eine Buchungssitzung (eigene Verbindung): Suche; bei Umwandlung Planbindung des Angebots (Testaufbau, nicht gemessen), Hold, Confirm, Status, ggf. Storno."""
    sub = rnd.choice(w.passengers)
    c = Client(rec, "127.0.0.1", app.port, w.label, lambda: idp.token(sub))
    try:
        wheel = 1 if rnd.random() < st.p.wheelchair_share else 0
        s, _, off = await c.call("POST /v1/ride-offers", "POST", "/v1/ride-offers", W.offer_body(w.service_id, w.days, 1, wheel))
        if s != 200 or not off or not off.get("offers"):
            st.count("offer_rejected" if s == 200 else f"offer_http_{s}")
            return
        st.count("offers")
        if not convert:
            return
        offer = off["offers"][0]
        run, seg = w.next_run_segment()
        await asyncio.to_thread(_bind, admin_factory, w.tenant_id, offer["id"], run, seg)
        s, hd, hold = await c.call("POST /v1/ride-offers/{id}/hold", "POST", f"/v1/ride-offers/{offer['id']}/hold", {"max_gross": offer["gross"]}, if_match=str(offer["revision"]))
        if s != 201:
            st.count(f"hold_http_{s}")
            return
        st.count("holds")
        s, hd, bk = await c.call("POST /v1/bookings", "POST", "/v1/bookings", {"hold_id": hold["id"], "max_gross": offer["gross"]}, if_match=hd.get("etag", "1"))
        if s != 201:
            st.count(f"confirm_http_{s}")
            return
        st.count("bookings")
        s, hd, _ = await c.call("GET /v1/bookings/{id}", "GET", f"/v1/bookings/{bk['id']}")
        if rnd.random() < st.p.cancel_share and s == 200:
            s, _, _ = await c.call("POST /v1/bookings/{id}/cancel", "POST", f"/v1/bookings/{bk['id']}/cancel", {"reason": "PASSENGER_REQUEST"}, if_match=hd.get("etag", "1"))
            st.count("cancels" if s == 200 else f"cancel_http_{s}")
    finally:
        await c.close()


async def booking_arrivals(st: State, rec, app, idp, admin_factory, w: W.TenantWorld, seed: int) -> None:
    rnd = random.Random(f"{seed}|{w.label}|arr")
    tasks: set[asyncio.Task] = set()
    per_day = st.p.bookings_per_day
    while not st.stop:
        rate = per_day / 86400.0 * st.p.peak_factor * st.factor * max(1.0, st.p.offers_per_booking)    # Suchen je Sekunde und Mandant
        if rate <= 0:
            await asyncio.sleep(0.5); continue
        await asyncio.sleep(rnd.expovariate(rate))
        if st.inflight >= st.p.max_inflight_bookings:
            st.count("arrivals_dropped_client_limit"); continue
        convert = rnd.random() < 1.0 / max(1.0, st.p.offers_per_booking)
        st.inflight += 1

        async def one(convert=convert):
            try:
                await booking_flow(st, rec, app, idp, admin_factory, w, random.Random(rnd.random()), convert)
            finally:
                st.inflight -= 1
        t = asyncio.create_task(one()); tasks.add(t); t.add_done_callback(tasks.discard)
    if tasks:
        await asyncio.wait(tasks, timeout=60)


async def read_session(st: State, rec, app, idp, w: W.TenantWorld, i: int, n_per_tenant: int, seed: int) -> None:
    rnd = random.Random(f"{seed}|{w.label}|read|{i}")
    sub = w.passengers[i % len(w.passengers)]
    c = Client(rec, "127.0.0.1", app.port, w.label, lambda: idp.token(sub))
    await asyncio.sleep(rnd.random() * st.p.session_think_s)
    active = seen = False
    try:
        while not st.stop:
            if i >= n_per_tenant * st.factor:
                await asyncio.sleep(1.0); continue
            if not active:
                active = True; st.count(f"sitzungen_aktiv_{w.label}")
            if rnd.random() < 0.7:
                await c.call("GET /v1/bookings", "GET", "/v1/bookings?limit=20")
            else:
                await c.call("GET /v1/me", "GET", "/v1/me")
            if not seen:
                seen = True; st.count(f"sitzungen_mit_anfrage_{w.label}")
            await asyncio.sleep(rnd.expovariate(1.0 / st.p.session_think_s))
    finally:
        await c.close()


OPERATOR_PATHS = [("GET /v1/services", "/v1/services"), ("GET /v1/service-areas", "/v1/service-areas"), ("GET /v1/stops", "/v1/stops"), ("GET /v1/operator/vehicles", "/v1/operator/vehicles"),
                  ("GET /v1/config/effective", "/v1/config/effective"), ("GET /v1/operator/alerts", "/v1/operator/alerts")]


async def operator_session(st: State, rec, app, idp, w: W.TenantWorld, i: int, seed: int) -> None:
    rnd = random.Random(f"{seed}|{w.label}|op|{i}")
    c = Client(rec, "127.0.0.1", app.port, w.label, lambda: idp.token(w.planner_sub))
    await asyncio.sleep(rnd.random() * st.p.operator_think_s)
    try:
        while not st.stop:
            if i >= st.p.operators_per_tenant * st.factor:
                await asyncio.sleep(1.0); continue
            name, path = rnd.choice(OPERATOR_PATHS)
            await c.call(name, "GET", path)
            await asyncio.sleep(rnd.expovariate(1.0 / st.p.operator_think_s))
    finally:
        await c.close()


async def driver_session(st: State, rec, app, idp, w: W.TenantWorld, rig: W.DriverRig, i: int, seed: int) -> None:
    from datetime import datetime, timezone
    rnd = random.Random(f"{seed}|{w.label}|drv|{i}")
    c = Client(rec, "127.0.0.1", app.port, w.label, lambda: idp.token(rig.subject, sid=rig.sid))
    await asyncio.sleep(rnd.random() * st.p.driver_interval_s)
    n = 0
    st.count(f"fahrersitzungen_{w.label}")
    try:
        while not st.stop:
            body = {"shift_id": rig.shift_id, "vehicle_id": rig.vehicle_id, "device_id": rig.device_id,
                    "samples": [{"position": {"latitude": round(51.04 + rnd.random() * 0.05, 6), "longitude": round(13.72 + rnd.random() * 0.06, 6)}, "accuracy_meters": 5.0, "measured_at": datetime.now(timezone.utc).isoformat()}]}
            await c.call("POST /v1/driver/positions", "POST", "/v1/driver/positions", body)
            n += 1
            if n % 4 == 0:
                await c.call("GET /v1/driver/shifts/{id}/run", "GET", f"/v1/driver/shifts/{rig.shift_id}/run")
            await asyncio.sleep(st.p.driver_interval_s)
    finally:
        await c.close()


async def burst_actor(st: State, rec, app, idp, w: W.TenantWorld, i: int) -> None:
    """Überlastakteur: ein einzelnes Konto ohne Pause (über der Quote je Akteur); zeigt die kontrollierte Abweisung (429 mit Retry-After)."""
    sub = w.passengers[i % len(w.passengers)]
    c = Client(rec, "127.0.0.1", app.port, w.label, lambda: idp.token(sub))
    try:
        while not st.stop:
            if i >= st.burst:
                await asyncio.sleep(0.5); continue
            s, hd, _ = await c.call("GET /v1/me (Überlastakteur)", "GET", "/v1/me")
            if s == 429:
                await asyncio.sleep(min(2.0, float(hd.get("retry-after", "1") or 1)))
    finally:
        await c.close()


# --- Konkurrenz- und Trennungsproben --------------------------------------------------------------------------------------------------------

async def last_seat_race(st: State, rec, app, idp, admin_factory, w: W.TenantWorld, n: int = 100) -> dict:
    """PH-AT-004 unter Last: n Fahrgäste bewerben sich um den letzten Sitz eines Umlaufs (Kapazität 1): n parallele Holds, dann n parallele Bestätigungen des gehaltenen Angebots."""
    clients, offers = [], []
    for i in range(n):
        sub = w.passengers[i % len(w.passengers)]
        c = Client(rec, "127.0.0.1", app.port, w.label, lambda sub=sub: idp.token(sub))
        s, _, off = await c.call("POST /v1/ride-offers", "POST", "/v1/ride-offers", W.offer_body(w.service_id, w.days, 1, 0), tag="last_seat")
        if s == 200 and off and off.get("offers"):
            clients.append(c); offers.append(off["offers"][0])
    for o in offers:
        await asyncio.to_thread(_bind, admin_factory, w.tenant_id, o["id"], w.last_seat_run, 0)
    holds = await asyncio.gather(*[c.call("POST /v1/ride-offers/{id}/hold", "POST", f"/v1/ride-offers/{o['id']}/hold", {"max_gross": o["gross"]}, if_match=str(o["revision"]), tag="last_seat")
                                   for c, o in zip(clients, offers)])
    held = [(c, o, h) for (c, o), h in zip(zip(clients, offers), holds) if h[0] == 201]
    out = {"angebote": len(offers), "holds_201": len(held), "holds_409": sum(1 for h in holds if h[0] == 409), "holds_andere": sum(1 for h in holds if h[0] not in (201, 409)), "bestaetigungen_201": 0, "bestaetigungen_409": 0}
    if held:
        c, o, h = held[0]
        # n parallele Bestätigungen desselben Holds mit je eigenem Idempotenzschlüssel (Mehrfachklick, mehrere Tabs, Retry-Sturm)
        confirms = await asyncio.gather(*[Client(rec, "127.0.0.1", app.port, w.label, c.token_fn).call("POST /v1/bookings", "POST", "/v1/bookings", {"hold_id": h[2]["id"], "max_gross": o["gross"]},
                                                                                                    if_match=h[1].get("etag", "1"), tag="last_seat") for _ in range(n)])
        out["bestaetigungen_201"] = sum(1 for r in confirms if r[0] == 201)
        out["bestaetigungen_409"] = sum(1 for r in confirms if r[0] == 409)
        out["verschiedene_buchungs_ids"] = len({r[2]["id"] for r in confirms if r[0] == 201 and r[2]})
    for c in clients:
        await c.close()
    return out


async def isolation_probe(app, idp, worlds: list[W.TenantWorld]) -> dict:
    """Zwei Mandanten mit identischen Objektnamen: jeder sieht nur Eigenes; fremde IDs sind 404; Dienstnamen sind gleich, IDs verschieden."""
    res = {"gleiche_namen": True, "fremde_ids_in_listen": 0, "fremde_id_antworten": []}
    names, ids = {}, {}
    for w in worlds:
        s, js, _ = await asyncio.to_thread(lambda w=w: W.call(app.base, "GET", "/v1/services", idp.token(w.admin_sub)))
        if s != 200:
            raise RuntimeError(f"isolation_probe: GET /v1/services -> {s} {js}")
        names[w.label] = sorted(i["name"] for i in js["items"]); ids[w.label] = {i["id"] for i in js["items"]}
    res["gleiche_namen"] = len({json.dumps(v) for v in names.values()}) == 1 and bool(names)
    offers = {}
    for w in worlds:
        s, js, _ = await asyncio.to_thread(lambda w=w: W.call(app.base, "POST", "/v1/ride-offers", idp.token(w.passengers[0]), W.offer_body(w.service_id, w.days)))
        offers[w.label] = js["offers"][0]["id"] if s == 200 and js and js.get("offers") else None
    for a in worlds:
        for b in worlds:
            if a is b:
                continue
            # Fahrgast bzw. Administrator von a greift auf Objekte von b zu: Angebot, Betriebszeiten und Stammdaten-Revision des Dienstes
            st1, _, _ = await asyncio.to_thread(lambda: W.call(app.base, "GET", f"/v1/ride-offers/{offers[b.label]}", idp.token(a.passengers[0])))
            st2, _, _ = await asyncio.to_thread(lambda: W.call(app.base, "GET", f"/v1/services/{b.service_id}/operating-hours", idp.token(a.admin_sub)))
            res["fremde_ids_in_listen"] += len(ids[a.label] & ids[b.label])
            res["fremde_id_antworten"].append({"von": a.label, "auf": b.label, "ride_offer": st1, "betriebszeiten": st2})
    res["alle_fremden_ids_404"] = all(x["ride_offer"] == 404 and x["betriebszeiten"] == 404 for x in res["fremde_id_antworten"])
    return res


# --- Lauf --------------------------------------------------------------------------------------------------------------------------------------

async def run_load(app, idp, admin_factory, worlds: list[W.TenantWorld], rigs: dict[str, list[W.DriverRig]], p: Profile, rec: Recorder, on_stage=None, race=None) -> dict:
    st = State(p)
    rec.t0 = st.t0 = time.monotonic()
    rec.warmup_s = p.warmup_s
    n_sess = p.sessions if p.sessions_scope == "pro_mandant" else p.sessions // max(1, len(worlds))
    tasks: list[asyncio.Task] = []
    for w in worlds:
        tasks.append(asyncio.create_task(booking_arrivals(st, rec, app, idp, admin_factory, w, p.seed)))
        tasks += [asyncio.create_task(read_session(st, rec, app, idp, w, i, n_sess, p.seed)) for i in range(n_sess)]
        tasks += [asyncio.create_task(operator_session(st, rec, app, idp, w, i, p.seed)) for i in range(p.operators_per_tenant)]
        tasks += [asyncio.create_task(driver_session(st, rec, app, idp, w, r, i, p.seed)) for i, r in enumerate(rigs.get(w.label, []))]
        if any(s.burst_actors for s in p.stages):
            # Überlastakteure: je Konto mehrere gleichzeitige Verbindungen ohne Pause (zusammen über der Quote je Akteur von 600 Anfragen/min)
            tasks += [asyncio.create_task(burst_actor(st, rec, app, idp, w, i)) for i in range(max(s.burst_actors for s in p.stages)) for _ in range(8)]
    stage_bounds, race_result = [], None
    for s in p.stages:
        st.factor, st.burst = s.factor, s.burst_actors
        t_start = time.monotonic() - st.t0
        if on_stage:
            on_stage(s)
        if race and s is race[0]:
            await asyncio.sleep(min(5, s.duration_s / 3))
            race_result = await race[1](st)
        await asyncio.sleep(max(0.0, s.duration_s - (time.monotonic() - st.t0 - t_start)))
        stage_bounds.append({"name": s.name, "factor": s.factor, "burst_actors": s.burst_actors, "t_start": round(t_start, 2), "t_end": round(time.monotonic() - st.t0, 2)})
    st.stop = True
    done, pending = await asyncio.wait(tasks, timeout=90)
    for t in pending:
        t.cancel()
    return {"stages": stage_bounds, "counters": st.counters, "race": race_result, "pending_at_stop": len(pending)}


def stage_of(bounds: list[dict], t: float) -> str:
    for b in bounds:
        if b["t_start"] <= t < b["t_end"]:
            return b["name"]
    return bounds[-1]["name"]


def write_raw(recs: list[Rec], bounds: list[dict], out_dir: Path) -> tuple[Path, str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    p = out_dir / "rohdaten.csv"
    with p.open("w", encoding="utf-8") as f:
        f.write("t_s;stufe;mandant;endpunkt;status;ms;retry_after;tag\n")
        for r in recs:
            f.write(f"{r.t:.3f};{stage_of(bounds, r.t)};{r.tenant};{r.endpoint};{r.status};{r.ms:.2f};{r.retry_after or ''};{r.tag}\n")
    return p, hashlib.sha256(p.read_bytes()).hexdigest()
