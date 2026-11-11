#!/usr/bin/env python3
"""Deterministische Sandbox-Partner (WP-M0-24): Betreiber A, Betreiber B und ein nur auskunftsfähiger Partner C.

NUR TESTINFRASTRUKTUR, nur Testnetz, ausschließlich synthetische Daten. Testschlüssel werden zur Laufzeit aus dem Seed
abgeleitet und nie im Repository abgelegt. Bildet den internen kanonischen Adaptervertrag ab (Kap. 05 §5.7 "Partner
intern"); reale TOMP-/OSDM-Profile folgen nach D-08 in M6.

Start:  python tests/sandbox/partner/partner_sandbox.py --instances A,B,C --base-port 18100 --seed 1
Je Instanz ein eigener Port (A=base, B=base+1, C=base+2).
"""
import argparse
import copy
import hashlib
import hmac
import json
import sys
import threading
import time
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "lib"))
from taktovia_testkit.jsonhttp import App, BadRequest, DropConnection, NoResponse, Response, http_call  # noqa: E402
from taktovia_testkit.partner_signature import MAX_BODY_BYTES, sign  # noqa: E402
from taktovia_testkit.testclock import DEFAULT_START, SimClock, iso, parse_iso  # noqa: E402

FEATURES = ["standard", "discovery", "information", "binding_prices", "hold", "hold_extension", "confirm",
            "deduplicated_requests", "status_lookup", "cancel", "refund", "ticket_issuance", "realtime",
            "signature_scheme", "known_quotas", "sandbox_evidence"]
HOLD_TRANSITIONS = {"HELD": {"CONSUMED", "EXPIRED", "RELEASED"}, "CONSUMED": set(), "RELEASED": set(), "EXPIRED": set()}
EVENT_TYPES = ["reservation.confirmed", "reservation.cancelled", "reservation.expired", "reservation.status_changed",
               "reservation.refunded"]
OPS = ("offers", "reserve", "confirm", "cancel", "extend", "refund", "status")
FAULT_MODES = ("timeout", "lost_response", "reject", "unavailable")
EVENT_MODES = ("valid", "bad_signature", "unknown_key_id", "stale_timestamp", "future_timestamp", "oversize",
               "tenant_mismatch", "disallowed_type", "duplicate", "same_id_changed_payload", "out_of_order",
               "version_gap", "rotation")
HOLD_TTL_DEFAULT, HOLD_TTL_MAX = 180, 900   # Planungsdefault Kap. 02 §7.2 nur als Testwert
KEY_CURRENT, KEY_NEXT, KEY_UNKNOWN = "key-2026-10", "key-2026-11", "key-unbekannt"
TENANT_OF = {"A": "00000000-0000-4000-8000-0000000000a1", "B": "00000000-0000-4000-8000-0000000000b1",
             "C": "00000000-0000-4000-8000-0000000000c1"}
OTHER_TENANT = "00000000-0000-4000-8000-0000000000ff"


def h(*parts):
    return hashlib.sha256(":".join(str(p) for p in parts).encode()).hexdigest()


def F(supported=True, **kw):
    return dict(supported=supported, **kw)


def full_matrix(**over):
    m = {"standard": F(name="TAKTOVIA-INTERNAL", version="1.0"), "discovery": F(), "information": F(),
         "binding_prices": F(), "hold": F(hold_ttl_seconds=HOLD_TTL_DEFAULT), "hold_extension": F(), "confirm": F(),
         "deduplicated_requests": F(), "status_lookup": F(), "cancel": F(), "refund": F(), "ticket_issuance": F(),
         "realtime": F(), "signature_scheme": F(scheme="HMAC-SHA256-v1"),
         "known_quotas": F(quotas={"seats": 4, "wheelchair": 1}), "sandbox_evidence": F(reference="SANDBOX-SYNTH-001")}
    m.update(over)
    return m


