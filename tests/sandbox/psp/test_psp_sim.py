"""Tests des PSP-Simulators (WP-M0-23). Alle Ergebnisse sind SIMULATOR-Ergebnisse, kein Nachweis gegen einen echten PSP."""
import json
import sys
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "lib"))
import psp_sim  # noqa: E402
from taktovia_testkit.jsonhttp import App, NoResponse, Response, http_call, assert_testnet_host  # noqa: E402

try:
    import jsonschema
except ImportError:  # pragma: no cover
    jsonschema = None

REPO = HERE.parents[2]


class Base(unittest.TestCase):
    seed = 7

    def setUp(self):
        self.sim = psp_sim.PspSimulator(self.seed, "2026-03-01T10:00:00Z")
        self.app = psp_sim.build_app(self.sim)
        self.app.start()
        self.url = self.app.url
        self.n = 0

    def tearDown(self):
        self.app.stop()

    def call(self, method, path, body=None, key=None, timeout=5):
        h = {"Idempotency-Key": key} if key else {}
        return http_call(method, self.url + path, body, h, timeout)

    def authorize(self, ref="ORD-1", amount=1000, pm="pm_ok", key=None, **kw):
        self.n += 1
        s, b, hd, _ = self.call("POST", "/psp/v1/payments",
                                dict(merchant_ref=ref, amount_minor=amount, currency="EUR", payment_method=pm, **kw),
                                key or f"k-auth-{ref}-{self.n}")
        return s, b

    def fault(self, operation, mode, ref="*", times=1, hold=0):
        s, b, _, _ = self.call("POST", "/control/faults", dict(operation=operation, mode=mode, ref=ref, times=times, hold_seconds=hold))
        self.assertEqual(s, 201, b)

    def log(self, operation=None):
        q = f"?operation={operation}" if operation else ""
        return self.call("GET", "/control/log" + q)[1]["calls"]


class Normalfall(Base):
    def test_authorize_capture_teilrefund_status(self):
        s, p = self.authorize(amount=1000)
        self.assertEqual((s, p["state"]), (201, "AUTHORIZED"))
        pid = p["payment_id"]
        s, p = self.call("POST", f"/psp/v1/payments/{pid}/capture", {"amount_minor": 800}, "k-cap")[:2]
        self.assertEqual((s, p["state"], p["captured_minor"]), (200, "CAPTURED", 800))
        s, p = self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 300}, "k-ref1")[:2]
        self.assertEqual((s, p["state"], p["refunded_minor"]), (200, "PARTIALLY_REFUNDED", 300))
        s, p = self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 500}, "k-ref2")[:2]
        self.assertEqual((s, p["state"], p["refunded_minor"]), (200, "REFUNDED", 800))
        # Statusabfrage über ursprüngliche Händlerreferenz, payment_id und provider_ref
        by_ref = self.call("GET", "/psp/v1/payments?merchant_ref=ORD-1")[1]
        by_id = self.call("GET", f"/psp/v1/payments/{pid}")[1]
        by_prov = self.call("GET", f"/psp/v1/payments/{p['provider_ref']}")[1]
        self.assertEqual(by_ref["payment_id"], by_id["payment_id"], by_prov["payment_id"])
        self.assertEqual([o["kind"] for o in by_ref["operations"]], ["authorize", "capture", "refund", "refund"])

    def test_void(self):
        _, p = self.authorize()
        s, v = self.call("POST", f"/psp/v1/payments/{p['payment_id']}/void", {}, "k-void")[:2]
        self.assertEqual((s, v["state"]), (200, "VOIDED"))

    def test_abgelehntes_zahlungsmittel(self):
        s, p = self.authorize(pm="pm_decline")
        self.assertEqual((s, p["state"], p["decline_code"]), (201, "AUTHORIZATION_FAILED", "card_declined"))

    def test_voller_capture_ohne_betrag(self):
        _, p = self.authorize(amount=555)
        _, c, _, _ = self.call("POST", f"/psp/v1/payments/{p['payment_id']}/capture", {}, "k")
        self.assertEqual(c["captured_minor"], 555)


