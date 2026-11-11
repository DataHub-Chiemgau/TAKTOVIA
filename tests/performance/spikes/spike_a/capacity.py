"""Wegwerf-Prototyp (NICHT Produktivcode): Hold und Confirm nach Kap. 01 §1.4 Schritte 5-6 und Kap. 02 §7.1.

Alles in PostgreSQL unter der Runtime-Rolle mit transaktionslokalem Mandantenkontext. Kapazitätsabschnitte sind die
Ganzzahlintervalle capacity_hold.interval_from/interval_to (halb offen [from,to)) je vehicle_run; der Ressourcenvektor
(Sitz, Rollstuhlplatz) liegt in capacity_hold.capacity_vector (jsonb). Eine Anfrage besteht aus Beinen (legs); jedes Bein
ergibt einen Hold (federation_ref = <ref>/<index>), jeder Hold genau eine Buchung.

Sperrstrategien (st.lock):
  run_row        Zeilensperre auf vehicle_run (FOR UPDATE), betroffene Läufe in aufsteigender ID-Reihenfolge   [Empfehlung]
  run_row_naive  wie run_row, aber in Anfragereihenfolge (Gegenprobe für Deadlocks)
  advisory       pg_advisory_xact_lock je betroffenem Abschnitt, aufsteigend nach Schlüssel
  advisory_naive wie advisory in Anfragereihenfolge (Gegenprobe)
  touch          UPDATE vehicle_run SET state=state (optimistisch, für REPEATABLE READ; Wiederholung bei 40001)
  none           keine Sperre (Negativkontrolle: zeigt die Race Condition)
Isolation (st.iso): RC = READ COMMITTED, RR = REPEATABLE READ.
Ablauf nach Serverzeit: abgelaufen gilt now >= expires_at mit clock_timestamp() nach Erwerb der Sperre.
"""
import hashlib
import time
import uuid
from dataclasses import dataclass, field

from psycopg import IsolationLevel, errors

NS = uuid.UUID("5a5a5a5a-0000-4000-8000-00000000a001")
DIMS = ("wheelchair", "seats")


def md5uuid(text):
    return uuid.UUID(hashlib.md5(text.encode()).hexdigest())


@dataclass(frozen=True)
class Strategy:
    name: str
    lock: str
    iso: str = "RC"
    max_retries: int = 8


STRATEGIES = {s.name: s for s in (
    Strategy("rc_run_row", "run_row"),
    Strategy("rc_run_row_naive", "run_row_naive"),
    Strategy("rc_advisory", "advisory"),
    Strategy("rc_advisory_naive", "advisory_naive"),
    Strategy("rc_none", "none"),
    Strategy("rr_run_row", "run_row", "RR"),
    Strategy("rr_touch", "touch", "RR"),
)}


@dataclass
class Outcome:
    kind: str                     # HELD | CONFIRMED | REJECTED | ERROR
    code: str = ""                # Abweisungsgrund bzw. Fehlerklasse
    ids: list = field(default_factory=list)
    replayed: bool = False
    t_lock_ms: float = 0.0        # Zeit für Sperrerwerb inkl. Laufzeile lesen (Sperrwartezeit)
    t_total_ms: float = 0.0
    retries: int = 0


class Reject(Exception):
    def __init__(self, code, commit=False):
        super().__init__(code)
        self.code, self.commit = code, commit


def _set_tenant(cur, tenant):
    cur.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant),))


def _adv_key(tenant, run, seg):
    return int.from_bytes(hashlib.sha256(f"{tenant}|{run}|{seg}".encode()).digest()[:8], "big", signed=True)


RUN_SQL = ("SELECT r.id, r.revision, r.state, v.active_block, v.capacity_config FROM taktovia.vehicle_run r "
           "JOIN taktovia.vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id "
           "WHERE r.tenant_id = %s AND r.id = ANY(%s::uuid[])")


def _lock_and_read(cur, tenant, specs, st):
    """Sperrt die von specs [(run, from, to)] betroffenen Ressourcen in der Reihenfolge der Strategie und liest die Läufe.
    -> dict run -> (revision, state, active_block, capacity_config)."""
    runs_in_order = []
    for run, _, _ in specs:
        if run not in runs_in_order:
            runs_in_order.append(run)
    sorted_runs = sorted(runs_in_order, key=str)
    out = {}

    def read_one(run, suffix=""):
        row = cur.execute(RUN_SQL + suffix, (tenant, [run])).fetchone()
        if row:
            out[run] = row[1:]

    if st.lock in ("run_row", "run_row_naive"):
        for run in (sorted_runs if st.lock == "run_row" else runs_in_order):
            read_one(run, " FOR UPDATE OF r")
    elif st.lock == "touch":
        for run in sorted_runs:
            cur.execute("UPDATE taktovia.vehicle_run SET state = state WHERE tenant_id = %s AND id = %s", (tenant, run))
        for run in sorted_runs:
            read_one(run)
    elif st.lock in ("advisory", "advisory_naive"):
        keys, seen = [], set()
        for run, a, b in specs:
            for seg in range(a, b):
                k = _adv_key(tenant, run, seg)
                if k not in seen:
                    seen.add(k)
                    keys.append(k)
        if st.lock == "advisory":
            keys.sort()
        cur.execute("SELECT count(pg_advisory_xact_lock(k)) FROM (SELECT k FROM unnest(%s::bigint[]) WITH ORDINALITY u(k, o) ORDER BY o OFFSET 0) s", (keys,))
        for run in runs_in_order:
            read_one(run)
    else:  # none
        for run in runs_in_order:
            read_one(run)
    return out


