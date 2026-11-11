#!/usr/bin/env python3
"""PSP-Simulator (WP-M0-23): deterministischer Zahlungsanbieter-Simulator mit steuerbaren Fehlern.

NUR TESTINFRASTRUKTUR. Nur im Testnetz erreichbar (Bind auf Loopback/private Adressen), in keiner Produktions-
konfiguration referenziert (siehe test_psp_sim.py::test_nicht_in_produktionskonfiguration). Anbieterneutral (D-03).
Die Schnittstelle ist zugleich der Entwurf des internen PSP-Ports für backend/adapters/psp (Bestätigung in WP-M0-29).

Start:  python tests/sandbox/psp/psp_sim.py --port 18091 --seed 1 --clock-start 2026-01-01T00:00:00Z
Port-Schnittstelle (JSON, Beträge als Ganzzahl in kleinster Einheit):
  POST /psp/v1/payments                       Authorize (Header Idempotency-Key Pflicht)
  POST /psp/v1/payments/{id}/capture|void|refund
  GET  /psp/v1/payments/{id}                  Statusabfrage nach payment_id oder provider_ref
  GET  /psp/v1/payments?merchant_ref=...      Statusabfrage nach ursprünglicher Händlerreferenz (auch operation_ref=)
  GET  /psp/v1/capabilities
Steuerung (nur Testnetz): /control/... (siehe openapi/psp-sim.yaml).
"""
import argparse
import hashlib
import hmac
import json
import sys
import threading
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "lib"))
from taktovia_testkit.jsonhttp import App, BadRequest, DropConnection, NoResponse, Response, http_call  # noqa: E402
from taktovia_testkit.testclock import DEFAULT_START, SimClock, iso, parse_iso  # noqa: E402