class Negativ(Base):
    def test_capture_ohne_autorisierung(self):
        _, p = self.authorize(pm="pm_sca")  # REQUIRES_ACTION
        s, b = self.call("POST", f"/psp/v1/payments/{p['payment_id']}/capture", {}, "k")[:2]
        self.assertEqual((s, b["code"]), (409, "INVALID_STATE"))

    def test_capture_ueber_autorisierung(self):
        _, p = self.authorize(amount=100)
        s, b = self.call("POST", f"/psp/v1/payments/{p['payment_id']}/capture", {"amount_minor": 101}, "k")[:2]
        self.assertEqual((s, b["code"]), (422, "AMOUNT_EXCEEDS_AUTHORIZED"))

    def test_refund_ueber_capture_und_vor_capture(self):
        _, p = self.authorize(amount=100)
        pid = p["payment_id"]
        s, b = self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 1}, "k0")[:2]
        self.assertEqual((s, b["code"]), (409, "INVALID_STATE"))
        self.call("POST", f"/psp/v1/payments/{pid}/capture", {"amount_minor": 60}, "k1")
        s, b = self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 61}, "k2")[:2]
        self.assertEqual((s, b["code"]), (422, "AMOUNT_EXCEEDS_CAPTURED"))
        self.assertEqual(self.call("GET", f"/psp/v1/payments/{pid}")[1]["refunded_minor"], 0)

    def test_unbekannte_referenz(self):
        self.assertEqual(self.call("GET", "/psp/v1/payments/pay_gibtesnicht")[0], 404)
        self.assertEqual(self.call("GET", "/psp/v1/payments?merchant_ref=NIX")[0], 404)
        self.assertEqual(self.call("POST", "/psp/v1/payments/pay_x/capture", {}, "k")[0], 404)

    def test_void_nach_capture_abgewiesen(self):
        _, p = self.authorize()
        self.call("POST", f"/psp/v1/payments/{p['payment_id']}/capture", {"amount_minor": 10}, "k1")
        s, b = self.call("POST", f"/psp/v1/payments/{p['payment_id']}/void", {}, "k2")[:2]
        self.assertEqual((s, b["code"]), (409, "INVALID_STATE"))

    def test_geldinvariante_ganzzahl(self):
        for bad in (10.5, "100", True, 0, -5, None):
            s, b = self.authorize(ref=f"R{bad!r}", amount=bad)
            self.assertEqual(s, 400, (bad, b))

    def test_idempotenzschluessel_pflicht_und_wiederverwendung(self):
        self.assertEqual(self.call("POST", "/psp/v1/payments", {"merchant_ref": "X", "amount_minor": 1, "currency": "EUR"})[0], 400)
        s1, p = self.authorize(ref="A", amount=100, key="same")
        s2, b, hd, _ = self.call("POST", "/psp/v1/payments", dict(merchant_ref="A", amount_minor=999, currency="EUR"), "same")
        self.assertEqual((s2, b["code"]), (422, "IDEMPOTENCY_KEY_REUSE"))

    def test_doppelte_haendlerreferenz_mit_neuem_schluessel_kein_zweiter_charge(self):
        self.authorize(ref="DUP", key="k1")
        s, b = self.authorize(ref="DUP", key="k2")
        self.assertEqual((s, b["code"]), (409, "DUPLICATE_MERCHANT_REF"))
        self.assertEqual(len(self.sim.payments), 1)