OCC_SQL = """SELECT s.seg, coalesce(sum((h.capacity_vector->>'seats')::int), 0), coalesce(sum((h.capacity_vector->>'wheelchair')::int), 0)
             FROM generate_series(%s::int, %s::int) AS s(seg)
             LEFT JOIN taktovia.capacity_hold h ON h.tenant_id = %s AND h.run_id = %s AND h.interval_from <= s.seg AND h.interval_to > s.seg
                  AND (h.state = 'CONSUMED' OR (h.state = 'HELD' AND h.expires_at > clock_timestamp()))
             GROUP BY s.seg"""


def _outbox(cur, tenant, aggregate_id, revision, etype, payload="{}"):
    cur.execute("INSERT INTO taktovia.outbox(tenant_id,event_id,aggregate_id,aggregate_revision,event_type,schema_version,occurred_at,payload) "
                "VALUES (%s,%s,%s,%s,%s,'1',clock_timestamp(),%s::jsonb)",
                (tenant, uuid.uuid5(NS, f"{aggregate_id}|{revision}|{etype}"), aggregate_id, revision, etype, payload))


def _run_tx(conn, st, body, box):
    """Führt body(cur) in einer Transaktion aus; behandelt Abweisungen, Deadlocks und Serialisierungsfehler (Wiederholung)."""
    t0 = time.perf_counter()
    retries = 0
    while True:
        cur = None
        try:
            conn.isolation_level = IsolationLevel.REPEATABLE_READ if st.iso == "RR" else IsolationLevel.READ_COMMITTED
            cur = conn.cursor()
            out = body(cur)
            conn.commit()
            out.retries, out.t_total_ms, out.t_lock_ms = retries, (time.perf_counter() - t0) * 1000, box.get("lock_ms", 0.0)
            return out
        except Reject as r:
            if r.commit:
                conn.commit()
            else:
                conn.rollback()
            return Outcome("REJECTED", r.code, retries=retries, t_total_ms=(time.perf_counter() - t0) * 1000, t_lock_ms=box.get("lock_ms", 0.0))
        except errors.DeadlockDetected:
            conn.rollback()
            return Outcome("ERROR", "DEADLOCK", retries=retries, t_total_ms=(time.perf_counter() - t0) * 1000, t_lock_ms=box.get("lock_ms", 0.0))
        except errors.SerializationFailure:
            conn.rollback()
            retries += 1
            if retries > st.max_retries:
                return Outcome("ERROR", "SERIALIZATION_FAILURE", retries=retries, t_total_ms=(time.perf_counter() - t0) * 1000)
        except Exception as e:  # noqa: BLE001 - jeder unerwartete Fehler wird als Outcome gezählt, nicht verschluckt
            conn.rollback()
            return Outcome("ERROR", type(e).__name__ + ":" + str(e)[:80], retries=retries, t_total_ms=(time.perf_counter() - t0) * 1000)


