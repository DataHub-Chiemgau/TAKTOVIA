#!/usr/bin/env python3
"""Reproduzierbares Messskript Spike (a), WP-M0-26: Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS.

Voraussetzung: PostgreSQL mit PostGIS und die Umgebungsvariablen aus db/db.py (Administrator, Migrator, Runtime). Für 100 gleichzeitig
offene Verbindungen muss max_connections > 100 sein; dafür gibt es cluster.py (eigener Cluster, Port 5433).

  source /tmp/taktovia-spike-pg/env.sh            # nach: python cluster.py start
  python tests/performance/spikes/spike_a/run_spike_a.py [--quick] [--only e1,e3] [--out DIR]

Die Datenbank (TAKTOVIA_SPIKE_DB, Standard taktovia_spike_a) wird neu angelegt und am Ende NICHT gelöscht (--drop zum Verwerfen).
Ausgabe: results.json und Rohdaten (CSV, gzip) unter spike-a-data/. Alle Zufallsentscheidungen sind seed-gesteuert (--seed).
"""
import argparse
import csv
import gzip
import json
import os
import random
import sys
import threading
import time
import uuid
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from spike_a import capacity as cap, geo, setup_data as sd  # noqa: E402
from spike_a.harness import Pool, ProcPool, task  # noqa: E402
from perfkit import hardware  # noqa: E402
from perfkit.stats import summarize  # noqa: E402
from taktovia_at import db as atdb, evidence, seed  # noqa: E402
import db as dbtool  # noqa: E402

OUT_DEFAULT = HERE.parent / "spike-a-data"
NSEG = 8
NONCE = format(time.time_ns() & 0xFFFFFF, "x")   # macht Referenzen je Skriptlauf eindeutig (--no-reload)
PRIMARY = "rc_run_row"


class Runs:
    """Vergibt frische Reserveläufe (6.000 je Mandant), damit jede Messiteration auf einem unberührten Lauf startet."""

    def __init__(self):
        self.n = {"A": 0, "B": 0}

    def take(self, label):
        self.n[label] += 1
        if self.n[label] > sd.SPARE_RUNS_PER_TENANT:
            raise RuntimeError("Reserveläufe erschöpft")
        return cap.md5uuid(f"{sd.tid(label)}spare{self.n[label]}")


def base_run(label, g):
    return cap.md5uuid(f"{sd.tid(label)}run{g}")


def tx(conn, tenant):
    cur = conn.cursor()
    cur.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))
    return cur


def prefill(conn, tenant, run, seats=0, wc=0, nseg=NSEG):
    cur = tx(conn, tenant)
    for kind, n, vec in (("s", seats, '{"seats":1,"wheelchair":0}'), ("w", wc, '{"seats":0,"wheelchair":1}')):
        for i in range(n):
            hid = uuid.uuid5(cap.NS, f"prefill|{run}|{kind}{i}")
            cur.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                        "VALUES (%s,%s,%s,1,%s,%s::jsonb,0,%s,clock_timestamp() + interval '1 day','CONSUMED')", (tenant, hid, run, f"pf-{hid}", vec, nseg))
            cur.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}',1)",
                        (tenant, uuid.uuid5(cap.NS, f"booking|{hid}"), hid, cap.md5uuid(f"{tenant}svc0"), f"bk-pf-{hid}"))
    conn.commit()


def verify_run(conn, tenant, run, ref_prefix, nseg=NSEG, cap_seats=4, cap_wc=1):
    cur = tx(conn, tenant)
    occ = cur.execute(cap.OCC_SQL, (0, nseg - 1, tenant, run)).fetchall()
    viol = [(s, a, b) for s, a, b in occ if a > cap_seats or b > cap_wc or a < 0 or b < 0]
    bookings = cur.execute("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id=b.tenant_id AND h.id=b.hold_id "
                           "WHERE h.tenant_id=%s AND h.run_id=%s AND h.federation_ref LIKE %s", (tenant, run, ref_prefix + "%")).fetchone()[0]
    holds = cur.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s AND federation_ref LIKE %s AND state IN ('HELD','CONSUMED')",
                        (tenant, run, ref_prefix + "%")).fetchone()[0]
    conn.rollback()
    return {"violations": viol, "bookings": bookings, "active_holds": holds, "max_seats": max(a for _, a, _ in occ), "max_wc": max(b for _, _, b in occ)}


def lat_summary(vals):
    return summarize(vals) if vals else {"n": 0}