PRESETS = {
    "A": {"instance": "A", "partner_id": "partner-a", "operator": "Betreiber A (Sandbox)",
          "matrix": full_matrix(),
          "services": {
              "SVC-A-LAST": {"capacity": {"seats": 1, "wheelchair": 0}, "price_minor": 1000, "currency": "EUR"},
              "SVC-A-MAIN": {"capacity": {"seats": 4, "wheelchair": 1}, "price_minor": 1500, "currency": "EUR"},
              "SVC-A-NOEXT": {"capacity": {"seats": 2, "wheelchair": 0}, "price_minor": 800, "currency": "EUR",
                              "capability_overrides": {"hold_extension": F(False), "refund": F(False)}}}},
    "B": {"instance": "B", "partner_id": "partner-b", "operator": "Betreiber B (Sandbox, proprietäre API)",
          "matrix": full_matrix(standard=F(name="PROPRIETARY-B", version="0.3"), hold_extension=F(False), refund=F(False),
                                realtime=F(False), ticket_issuance=F(False),
                                known_quotas=F(False), sandbox_evidence=F(reference="SANDBOX-SYNTH-002")),
          "services": {
              "SVC-B-MAIN": {"capacity": {"seats": 3, "wheelchair": 1}, "price_minor": 2000, "currency": "EUR"},
              "SVC-B-RAIL": {"capacity": {"seats": 10, "wheelchair": 2}, "price_minor": 500, "currency": "EUR",
                             "capability_overrides": {"binding_prices": F(False)}}}},
    "C": {"instance": "C", "partner_id": "partner-c", "operator": "Linienbus C (nur Auskunft, PH-AT-006)",
          "matrix": full_matrix(standard=F(name="GTFS-RT", version="2.0"), binding_prices=F(False), hold=F(False),
                                hold_extension=F(False), confirm=F(False), deduplicated_requests=F(False),
                                status_lookup=F(False), cancel=F(False), refund=F(False), ticket_issuance=F(False),
                                realtime=F(), signature_scheme=F(False), known_quotas=F(False),
                                sandbox_evidence=F(False)),
          "services": {"SVC-C-LINE": {"capacity": {"seats": 50, "wheelchair": 2}, "price_minor": 300, "currency": "EUR"}}},
}


class ApiError(Exception):
    def __init__(self, status, code, message=""):
        super().__init__(message or code)
        self.status, self.code, self.message = status, code, message or code