def hold(conn, tenant, req, st, ttl_s=180):
    """req = {ref, legs:[{run, frm, to, seats, wheelchair, expect_rev}]} -> Outcome(HELD, ids=[hold_id,...])."""
    legs = req["legs"]
    box = {}

    def body(cur):
        _set_tenant(cur, tenant)
        t_l = time.perf_counter()
        runs = _lock_and_read(cur, tenant, [(l["run"], l["frm"], l["to"]) for l in legs], st)
        lock_ms = box["lock_ms"] = (time.perf_counter() - t_l) * 1000
        ids = []
        existing = cur.execute("SELECT id FROM taktovia.capacity_hold WHERE tenant_id = %s AND federation_ref = ANY(%s) ORDER BY federation_ref",
                               (tenant, [f"{req['ref']}/{i}" for i in range(len(legs))])).fetchall()
        if existing:                                   # idempotente Wiederholung derselben Anfrage
            o = Outcome("HELD", ids=[r[0] for r in existing], replayed=True)
            o.t_lock_ms = lock_ms
            return o
        for i, leg in enumerate(legs):
            row = runs.get(leg["run"])
            if row is None:
                raise Reject("UNKNOWN_RUN")
            revision, state, active_block, cfg = row
            if state != "ACTIVE":
                raise Reject("RUN_NOT_ACTIVE")
            if not active_block:
                raise Reject("SAFETY_BLOCK")
            if revision != leg["expect_rev"]:
                raise Reject("REVISION_CONFLICT")
            want = {"seats": leg.get("seats", 1), "wheelchair": leg.get("wheelchair", 0)}
            for seg, seats, wc in cur.execute(OCC_SQL, (leg["frm"], leg["to"] - 1, tenant, leg["run"])).fetchall():
                occ = {"seats": seats, "wheelchair": wc}
                for d in DIMS:
                    if want[d] > 0 and occ[d] + want[d] > cfg[d]:
                        raise Reject("NO_CAPACITY_" + d.upper())
            hid = uuid.uuid5(NS, f"{tenant}|{req['ref']}|{i}")
            cur.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                        "VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s,%s,clock_timestamp() + make_interval(secs => %s),'HELD')",
                        (tenant, hid, leg["run"], revision, f"{req['ref']}/{i}",
                         '{"seats": %d, "wheelchair": %d}' % (want["seats"], want["wheelchair"]), leg["frm"], leg["to"], ttl_s))
            _outbox(cur, tenant, hid, 1, "CapacityHeld")
            ids.append(hid)
        o = Outcome("HELD", ids=ids)
        o.t_lock_ms = lock_ms
        return o
    return _run_tx(conn, st, body, box)


def confirm(conn, tenant, hold_ids, st, service_id=None):
    """Konvertiert gültige Holds in Buchungen (eine Transaktion). Wiederholung liefert dieselben Buchungen."""
    box = {}

    def body(cur):
        if not hold_ids:
            raise Reject("NO_HOLDS")
        _set_tenant(cur, tenant)
        rows = cur.execute("SELECT id, run_id, interval_from, interval_to FROM taktovia.capacity_hold WHERE tenant_id = %s AND id = ANY(%s) ORDER BY id",
                           (tenant, list(hold_ids))).fetchall()
        if len(rows) != len(hold_ids):
            raise Reject("UNKNOWN_HOLD")
        t_l = time.perf_counter()
        runs = _lock_and_read(cur, tenant, [(r[1], r[2], r[3]) for r in rows], st)
        lock_ms = box["lock_ms"] = (time.perf_counter() - t_l) * 1000
        holds = cur.execute("SELECT id, run_id, run_revision, state, federation_ref, clock_timestamp() >= expires_at FROM taktovia.capacity_hold "
                            "WHERE tenant_id = %s AND id = ANY(%s) ORDER BY id" + ("" if st.lock == "none" else " FOR UPDATE"),
                            (tenant, list(hold_ids))).fetchall()
        bookings = dict(cur.execute("SELECT hold_id, id FROM taktovia.booking WHERE tenant_id = %s AND hold_id = ANY(%s)", (tenant, list(hold_ids))).fetchall())
        if len(bookings) == len(hold_ids):
            o = Outcome("CONFIRMED", ids=[bookings[h[0]] for h in holds], replayed=True)
            o.t_lock_ms = lock_ms
            return o
        for hid, run, run_rev, state, fed, expired in holds:
            if hid in bookings:
                continue
            if state == "EXPIRED":
                raise Reject("HOLD_EXPIRED")
            if state != "HELD":
                raise Reject("HOLD_NOT_HELD")
            if expired:                                  # now >= expires_at: abgelaufen, nie "abgelaufen und gebucht"
                for h2 in holds:
                    if h2[3] == "HELD":
                        cur.execute("UPDATE taktovia.capacity_hold SET state = 'EXPIRED' WHERE tenant_id = %s AND id = %s AND state = 'HELD'", (tenant, h2[0]))
                        _outbox(cur, tenant, h2[0], 2, "CapacityExpired")
                raise Reject("HOLD_EXPIRED", commit=True)
            if runs[run][0] != run_rev:
                raise Reject("REVISION_CONFLICT")
        out = []
        svc = service_id or md5uuid(f"{tenant}svc0")
        for hid, run, run_rev, state, fed, expired in holds:
            if hid in bookings:
                out.append(bookings[hid])
                continue
            bid = uuid.uuid5(NS, f"booking|{hid}")
            cur.execute("UPDATE taktovia.capacity_hold SET state = 'CONSUMED' WHERE tenant_id = %s AND id = %s", (tenant, hid))
            cur.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) "
                        "VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}',1)", (tenant, bid, hid, svc, "bk-" + fed))
            _outbox(cur, tenant, bid, 1, "BookingConfirmed")
            out.append(bid)
        o = Outcome("CONFIRMED", ids=out)
        o.t_lock_ms = lock_ms
        return o
    return _run_tx(conn, st, body, box)