# ------------------------------------------------------------------ E1/E2: Korrektheit unter Konkurrenz (PH-AT-004)
def exp_race(pool, runs, st, n_iter, mode="seat", seed_=1, tag="e1", nreq=100):
    """mode: seat (genau ein freier Sitz), wheelchair (Sitz frei, Rollstuhlplatz belegt; gemischte Anfragen), revision (Routenrevision verändert)."""
    agg = Counter()
    lat, lock, rows = [], [], []
    for it in range(n_iter):
        label = "AB"[it % 2]
        tenant, run = sd.tid(label), runs.take(label)
        prefill(pool.ctl[0], tenant, run, seats=3, wc=1 if mode == "wheelchair" else 0)
        if mode == "revision":
            cur = tx(pool.ctl[0], tenant)
            cur.execute("UPDATE taktovia.vehicle_run SET revision = 2 WHERE tenant_id=%s AND id=%s", (tenant, run))
            pool.ctl[0].commit()
        rnd = random.Random(seed_ * 1_000_003 + it)
        reqs = []
        for k in range(nreq):
            wc_req = mode == "wheelchair" and k % 2 == 0
            reqs.append({"ref": f"{tag}-{str(run)[:8]}-{k}", "wc": wc_req, "legs": [{"run": run, "frm": rnd.randint(0, 4), "to": rnd.randint(5, 8),
                                                                              "seats": 0 if wc_req else 1, "wheelchair": 1 if wc_req else 0, "expect_rev": 1}]})
        outs = pool.run("book", [(tenant, r, st.name) for r in reqs])
        v = verify_run(pool.ctl[0], tenant, run, f"{tag}-{str(run)[:8]}-")
        c = Counter()
        for r, o in zip(reqs, outs):
            if isinstance(o, BaseException):
                c["EXCEPTION:" + type(o).__name__] += 1
                continue
            lat.append(o.t_total_ms)
            lock.append(o.t_lock_ms)
            c[f"{o.kind}:{o.code}" if o.code else o.kind] += 1
            if o.kind == "CONFIRMED" and mode == "wheelchair" and r["wc"]:
                c["WHEELCHAIR_REQUEST_CONFIRMED"] += 1
        confirmed = c["CONFIRMED"]
        expected_confirmed = 0 if mode == "revision" else 1
        if confirmed > 1 and mode != "revision":
            agg["iterations_overbooked"] += 1
        if confirmed < expected_confirmed:
            agg["iterations_liveness_failure"] += 1            # freier Platz vorhanden, aber niemand bekam ihn
        if confirmed != v["bookings"] or (mode != "revision" and confirmed != v["active_holds"]):
            agg["iterations_booking_count_mismatch"] += 1
        if v["violations"]:
            agg["iterations_capacity_violation"] += 1
        allowed = {"CONFIRMED", "REJECTED:NO_CAPACITY_SEATS", "REJECTED:NO_CAPACITY_WHEELCHAIR", "REJECTED:REVISION_CONFLICT"}
        bad = {k: n for k, n in c.items() if k not in allowed and k != "WHEELCHAIR_REQUEST_CONFIRMED"}
        if bad:
            agg["iterations_with_unexpected_outcome"] += 1
            for k, n in bad.items():
                agg["unexpected:" + k] += n
        if c["WHEELCHAIR_REQUEST_CONFIRMED"]:
            agg["iterations_wheelchair_substituted"] += 1
        agg["deadlocks"] += c["ERROR:DEADLOCK"]
        agg["serialization_failures"] += c["ERROR:SERIALIZATION_FAILURE"]
        for k, n in c.items():
            agg["outcome:" + k] += n
        rows.append([it, label, confirmed, v["bookings"], v["active_holds"], len(v["violations"]), v["max_seats"], v["max_wc"], json.dumps(dict(c), sort_keys=True)])
    return {"strategy": st.name, "mode": mode, "iterations": n_iter, "requests_per_iteration": nreq, "requests_total": n_iter * nreq,
            "counts": dict(sorted(agg.items())), "latency_ms": lat_summary(lat), "lock_wait_ms": lat_summary(lock)}, rows


# ------------------------------------------------------------------ E2b/E3: Revision nach Hold, Wiederholung, Grenzzeitpunkt, Deadlock
def exp_revision_after_hold(pool, runs, st, n_iter):
    agg = Counter()
    for it in range(n_iter):
        label = "AB"[it % 2]
        tenant, run = sd.tid(label), runs.take(label)
        ids = []
        for k in range(4):
            h = cap.hold(pool.ctl[0], tenant, {"ref": f"e2b-{str(run)[:8]}-{k}", "legs": [{"run": run, "frm": k * 2, "to": k * 2 + 2, "seats": 1, "wheelchair": 0, "expect_rev": 1}]}, st)
            ids.append(h.ids)
        cur = tx(pool.ctl[0], tenant)
        cur.execute("UPDATE taktovia.vehicle_run SET revision = 2 WHERE tenant_id=%s AND id=%s", (tenant, run))
        pool.ctl[0].commit()
        outs = pool.run("confirm", [(tenant, ids[i % 4], st.name) for i in range(100)])
        for o in outs:
            agg[f"{o.kind}:{o.code}" if not isinstance(o, BaseException) else "EXCEPTION"] += 1
        agg["bookings"] += verify_run(pool.ctl[0], tenant, run, f"e2b-{str(run)[:8]}-")["bookings"]
    return {"strategy": st.name, "iterations": n_iter, "confirm_requests": n_iter * 100, "counts": dict(agg)}


def exp_replay(pool, runs, st, n_iter, n_par=100):
    agg = Counter()
    for it in range(n_iter):
        label = "AB"[it % 2]
        tenant, run = sd.tid(label), runs.take(label)
        h = cap.hold(pool.ctl[0], tenant, {"ref": f"e3r-{str(run)[:8]}", "legs": [{"run": run, "frm": 2, "to": 5, "seats": 1, "wheelchair": 0, "expect_rev": 1}]}, st)
        outs = pool.run("confirm", [(tenant, h.ids, st.name)] * n_par)
        bad = [o for o in outs if isinstance(o, BaseException) or o.kind != "CONFIRMED"]
        ids = {tuple(o.ids) for o in outs if not isinstance(o, BaseException)}
        fresh = sum(1 for o in outs if not isinstance(o, BaseException) and not o.replayed)
        v = verify_run(pool.ctl[0], tenant, run, f"e3r-{str(run)[:8]}")
        agg["iterations"] += 1
        agg["not_confirmed_outcomes"] += len(bad)
        agg["iterations_with_distinct_booking_ids"] += 1 if len(ids) != 1 else 0
        agg["iterations_not_exactly_one_fresh_confirm"] += 1 if fresh != 1 else 0
        agg["iterations_booking_rows_not_one"] += 1 if v["bookings"] != 1 else 0
        agg["deadlocks"] += sum(1 for o in outs if not isinstance(o, BaseException) and o.code == "DEADLOCK")
    return {"strategy": st.name, "parallel_confirms_per_iteration": n_par, "counts": dict(agg)}