class PartnerSandbox:
    def __init__(self, cfg, seed=1, clock_start=DEFAULT_START):
        self.cfg = copy.deepcopy(cfg)
        self.seed = seed
        self.lock = threading.RLock()
        self.clock = SimClock(clock_start)
        self.callers = list(self.cfg.get("allowed_callers", ["taktovia-core", "partner-x"]))
        self.callers_primary = "taktovia-core"
        self.reset_state()

    def reset_state(self):
        self.reservations = {}
        self.by_command = {}
        self.faults = []
        self.calls = []
        self.events = []
        self.deliveries = []
        self.cap_patch = {}          # (service|None) -> {feature: {...}}
        self.settings = {"latency_ms": 0, "dedup_enabled": True}
        self.counter = 0

    # ----------------------------------------------------------- Identität/Schlüssel
    @property
    def partner_id(self):
        return self.cfg["partner_id"]

    @property
    def tenant_id(self):
        return TENANT_OF[self.cfg["instance"]]

    def key_secret(self, key_id):  # abgeleitet aus dem Seed, nur lokal; kein Schlüssel im Repository
        return h(self.seed, self.cfg["instance"], "partner-key", key_id)

    def keys(self):
        return {KEY_CURRENT: self.key_secret(KEY_CURRENT), KEY_NEXT: self.key_secret(KEY_NEXT)}

    def binding(self):
        return {"partner_id": self.partner_id, "tenant_id": self.tenant_id, "allowed_types": list(EVENT_TYPES)}

    # ----------------------------------------------------------- Capabilities
    def matrix(self, service_ref=None):
        m = copy.deepcopy(self.cfg["matrix"])
        for scope in (None, service_ref):
            if scope is None:
                m.update(copy.deepcopy(self.cap_patch.get(None, {})))
            elif scope in self.cfg["services"]:
                m.update(copy.deepcopy(self.cfg["services"][scope].get("capability_overrides", {})))
                m.update(copy.deepcopy(self.cap_patch.get(scope, {})))
        if not self.settings["dedup_enabled"]:
            m["deduplicated_requests"] = F(False)
        return m

    def supports(self, service_ref, feature):
        return bool(self.matrix(service_ref)[feature]["supported"])

    def require(self, service_ref, feature):
        if not self.supports(service_ref, feature):
            raise ApiError(422, "CAPABILITY_NOT_SUPPORTED", f"{feature} wird von {self.cfg['instance']}/{service_ref} nicht unterstützt")

    # ----------------------------------------------------------- Hilfen
    def _log(self, **kw):
        kw["seq"] = len(self.calls) + 1
        kw["at"] = self.clock.now_iso()
        self.calls.append(kw)

    def _expire(self):
        now = self.clock.now()
        for r in self.reservations.values():
            if r["state"] == "HELD" and now >= parse_iso(r["expires_at"]):
                self._transition(r, "EXPIRED")

    def _transition(self, r, new):
        assert new in HOLD_TRANSITIONS[r["state"]], (r["state"], new)
        r["state"] = new
        self._emit_change(r, {"CONSUMED": "reservation.confirmed", "EXPIRED": "reservation.expired",
                              "RELEASED": "reservation.cancelled"}[new])

    def _emit_change(self, r, etype):
        """Jede Zustandsänderung erhöht die Aggregatversion um 1 und erzeugt genau ein Ereignis."""
        r["version"] += 1
        r["updated_at"] = self.clock.now_iso()
        self.events.append(self.build_event(etype, r["federation_ref"], r["version"],
                                            {"reservation_id": r["reservation_id"], "state": r["state"],
                                             "booking_state": r["booking_state"]}))

    def build_event(self, etype, fed_ref, version, payload, event_id=None):
        n = len(self.events) + 1
        return {"event_id": event_id or "pev_" + h(self.seed, self.cfg["instance"], "event", n)[:16],
                "event_type": etype, "occurred_at": self.clock.now_iso(), "tenant_id": self.tenant_id,
                "partner_id": self.partner_id, "aggregate_ref": fed_ref, "aggregate_version": version, "payload": payload}

    def _used(self, service_ref):
        used = {"seats": 0, "wheelchair": 0}
        for r in self.reservations.values():
            if r["service_ref"] != service_ref:
                continue
            active = r["state"] == "HELD" or (r["state"] == "CONSUMED" and r["booking_state"] == "CONFIRMED")
            if active:
                for k in used:
                    used[k] += r["resource_vector"][k]
        return used

    def _service(self, ref):
        if ref not in self.cfg["services"]:
            raise ApiError(404, "UNKNOWN_SERVICE", f"unbekannter Dienst {ref}")
        return self.cfg["services"][ref]

    def _resolve(self, ident, caller):
        cands = [r for r in self.reservations.values()
                 if ident in (r["reservation_id"], r["federation_ref"], r["command_id"])]
        cands = [r for r in cands if r["caller"] == caller]
        if not cands:
            raise ApiError(404, "UNKNOWN_REFERENCE", "unbekannte Referenz")
        # bei abgeschalteter Deduplikation (mehrere Treffer) wird der jüngste geliefert; matches zeigt die Anzahl
        return cands[-1], len(cands)

    def _vector(self, body):
        rv = body.get("resource_vector", {"seats": 1, "wheelchair": 0})
        if not isinstance(rv, dict):
            raise ApiError(400, "INVALID_REQUEST", "resource_vector muss ein Objekt sein")
        out = {}
        for k in ("seats", "wheelchair"):
            v = rv.get(k, 0)
            if isinstance(v, bool) or not isinstance(v, int) or v < 0:
                raise ApiError(400, "INVALID_REQUEST", f"{k} muss eine nichtnegative Ganzzahl sein")
            out[k] = v
        if out["seats"] + out["wheelchair"] == 0:
            raise ApiError(400, "INVALID_REQUEST", "mindestens ein Platz erforderlich")
        return out

    def _view(self, r, matches=1):
        v = {k: r[k] for k in ("reservation_id", "federation_ref", "service_ref", "state", "booking_state", "expires_at",
                               "resource_vector", "version", "price_minor", "currency", "refunded_minor",
                               "ticket_ref", "hold_ttl_seconds")}
        v["matches"] = matches
        v["server_time"] = self.clock.now_iso()
        return v

    # ----------------------------------------------------------- Fehlersteuerung
    def add_fault(self, spec):
        op, mode = spec.get("operation"), spec.get("mode")
        if op not in OPS or mode not in FAULT_MODES:
            raise BadRequest(f"operation in {OPS}, mode in {FAULT_MODES}", "INVALID_FAULT")
        f = {"operation": op, "ref": spec.get("ref", "*"), "mode": mode, "times": int(spec.get("times", 1)),
             "hold_seconds": float(spec.get("hold_seconds", 0)), "fired": 0}
        with self.lock:
            self.faults.append(f)
        return f

    def _take_fault(self, op, refs):
        for f in self.faults:
            if f["operation"] == op and f["ref"] in ("*", *refs) and (f["times"] < 0 or f["fired"] < f["times"]):
                f["fired"] += 1
                return f

    # ----------------------------------------------------------- Operationen
    def handle(self, op, caller, ident, body):
        if self.settings["latency_ms"]:
            time.sleep(self.settings["latency_ms"] / 1000)
        with self.lock:
            if caller not in self.callers:
                self._log(operation=op, caller=caller, status=403)
                raise ApiError(403, "CALLER_NOT_ALLOWED", "Maschinenidentität nicht zugelassen")
            self._expire()
            refs = [x for x in (ident, body.get("federation_ref"), body.get("command_id")) if x]
            fault = self._take_fault(op, refs)
            entry = {"operation": op, "caller": caller, "ref": ident or body.get("federation_ref"),
                     "command_id": body.get("command_id"), "fault": fault["mode"] if fault else None,
                     "effect_applied": False, "replayed": False, "status": None, "response_dropped": False}
            if fault and fault["mode"] == "timeout":
                entry["response_dropped"] = True
                self._log(**entry)
                raise DropConnection(fault["hold_seconds"])
            if fault and fault["mode"] == "unavailable":
                entry["status"] = 503
                self._log(**entry)
                return Response(503, {"code": "PARTNER_UNAVAILABLE"})
            if fault and fault["mode"] == "reject":
                entry["status"] = 422
                self._log(**entry)
                return Response(422, {"code": "REJECTED_BY_PARTNER", "message": "definitive Ablehnung (Fehlermodus)"})
            try:
                status, out, replay = getattr(self, "_op_" + op)(caller, ident, body, entry)
            except ApiError as e:
                status, out, replay = e.status, {"code": e.code, "message": e.message}, False
            entry.update(status=status, replayed=replay)
            if fault and fault["mode"] == "lost_response":
                entry["response_dropped"] = True
                self._log(**entry)
                raise DropConnection(fault["hold_seconds"])
            self._log(**entry)
            return Response(status, out, {"Idempotent-Replayed": "true"} if replay else {})

    def _op_offers(self, caller, _ident, body, entry):
        sref = body.get("service_ref")
        svc = self._service(sref)
        self.require(sref, "information")
        rv = self._vector(body)
        free = {k: svc["capacity"][k] - self._used(sref)[k] for k in ("seats", "wheelchair")}
        if any(rv[k] > free[k] for k in rv):
            return 200, {"information_only": not self.supports(sref, "hold"), "offers": []}, False
        base = parse_iso(body["earliest_at"]) if body.get("earliest_at") else self.clock.now()
        binding = self.supports(sref, "binding_prices")
        bookable = self.supports(sref, "hold")
        offer = {"offer_id": "off_" + h(self.seed, self.cfg["instance"], "offer", sref, iso(base), rv["seats"], rv["wheelchair"])[:16],
                 "service_ref": sref, "window": {"earliest_at": iso(base), "latest_at": iso(base + timedelta(minutes=15))},
                 "price_minor": svc["price_minor"], "currency": svc["currency"], "binding": binding,
                 "bookable": bookable, "valid_until": iso(self.clock.now() + timedelta(seconds=HOLD_TTL_DEFAULT))}
        return 200, {"information_only": not bookable, "offers": [offer]}, False

    def _op_reserve(self, caller, _ident, body, entry):
        sref = body.get("service_ref")
        svc = self._service(sref)
        for k in ("command_id", "federation_ref"):
            if not isinstance(body.get(k), str) or not body[k]:
                raise ApiError(400, "INVALID_REQUEST", f"{k} fehlt")
        self.require(sref, "hold")
        rv = self._vector(body)
        dedup = self.supports(sref, "deduplicated_requests")
        if dedup:
            stored = self.by_command.get((caller, body["command_id"]))
            if stored:
                r = self.reservations[stored]
                entry["replayed"] = True
                return 201, self._view(r), True
            same_ref = [r for r in self.reservations.values() if r["federation_ref"] == body["federation_ref"]
                        and r["caller"] == caller and r["state"] in ("HELD", "CONSUMED")]
            if same_ref:
                return 200, self._view(same_ref[-1]), True
        ttl = body.get("hold_ttl_seconds", HOLD_TTL_DEFAULT)
        if isinstance(ttl, bool) or not isinstance(ttl, int) or not 1 <= ttl <= HOLD_TTL_MAX:
            raise ApiError(400, "INVALID_REQUEST", f"hold_ttl_seconds muss zwischen 1 und {HOLD_TTL_MAX} liegen")
        used = self._used(sref)
        short = [k for k in rv if used[k] + rv[k] > svc["capacity"][k]]
        if short:
            raise ApiError(409, "NO_CAPACITY", "Kapazität erschöpft: " + ",".join(short))
        self.counter += 1
        now = self.clock.now()
        r = {"reservation_id": "res_" + h(self.seed, self.cfg["instance"], "res", self.counter)[:16],
             "federation_ref": body["federation_ref"], "command_id": body["command_id"], "caller": caller,
             "service_ref": sref, "state": "HELD", "booking_state": "NONE", "resource_vector": rv,
             "hold_ttl_seconds": ttl, "expires_at": iso(now + timedelta(seconds=ttl)), "version": 0,
             "price_minor": svc["price_minor"], "currency": svc["currency"], "refunded_minor": 0, "ticket_ref": None,
             "updated_at": iso(now), "created_at": iso(now)}
        self.reservations[r["reservation_id"]] = r
        self.by_command[(caller, body["command_id"])] = r["reservation_id"]
        entry["effect_applied"] = True
        return 201, self._view(r), False

    def _op_confirm(self, caller, ident, body, entry):
        r, n = self._resolve(ident, caller)
        self.require(r["service_ref"], "confirm")
        if r["state"] == "CONSUMED" and r["booking_state"] == "CONFIRMED":
            return 200, self._view(r, n), True      # wiederholte Bestätigung: dieselbe Reservierung, keine zweite
        if r["state"] == "EXPIRED":
            raise ApiError(409, "HOLD_EXPIRED", "Hold abgelaufen (now >= expires_at)")
        if r["state"] == "RELEASED" or r["booking_state"] == "CANCELLED":
            raise ApiError(409, "HOLD_RELEASED", "Hold freigegeben oder storniert")
        r["booking_state"] = "CONFIRMED"
        if self.supports(r["service_ref"], "ticket_issuance"):
            r["ticket_ref"] = "tkt_" + h(self.seed, self.cfg["instance"], "ticket", r["reservation_id"])[:12]
        self._transition(r, "CONSUMED")
        entry["effect_applied"] = True
        return 200, self._view(r, n), False

    def _op_cancel(self, caller, ident, body, entry):
        r, n = self._resolve(ident, caller)
        self.require(r["service_ref"], "cancel")
        if r["state"] == "EXPIRED":
            raise ApiError(409, "HOLD_EXPIRED", "Hold abgelaufen; nichts zu stornieren")
        if r["state"] == "RELEASED" or r["booking_state"] == "CANCELLED":
            return 200, self._view(r, n), True
        if r["state"] == "HELD":
            self._transition(r, "RELEASED")
        else:  # CONSUMED und bestätigt: Buchung stornieren, Kapazität wird frei
            r["booking_state"] = "CANCELLED"
            self._emit_change(r, "reservation.cancelled")
        entry["effect_applied"] = True
        return 200, self._view(r, n), False

    def _op_extend(self, caller, ident, body, entry):
        r, n = self._resolve(ident, caller)
        self.require(r["service_ref"], "hold_extension")
        if r["state"] != "HELD":
            raise ApiError(409, "HOLD_NOT_ACTIVE", f"Holdverlängerung nur im Zustand HELD, nicht {r['state']}")
        add = body.get("extra_ttl_seconds")
        if isinstance(add, bool) or not isinstance(add, int) or add <= 0:
            raise ApiError(400, "INVALID_REQUEST", "extra_ttl_seconds muss eine positive Ganzzahl sein")
        new_exp = parse_iso(r["expires_at"]) + timedelta(seconds=add)
        if new_exp > parse_iso(r["created_at"]) + timedelta(seconds=HOLD_TTL_MAX):
            raise ApiError(422, "HOLD_TTL_LIMIT", f"Gesamtdauer über {HOLD_TTL_MAX} s")
        r["expires_at"] = iso(new_exp)
        entry["effect_applied"] = True
        return 200, self._view(r, n), False

    def _op_refund(self, caller, ident, body, entry):
        r, n = self._resolve(ident, caller)
        self.require(r["service_ref"], "refund")
        amt = body.get("amount_minor")
        if isinstance(amt, bool) or not isinstance(amt, int) or amt <= 0:
            raise ApiError(400, "INVALID_AMOUNT", "amount_minor muss eine positive Ganzzahl sein")
        if r["state"] != "CONSUMED":
            raise ApiError(409, "NOT_CONFIRMED", "Refund nur für bestätigte Buchungen")
        if r["refunded_minor"] + amt > r["price_minor"]:
            raise ApiError(422, "AMOUNT_EXCEEDS_PRICE", "Erstattung über Preis")
        r["refunded_minor"] += amt
        self._emit_change(r, "reservation.refunded")
        entry["effect_applied"] = True
        return 200, self._view(r, n), False

    def _op_status(self, caller, ident, body, entry):
        r, n = self._resolve(ident, caller)
        self.require(r["service_ref"], "status_lookup")
        return 200, self._view(r, n), False

    # ----------------------------------------------------------- Ausgehende Ereignisse
    def _signed(self, ev, mode, key_id=KEY_CURRENT, ts=None):
        raw = json.dumps(ev, sort_keys=True, separators=(",", ":")).encode()
        if mode == "oversize":
            pad = dict(ev, payload=dict(ev["payload"], padding="x" * (MAX_BODY_BYTES + 1024)))
            raw = json.dumps(pad, sort_keys=True, separators=(",", ":")).encode()
        now = self.clock.epoch()
        ts = now if ts is None else ts
        secret = self.key_secret(key_id)
        if mode == "stale_timestamp":
            ts = now - 3600
        elif mode == "future_timestamp":
            ts = now + 3600
        elif mode == "unknown_key_id":
            key_id, secret = KEY_UNKNOWN, h(self.seed, "unbekannt")
        sig = sign(secret if mode != "bad_signature" else h(self.seed, "falsch"), ts, raw)
        return {"headers": {"X-Partner-Key-Id": key_id, "X-Partner-Timestamp": str(ts), "X-Partner-Signature": "v1=" + sig},
                "body": raw}

    def plan_deliveries(self, ev, mode, repeat=1, versions=None):
        """Liefert die zu sendenden Requests (Liste von {headers, body}) für einen Fehlermodus."""
        if mode not in EVENT_MODES:
            raise BadRequest(f"mode in {EVENT_MODES}", "INVALID_MODE")
        if repeat < 1 or repeat > 100:
            raise BadRequest("repeat muss zwischen 1 und 100 liegen", "INVALID_REPEAT")
        e = copy.deepcopy(ev)
        if mode == "tenant_mismatch":
            e["tenant_id"] = OTHER_TENANT
        if mode == "disallowed_type":
            e["event_type"] = "payroll.exported"
        if mode == "duplicate":
            return [self._signed(e, "valid") for _ in range(repeat)]
        if mode == "same_id_changed_payload":
            changed = copy.deepcopy(e)
            changed["payload"] = dict(changed["payload"], manipuliert=True)
            return [self._signed(e, "valid"), self._signed(changed, "valid")]
        if mode in ("out_of_order", "version_gap"):
            vs = versions or ([2, 1] if mode == "out_of_order" else [1, 3])
            out = []
            for i, v in enumerate(vs):
                x = copy.deepcopy(e)
                x["aggregate_version"] = v
                x["event_id"] = "pev_" + h(self.seed, e["event_id"], mode, i)[:16]
                out.append(self._signed(x, "valid"))
            return out
        if mode == "rotation":   # alter und neuer Schlüssel gleichzeitig gültig
            return [self._signed(e, "valid", KEY_CURRENT), self._signed(e, "valid", KEY_NEXT)]
        return [self._signed(e, mode)]

    def emit(self, ev, mode, target_url=None, repeat=1, versions=None, dry_run=False):
        plans = self.plan_deliveries(ev, mode, repeat, versions)
        out = []
        for i, p in enumerate(plans):
            rec = {"mode": mode, "delivery": i + 1, "of": len(plans), "event_id": ev["event_id"], "target": target_url,
                   "http_status": None, "error": None, "at": self.clock.now_iso()}
            if not dry_run:
                if not target_url:
                    raise BadRequest("target_url fehlt (oder dry_run=true)", "NO_TARGET")
                try:
                    rec["http_status"], _, _, _ = http_call("POST", target_url, headers=p["headers"], raw_body=p["body"], timeout=5)
                except NoResponse as ex:
                    rec["error"] = str(ex)
            with self.lock:
                rec["seq"] = len(self.deliveries) + 1
                self.deliveries.append(rec)
            out.append(dict(rec, headers=p["headers"], body_bytes=len(p["body"]), body=p["body"].decode() if dry_run else None))
        return out