class Fehlermodi(Base):
    """Jeder Fehlermodus je Operation gezielt auslösbar und im Protokoll nachvollziehbar (Akzeptanz 2 und 3)."""

    def prepared(self, op):
        """Liefert (payment_id, ref, call) für die Operation; Vorbedingungen ohne Fehlermodus herstellen."""
        ref = f"REF-{op}"
        if op == "authorize":
            return None, ref, lambda key: self.call("POST", "/psp/v1/payments", dict(merchant_ref=ref, amount_minor=500, currency="EUR"), key, timeout=1.0)
        _, p = self.authorize(ref=ref, amount=500)
        pid = p["payment_id"]
        if op == "refund":
            self.call("POST", f"/psp/v1/payments/{pid}/capture", {}, "pre-cap")
            return pid, ref, lambda key: self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 200}, key, timeout=1.0)
        if op == "status":
            return pid, ref, lambda key: self.call("GET", f"/psp/v1/payments/{pid}", timeout=1.0)
        return pid, ref, lambda key: self.call("POST", f"/psp/v1/payments/{pid}/{op}", {}, key, timeout=1.0)

    def effects(self, ref, kind):
        p = self.sim.payments[self.sim.by_merchant[ref]]
        return [o for o in p["operations"] if o["kind"] == kind]

    def test_timeout_je_operation_wirkung_nicht_angewandt(self):
        for op in ("authorize", "capture", "void", "refund"):
            with self.subTest(op=op):
                pid, ref, do = self.prepared(op)
                self.fault(op, "timeout", ref)
                with self.assertRaises(NoResponse):
                    do("k-" + op)
                if op == "authorize":
                    self.assertEqual(self.call("GET", f"/psp/v1/payments?merchant_ref={ref}")[0], 404)
                else:
                    self.assertEqual(len([o for o in self.effects(ref, op) if o["idempotency_key"] == "k-" + op]), 0)
                entry = [c for c in self.log(op) if c["merchant_ref"] == ref and c["fault"] == "timeout"]
                self.assertEqual((len(entry), entry[0]["effect_applied"], entry[0]["response_dropped"]), (1, False, True))
                s, b, _, _ = do("k-" + op)       # Wiederholung mit gleichem Schlüssel wirkt genau einmal
                self.assertIn(s, (200, 201), b)

    def test_verlorene_antwort_wirkung_vorhanden_wiederholung_ohne_zweite_wirkung(self):
        for op in ("authorize", "capture", "void", "refund"):
            with self.subTest(op=op):
                pid, ref, do = self.prepared(op)
                self.fault(op, "lost_response", ref)
                with self.assertRaises(NoResponse):
                    do("k-lost-" + op)
                st = self.call("GET", f"/psp/v1/payments?merchant_ref={ref}")[1]   # Statusabfrage über ursprüngliche Referenz
                kinds = [o["kind"] for o in st["operations"]]
                self.assertEqual(kinds.count(op), 1, kinds)
                before = json.dumps(st["operations"])
                s, b, hd, _ = do("k-lost-" + op)
                self.assertIn(s, (200, 201))
                self.assertEqual(hd.get("idempotent-replayed"), "true")
                after = self.call("GET", f"/psp/v1/payments?merchant_ref={ref}")[1]
                self.assertEqual(json.dumps(after["operations"]), before)
                e = [c for c in self.log(op) if c["merchant_ref"] == ref]
                self.assertTrue(any(c["fault"] == "lost_response" and c["effect_applied"] and c["response_dropped"] for c in e))

    def test_duplikat_je_operation_genau_eine_wirkung(self):
        for op in ("authorize", "capture", "void", "refund"):
            with self.subTest(op=op):
                pid, ref, do = self.prepared(op)
                self.fault(op, "duplicate", ref)
                s, b, hd, _ = do("k-dup-" + op)
                self.assertIn(s, (200, 201))
                st = self.call("GET", f"/psp/v1/payments?merchant_ref={ref}")[1]
                self.assertEqual([o["kind"] for o in st["operations"]].count(op), 1)
                e = [c for c in self.log(op) if c["merchant_ref"] == ref and c["idempotency_key"] == "k-dup-" + op]
                self.assertEqual([c["fault"] for c in e], ["duplicate_delivery", "duplicate"])
                self.assertTrue(e[0]["replayed"])

    def test_definitive_ablehnung(self):
        pid, ref, do = self.prepared("authorize")
        self.fault("authorize", "decline", ref)
        s, b, _, _ = do("k")
        self.assertEqual((s, b["state"]), (201, "AUTHORIZATION_FAILED"))
        for op in ("capture", "void", "refund"):
            pid, ref, do = self.prepared(op)
            self.fault(op, "decline", ref)
            s, b, _, _ = do("k-d-" + op)
            self.assertEqual((s, b["code"]), (422, "DECLINED_BY_PROVIDER"), op)

    def test_statusabfrage_nicht_verfuegbar(self):
        pid, ref, do = self.prepared("status")
        self.fault("status", "unavailable", ref)
        self.assertEqual(do(None)[0], 503)
        self.assertEqual(do(None)[0], 200)   # times=1

    def test_fehler_nur_fuer_gezielte_referenz(self):
        self.fault("authorize", "timeout", "ZIEL")
        s, _ = self.authorize(ref="ANDERE")
        self.assertEqual(s, 201)