def exp_boundary(pool, runs, st, n_iter, k=20, seed_=1):
    """Hold-Ablauf und Confirm gleichzeitig um den Grenzzeitpunkt: nie 'abgelaufen und gebucht'."""
    agg = Counter()
    for it in range(n_iter):
        label = "AB"[it % 2]
        tenant, run = sd.tid(label), runs.take(label)
        rnd = random.Random(seed_ * 7919 + it)
        c0 = pool.ctl[0]
        cur = tx(c0, tenant)
        hids = []
        for i in range(k):
            hid = uuid.uuid5(cap.NS, f"boundary|{run}|{i}")
            cur.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                        "VALUES (%s,%s,%s,1,%s,'{\"seats\":1,\"wheelchair\":0}'::jsonb,%s,%s,clock_timestamp() + %s * interval '1 millisecond','HELD')",
                        (tenant, hid, run, f"bd-{hid}", i * 2, i * 2 + 1, rnd.randint(5, 45)))
            hids.append(hid)
        c0.commit()
        delays = [rnd.uniform(0, 0.05) for _ in range(k)]
        exp_delays = [rnd.uniform(0.005, 0.02) for _ in range(4)]
        outs = pool.run("boundary", [("confirm", tenant, hids[i], delays[i], st.name) for i in range(k)] + [("expiry", tenant, None, exp_delays, st.name)])
        cur = tx(c0, tenant)
        for i, hid in enumerate(hids):
            state = cur.execute("SELECT state FROM taktovia.capacity_hold WHERE tenant_id=%s AND id=%s", (tenant, hid)).fetchone()[0]
            booked = cur.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND hold_id=%s", (tenant, hid)).fetchone()[0]
            o = outs[i]
            agg["holds"] += 1
            if isinstance(o, BaseException) or o.kind == "ERROR":
                agg["errors"] += 1
                continue
            if state == "EXPIRED" and booked:
                agg["VIOLATION_expired_and_booked"] += 1
            if state == "CONSUMED" and not booked:
                agg["VIOLATION_consumed_without_booking"] += 1
            if state == "HELD":
                agg["VIOLATION_still_held"] += 1
            if o.kind == "CONFIRMED":
                agg["confirm_won"] += 1
                if state != "CONSUMED":
                    agg["VIOLATION_confirmed_but_state_" + state] += 1
            else:
                agg["confirm_rejected:" + o.code] += 1
                if o.code == "HOLD_EXPIRED" and state != "EXPIRED":
                    agg["VIOLATION_rejected_expired_but_state_" + state] += 1
        c0.rollback()
        agg["iterations"] += 1
    return {"strategy": st.name, "holds_per_iteration": k, "counts": dict(sorted(agg.items()))}


def exp_expired_exact(pool, runs, st, n=50):
    """Confirm auf Hold mit expires_at = Einfügezeitpunkt (Grenzzeitpunkt oder bereits überschritten): immer abgelaufen, nie gebucht."""
    agg = Counter()
    for it in range(n):
        label = "AB"[it % 2]
        tenant, run = sd.tid(label), runs.take(label)
        c0 = pool.ctl[0]
        cur = tx(c0, tenant)
        hid = uuid.uuid5(cap.NS, f"exact|{run}")
        cur.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                    "VALUES (%s,%s,%s,1,%s,'{\"seats\":1,\"wheelchair\":0}'::jsonb,0,2,clock_timestamp(),'HELD')", (tenant, hid, run, f"ex-{hid}"))
        c0.commit()
        o = cap.confirm(c0, tenant, [hid], st)
        cur = tx(c0, tenant)
        state = cur.execute("SELECT state FROM taktovia.capacity_hold WHERE tenant_id=%s AND id=%s", (tenant, hid)).fetchone()[0]
        booked = cur.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND hold_id=%s", (tenant, hid)).fetchone()[0]
        c0.rollback()
        agg[f"{o.kind}:{o.code}|state={state}|bookings={booked}"] += 1
        # Confirm auf freigegebenen Hold
    return {"strategy": st.name, "n": n, "counts": dict(agg)}


def exp_deadlock(pool, runs, st, n_iter, nreq=100):
    agg = Counter()
    lat = []
    for it in range(n_iter):
        label = "AB"[it % 2]
        tenant, r1, r2 = sd.tid(label), runs.take(label), runs.take(label)
        reqs = []
        for k in range(nreq):
            legs = [{"run": r1, "frm": 0, "to": 2, "seats": 1, "wheelchair": 0, "expect_rev": 1}, {"run": r2, "frm": 0, "to": 2, "seats": 1, "wheelchair": 0, "expect_rev": 1}]
            reqs.append({"ref": f"e4-{str(r1)[:8]}-{k}", "legs": legs if k % 2 == 0 else legs[::-1]})   # gegenläufige Reihenfolge
        t0 = time.perf_counter()
        outs = pool.run("book", [(tenant, r, st.name) for r in reqs])
        wall = time.perf_counter() - t0
        agg["iteration_wall_s_total_x1000"] += int(wall * 1000)
        for o in outs:
            if isinstance(o, BaseException):
                agg["EXCEPTION"] += 1
            else:
                agg[f"{o.kind}:{o.code}" if o.code else o.kind] += 1
                lat.append(o.t_total_ms)
        for run in (r1, r2):
            v = verify_run(pool.ctl[0], tenant, run, f"e4-{str(r1)[:8]}-", nseg=2)
            if v["violations"]:
                agg["iterations_capacity_violation"] += 1
        agg["iterations"] += 1
    agg["deadlocks"] = agg["ERROR:DEADLOCK"]
    return {"strategy": st.name, "iterations": n_iter, "requests_total": n_iter * nreq, "counts": dict(sorted(agg.items())), "latency_ms": lat_summary(lat)}