# ------------------------------------------------------------------------------ HTTP
def build_app(sb: PartnerSandbox, host="127.0.0.1", port=0) -> App:
    app = App(host, port)

    def caller(req):
        return req.header("x-caller-id", "")

    def op(name, with_id=True):
        def run(req):
            body = req.json() if req.method == "POST" else {}
            ident = req.match.groupdict().get("rid")
            try:
                return sb.handle(name, caller(req), ident, body)
            except ApiError as e:
                return Response(e.status, {"code": e.code, "message": e.message})
        return run

    app.route("POST", r"/v1/partner/offers")(op("offers"))
    app.route("POST", r"/v1/partner/reservations")(op("reserve"))
    app.route("POST", r"/v1/partner/reservations/(?P<rid>[^/]+)/confirm")(op("confirm"))
    app.route("POST", r"/v1/partner/reservations/(?P<rid>[^/]+)/cancel")(op("cancel"))
    app.route("POST", r"/v1/partner/reservations/(?P<rid>[^/]+)/extend")(op("extend"))
    app.route("POST", r"/v1/partner/reservations/(?P<rid>[^/]+)/refund")(op("refund"))
    app.route("GET", r"/v1/partner/reservations/(?P<rid>[^/]+)/status")(op("status"))

    @app.route("GET", r"/v1/partner/capabilities")
    def capabilities(req):
        if sb.settings["latency_ms"]:
            time.sleep(sb.settings["latency_ms"] / 1000)
        with sb.lock:
            if caller(req) not in sb.callers:
                return Response(403, {"code": "CALLER_NOT_ALLOWED"})
            sref = req.query.get("service_ref")
            if sref and sref not in sb.cfg["services"]:
                return Response(404, {"code": "UNKNOWN_SERVICE"})
            body = {"instance": sb.cfg["instance"], "operator": sb.cfg["operator"], "partner_id": sb.partner_id,
                    "matrix": sb.matrix(sref), "service_ref": sref}
            if not sref:
                body["services"] = {s: {"matrix": sb.matrix(s), "capacity": v["capacity"]} for s, v in sb.cfg["services"].items()}
            return Response(200, body)

    @app.route("GET", r"/control/clock")
    def clock_get(_r):
        return Response(200, sb.clock.snapshot())

    @app.route("POST", r"/control/clock")
    def clock_set(req):
        try:
            with sb.lock:
                out = sb.clock.control(req.json())
                sb._expire()
            return Response(200, out)
        except ValueError as e:
            raise BadRequest(str(e), "INVALID_CLOCK")

    @app.route("POST", r"/control/faults")
    def fault_add(req):
        return Response(201, sb.add_fault(req.json()))

    @app.route("DELETE", r"/control/faults")
    def fault_clear(_r):
        with sb.lock:
            sb.faults.clear()
        return Response(200, {"faults": []})

    @app.route("POST", r"/control/config")
    def cfg(req):
        b = req.json()
        with sb.lock:
            if "latency_ms" in b:
                sb.settings["latency_ms"] = int(b["latency_ms"])
            if "dedup_enabled" in b:
                sb.settings["dedup_enabled"] = bool(b["dedup_enabled"])
            if "status_enabled" in b:   # abgeschaltete Statusfähigkeit
                sb.cap_patch.setdefault(b.get("service_ref"), {})["status_lookup"] = F(bool(b["status_enabled"]))
            if "capabilities" in b:
                for feat, val in b["capabilities"].items():
                    if feat not in FEATURES:
                        raise BadRequest(f"unbekanntes Merkmal {feat}")
                    sb.cap_patch.setdefault(b.get("service_ref"), {})[feat] = val
            return Response(200, {"settings": sb.settings, "cap_patch": sb.cap_patch})

    @app.route("POST", r"/control/reset")
    def reset(_r):
        with sb.lock:
            sb.reset_state()
        return Response(200, {"reset": True})

    @app.route("GET", r"/control/log")
    def log(_r):
        with sb.lock:
            return Response(200, {"calls": list(sb.calls), "deliveries": list(sb.deliveries)})

    @app.route("GET", r"/control/events")
    def events(_r):
        with sb.lock:
            return Response(200, {"events": list(sb.events)})

    @app.route("GET", r"/control/keys")
    def keys(_r):
        return Response(200, {"keys": sb.keys(), "binding": sb.binding(), "note": "nur lokal: aus dem Seed abgeleitete Testschlüssel"})

    @app.route("POST", r"/control/events/emit")
    def emit(req):
        b = req.json()
        with sb.lock:
            ev = None
            if b.get("event_id"):
                ev = next((e for e in sb.events if e["event_id"] == b["event_id"]), None)
            elif isinstance(b.get("event"), dict):
                ev = b["event"]
            elif sb.events:
                ev = sb.events[-1]
        if ev is None:
            return Response(404, {"code": "UNKNOWN_EVENT"})
        res = sb.emit(ev, b.get("mode", "valid"), b.get("target_url"), int(b.get("repeat", 1)), b.get("versions"),
                      bool(b.get("dry_run")))
        return Response(200, {"deliveries": res})

    return app


def launch(instances=("A", "B", "C"), seed=1, clock_start=DEFAULT_START, host="127.0.0.1", base_port=0):
    """Startet Instanzen parallel (je eigener Port). -> {name: (sandbox, app)}"""
    out = {}
    for i, name in enumerate(instances):
        sb = PartnerSandbox(PRESETS[name], seed, clock_start)
        app = build_app(sb, host, base_port + i if base_port else 0)
        app.start()
        out[name] = (sb, app)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--instances", default="A,B,C")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--base-port", type=int, default=18100)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--clock-start", default=DEFAULT_START)
    a = ap.parse_args(argv)
    parse_iso(a.clock_start)
    running = launch(a.instances.split(","), a.seed, a.clock_start, a.host, a.base_port)
    for n, (_, app) in running.items():
        print(f"Sandbox-Partner {n} (nur Testnetz) auf {app.url}", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