class Determinismus(unittest.TestCase):
    def run_scenario(self):
        sim = psp_sim.PspSimulator(42, "2026-01-01T00:00:00Z")
        app = psp_sim.build_app(sim)
        app.start()
        try:
            out = []
            def c(m, p, b=None, k=None):
                s, body, _, _ = http_call(m, app.url + p, b, {"Idempotency-Key": k} if k else {})
                out.append((s, body))
                return body
            p = c("POST", "/psp/v1/payments", {"merchant_ref": "O1", "amount_minor": 900, "currency": "EUR"}, "a")
            c("POST", f"/psp/v1/payments/{p['payment_id']}/capture", {"amount_minor": 900}, "b")
            c("POST", f"/psp/v1/payments/{p['payment_id']}/refund", {"amount_minor": 100}, "c")
            c("GET", "/psp/v1/payments?merchant_ref=O1")
            return out, sim.events
        finally:
            app.stop()

    def test_gleicher_seed_gleiche_referenzen_und_ergebnisse(self):
        a, ea = self.run_scenario()
        b, eb = self.run_scenario()
        self.assertEqual(a, b)
        self.assertEqual(ea, eb)
        self.assertTrue(a[0][1]["payment_id"].startswith("pay_"))

    def test_anderer_seed_andere_referenzen(self):
        s1, s2 = psp_sim.PspSimulator(1), psp_sim.PspSimulator(2)
        self.assertNotEqual(s1._next("pay"), s2._next("pay"))


class Konkurrenz(Base):
    def run_threads(self, n, fn):
        barrier, results = threading.Barrier(n), [None] * n

        def worker(i):
            barrier.wait()
            try:
                results[i] = fn(i)
            except Exception as e:  # noqa
                results[i] = e
        ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        return results

    def test_100_gleichzeitige_captures_gleicher_schluessel_genau_eine_erfassung(self):
        _, p = self.authorize(amount=5000)
        pid = p["payment_id"]
        res = self.run_threads(100, lambda i: self.call("POST", f"/psp/v1/payments/{pid}/capture", {"amount_minor": 5000}, "same-key", timeout=20))
        self.assertTrue(all(isinstance(r, tuple) for r in res), [r for r in res if not isinstance(r, tuple)][:3])
        self.assertEqual({r[0] for r in res}, {200})
        self.assertEqual(sum(1 for r in res if r[2].get("idempotent-replayed") != "true"), 1)
        st = self.call("GET", f"/psp/v1/payments/{pid}")[1]
        self.assertEqual((st["state"], st["captured_minor"]), ("CAPTURED", 5000))
        self.assertEqual([o["kind"] for o in st["operations"]].count("capture"), 1)
        self.assertEqual(sum(1 for c in self.log("capture") if c["effect_applied"]), 1)

    def test_zwei_gleichzeitige_refunds_ueber_capture_hoechstens_einer(self):
        _, p = self.authorize(amount=1000)
        pid = p["payment_id"]
        self.call("POST", f"/psp/v1/payments/{pid}/capture", {"amount_minor": 1000}, "cap")
        res = self.run_threads(2, lambda i: self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 600}, f"ref-{i}", timeout=10))
        codes = sorted(r[0] for r in res)
        self.assertEqual(codes, [200, 422])
        st = self.call("GET", f"/psp/v1/payments/{pid}")[1]
        self.assertEqual(st["refunded_minor"], 600)
        self.assertLessEqual(st["refunded_minor"], st["captured_minor"])

    def test_viele_refunds_nie_ueber_capture(self):
        _, p = self.authorize(amount=1000)
        pid = p["payment_id"]
        self.call("POST", f"/psp/v1/payments/{pid}/capture", {}, "cap")
        res = self.run_threads(50, lambda i: self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 30}, f"r{i}", timeout=20))
        ok = sum(1 for r in res if r[0] == 200)
        st = self.call("GET", f"/psp/v1/payments/{pid}")[1]
        self.assertEqual((ok, st["refunded_minor"]), (33, 990))


class Receiver:
    """Lokaler Webhook-Empfänger, prüft die PSP-Signatur wie ein korrekter Empfänger."""

    def __init__(self, secret, clock):
        self.secret, self.clock = secret, clock
        self.seen, self.rejected, self.lock = [], [], threading.Lock()
        self.app = App()

        @self.app.route("POST", r"/hook")
        def hook(req):
            ok, why = psp_sim.verify_signature(self.secret, req.header("x-psp-signature", ""), req.raw_body, self.clock.epoch())
            with self.lock:
                (self.seen if ok else self.rejected).append((why, req.header("x-psp-event-id")))
            return Response(200 if ok else 401, {"verified": ok, "reason": why})
        self.app.start()

    @property
    def url(self):
        return self.app.url + "/hook"


