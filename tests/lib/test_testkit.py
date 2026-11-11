"""Tests der gemeinsamen Testbibliothek: Testzeit-Protokoll, JSON-HTTP, Referenzprüfer für Partnerereignisse."""
import json
import os
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_testkit import partner_signature as ps  # noqa: E402
from taktovia_testkit.jsonhttp import App, BadRequest, DropConnection, NoResponse, Response, assert_testnet_host, http_call  # noqa: E402
from taktovia_testkit.testclock import SimClock, iso, parse_iso, set_all  # noqa: E402


class Testzeit(unittest.TestCase):
    def test_eingefroren_und_vorstellen(self):
        c = SimClock("2026-01-01T00:00:00Z")
        a = c.now_iso()
        time.sleep(0.02)
        self.assertEqual(c.now_iso(), a)                                   # eingefroren
        c.advance(90)
        self.assertEqual(c.now_iso(), "2026-01-01T00:01:30.000Z")
        with self.assertRaises(ValueError):
            c.advance(-1)
        c.control({"set": "2030-02-03T04:05:06Z"})
        self.assertEqual(c.epoch(), int(parse_iso("2030-02-03T04:05:06Z").timestamp()))

    def test_echtzeitmodus_laeuft_ab_gesetztem_wert(self):
        c = SimClock("2026-01-01T00:00:00Z")
        c.set_mode("real")
        t1 = c.now()
        time.sleep(0.05)
        self.assertGreater((c.now() - t1).total_seconds(), 0.04)
        c.set_mode("frozen")
        t2 = c.now()
        time.sleep(0.02)
        self.assertEqual(c.now(), t2)

    def test_ungueltige_eingaben(self):
        c = SimClock()
        for bad in ({"set": "kein-datum"}, {"set": "2026-01-01T00:00:00"}, {"advance_seconds": "5"}, {"advance_seconds": True}, {"mode": "schnell"}):
            with self.assertRaises(ValueError, msg=str(bad)):
                c.control(bad)

    def test_gemeinsame_vereinbarung_ueber_http_fuer_mehrere_dienste(self):
        apps, clocks = [], []
        for _ in range(2):
            clock, app = SimClock(), App()
            app.route("POST", r"/control/clock")(lambda req, clock=clock: Response(200, clock.control(req.json())))
            app.route("GET", r"/control/clock")(lambda req, clock=clock: Response(200, clock.snapshot()))
            app.start()
            apps.append(app)
            clocks.append(clock)
        try:
            out = set_all([a.url for a in apps], "2027-07-07T07:07:07Z")
            self.assertEqual([o["now"] for o in out], ["2027-07-07T07:07:07.000Z"] * 2)
            self.assertEqual({iso(c.now()) for c in clocks}, {"2027-07-07T07:07:07.000Z"})
        finally:
            [a.stop() for a in apps]


class JsonHttp(unittest.TestCase):
    def test_testnetzbindung(self):
        os.environ.pop("TAKTOVIA_TESTNET", None)
        for ok in ("127.0.0.1", "localhost", "10.0.0.5", "192.168.1.9"):
            assert_testnet_host(ok)
        for bad in ("0.0.0.0", "8.8.8.8"):
            with self.assertRaises(SystemExit):
                assert_testnet_host(bad)
        os.environ["TAKTOVIA_TESTNET"] = "1"
        try:
            assert_testnet_host("0.0.0.0")
        finally:
            os.environ.pop("TAKTOVIA_TESTNET")

    def test_antworten_fehler_und_verbindungsabbruch(self):
        app = App()
        app.route("POST", r"/echo")(lambda req: Response(200, req.json()))
        app.route("GET", r"/drop")(lambda req: (_ for _ in ()).throw(DropConnection()))
        app.route("GET", r"/bad")(lambda req: (_ for _ in ()).throw(BadRequest("kaputt", "X")))
        app.start()
        try:
            self.assertEqual(http_call("POST", app.url + "/echo", {"a": 1})[1], {"a": 1})
            self.assertEqual(http_call("POST", app.url + "/echo", raw_body=b"{kaputt")[0], 400)
            self.assertEqual(http_call("POST", app.url + "/echo", raw_body=b"[1]")[0], 400)
            self.assertEqual(http_call("GET", app.url + "/nix")[0], 404)
            s, b, _, _ = http_call("GET", app.url + "/bad")
            self.assertEqual((s, b["code"]), (400, "X"))
            with self.assertRaises(NoResponse):
                http_call("GET", app.url + "/drop")
        finally:
            app.stop()
        with self.assertRaises(NoResponse):
            http_call("GET", "http://127.0.0.1:9/", timeout=0.5)

    def test_100_gleichzeitige_verbindungen(self):
        import threading
        app = App()
        app.route("GET", r"/ping")(lambda req: Response(200, {"ok": True}))
        app.start()
        res, ts = [], []
        try:
            for _ in range(100):
                t = threading.Thread(target=lambda: res.append(http_call("GET", app.url + "/ping", timeout=10)[0]))
                ts.append(t)
            [t.start() for t in ts]
            [t.join() for t in ts]
        finally:
            app.stop()
        self.assertEqual(res, [200] * 100)