def release(conn, tenant, hold_ids, st):
    """Freigabe (HELD -> RELEASED) unter demselben Kapazitätslock."""
    box = {}

    def body(cur):
        _set_tenant(cur, tenant)
        rows = cur.execute("SELECT id, run_id, interval_from, interval_to FROM taktovia.capacity_hold WHERE tenant_id = %s AND id = ANY(%s)", (tenant, list(hold_ids))).fetchall()
        if not rows:
            raise Reject("UNKNOWN_HOLD")
        _t = time.perf_counter()
        _lock_and_read(cur, tenant, [(r[1], r[2], r[3]) for r in rows], st)
        box["lock_ms"] = (time.perf_counter() - _t) * 1000
        n = 0
        for r in rows:
            if cur.execute("UPDATE taktovia.capacity_hold SET state = 'RELEASED' WHERE tenant_id = %s AND id = %s AND state = 'HELD'", (tenant, r[0])).rowcount:
                _outbox(cur, tenant, r[0], 2, "CapacityReleased")
                n += 1
        return Outcome("CONFIRMED" if n else "REJECTED", "" if n else "HOLD_NOT_HELD", ids=[r[0] for r in rows])
    return _run_tx(conn, st, body, box)


def cancel_booking(conn, tenant, hold_ids, st):
    """Storno einer bestätigten Buchung: Buchung CANCELLED, Hold RELEASED, Kapazität wird frei."""
    box = {}

    def body(cur):
        _set_tenant(cur, tenant)
        rows = cur.execute("SELECT id, run_id, interval_from, interval_to FROM taktovia.capacity_hold WHERE tenant_id = %s AND id = ANY(%s)", (tenant, list(hold_ids))).fetchall()
        if not rows:
            raise Reject("UNKNOWN_HOLD")
        _t = time.perf_counter()
        _lock_and_read(cur, tenant, [(r[1], r[2], r[3]) for r in rows], st)
        box["lock_ms"] = (time.perf_counter() - _t) * 1000
        n = 0
        for r in rows:
            if cur.execute("UPDATE taktovia.booking SET state = 'CANCELLED', revision = revision + 1 WHERE tenant_id = %s AND hold_id = %s AND state = 'CONFIRMED'", (tenant, r[0])).rowcount:
                cur.execute("UPDATE taktovia.capacity_hold SET state = 'RELEASED' WHERE tenant_id = %s AND id = %s", (tenant, r[0]))
                _outbox(cur, tenant, uuid.uuid5(NS, f"booking|{r[0]}"), 2, "BookingCancelled")
                n += 1
        return Outcome("CONFIRMED" if n else "REJECTED", "" if n else "NOT_CONFIRMED", ids=[r[0] for r in rows])
    return _run_tx(conn, st, body, box)


def expire_due(conn, tenant, st, limit=1000):
    """Hintergrund-Expiryjob: setzt fällige Holds auf EXPIRED, mit demselben Lock wie Confirm (nur Beschleunigung)."""
    box = {}

    def body(cur):
        _set_tenant(cur, tenant)
        rows = cur.execute("SELECT id, run_id, interval_from, interval_to FROM taktovia.capacity_hold WHERE tenant_id = %s AND state = 'HELD' "
                           "AND expires_at <= clock_timestamp() ORDER BY run_id, id LIMIT %s", (tenant, limit)).fetchall()
        if not rows:
            return Outcome("CONFIRMED", ids=[])
        _lock_and_read(cur, tenant, [(r[1], r[2], r[3]) for r in rows], st)
        done = []
        for r in rows:
            if cur.execute("UPDATE taktovia.capacity_hold SET state = 'EXPIRED' WHERE tenant_id = %s AND id = %s AND state = 'HELD' AND expires_at <= clock_timestamp()", (tenant, r[0])).rowcount:
                _outbox(cur, tenant, r[0], 2, "CapacityExpired")
                done.append(r[0])
        return Outcome("CONFIRMED", ids=done)
    return _run_tx(conn, st, body, box)


def book(conn, tenant, req, st, ttl_s=180):
    """Hold, danach Confirm (zwei Transaktionen wie Kap. 01 §1.4 Schritte 5-6). Ergebnis: Outcome des letzten Schritts."""
    h = hold(conn, tenant, req, st, ttl_s)
    if h.kind != "HELD":
        return h
    c = confirm(conn, tenant, h.ids, st)
    c.t_lock_ms += h.t_lock_ms
    c.t_total_ms += h.t_total_ms
    c.retries += h.retries
    c.ids = h.ids if not c.ids else c.ids
    return c