class Webhooks(Base):
    def setUp(self):
        super().setUp()
        self.rcv = Receiver(self.sim.webhook_secret, self.sim.clock)

    def tearDown(self):
        self.rcv.app.stop()
        super().tearDown()

    def captured(self):
        _, p = self.authorize(amount=700)
        self.call("POST", f"/psp/v1/payments/{p['payment_id']}/capture", {}, "k-cap")
        return p["payment_id"]

    def deliver(self, pid, **kw):
        s, b, _, _ = self.call("POST", "/control/webhooks/deliver", dict(payment_id=pid, type="payment.captured", target_url=self.rcv.url, **kw))
        self.assertEqual(s, 200, b)
        return b

    def test_100_identische_zustellungen_im_protokoll(self):
        pid = self.captured()
        b = self.deliver(pid, mode="valid", repeat=100)
        self.assertEqual(len(b["deliveries"]), 100)
        self.assertEqual(len(self.rcv.seen), 100)
        self.assertEqual(len({e for _, e in self.rcv.seen}), 1)   # identische event_id
        self.assertEqual(len(self.call("GET", "/control/log")[1]["deliveries"]), 100)

    def test_ungueltige_signatur_und_verspaetet(self):
        pid = self.captured()
        self.deliver(pid, mode="bad_signature", repeat=3)
        self.assertEqual([w for w, _ in self.rcv.rejected], ["BAD_SIGNATURE"] * 3)
        self.deliver(pid, mode="late", repeat=2, late_seconds=900)
        self.assertEqual([w for w, _ in self.rcv.rejected][3:], ["TIMESTAMP_OUT_OF_WINDOW"] * 2)
        self.assertEqual(self.rcv.seen, [])
        self.assertEqual({d["http_status"] for d in self.call("GET", "/control/log")[1]["deliveries"]}, {401})

    def test_mehr_als_100_abgewiesen(self):
        pid = self.captured()
        s, b, _, _ = self.call("POST", "/control/webhooks/deliver", dict(payment_id=pid, target_url=self.rcv.url, repeat=101))
        self.assertEqual(s, 400)

    def test_ereignisformat_entspricht_schema(self):
        pid = self.captured()
        self.call("POST", "/control/disputes", {"payment_id": pid})
        evs = self.call("GET", "/control/events")[1]["events"]
        self.assertGreaterEqual(len(evs), 3)
        if jsonschema is None:
            self.skipTest("jsonschema nicht installiert")
        schema = json.loads((HERE / "schemas" / "provider-event.schema.json").read_text())
        v = jsonschema.Draft202012Validator(schema)
        for e in evs:
            v.validate(e)

    def test_automatische_zustellung(self):
        self.call("POST", "/control/config", {"webhook_url": self.rcv.url, "webhook_auto": True})
        self.authorize(amount=100)
        for _ in range(100):
            if self.rcv.seen:
                break
            threading.Event().wait(0.02)
        self.assertEqual(len(self.rcv.seen), 1)


class SCAStreitfallAsync(Base):
    def test_sca_laenger_als_hold(self):
        _, p = self.authorize(pm="pm_sca", amount=300)
        pid = p["payment_id"]
        self.assertEqual(p["state"], "REQUIRES_ACTION")
        self.call("POST", "/control/clock", {"advance_seconds": 181})   # länger als der Planungs-Hold (180 s)
        st = self.call("GET", f"/psp/v1/payments/{pid}")[1]
        self.assertEqual(st["state"], "REQUIRES_ACTION")                 # PSP wartet weiter: Hold ist Sache des Aufrufers
        s, b, _, _ = self.call("POST", f"/control/sca/{pid}/complete", {"outcome": "success"})
        self.assertEqual((s, b["state"]), (200, "AUTHORIZED"))

    def test_sca_ablauf_fuehrt_zu_fehlgeschlagen(self):
        _, p = self.authorize(pm="pm_sca")
        self.call("POST", "/control/clock", {"advance_seconds": 901})
        self.assertEqual(self.call("GET", f"/psp/v1/payments/{p['payment_id']}")[1]["state"], "AUTHORIZATION_FAILED")
        self.assertEqual(self.call("POST", f"/control/sca/{p['payment_id']}/complete", {})[0], 409)

    def test_streitfall_getrennt_vom_zustand(self):
        _, p = self.authorize(amount=100)
        pid = p["payment_id"]
        self.assertEqual(self.call("POST", "/control/disputes", {"payment_id": pid})[0], 409)   # vor Capture
        self.call("POST", f"/psp/v1/payments/{pid}/capture", {}, "k")
        s, b, _, _ = self.call("POST", "/control/disputes", {"payment_id": pid})
        self.assertEqual((s, b["state"], b["dispute_state"]), (200, "CAPTURED", "OPEN"))
        s, b, _, _ = self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 100}, "r")
        self.assertEqual((b["state"], b["dispute_state"]), ("REFUNDED", "OPEN"))

    def test_asynchroner_capture_ueber_testuhr(self):
        self.call("POST", "/control/config", {"settle_delay_s": {"capture": 30}})
        _, p = self.authorize(amount=100)
        pid = p["payment_id"]
        s, b, _, _ = self.call("POST", f"/psp/v1/payments/{pid}/capture", {}, "k")
        self.assertEqual(b["state"], "CAPTURE_PENDING")
        self.assertEqual(self.call("POST", f"/psp/v1/payments/{pid}/void", {}, "v")[1]["code"], "OPERATION_IN_PROGRESS")
        self.assertEqual(self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 1}, "r")[1]["code"], "OPERATION_IN_PROGRESS")
        self.call("POST", "/control/clock", {"advance_seconds": 30})
        self.assertEqual(self.call("GET", f"/psp/v1/payments/{pid}")[1]["state"], "CAPTURED")

    def test_testzeit_zeitstempel_deterministisch(self):
        self.call("POST", "/control/clock", {"set": "2030-05-05T05:05:05Z"})
        _, p = self.authorize()
        self.assertEqual(p["created_at"], "2030-05-05T05:05:05.000Z")
        self.assertEqual(self.call("POST", "/control/clock", {"advance_seconds": -1})[0], 400)


