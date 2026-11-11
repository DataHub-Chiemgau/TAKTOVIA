#!/usr/bin/env python3
"""Port-Konformitätsprüfung für PSP-Adapter (WP-M0-29). Läuft hier gegen den PSP-Simulator: das Ergebnis ist ein SIMULATOR-Ergebnis,
kein Nachweis gegen einen realen PSP. Derselbe Ablauf ist für Kandidaten-Sandboxen vorgesehen (candidate_adapter_template.py).

  python tests/performance/spikes/spike_d/conformance.py                       # gegen den Simulator (in-process)
  python tests/performance/spikes/spike_d/conformance.py --adapter modul:Klasse --label Kandidat   # reale Sandbox (nicht ausgeführt)

Prüfungen mit Fehlerinjektion (Timeout, verlorene Antwort, Duplikat-Webhooks) brauchen die Steuerschnittstelle des Simulators. Bei einer realen
Sandbox wird der Timeout clientseitig nachgestellt (Anfrage senden, Verbindung vor der Antwort schließen) und über die Statusabfrage aufgelöst;
das ist im Testplan beschrieben und im Adapter zu implementieren (abort_after_send).
"""
import argparse
import importlib
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from spike_d import HERE  # noqa: E402
from spike_d.psp_port import Rejected, Unknown, resolve_unknown  # noqa: E402
from perfkit.stats import summarize  # noqa: E402
from taktovia_testkit.jsonhttp import App, Response, http_call  # noqa: E402


class Ctl:
    """Steuerschnittstelle des Simulators (Fehlerinjektion); bei realen Sandboxen nicht vorhanden."""

    def __init__(self, url):
        self.url = url

    def fault(self, operation, mode, ref="*", times=1):
        s, b, _, _ = http_call("POST", self.url + "/control/faults", dict(operation=operation, mode=mode, ref=ref, times=times))
        assert s == 201, b

    def get(self, path):
        return http_call("GET", self.url + path)[1]

    def post(self, path, body):
        return http_call("POST", self.url + path, body)


class Recorder:
    def __init__(self):
        self.results = []

    def check(self, cid, desc, fn):
        t = time.perf_counter()
        try:
            detail = fn()
            ok, detail = (detail is not False), (detail if isinstance(detail, str) else "")
        except AssertionError as e:
            ok, detail = False, f"Assertion: {e}"
        except Exception as e:  # noqa: BLE001
            ok, detail = False, f"{type(e).__name__}: {e}"
        self.results.append({"id": cid, "description": desc, "ok": ok, "detail": detail, "duration_ms": round((time.perf_counter() - t) * 1000, 1)})