PROVIDER = "simpsp"
MAX_REPEAT = 100
STATES = ["CREATED", "REQUIRES_ACTION", "AUTHORIZED", "CAPTURE_PENDING", "CAPTURED", "AUTHORIZATION_FAILED",
          "VOID_PENDING", "VOIDED", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED"]
# Erlaubte Übergänge nach Kap. 03 §4.2. CAPTURE_UNKNOWN/RECONCILING entstehen auf der Aufruferseite; DISPUTED ist
# ein getrenntes Feld (dispute_state), kein Zustand dieses Automaten.
TRANSITIONS = {
    "CREATED": {"REQUIRES_ACTION", "AUTHORIZED", "AUTHORIZATION_FAILED"},
    "REQUIRES_ACTION": {"AUTHORIZED", "AUTHORIZATION_FAILED"},
    "AUTHORIZED": {"CAPTURE_PENDING", "CAPTURED", "VOID_PENDING", "VOIDED"},
    "CAPTURE_PENDING": {"CAPTURED"},
    "CAPTURED": {"REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED"},
    "VOID_PENDING": {"VOIDED"},
    "REFUND_PENDING": {"PARTIALLY_REFUNDED", "REFUNDED"},
    "PARTIALLY_REFUNDED": {"REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED"},
    "AUTHORIZATION_FAILED": set(), "VOIDED": set(), "REFUNDED": set(),
}
OPERATIONS = ("authorize", "capture", "void", "refund", "status")
FAULT_MODES = ("timeout", "lost_response", "duplicate", "decline", "unavailable")
EVENT_TYPES = ["payment.requires_action", "payment.authorized", "payment.authorization_failed",
               "payment.capture_pending", "payment.captured", "payment.void_pending", "payment.voided",
               "payment.refund_pending", "payment.partially_refunded", "payment.refunded", "payment.dispute.opened",
               "payment.dispute.won", "payment.dispute.lost"]


class ApiError(Exception):
    def __init__(self, status, code, message=""):
        super().__init__(message or code)
        self.status, self.code, self.message = status, code, message or code


def h(*parts):
    return hashlib.sha256(":".join(str(p) for p in parts).encode()).hexdigest()


def money(v, field="amount_minor"):
    if isinstance(v, bool) or not isinstance(v, int):
        raise ApiError(400, "INVALID_AMOUNT", f"{field} muss eine Ganzzahl in kleinster Einheit sein (kein Gleitkomma)")
    if v <= 0 or v > 10 ** 12:
        raise ApiError(400, "INVALID_AMOUNT", f"{field} muss zwischen 1 und 10^12 liegen")
    return v


def sign(secret: str, timestamp: int, raw_body: bytes) -> str:
    return hmac.new(secret.encode(), str(timestamp).encode() + b"." + raw_body, hashlib.sha256).hexdigest()


def verify_signature(secret: str, header: str, raw_body: bytes, now_epoch: int, tolerance_s: int = 300):
    """Referenzprüfung des Empfängers für die PSP-Signatur (Format: 't=<epoch>,v1=<hex>'). -> (ok, grund)."""
    try:
        kv = dict(p.split("=", 1) for p in header.split(","))
        t, sig = int(kv["t"]), kv["v1"]
    except (KeyError, ValueError, AttributeError):
        return False, "MALFORMED_SIGNATURE"
    if not hmac.compare_digest(sig, sign(secret, t, raw_body)):
        return False, "BAD_SIGNATURE"
    if abs(now_epoch - t) > tolerance_s:
        return False, "TIMESTAMP_OUT_OF_WINDOW"
    return True, "OK"


class PspSimulator:
    def __init__(self, seed=1, clock_start=DEFAULT_START):
        self.lock = threading.RLock()
        self.seed = seed
        self.clock = SimClock(clock_start)
        self._init_state()

    def _init_state(self):
        self.payments = {}      # payment_id -> dict
        self.by_merchant = {}   # merchant_ref -> payment_id
        self.by_opref = {}      # operation_ref -> payment_id
        self.idem = {}          # key -> dict
        self.faults = []        # Liste von Fault-Dicts
        self.calls = []
        self.events = []
        self.deliveries = []
        self.counter = 0
        self.config = {"webhook_url": None, "webhook_auto": False, "idempotency_ttl_s": 86400,
                       "sca_action_ttl_s": 900, "settle_delay_s": {"capture": 0, "void": 0, "refund": 0}}

    @property
    def webhook_secret(self):  # abgeleitet, nur lokal; kein Geheimnis im Repository
        return h(self.seed, "webhook-secret-nur-lokal")

    # ---------------------------------------------------------------- Hilfen
    def _next(self, prefix):
        self.counter += 1
        return f"{prefix}_{h(self.seed, prefix, self.counter)[:16]}"

    def _log(self, **kw):
        kw["seq"] = len(self.calls) + 1
        kw["at"] = self.clock.now_iso()
        self.calls.append(kw)
        return kw

    def _view(self, p):
        v = {k: p[k] for k in ("payment_id", "provider_ref", "merchant_ref", "state", "currency", "authorized_minor",
                               "captured_minor", "refunded_minor", "dispute_state", "decline_code", "action",
                               "created_at", "updated_at", "payment_method")}
        v["pending"] = p["pending"]
        v["operations"] = list(p["operations"])
        v["provider"] = PROVIDER
        return v

    def _set_state(self, p, new):
        if new != p["state"]:
            if new not in TRANSITIONS[p["state"]]:
                raise AssertionError(f"unzulässiger Übergang {p['state']} -> {new}")
            p["state"] = new
        p["updated_at"] = self.clock.now_iso()
        self._emit(p, "payment." + new.lower())

    def _emit(self, p, etype):
        seq = len(self.events) + 1
        ev = {"event_id": "evt_" + h(self.seed, "event", seq)[:16], "sequence": seq, "provider": PROVIDER,
              "type": etype, "created_at": self.clock.now_iso(),
              "data": {"payment_id": p["payment_id"], "provider_ref": p["provider_ref"],
                       "merchant_ref": p["merchant_ref"], "state": p["state"], "currency": p["currency"],
                       "authorized_minor": p["authorized_minor"], "captured_minor": p["captured_minor"],
                       "refunded_minor": p["refunded_minor"], "dispute_state": p["dispute_state"]}}
        self.events.append(ev)
        if self.config["webhook_auto"] and self.config["webhook_url"]:
            threading.Thread(target=self.deliver, args=(ev,), kwargs={"mode": "valid", "repeat": 1}, daemon=True).start()
        return ev

    def _settle_due(self):
        now = self.clock.epoch()
        for p in self.payments.values():
            if p["state"] == "REQUIRES_ACTION" and p["action"] and now >= p["action"]["_expires_epoch"]:
                p["decline_code"] = "sca_expired"
                self._set_state(p, "AUTHORIZATION_FAILED")
            pend = p["pending"]
            if pend and now >= pend["_due_epoch"]:
                self._apply_pending(p)

    def _apply_pending(self, p):
        pend, p["pending"] = p["pending"], None
        if pend["kind"] == "capture":
            p["captured_minor"] = pend["amount_minor"]
            self._set_state(p, "CAPTURED")
        elif pend["kind"] == "void":
            self._set_state(p, "VOIDED")
        elif pend["kind"] == "refund":
            p["refunded_minor"] += pend["amount_minor"]
            self._set_state(p, "REFUNDED" if p["refunded_minor"] == p["captured_minor"] else "PARTIALLY_REFUNDED")

    def settle_all(self):
        with self.lock:
            for p in self.payments.values():
                if p["pending"]:
                    self._apply_pending(p)
            self._settle_due()

    def _find(self, ident):
        pid = ident if ident in self.payments else None
        if pid is None:
            for p in self.payments.values():
                if p["provider_ref"] == ident:
                    pid = p["payment_id"]
                    break
        if pid is None:
            raise ApiError(404, "UNKNOWN_REFERENCE", f"unbekannte Zahlung {ident}")
        return self.payments[pid]

    # ---------------------------------------------------------------- Fehlersteuerung
    def add_fault(self, spec):
        op, mode = spec.get("operation"), spec.get("mode")
        if op not in OPERATIONS or mode not in FAULT_MODES:
            raise BadRequest(f"operation in {OPERATIONS}, mode in {FAULT_MODES}", "INVALID_FAULT")
        if mode in ("decline",) and op in ("status",):
            raise BadRequest("decline gilt nicht für status", "INVALID_FAULT")
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
        return None

    # ---------------------------------------------------------------- Operationen (unter Lock aufrufen)
    def _fingerprint(self, op, ident, body):
        return h(op, ident, json.dumps(body, sort_keys=True))

    def execute(self, op, ident, body, key):
        """Führt eine Port-Operation mit Idempotenz und Fehlersteuerung aus. -> (Response, Drop|None)."""
        if op != "status" and not key:
            raise ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", "Header Idempotency-Key ist Pflicht")
        with self.lock:
            self._settle_due()
            refs = []
            if op == "authorize":
                refs.append(body.get("merchant_ref"))
            elif op != "status" or ident:
                try:
                    refs.append(self._find(ident)["merchant_ref"])
                except ApiError:
                    pass
            if body.get("operation_ref"):
                refs.append(body["operation_ref"])
            fault = self._take_fault(op, [r for r in refs if r])
            entry = {"operation": op, "merchant_ref": refs[0] if refs else None, "operation_ref": body.get("operation_ref"),
                     "payment_id": ident if op != "authorize" else None, "idempotency_key": key,
                     "amount_minor": body.get("amount_minor"), "fault": fault["mode"] if fault else None,
                     "effect_applied": False, "replayed": False, "status": None, "response_dropped": False}
            if fault and fault["mode"] == "timeout":     # Wirkung NICHT angewandt, keine Antwort
                entry["response_dropped"] = True
                self._log(**entry)
                return None, DropConnection(fault["hold_seconds"])
            if fault and fault["mode"] == "unavailable":
                entry["status"] = 503
                self._log(**entry)
                return Response(503, {"code": "PROVIDER_UNAVAILABLE"}, {"Retry-After": "1"}), None
            resp = self._run_idempotent(op, ident, body, key, entry, fault)
            if fault and fault["mode"] == "duplicate":    # Anfrage trifft ein zweites Mal ein (gleicher Schlüssel)
                dup = dict(entry, fault="duplicate_delivery", effect_applied=False)
                self._run_idempotent(op, ident, body, key, dup, None)
                self._log(**dup)
            if fault and fault["mode"] == "lost_response":  # Wirkung persistiert, Antwort verworfen
                entry["response_dropped"] = True
                self._log(**entry)
                return None, DropConnection(fault["hold_seconds"])
            self._log(**entry)
            return resp, None

    def _run_idempotent(self, op, ident, body, key, entry, fault):
        now = self.clock.epoch()
        rec = self.idem.get(key) if key else None
        fp = self._fingerprint(op, ident, body)
        if rec and rec["expires"] > now:
            if rec["fp"] != fp:
                entry["status"] = 422
                return Response(422, {"code": "IDEMPOTENCY_KEY_REUSE",
                                      "message": "gleicher Idempotenzschlüssel, anderer Request"})
            entry.update(replayed=True, status=rec["status"], payment_id=rec["body"].get("payment_id"))
            return Response(rec["status"], rec["body"], {"Idempotent-Replayed": "true"})
        try:
            status, out = getattr(self, "_op_" + op)(ident, body, fault, entry)
        except ApiError as e:
            status, out = e.status, {"code": e.code, "message": e.message}
        if key and op != "status" and (status < 400 or status in (402, 409, 422)):
            self.idem[key] = {"fp": fp, "status": status, "body": out,
                              "expires": now + self.config["idempotency_ttl_s"]}
        entry["status"] = status
        if isinstance(out, dict) and out.get("payment_id"):
            entry["payment_id"] = out["payment_id"]
            entry["outcome_state"] = out.get("state")
        return Response(status, out)

    def _op_authorize(self, _ident, body, fault, entry):
        mref = body.get("merchant_ref")
        if not isinstance(mref, str) or not mref:
            raise ApiError(400, "INVALID_REQUEST", "merchant_ref fehlt")
        amount = money(body.get("amount_minor"))
        cur = body.get("currency")
        if not (isinstance(cur, str) and len(cur) == 3 and cur.isupper()):
            raise ApiError(400, "INVALID_CURRENCY", "currency muss ein dreistelliger Großbuchstaben-Code sein")
        pm = body.get("payment_method", "pm_ok")
        if pm not in ("pm_ok", "pm_decline", "pm_sca"):
            raise ApiError(422, "UNKNOWN_PAYMENT_METHOD", "Testzahlungsmittel: pm_ok, pm_decline, pm_sca")
        if mref in self.by_merchant:
            raise ApiError(409, "DUPLICATE_MERCHANT_REF", "Händlerreferenz existiert; kein zweiter Charge auf Verdacht")
        now = self.clock.now_iso()
        p = {"payment_id": self._next("pay"), "provider_ref": self._next("psp"), "merchant_ref": mref,
             "state": "CREATED", "currency": cur, "authorized_minor": amount, "captured_minor": 0, "refunded_minor": 0,
             "dispute_state": None, "decline_code": None, "action": None, "created_at": now, "updated_at": now,
             "payment_method": pm, "pending": None, "operations": []}
        self.payments[p["payment_id"]] = p
        self.by_merchant[mref] = p["payment_id"]
        if body.get("operation_ref"):
            self.by_opref[body["operation_ref"]] = p["payment_id"]
        if (fault and fault["mode"] == "decline") or pm == "pm_decline":
            p["authorized_minor"] = amount
            p["decline_code"] = "card_declined"
            self._set_state(p, "AUTHORIZATION_FAILED")
        elif pm == "pm_sca":
            ttl = self.config["sca_action_ttl_s"]
            exp = self.clock.now() + timedelta(seconds=ttl)
            p["action"] = {"type": "SCA_3DS", "expires_at": iso(exp), "_expires_epoch": int(exp.timestamp()),
                           "ttl_seconds": ttl}
            self._set_state(p, "REQUIRES_ACTION")
        else:
            self._set_state(p, "AUTHORIZED")
        p["operations"].append({"kind": "authorize", "operation_ref": body.get("operation_ref"), "amount_minor": amount,
                                "idempotency_key": entry["idempotency_key"], "at": now, "result": p["state"]})
        entry["effect_applied"] = True
        return 201, self._view(p)

    def _guard_pending(self, p):
        if p["pending"]:
            raise ApiError(409, "OPERATION_IN_PROGRESS", f"{p['pending']['kind']} läuft noch")

    def _record(self, p, kind, body, entry, amount, result):
        p["operations"].append({"kind": kind, "operation_ref": body.get("operation_ref"), "amount_minor": amount,
                                "idempotency_key": entry["idempotency_key"], "at": self.clock.now_iso(), "result": result})
        if body.get("operation_ref"):
            self.by_opref[body["operation_ref"]] = p["payment_id"]
        entry["effect_applied"] = True

    def _start(self, p, kind, final_state, pending_state, body, entry, amount):
        delay = self.config["settle_delay_s"][kind]
        if delay > 0:
            p["pending"] = {"kind": kind, "amount_minor": amount, "_due_epoch": self.clock.epoch() + delay,
                            "due_at": None}
            self._set_state(p, pending_state)
        else:
            p["pending"] = {"kind": kind, "amount_minor": amount, "_due_epoch": 0}
            self._apply_pending(p)
        self._record(p, kind, body, entry, amount, p["state"])

    def _op_capture(self, ident, body, fault, entry):
        p = self._find(ident)
        self._guard_pending(p)
        if fault and fault["mode"] == "decline":
            raise ApiError(422, "DECLINED_BY_PROVIDER", "Capture definitiv abgelehnt")
        if p["state"] != "AUTHORIZED":
            raise ApiError(409, "INVALID_STATE", f"Capture nur aus AUTHORIZED, Zustand ist {p['state']}")
        amount = money(body["amount_minor"]) if "amount_minor" in body else p["authorized_minor"]
        if amount > p["authorized_minor"]:
            raise ApiError(422, "AMOUNT_EXCEEDS_AUTHORIZED", "erfasst darf autorisiert nicht übersteigen")
        self._start(p, "capture", "CAPTURED", "CAPTURE_PENDING", body, entry, amount)
        return 200, self._view(p)

    def _op_void(self, ident, body, fault, entry):
        p = self._find(ident)
        self._guard_pending(p)
        if fault and fault["mode"] == "decline":
            raise ApiError(422, "DECLINED_BY_PROVIDER", "Void definitiv abgelehnt")
        if p["state"] != "AUTHORIZED":
            raise ApiError(409, "INVALID_STATE", f"Void nur aus AUTHORIZED (nicht nach Capture/Teil-Capture), Zustand ist {p['state']}")
        self._start(p, "void", "VOIDED", "VOID_PENDING", body, entry, p["authorized_minor"])
        return 200, self._view(p)

    def _op_refund(self, ident, body, fault, entry):
        p = self._find(ident)
        self._guard_pending(p)
        if fault and fault["mode"] == "decline":
            raise ApiError(422, "DECLINED_BY_PROVIDER", "Refund definitiv abgelehnt")
        if p["state"] not in ("CAPTURED", "PARTIALLY_REFUNDED"):
            raise ApiError(409, "INVALID_STATE", f"Refund nur nach Capture, Zustand ist {p['state']}")
        amount = money(body.get("amount_minor"))
        if p["refunded_minor"] + amount > p["captured_minor"]:
            raise ApiError(422, "AMOUNT_EXCEEDS_CAPTURED", "erstattet darf erfasst nicht übersteigen")
        self._start(p, "refund", "REFUNDED", "REFUND_PENDING", body, entry, amount)
        return 200, self._view(p)

    def _op_status(self, ident, body, fault, entry):
        q = body.get("_query", {})
        if q.get("merchant_ref"):
            pid = self.by_merchant.get(q["merchant_ref"])
        elif q.get("operation_ref"):
            pid = self.by_opref.get(q["operation_ref"]) or self.by_merchant.get(q["operation_ref"])
        else:
            pid = self._find(ident)["payment_id"]
        if pid is None:
            raise ApiError(404, "UNKNOWN_REFERENCE", "keine Zahlung zu dieser Referenz")
        entry["effect_applied"] = False
        return 200, self._view(self.payments[pid])

    # ---------------------------------------------------------------- Streitfall und SCA (Steuerung)
    def open_dispute(self, payment_id):
        with self.lock:
            p = self._find(payment_id)
            if p["captured_minor"] <= 0:
                raise ApiError(409, "INVALID_STATE", "Streitfall nur nach Capture")
            p["dispute_state"] = "OPEN"
            p["updated_at"] = self.clock.now_iso()
            self._emit(p, "payment.dispute.opened")
            return self._view(p)

    def resolve_dispute(self, payment_id, outcome):
        with self.lock:
            p = self._find(payment_id)
            if p["dispute_state"] != "OPEN" or outcome not in ("WON", "LOST"):
                raise ApiError(409, "INVALID_STATE", "kein offener Streitfall oder Ergebnis ungültig")
            p["dispute_state"] = outcome
            self._emit(p, "payment.dispute." + outcome.lower())
            return self._view(p)

    def complete_sca(self, payment_id, outcome):
        """Simuliert die Rückkehr des Kunden aus SCA/3DS (verzögerte Rückkehr: vorher Uhr vorstellen)."""
        with self.lock:
            self._settle_due()
            p = self._find(payment_id)
            if p["state"] != "REQUIRES_ACTION":
                raise ApiError(409, "INVALID_STATE", f"keine SCA-Aktion offen, Zustand {p['state']}")
            if outcome == "success":
                p["action"] = None
                self._set_state(p, "AUTHORIZED")
            else:
                p["decline_code"] = "sca_failed"
                self._set_state(p, "AUTHORIZATION_FAILED")
            return self._view(p)

    # ---------------------------------------------------------------- Webhooks
    def deliver(self, event, target_url=None, mode="valid", repeat=1, late_seconds=900):
        if repeat < 1 or repeat > MAX_REPEAT:
            raise BadRequest(f"repeat muss zwischen 1 und {MAX_REPEAT} liegen", "INVALID_REPEAT")
        if mode not in ("valid", "bad_signature", "late"):
            raise BadRequest("mode: valid | bad_signature | late", "INVALID_MODE")
        url = target_url or self.config["webhook_url"]
        if not url:
            raise BadRequest("kein Ziel (target_url oder webhook_url)", "NO_TARGET")
        body = json.dumps(event, sort_keys=True, separators=(",", ":")).encode()
        results = []
        for i in range(repeat):
            now = self.clock.epoch()
            ts = now - int(late_seconds) if mode == "late" else now
            secret = self.webhook_secret if mode != "bad_signature" else h(self.seed, "falscher-schluessel")
            headers = {"X-PSP-Signature": f"t={ts},v1={sign(secret, ts, body)}", "X-PSP-Event-Id": event["event_id"]}
            status, err = None, None
            try:
                status, _, _, _ = http_call("POST", url, headers=headers, raw_body=body, timeout=5)
            except NoResponse as e:
                err = str(e)
            with self.lock:
                rec = {"seq": len(self.deliveries) + 1, "event_id": event["event_id"], "mode": mode, "delivery": i + 1,
                       "of": repeat, "target": url, "timestamp_header": ts, "http_status": status, "error": err,
                       "at": self.clock.now_iso()}
                self.deliveries.append(rec)
            results.append(rec)
        return results


# ------------------------------------------------------------------------------ HTTP
def build_app(sim: PspSimulator, host="127.0.0.1", port=0) -> App:
    app = App(host, port)

    def reply(resp_drop):
        resp, drop = resp_drop
        if drop:
            raise drop
        return resp

    def op(name):
        def run(req):
            body = req.json() if req.method == "POST" else {}
            ident = req.match.groupdict().get("pid")
            key = req.header("idempotency-key")
            try:
                return reply(sim.execute(name, ident, body, key))
            except ApiError as e:
                return Response(e.status, {"code": e.code, "message": e.message})
        return run

    app.route("POST", r"/psp/v1/payments")(op("authorize"))
    app.route("POST", r"/psp/v1/payments/(?P<pid>[^/]+)/capture")(op("capture"))
    app.route("POST", r"/psp/v1/payments/(?P<pid>[^/]+)/void")(op("void"))
    app.route("POST", r"/psp/v1/payments/(?P<pid>[^/]+)/refund")(op("refund"))

    @app.route("GET", r"/psp/v1/payments/(?P<pid>[^/]+)")
    def status_by_id(req):
        try:
            return reply(sim.execute("status", req.match["pid"], {}, None))
        except ApiError as e:
            return Response(e.status, {"code": e.code, "message": e.message})

    @app.route("GET", r"/psp/v1/payments")
    def status_by_ref(req):
        if not ({"merchant_ref", "operation_ref"} & set(req.query)):
            return Response(400, {"code": "INVALID_REQUEST", "message": "merchant_ref oder operation_ref angeben"})
        try:
            return reply(sim.execute("status", None, {"_query": req.query,
                                                      "operation_ref": req.query.get("merchant_ref")}, None))
        except ApiError as e:
            return Response(e.status, {"code": e.code, "message": e.message})

    @app.route("GET", r"/psp/v1/capabilities")
    def caps(_req):
        return Response(200, {"provider": PROVIDER, "idempotency_key": {"required": True, "ttl_seconds": sim.config["idempotency_ttl_s"]},
                              "status_by_merchant_ref": True, "partial_capture": True, "partial_refund": True,
                              "void_after_partial_capture": False, "multiple_captures": False,
                              "async_notifications": {"signature": "t=<epoch>,v1=hex(HMAC-SHA256(secret,t+'.'+body))",
                                                      "retry": "on demand (control)", "ordering": "sequence field"},
                              "sca": True, "split_payments": False, "states": STATES + ["DISPUTED(separat)"]})

    # --- Steuerschnittstelle
    @app.route("GET", r"/control/clock")
    def clock_get(_req):
        return Response(200, sim.clock.snapshot())

    @app.route("POST", r"/control/clock")
    def clock_set(req):
        try:
            with sim.lock:
                out = sim.clock.control(req.json())
                sim._settle_due()
            return Response(200, out)
        except ValueError as e:
            raise BadRequest(str(e), "INVALID_CLOCK")

    @app.route("POST", r"/control/reset")
    def reset(req):
        b = req.json()
        with sim.lock:
            sim.seed = b.get("seed", sim.seed)
            if "clock_start" in b:
                sim.clock = SimClock(b["clock_start"])
            sim._init_state()
        return Response(200, {"reset": True, "seed": sim.seed, "clock": sim.clock.snapshot()})

    @app.route("GET", r"/control/config")
    def cfg_get(_req):
        with sim.lock:
            return Response(200, dict(sim.config, webhook_secret=sim.webhook_secret, provider=PROVIDER, seed=sim.seed,
                                      note="nur lokal: Testschlüssel aus dem Seed abgeleitet"))

    @app.route("POST", r"/control/config")
    def cfg_set(req):
        b = req.json()
        with sim.lock:
            for k in ("webhook_url", "webhook_auto", "idempotency_ttl_s", "sca_action_ttl_s"):
                if k in b:
                    sim.config[k] = b[k]
            if "settle_delay_s" in b:
                sim.config["settle_delay_s"].update(b["settle_delay_s"])
            return Response(200, dict(sim.config))

    @app.route("POST", r"/control/faults")
    def fault_add(req):
        return Response(201, sim.add_fault(req.json()))

    @app.route("GET", r"/control/faults")
    def fault_list(_req):
        with sim.lock:
            return Response(200, {"faults": list(sim.faults)})

    @app.route("DELETE", r"/control/faults")
    def fault_clear(_req):
        with sim.lock:
            sim.faults.clear()
        return Response(200, {"faults": []})

    @app.route("GET", r"/control/log")
    def log(req):
        with sim.lock:
            calls = [c for c in sim.calls if not req.query.get("operation") or c["operation"] == req.query["operation"]]
            return Response(200, {"calls": calls, "deliveries": list(sim.deliveries)})

    @app.route("GET", r"/control/events")
    def events(_req):
        with sim.lock:
            return Response(200, {"events": list(sim.events)})

    @app.route("POST", r"/control/settle")
    def settle(_req):
        sim.settle_all()
        return Response(200, {"settled": True})

    @app.route("POST", r"/control/disputes")
    def dispute(req):
        b = req.json()
        try:
            if b.get("resolve"):
                return Response(200, sim.resolve_dispute(b["payment_id"], b["resolve"]))
            return Response(200, sim.open_dispute(b["payment_id"]))
        except KeyError:
            raise BadRequest("payment_id fehlt")
        except ApiError as e:
            return Response(e.status, {"code": e.code, "message": e.message})

    @app.route("POST", r"/control/sca/(?P<pid>[^/]+)/complete")
    def sca(req):
        try:
            return Response(200, sim.complete_sca(req.match["pid"], req.json().get("outcome", "success")))
        except ApiError as e:
            return Response(e.status, {"code": e.code, "message": e.message})

    @app.route("POST", r"/control/webhooks/deliver")
    def deliver(req):
        b = req.json()
        with sim.lock:
            ev = None
            if b.get("event_id"):
                ev = next((e for e in sim.events if e["event_id"] == b["event_id"]), None)
            elif b.get("payment_id"):
                cands = [e for e in sim.events if e["data"]["payment_id"] == b["payment_id"]
                         and (not b.get("type") or e["type"] == b["type"])]
                ev = cands[-1] if cands else None
        if ev is None:
            return Response(404, {"code": "UNKNOWN_EVENT"})
        res = sim.deliver(ev, b.get("target_url"), b.get("mode", "valid"), int(b.get("repeat", 1)),
                          int(b.get("late_seconds", 900)))
        return Response(200, {"event_id": ev["event_id"], "deliveries": res})

    return app


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=18091)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--clock-start", default=DEFAULT_START)
    a = ap.parse_args(argv)
    parse_iso(a.clock_start)
    sim = PspSimulator(a.seed, a.clock_start)
    app = build_app(sim, a.host, a.port)
    print(f"PSP-Simulator (nur Testnetz) auf {app.url}, seed={a.seed}, clock={sim.clock.now_iso()}", flush=True)
    try:
        app.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