class Zustandsautomat(Base):
    def test_automat_datei_stimmt_mit_code_ueberein(self):
        spec = json.loads((HERE / "schemas" / "state-machine.json").read_text())
        self.assertEqual({k: set(v) for k, v in spec["transitions"].items()}, psp_sim.TRANSITIONS)
        self.assertEqual(set(spec["transitions"]), set(psp_sim.STATES))

    def test_beobachtete_uebergaenge_erlaubt(self):
        _, p = self.authorize(amount=100)
        pid = p["payment_id"]
        self.call("POST", f"/psp/v1/payments/{pid}/capture", {}, "c")
        self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 40}, "r1")
        self.call("POST", f"/psp/v1/payments/{pid}/refund", {"amount_minor": 60}, "r2")
        seq = [e["data"]["state"] for e in self.sim.events]
        for a, b in zip(seq, seq[1:]):
            self.assertIn(b, psp_sim.TRANSITIONS[a], (a, b))


class Testnetz(unittest.TestCase):
    def test_bind_nur_testnetz(self):
        for bad in ("8.8.8.8", "1.1.1.1"):
            with self.assertRaises(SystemExit):
                assert_testnet_host(bad)
        import os
        os.environ.pop("TAKTOVIA_TESTNET", None)
        with self.assertRaises(SystemExit):
            assert_testnet_host("0.0.0.0")
        assert_testnet_host("127.0.0.1")
        assert_testnet_host("10.1.2.3")

    def test_nicht_in_produktionskonfiguration(self):
        """Der Simulator darf in keiner Produktionskonfiguration und keinem Produktionscode referenziert werden."""
        needles = ("psp_sim", "psp-sim", "simpsp")
        hits = []
        roots = [REPO / "infra", REPO / "backend", REPO / "workers", REPO / "web", REPO / "mobile", REPO / ".github"]
        for root in roots:
            if not root.exists():
                continue
            for f in root.rglob("*"):
                if not f.is_file() or f.suffix in (".jar", ".png", ".lock", ".class") or "node_modules" in f.parts or "build" in f.parts:
                    continue
                rel = f.relative_to(REPO).as_posix().lower()
                prod_path = any(t in rel for t in ("prod", "main/", "/src/main"))
                if not prod_path:
                    continue
                try:
                    txt = f.read_text(errors="ignore").lower()
                except OSError:
                    continue
                if any(n in txt for n in needles):
                    hits.append(rel)
        self.assertEqual(hits, [], "Simulator in Produktionsdateien referenziert")


@unittest.skipIf(jsonschema is None, "jsonschema fehlt")
class Vertrag(unittest.TestCase):
    def test_openapi_parsbar(self):
        try:
            from openapi_spec_validator import validate
            import yaml
        except ImportError:
            self.skipTest("openapi-spec-validator/PyYAML nicht installiert")
        validate(yaml.safe_load((HERE / "openapi" / "psp-sim.yaml").read_text()))


if __name__ == "__main__":
    unittest.main()