def run(port, ctl=None, label=None, latency_n=30):
    rec, n = Recorder(), [0]

    def ref(prefix="C"):
        n[0] += 1
        return f"{prefix}-{port.name}-{n[0]}-{time.time_ns()}"

    def auth(amount=1000, pm="pm_ok"):
        r = ref()
        return port.authorize(r, amount, "EUR", pm, "k-" + r, "op-" + r)

    def c1():
        p = auth(1000)
        assert p.state == "AUTHORIZED"
        p = port.capture(p.payment_id, 800, "k-cap-" + p.merchant_ref)
        assert (p.state, p.captured_minor) == ("CAPTURED", 800)
        p = port.refund(p.payment_id, 300, "k-ref-" + p.merchant_ref)
        assert (p.state, p.refunded_minor) == ("PARTIALLY_REFUNDED", 300)
        s = port.status_by_merchant_ref(p.merchant_ref)
        assert [o["kind"] for o in s.operations] == ["authorize", "capture", "refund"], s.operations
        assert s.payment_id == p.payment_id
    rec.check("C1", "Authorize -> Capture (Teilbetrag) -> Teil-Refund -> Status über Händlerreferenz", c1)

    def c2():
        p = auth()
        assert port.void(p.payment_id, "k-void-" + p.merchant_ref).state == "VOIDED"
    rec.check("C2", "Authorize -> Void", c2)

    def c3():
        p = auth(100)
        for fn, label in ((lambda: port.capture(p.payment_id, 101, "k1-" + p.merchant_ref), "Capture über Autorisierung"),
                          (lambda: port.refund(p.payment_id, 1, "k2-" + p.merchant_ref), "Refund ohne Capture")):
            try:
                fn()
            except Rejected:
                continue
            raise AssertionError(f"{label} wurde nicht abgewiesen")
        q = port.capture(p.payment_id, 60, "k3-" + p.merchant_ref)
        try:
            port.refund(q.payment_id, 61, "k4-" + p.merchant_ref)
        except Rejected:
            pass
        else:
            raise AssertionError("Refund über Capture nicht abgewiesen")
        d = auth(100, "pm_decline")
        assert d.state == "AUTHORIZATION_FAILED", d.state
    rec.check("C3", "Negativ: Capture über Autorisierung, Refund über Capture/ohne Capture, abgelehntes Testzahlungsmittel", c3)

    def c6():
        r = ref()
        port.authorize(r, 500, "EUR", "pm_ok", "same-" + r, None)
        try:
            port.authorize(r, 999, "EUR", "pm_ok", "same-" + r, None)
        except Rejected:
            return
        raise AssertionError("gleicher Schlüssel mit anderem Request nicht abgewiesen")
    rec.check("C6", "Idempotenzschlüssel mit abweichendem Request wird abgewiesen", c6)

    if ctl:
        def timeout_case(op, mode):
            def f():
                r = ref("T")
                if op == "authorize":
                    ctl.fault("authorize", mode, r)
                    key = "k-" + r
                    try:
                        port.authorize(r, 400, "EUR", "pm_ok", key, "op-" + r)
                        raise AssertionError("Fehlermodus nicht ausgelöst")
                    except Unknown:
                        pass
                    done, view = resolve_unknown(port, r, "authorize")
                    assert done == (mode == "lost_response"), (mode, done)
                    again = port.authorize(r, 400, "EUR", "pm_ok", key, "op-" + r)           # Wiederholung mit gleichem Schlüssel
                    assert [o["kind"] for o in port.status_by_merchant_ref(r).operations].count("authorize") == 1
                    return f"Wiederholung replayed={again.replayed}"
                p = port.authorize(r, 400, "EUR", "pm_ok", "a-" + r, "op-" + r)
                if op == "refund":
                    port.capture(p.payment_id, 400, "c-" + r)
                ctl.fault(op, mode, r)
                key, opref = f"k-{op}-{r}", f"op-{op}-{r}"
                call = {"capture": lambda: port.capture(p.payment_id, 400, key, opref), "void": lambda: port.void(p.payment_id, key, opref),
                        "refund": lambda: port.refund(p.payment_id, 150, key, opref)}[op]
                try:
                    call()
                    raise AssertionError("Fehlermodus nicht ausgelöst")
                except Unknown:
                    pass
                done, view = resolve_unknown(port, r, op, opref)
                assert done == (mode == "lost_response"), (op, mode, done)
                call()                                                                      # Wiederholung mit gleichem Schlüssel
                kinds = [o["kind"] for o in port.status_by_merchant_ref(r).operations]
                assert kinds.count(op) == 1, kinds
            return f
        for op in ("authorize", "capture", "void", "refund"):
            rec.check(f"C4-{op}-lost", f"{op}: Antwort verloren -> Status zeigt Wirkung, Wiederholung ohne zweite Wirkung", timeout_case(op, "lost_response"))
            rec.check(f"C4-{op}-timeout", f"{op}: Timeout ohne Wirkung -> Status zeigt keine Wirkung, Wiederholung wirkt genau einmal", timeout_case(op, "timeout"))

        def c5a():
            p = auth(2000)
            out, bar = [], threading.Barrier(2)

            def w():
                bar.wait()
                try:
                    out.append(port.capture(p.payment_id, 2000, "dup-" + p.merchant_ref))
                except Exception as e:  # noqa: BLE001
                    out.append(e)
            ts = [threading.Thread(target=w) for _ in range(2)]
            [t.start() for t in ts]
            [t.join() for t in ts]
            assert all(not isinstance(o, Exception) for o in out), out
            kinds = [o["kind"] for o in port.status_by_merchant_ref(p.merchant_ref).operations]
            assert kinds.count("capture") == 1, kinds
        rec.check("C5a", "Zwei gleichzeitige Capture-Aufrufe mit gleichem Schlüssel: genau eine Erfassung", c5a)

        def c5b():
            seen, lock = {}, threading.Lock()
            app = App()

            @app.route("POST", r"/hook")
            def hook(req):
                import psp_sim
                from datetime import datetime
                now = int(datetime.fromisoformat(ctl.get("/control/clock")["now"].replace("Z", "+00:00")).timestamp())
                ok, _ = psp_sim.verify_signature(ctl.get("/control/config")["webhook_secret"], req.header("x-psp-signature", ""), req.raw_body, now)
                eid = req.header("x-psp-event-id")
                with lock:
                    if ok:
                        seen[eid] = seen.get(eid, 0) + 1
                return Response(200 if ok else 401, {"ok": ok})
            app.start()
            try:
                p = auth(700)
                port.capture(p.payment_id, 700, "n-" + p.merchant_ref)
                b = ctl.post("/control/webhooks/deliver", {"payment_id": p.payment_id, "type": "payment.captured", "target_url": app.url + "/hook", "repeat": 5})[1]
                assert len(b["deliveries"]) == 5
                effects = sum(1 for v in seen.values() if v)
                assert (effects, sum(seen.values())) == (1, 5), seen            # fünf Zustellungen, eine Wirkung nach Deduplikation
            finally:
                app.stop()
        rec.check("C5b", "Fünffach zugestellte Benachrichtigung mit gültiger Signatur: eine Wirkung nach Deduplikation (event_id)", c5b)
    else:
        for cid in ("C4", "C5"):
            rec.results.append({"id": cid, "description": "Fehlerinjektion (Timeout, Duplikat-Webhook)", "ok": None,
                                "detail": "nicht automatisiert: keine Steuerschnittstelle; bei realer Sandbox clientseitiger Abbruch nach dem Senden, siehe Testplan", "duration_ms": 0})

    lat = {}
    for op in ("authorize", "capture", "refund", "status"):
        vals = []
        for _ in range(latency_n):
            r = ref("L")
            t = time.perf_counter()
            if op == "authorize":
                p = port.authorize(r, 100, "EUR", "pm_ok", "k-" + r)
            else:
                p = auth(100)
                t = time.perf_counter()
                if op == "capture":
                    port.capture(p.payment_id, 100, "k-" + r)
                elif op == "refund":
                    port.capture(p.payment_id, 100, "kc-" + r)
                    t = time.perf_counter()
                    port.refund(p.payment_id, 10, "k-" + r)
                else:
                    port.status_by_merchant_ref(p.merchant_ref)
            vals.append((time.perf_counter() - t) * 1000)
        lat[op] = summarize(vals)
    return {"target": port.name, "label": label or port.name, "is_simulator": ctl is not None, "results": rec.results, "latency_ms": lat,
            "summary": {"ok": sum(1 for r in rec.results if r["ok"] is True), "failed": sum(1 for r in rec.results if r["ok"] is False),
                        "not_run": sum(1 for r in rec.results if r["ok"] is None)},
            "limitations": ["Simulator-Ergebnis (Loopback), kein PSP-Nachweis" if ctl else "reale Sandbox: Ergebnis nur für den benannten Kandidaten und Testkonten"]}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--adapter", default="simulator")
    ap.add_argument("--label")
    ap.add_argument("--out", default=str(HERE.parent / "spike-d-data"))
    a = ap.parse_args(argv)
    if a.adapter == "simulator":
        import psp_sim
        from spike_d.simulator_adapter import SimulatorAdapter
        sim = psp_sim.PspSimulator(seed=1, clock_start="2026-01-01T00:00:00Z")
        app = psp_sim.build_app(sim)
        app.start()
        try:
            res = run(SimulatorAdapter(app.url, timeout=1.0), Ctl(app.url), a.label)
        finally:
            app.stop()
    else:
        mod, cls = a.adapter.split(":")
        res = run(getattr(importlib.import_module(mod), cls)(), None, a.label)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    f = out / f"conformance-{'simulator' if a.adapter == 'simulator' else (a.label or 'kandidat')}.json"
    f.write_text(json.dumps(res, indent=1, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps(res["summary"]), "->", f)
    for r in res["results"]:
        print(f"  {'OK ' if r['ok'] else ('--' if r['ok'] is None else 'FEHLER')} {r['id']}: {r['description']} {r['detail']}")
    return 0 if res["summary"]["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