# ------------------------------------------------------------------ E5: Latenz unter 100 parallelen Transaktionen
def exp_overhead(pool, loops=100):
    res = pool.run("select1", [(loops,)] * pool.n)
    return {"parallel": pool.n, "executor": pool.kind, "latency_ms": lat_summary([x for r in res if not isinstance(r, BaseException) for x in r])}


def exp_workload(pool, st, scenario, cycles, seed_=1, label="", think_ms=0):
    n = pool.n
    events = []
    t0 = time.perf_counter()
    res = pool.run("workload", [(i, scenario, cycles, seed_, label, st.name, think_ms) for i in range(n)])
    wall = time.perf_counter() - t0
    exc = [r for r in res if isinstance(r, BaseException)]
    for r in res:
        if not isinstance(r, BaseException):
            events += r
    per_op = {}
    for op in ("hold", "confirm", "cancel"):
        ev = [e for e in events if e[0] == op]
        per_op[op] = {"total_ms": lat_summary([e[2] for e in ev]), "lock_wait_ms": lat_summary([e[3] for e in ev]),
                      "outcomes": dict(Counter(f"{e[4]}:{e[5]}" if e[5] else e[4] for e in ev))}
    holds_ok = [e[2] for e in events if e[0] == "hold" and e[4] == "HELD"]
    return {"strategy": st.name, "scenario": scenario, "think_ms": think_ms, "parallel_transactions": n, "executor": pool.kind, "cycles_per_worker": cycles, "wall_s": round(wall, 3),
            "transactions": len(events), "throughput_tx_per_s": round(len(events) / wall, 1), "cycles_completed": sum(1 for e in events if e[0] == "hold"),
            "per_op": per_op, "hold_success_total_ms": lat_summary(holds_ok), "worker_exceptions": [repr(e)[:200] for e in exc],
            "deadlocks": sum(1 for e in events if e[5] == "DEADLOCK"), "serialization_failures": sum(1 for e in events if e[5] == "SERIALIZATION_FAILURE"),
            "retries": sum(e[6] for e in events), "errors_other": sum(1 for e in events if e[4] == "ERROR" and e[5] not in ("DEADLOCK", "SERIALIZATION_FAILURE"))}, events