class Referenzpruefer(unittest.TestCase):
    keys = {"k1": "geheim-test-1", "k2": "geheim-test-2"}
    binding = {"partner_id": "p", "tenant_id": "t1", "allowed_types": ["reservation.confirmed"]}

    def req(self, key="k1", ts=1000, body=None, secret=None, **ev):
        body = json.dumps(dict({"event_id": "e", "event_type": "reservation.confirmed", "tenant_id": "t1", "partner_id": "p",
                                "aggregate_ref": "a", "aggregate_version": 1, "payload": {}}, **ev)).encode() if body is None else body
        sig = ps.sign(secret or self.keys.get(key, "x"), ts, body)
        return {"x-partner-key-id": key, "x-partner-timestamp": str(ts), "x-partner-signature": "v1=" + sig}, body

    def v(self, headers, body, now=1000):
        return ps.verify(headers, body, now, self.keys, self.binding)

    def test_gueltig_und_rotation(self):
        self.assertEqual(self.v(*self.req("k1")), (200, "VERIFIED"))
        self.assertEqual(self.v(*self.req("k2")), (200, "VERIFIED"))      # beide Schlüssel gleichzeitig verifizierbar

    def test_ablehnungen(self):
        h, b = self.req()
        self.assertEqual(self.v(h, b + b" ")[1], "BAD_SIGNATURE")                      # Body exakt signiert
        self.assertEqual(self.v(*self.req("k3"))[1], "UNKNOWN_KEY_ID")
        self.assertEqual(self.v(*self.req(ts=1000 - 301))[1], "TIMESTAMP_OUT_OF_WINDOW")
        self.assertEqual(self.v(*self.req(ts=1000 + 301))[1], "TIMESTAMP_OUT_OF_WINDOW")
        self.assertEqual(self.v(*self.req(ts=1000 - 300))[1], "VERIFIED")
        self.assertEqual(self.v(*self.req(secret="falsch"))[1], "BAD_SIGNATURE")
        self.assertEqual(self.v(*self.req(tenant_id="t2"))[1], "TENANT_BINDING_MISMATCH")
        self.assertEqual(self.v(*self.req(event_type="payroll.exported"))[1], "EVENT_TYPE_NOT_ALLOWED")
        self.assertEqual(self.v(*self.req(body=b"x" * (ps.MAX_BODY_BYTES + 1)))[1], "BODY_TOO_LARGE")
        h["x-partner-signature"] = "v2=abc"
        self.assertEqual(self.v(h, b)[1], "MALFORMED_SIGNATURE")

    def test_inbox_deduplikation(self):
        ib = ps.Inbox()
        raw = b'{"a":1}'
        ev = {"partner_id": "p", "event_id": "e1", "aggregate_ref": "a", "aggregate_version": 1}
        self.assertEqual(ib.accept(ev, raw), (202, "ACCEPTED"))
        for _ in range(99):
            self.assertEqual(ib.accept(ev, raw), (200, "ALREADY_RECEIVED"))
        self.assertEqual(ib.accept(ev, b'{"a":2}'), (409, "PAYLOAD_CHANGED_QUARANTINE"))
        self.assertEqual(ib.effects, 1)
        self.assertEqual(ib.accept(dict(ev, event_id="e0", aggregate_version=1), raw), (202, "STALE_IGNORED"))
        self.assertEqual(ib.accept(dict(ev, event_id="e3", aggregate_version=3), raw), (202, "ACCEPTED_VERSION_GAP_RECONCILE"))


if __name__ == "__main__":
    unittest.main()