def occ_explain(conn, tenant, run):
    cur = tx(conn, tenant)
    plan = cur.execute("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + cap.OCC_SQL, (10, 14, tenant, run)).fetchone()[0][0]
    conn.rollback()
    return geo.plan_summary(plan)


# ------------------------------------------------------------------ E6: PostGIS
def geo_setup_fixture(c, label):
    tenant = sd.tid(label)
    ids = seed.fixture_ids()[label]
    cfg = cap.md5uuid(f"{tenant}cfg")
    return geo.create_fixture(c, tenant, ids["organization"], cfg)


def geo_correctness(c):
    out = []
    for label in "AB":
        tenant = sd.tid(label)
        ids = geo_setup_fixture(c, label)
        x0, y0, x1, y1 = geo.RECT
        sx0, sy0, sx1, sy1 = geo.RECT_SMALL
        cases = [  # (Beschreibung, lon, lat, Zeitpunkt, erwartet: Revisionen die das Gebiet abdecken)
            ("innerhalb (Revision 1 wirksam)", 12.25, 52.04, geo.T_REV1, [1]),
            ("außerhalb", 12.45, 52.04, geo.T_REV1, []),
            ("auf der Grenze (Kantenmitte West)", x0, 52.04, geo.T_REV1, [1]),
            ("auf der Grenze (Ecke)", x0, y0, geo.T_REV1, [1]),
            ("auf der Grenze (Kantenmitte Ost)", x1, 52.04, geo.T_REV1, [1]),
            ("knapp außerhalb der Grenze (0,000001 Grad)", x0 - 0.000001, 52.04, geo.T_REV1, []),
            ("innerhalb Revision 1 und 2 zum späteren Zeitpunkt", 12.25, 52.04, geo.T_REV2, [2]),
            ("nur in Revision 1 (Randstreifen), späterer Zeitpunkt: nicht mehr wirksam", 12.21, 52.04, geo.T_REV2, []),
            ("Zeitpunkt vor Gültigkeit", 12.25, 52.04, "2025-12-31T12:00:00+00", []),
            ("Gültigkeitsgrenze: exakt valid_to von Revision 1 = valid_from von Revision 2", 12.25, 52.04, "2026-06-01T00:00:00+00", [2]),
        ]
        for desc, lon, lat, at, expected in cases:
            rows = geo.area_check(c, tenant, lon, lat, at)
            got = sorted(r[2] for r in rows if r[0] in (ids["area1"], ids["area2"]))
            out.append({"tenant": label, "case": desc, "expected_revisions": expected, "actual_revisions": got, "ok": got == expected})
    return out


def geo_stop_correctness(c):
    out = []
    lon, lat = geo.QUERY_POINT
    for label in "AB":
        tenant = sd.tid(label)
        for variant in geo.STOP_VARIANTS:
            for at, expect in ((geo.T_REV1, {"Test N100": 100, "Test E250": 250}), (geo.T_REV2, {"Test E250": 250, "Test N100": 300})):
                rows = geo.nearest_stops(c, tenant, lon, lat, at, variant, r=500, k=5)
                names = {}
                cur = tx(c, tenant)
                for stop_id, rev, d in rows:
                    nm = cur.execute("SELECT name FROM taktovia.stop_revision WHERE tenant_id=%s AND stop_id=%s AND revision=%s", (tenant, stop_id, rev)).fetchone()[0]
                    names[nm] = d
                c.rollback()
                ok = set(names) == set(expect) and all(abs(names[n] - expect[n]) < 1.0 for n in expect)
                out.append({"tenant": label, "variant": variant, "at": at, "expected_m": expect, "actual_m": {k: round(v, 2) for k, v in names.items()}, "ok": ok,
                            "note": "S600 (600 m) liegt außerhalb des Radius 500 m"})
    return out


def geo_latency(pool, variant_kind, n_single, par_queries, seed_=1):
    """variant_kind: 'area' oder Name aus geo.STOP_VARIANTS. Zufällige Punkte in der BBOX (synthetische Stellvertreterregion)."""
    lo, la, hi, ha = sd.BBOX
    at = "2026-07-01T12:00:00+00"

    def one(conn, tenant, rnd):
        lon, lat = rnd.uniform(lo, hi), rnd.uniform(la, ha)
        t = time.perf_counter()
        if variant_kind == "area":
            r = geo.area_check(conn, tenant, lon, lat, at)
        else:
            r = geo.nearest_stops(conn, tenant, lon, lat, at, variant_kind)
        return (time.perf_counter() - t) * 1000, len(r)
    rnd = random.Random(seed_)
    single = [one(pool.ctl[0], sd.tid("AB"[i % 2]), rnd) for i in range(n_single)]

    t0 = time.perf_counter()
    res = pool.run("geo_worker", [(i, variant_kind, par_queries, seed_) for i in range(pool.n)])
    wall = time.perf_counter() - t0
    par = [x for r in res if not isinstance(r, BaseException) for x in r]
    return {"kind": variant_kind, "single_connection": lat_summary([x[0] for x in single]), "parallel": dict(lat_summary([x[0] for x in par]), connections=pool.n, wall_s=round(wall, 2)),
            "avg_result_rows": round(sum(x[1] for x in single) / len(single), 2), "errors": sum(1 for r in res if isinstance(r, BaseException))}


def geo_plans(c):
    lo, la, hi, ha = sd.BBOX
    lon, lat = (lo + hi) / 2, (la + ha) / 2
    at = "2026-07-01T12:00:00+00"
    out = {}
    for label in "AB":
        t = sd.tid(label)
        out[f"area_{label}"] = geo.plan_summary(geo.explain(c, t, geo.AREA_SQL, geo.params(t, lon, lat, at)))
        for name, sql in geo.STOP_VARIANTS.items():
            out[f"stops_{name}_{label}"] = geo.plan_summary(geo.explain(c, t, sql, geo.params(t, lon, lat, at)))
    return out


def geo_rls_evidence():
    """Belegt, warum der GiST-Index unter RLS nicht für &&/ST_DWithin/ST_Covers genutzt wird: die Operatoren sind nicht leakproof."""
    t = sd.tid("A")
    q = "EXPLAIN (ANALYZE, FORMAT JSON) SELECT s.stop_id FROM taktovia.stop_revision s WHERE s.tenant_id=%s AND s.point && ST_Expand(ST_SetSRID(ST_MakePoint(13.775,51.075),4326), 0.01)"
    out = {}
    with dbtool.admin(sd.SPIKE_DB) as a:
        out["leakproof"] = {n: bool(l) for n, l in a.execute("SELECT proname || '/' || pronargs, proleakproof FROM pg_proc WHERE proname IN ('geometry_overlaps','st_dwithin','st_covers','geometry_distance_centroid','uuid_eq','int8eq') ORDER BY 1")}
        p = a.execute(q, (t,)).fetchone()[0][0]
        out["superuser_no_rls"] = geo.plan_summary(p)
    c = atdb.runtime_conn(sd.SPIKE_DB)
    try:
        cur = tx(c, t)
        p = cur.execute(q, (t,)).fetchone()[0][0]
        out["runtime_with_rls"] = geo.plan_summary(p)
        c.rollback()
    finally:
        c.close()
    return out


def write_gz(path, header, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wt", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


# ------------------------------------------------------------------ E7: Mandantentrennung
def exp_isolation(pool, runs):
    A, B = sd.tid("A"), sd.tid("B")
    st = cap.STRATEGIES[PRIMARY]
    out = {}
    run_a, run_b = base_run("A", 7), base_run("B", 7)
    # (a) B hält die Zeilensperre auf seinem Lauf; A bucht gleichzeitig auf seinem Lauf: keine Wartezeit durch B
    cb = pool.ctl[1]
    cur = tx(cb, B)
    cur.execute("SELECT 1 FROM taktovia.vehicle_run WHERE tenant_id=%s AND id=%s FOR UPDATE", (B, run_b))
    t = time.perf_counter()
    h = cap.hold(pool.ctl[0], A, {"ref": f"iso-a-1{NONCE}", "legs": [{"run": run_a, "frm": 110, "to": 112, "seats": 1, "wheelchair": 0, "expect_rev": 1}]}, st)
    out["a_hold_A_while_B_holds_lock_ms"] = round((time.perf_counter() - t) * 1000, 2)
    out["a_hold_A_result"] = h.kind
    # (b) A versucht, den Lauf von B zu sperren oder zu sehen
    ca = pool.ctl[2]
    cur = tx(ca, A)
    t = time.perf_counter()
    rows = cur.execute("SELECT 1 FROM taktovia.vehicle_run WHERE tenant_id=%s AND id=%s FOR UPDATE", (B, run_b)).fetchall()
    out["b_A_select_for_update_on_B_run_rows"] = len(rows)
    out["b_A_select_for_update_on_B_run_ms"] = round((time.perf_counter() - t) * 1000, 2)
    rows2 = cur.execute("SELECT 1 FROM taktovia.vehicle_run WHERE id=%s", (run_b,)).fetchall()      # auch ohne Mandantenbedingung
    out["b_A_select_B_run_without_tenant_condition_rows"] = len(rows2)
    ca.rollback()
    # (c) Hold von A auf B-Lauf, Occupancy-Sicht
    o = cap.hold(pool.ctl[3], A, {"ref": f"iso-a-2{NONCE}", "legs": [{"run": run_b, "frm": 0, "to": 2, "seats": 1, "wheelchair": 0, "expect_rev": 1}]}, st)
    out["c_hold_A_on_B_run"] = f"{o.kind}:{o.code}"
    cur = tx(pool.ctl[3], A)
    out["c_A_sees_B_holds_rows"] = cur.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE run_id=%s", (run_b,)).fetchone()[0]
    out["c_A_sees_B_bookings_rows"] = cur.execute("SELECT count(*) FROM taktovia.booking WHERE hold_id IN (SELECT id FROM taktovia.capacity_hold)", ()).fetchone()[0]
    out["c_A_total_holds_visible"] = cur.execute("SELECT count(*) FROM taktovia.capacity_hold", ()).fetchone()[0]
    out["c_A_distinct_tenant_ids_visible"] = cur.execute("SELECT count(DISTINCT tenant_id) FROM taktovia.capacity_hold", ()).fetchone()[0]
    pool.ctl[3].rollback()
    # (d) ohne Kontext
    cur = pool.ctl[3].cursor()
    out["d_no_context_holds_visible"] = cur.execute("SELECT count(*) FROM taktovia.capacity_hold").fetchone()[0]
    pool.ctl[3].rollback()
    cb.rollback()
    # (e) Geoabfragen mit Kontext A liefern nie Zeilen von B
    lon, lat = (sd.BBOX[0] + sd.BBOX[2]) / 2, (sd.BBOX[1] + sd.BBOX[3]) / 2
    cur = tx(pool.ctl[4], A)
    out["e_A_stop_rows_visible"] = cur.execute("SELECT count(*) FROM taktovia.stop_revision").fetchone()[0]
    out["e_A_stop_foreign_tenant_rows"] = cur.execute("SELECT count(*) FROM taktovia.stop_revision WHERE tenant_id <> %s", (A,)).fetchone()[0]
    rows = cur.execute(geo.STOPS_BBOX_SQL.replace("s.tenant_id = %(t)s AND ", ""), geo.params(A, lon, lat, "2026-07-01T12:00:00+00", 2000, 50)).fetchall()
    out["e_A_geo_query_without_tenant_condition_rows"] = len(rows)
    ids = list({r[0] for r in rows})
    out["e_distinct_tenants_of_returned_stops"] = cur.execute("SELECT count(DISTINCT tenant_id) FROM taktovia.stop_revision WHERE stop_id = ANY(%s)", (ids,)).fetchone()[0]
    pool.ctl[4].rollback()
    return out


# ------------------------------------------------------------------ Aufträge für die Gleichzeitigkeitswerkzeuge (Registry)
@task("book")
def t_book(conn, tenant, req, stname):
    return cap.book(conn, tenant, req, cap.STRATEGIES[stname])


@task("confirm")
def t_confirm(conn, tenant, hold_ids, stname):
    return cap.confirm(conn, tenant, hold_ids, cap.STRATEGIES[stname])


@task("boundary")
def t_boundary(conn, kind, tenant, hid, delay, stname):
    st = cap.STRATEGIES[stname]
    if kind == "confirm":
        time.sleep(delay)
        return cap.confirm(conn, tenant, [hid], st)
    res = []
    for d in delay:
        time.sleep(d)
        res.append(cap.expire_due(conn, tenant, st))
    return res


@task("select1")
def t_select1(conn, loops):
    out = []
    for _ in range(loops):
        t = time.perf_counter()
        conn.execute("SELECT 1")
        conn.commit()
        out.append((time.perf_counter() - t) * 1000)
    return out


@task("workload")
def t_workload(conn, i, scenario, cycles, seed_, label, stname, think_ms=0):
    st = cap.STRATEGIES[stname]
    rnd = random.Random(seed_ * 100_003 + i)
    lab = "AB"[i % 2]
    tenant = sd.tid(lab)
    local = []
    for c in range(cycles):
        g = {"spread": rnd.randint(1, 250), "hot5": rnd.randint(1, 5), "single": 1}[scenario]
        frm = rnd.randint(0, 113)
        wc = rnd.random() < 0.05
        req = {"ref": f"w{NONCE}-{label}-{i}-{c}", "legs": [{"run": base_run(lab, g), "frm": frm, "to": frm + rnd.randint(1, 6), "seats": 0 if wc else 1, "wheelchair": 1 if wc else 0, "expect_rev": 1}]}
        h = cap.hold(conn, tenant, req, st)
        local.append(("hold", lab, h.t_total_ms, h.t_lock_ms, h.kind, h.code, h.retries))
        if h.kind == "HELD":
            k = cap.confirm(conn, tenant, h.ids, st)
            local.append(("confirm", lab, k.t_total_ms, k.t_lock_ms, k.kind, k.code, k.retries))
            if k.kind == "CONFIRMED":
                x = cap.cancel_booking(conn, tenant, h.ids, st)
                local.append(("cancel", lab, x.t_total_ms, x.t_lock_ms, x.kind, x.code, x.retries))
        if think_ms:
            time.sleep(think_ms / 1000)
    return local


@task("geo_worker")
def t_geo_worker(conn, i, kind, n, seed_):
    lo, la, hi, ha = sd.BBOX
    at = "2026-07-01T12:00:00+00"
    r = random.Random(seed_ * 31 + i)
    tenant = sd.tid("AB"[i % 2])
    out = []
    for _ in range(n):
        lon, lat = r.uniform(lo, hi), r.uniform(la, ha)
        t = time.perf_counter()
        rows = geo.area_check(conn, tenant, lon, lat, at) if kind == "area" else geo.nearest_stops(conn, tenant, lon, lat, at, kind)
        out.append(((time.perf_counter() - t) * 1000, len(rows)))
    return out


# ------------------------------------------------------------------ Hauptablauf
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="stark verkleinerte Läufe (Entwicklung, keine Messaussage)")
    ap.add_argument("--only", default="", help="Kommaliste: e0,e1,e2,e3,e4,e5,e6,e7,e8")
    ap.add_argument("--out", default=str(OUT_DEFAULT))
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--parallel", type=int, default=100)
    ap.add_argument("--executor", choices=["threads", "processes"], default="processes", help="processes: 4 Prozesse mit je 25 Threads (Standard)")
    ap.add_argument("--no-reload", action="store_true", help="vorhandene Datenbank weiterverwenden")
    ap.add_argument("--drop", action="store_true")
    a = ap.parse_args(argv)
    only = set(filter(None, a.only.split(",")))
    want = lambda e: not only or e in only  # noqa: E731
    out_dir = Path(a.out)
    q = a.quick
    S = {"e1": 30 if q else 1000, "e1_alt": 10 if q else 200, "e1_neg": 10 if q else 100, "e2_wc": 10 if q else 100, "e2_rev": 5 if q else 50,
         "e3_replay": 5 if q else 100, "e3_boundary": 5 if q else 300, "e4_naive": 3 if q else 20, "e4_stable": 5 if q else 50,
         "e5_cycles": 5 if q else 100, "geo_single": 50 if q else 1000, "geo_par": 5 if q else 50}
    t_start = time.time()
    res = {"meta": {"seed": a.seed, "quick": q, "scale": S, "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "loadavg_start": os.getloadavg()}}
    if not a.no_reload:
        sd.prepare_database()
        sd.load_base_data()
    runs = Runs()
    if a.no_reload:
        runs.n = {"A": 3000, "B": 3000}   # Reserveläufe früherer Läufe nicht erneut verwenden
    pool = (Pool if a.executor == "threads" else ProcPool)(a.parallel, sd.SPIKE_DB)
    prim = cap.STRATEGIES[PRIMARY]
    with dbtool.admin(sd.SPIKE_DB) as adm:
        pg = {k: adm.execute(f"SHOW {k}").fetchone()[0] for k in ("max_connections", "shared_buffers", "work_mem", "effective_cache_size", "fsync", "synchronous_commit",
                                                                     "deadlock_timeout", "default_transaction_isolation", "max_wal_size", "random_page_cost")}
        pg["version"] = adm.execute("SELECT version()").fetchone()[0]
        pg["postgis"] = adm.execute("SELECT PostGIS_Version()").fetchone()[0]
    res["meta"].update(hardware=hardware.collect(), postgresql=pg, pool={"connections": a.parallel, "role": "taktovia_runtime", "driver": "psycopg 3", "executor": pool.kind, "processes": getattr(pool, "nprocs", 1), "note": "je Auftrag eigener Thread und eigene Verbindung; alle Aufträge einer Runde starten gleichzeitig"},
                       software_build=evidence.software_build(), data=sd.table_counts(), data_notes={"base_runs_per_tenant": sd.VEHICLES_PER_TENANT, "holds_per_base_run": sd.HOLDS_PER_RUN, "segments_per_run": sd.SEGMENTS_PER_RUN,
                       "stops_per_tenant_base": sd.STOPS_PER_TENANT, "service_areas_per_tenant": sd.SERVICES_PER_TENANT * 3, "bbox": sd.BBOX})
    adm2 = dbtool.admin(sd.SPIKE_DB)
    adm2.autocommit = True
    last = [adm2.execute("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()").fetchone()[0]]
    res["server_deadlocks_per_block"] = {}

    def mark(block):
        """Deadlock-Zähler des Servers (pg_stat_database) je Messblock: unabhängig von der Zählung im Client."""
        now = adm2.execute("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()").fetchone()[0]
        res["server_deadlocks_per_block"][block] = now - last[0]
        last[0] = now

    if want("e0"):
        res["e0_overhead_select1"] = exp_overhead(pool)
    # --- Korrektheit
    if want("e1"):
        r, rows = exp_race(pool, runs, prim, S["e1"], "seat", a.seed, "e1")
        res["e1_primary"] = r
        write_gz(out_dir / "e1_primary_iterations.csv.gz", ["iteration", "tenant", "confirmed", "bookings", "active_holds", "violations", "max_seats", "max_wc", "outcomes"], rows)
        res["e1_variants"] = {}
        for name in ("rc_advisory", "rr_touch"):
            r, _ = exp_race(pool, runs, cap.STRATEGIES[name], S["e1_alt"], "seat", a.seed, "e1" + name[:4])
            res["e1_variants"][name] = r
        for name in ("rc_none", "rr_run_row"):    # Negativkontrollen: ohne Sperre bzw. mit veraltetem Snapshot
            r, _ = exp_race(pool, runs, cap.STRATEGIES[name], S["e1_neg"], "seat", a.seed, "e1" + name[:4])
            res["e1_variants"][name] = r
    mark("e0_e1")
    if want("e2"):
        res["e2_wheelchair"], _ = exp_race(pool, runs, prim, S["e2_wc"], "wheelchair", a.seed, "e2w")
        res["e2_revision_before"], _ = exp_race(pool, runs, prim, S["e2_rev"], "revision", a.seed, "e2r")
        res["e2_revision_after_hold"] = exp_revision_after_hold(pool, runs, prim, S["e2_rev"])
    mark("e2")
    if want("e3"):
        res["e3_replay"] = exp_replay(pool, runs, prim, S["e3_replay"])
        res["e3_boundary_race"] = exp_boundary(pool, runs, prim, S["e3_boundary"], seed_=a.seed)
        res["e3_expired_exact"] = exp_expired_exact(pool, runs, prim)
    mark("e3")
    if want("e4"):
        res["e4_deadlock"] = {}
        for name, n in (("rc_run_row_naive", S["e4_naive"]), ("rc_run_row", S["e4_stable"]), ("rc_advisory_naive", S["e4_naive"]), ("rc_advisory", S["e4_stable"])):
            res["e4_deadlock"][name] = exp_deadlock(pool, runs, cap.STRATEGIES[name], n)
    mark("e4")
    # --- Latenz
    if want("e5"):
        res["e5"] = {"baseline_kernschema_indexes": {}, "with_hold_indexes": {}}
        for phase, key in (("baseline", "baseline_kernschema_indexes"), ("indexed", "with_hold_indexes")):
            if phase == "indexed":
                sd.create_indexes(sd.HOLD_INDEXES)
            if phase == "baseline":
                pass
            c0 = pool.ctl[0]
            res["e5"][key]["occupancy_plan_run_A"] = occ_explain(c0, sd.tid("A"), base_run("A", 3))
            for scen, think in (("spread", 0), ("hot5", 0), ("single", 0), ("spread", 300)):
                skey = scen if not think else f"{scen}_paced{think}ms"
                r, ev = exp_workload(pool, prim, scen, S["e5_cycles"] if not think else max(3, S["e5_cycles"] // 3), a.seed, label=f"{phase}-{skey}", think_ms=think)
                res["e5"][key][skey] = r
                write_gz(out_dir / f"e5_{phase}_{skey}_events.csv.gz", ["op", "tenant", "total_ms", "lock_ms", "kind", "code", "retries"], [[e[0], e[1], f"{e[2]:.3f}", f"{e[3]:.3f}", e[4], e[5], e[6]] for e in ev])
            if phase == "indexed":
                res["e5"][key]["alt_strategies_spread"] = {}
                for name in ("rc_advisory", "rr_touch"):
                    r, _ = exp_workload(pool, cap.STRATEGIES[name], "spread", S["e5_cycles"], a.seed, label=f"alt-{name}")
                    res["e5"][key]["alt_strategies_spread"][name] = r
                    r2, _ = exp_workload(pool, cap.STRATEGIES[name], "hot5", S["e5_cycles"], a.seed, label=f"alt-{name}-h")
                    res["e5"][key]["alt_strategies_spread"][name + "_hot5"] = r2
    mark("e5")
    # --- PostGIS
    if want("e6"):
        c0 = pool.ctl[0]
        g = {"rls_index_usage": geo_rls_evidence()}
        sd.add_grid_cells()
        g["area_correctness"] = geo_correctness(c0)
        g["stop_correctness"] = geo_stop_correctness(c0)
        g["plans_baseline"] = geo_plans(c0)
        g["latency_baseline"] = {k: geo_latency(pool, k, S["geo_single"], S["geo_par"], a.seed) for k in ("area", *geo.STOP_VARIANTS)}
        sd.ensure_btree_gist()
        sd.create_indexes(sd.GEO_INDEXES)
        g["plans_composite_index"] = geo_plans(c0)
        g["latency_composite_index"] = {k: geo_latency(pool, k, S["geo_single"], S["geo_par"], a.seed) for k in ("area", *geo.STOP_VARIANTS)}
        sd.drop_indexes(sd.GEO_INDEXES)
        res["e6_postgis"] = g
    mark("e6")
    if want("e7"):
        res["e7_tenant_isolation"] = exp_isolation(pool, runs)
    if want("e8"):
        res["e8_jsonb_vs_int"] = exp_jsonb_vs_int(pool)
    mark("e7_e8")
    adm2.close()
    res["meta"].update(finished_utc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), duration_s=round(time.time() - t_start, 1), loadavg_end=os.getloadavg(),
                       spare_runs_used=runs.n)
    pool.close()
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / ("results-quick.json" if q else "results.json")).write_text(json.dumps(res, indent=1, sort_keys=True, default=str) + "\n")
    print("fertig:", out_dir / ("results-quick.json" if q else "results.json"), f"({res['meta']['duration_s']} s)")
    if a.drop:
        atdb.drop_database(sd.SPIKE_DB)
    return 0


def exp_jsonb_vs_int(pool, n=400):
    """Eignung von capacity_vector jsonb: gleiche Abfrage mit jsonb-Zugriff und mit generierten Integer-Spalten (nur Messvariante)."""
    import psycopg
    m = atdb.migrator_conn(sd.SPIKE_DB)
    m.autocommit = True
    try:
        m.execute("ALTER TABLE taktovia.capacity_hold ADD COLUMN IF NOT EXISTS seats_i int GENERATED ALWAYS AS ((capacity_vector->>'seats')::int) STORED, "
                  "ADD COLUMN IF NOT EXISTS wc_i int GENERATED ALWAYS AS ((capacity_vector->>'wheelchair')::int) STORED")
        m.execute("ANALYZE taktovia.capacity_hold")
    finally:
        m.close()
    int_sql = cap.OCC_SQL.replace("(h.capacity_vector->>'seats')::int", "h.seats_i").replace("(h.capacity_vector->>'wheelchair')::int", "h.wc_i")
    out = {}
    try:
        for name, sql in (("jsonb", cap.OCC_SQL), ("generated_int_columns", int_sql)):
            rnd = random.Random(5)
            lat = []
            conn = pool.ctl[0]
            for i in range(n):
                lab = "AB"[i % 2]
                cur = tx(conn, sd.tid(lab))
                run = base_run(lab, rnd.randint(1, 250))
                t = time.perf_counter()
                cur.execute(sql, (0, 119, sd.tid(lab), run)).fetchall()
                lat.append((time.perf_counter() - t) * 1000)
                conn.rollback()
            out[name] = lat_summary(lat)
    finally:
        m = atdb.migrator_conn(sd.SPIKE_DB)
        m.autocommit = True
        m.execute("ALTER TABLE taktovia.capacity_hold DROP COLUMN IF EXISTS seats_i, DROP COLUMN IF EXISTS wc_i")
        m.close()
    out["query"] = "Belegung je Abschnitt über 120 Abschnitte eines Basislaufs (60 Holds)"
    return out


if __name__ == "__main__":
    sys.exit(main())
