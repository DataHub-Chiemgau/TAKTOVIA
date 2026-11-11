"""Tests der Sandbox-Partner (WP-M0-24). Ergebnisse sind SANDBOX-Ergebnisse, kein Nachweis gegen reale Partner."""
import json
import sys
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "lib"))
import partner_sandbox as ps  # noqa: E402
from taktovia_testkit import partner_signature as sig  # noqa: E402
from taktovia_testkit.jsonhttp import App, NoResponse, Response, http_call  # noqa: E402
from taktovia_testkit.testclock import set_all  # noqa: E402

try:
    import jsonschema
except ImportError:  # pragma: no cover
    jsonschema = None

CORE = {"X-Caller-Id": "taktovia-core"}


class Base(unittest.TestCase):
    seed = 3
    instances = ("A", "B", "C")

    def setUp(self):
        self.run_ = ps.launch(self.instances, self.seed, "2026-04-01T08:00:00Z")
        self.n = 0

    def tearDown(self):
        for _, app in self.run_.values():
            app.stop()

    def url(self, inst):
        return self.run_[inst][1].url

    def sb(self, inst):
        return self.run_[inst][0]

    def call(self, inst, method, path, body=None, headers=CORE, timeout=5):
        return http_call(method, self.url(inst) + path, body, headers, timeout)

    def reserve(self, inst="A", svc="SVC-A-MAIN", fed=None, cmd=None, seats=1, wc=0, ttl=None, headers=CORE):
        self.n += 1
        body = {"command_id": cmd or f"cmd-{self.n}", "federation_ref": fed or f"fed-{self.n}", "service_ref": svc,
                "resource_vector": {"seats": seats, "wheelchair": wc}}
        if ttl:
            body["hold_ttl_seconds"] = ttl
        s, b, hd, _ = self.call(inst, "POST", "/v1/partner/reservations", body, headers)
        return s, b, body

    def fault(self, inst, op, mode, ref="*", times=1, hold=0):
        s, b, _, _ = self.call(inst, "POST", "/control/faults", dict(operation=op, mode=mode, ref=ref, times=times, hold_seconds=hold), {})
        self.assertEqual(s, 201, b)

    def log(self, inst, op=None):
        calls = self.call(inst, "GET", "/control/log", headers={})[1]["calls"]
        return [c for c in calls if not op or c["operation"] == op]

    def status(self, inst, ident):
        return self.call(inst, "GET", f"/v1/partner/reservations/{ident}/status")


class Capabilities(Base):
    def test_drei_instanzen_parallel_mit_unterschiedlicher_matrix(self):
        ms = {}
        for i in "ABC":
            s, b, _, _ = self.call(i, "GET", "/v1/partner/capabilities")
            self.assertEqual(s, 200)
            ms[i] = b["matrix"]
        self.assertTrue(ms["A"]["hold_extension"]["supported"] and not ms["B"]["hold_extension"]["supported"])
        self.assertFalse(ms["C"]["hold"]["supported"])
        self.assertTrue(ms["C"]["information"]["supported"])
        self.assertNotEqual(ms["A"], ms["B"])
        self.assertEqual(len({self.url(i) for i in "ABC"}), 3)

    def test_jedes_merkmal_gesetzt_und_schema_gueltig(self):
        schema = json.loads((HERE / "schemas" / "capability-matrix.schema.json").read_text()) if jsonschema else None
        for i in "ABC":
            b = self.call(i, "GET", "/v1/partner/capabilities")[1]
            self.assertEqual(set(b["matrix"]), set(ps.FEATURES))
            for svc, info in b["services"].items():
                self.assertEqual(set(info["matrix"]), set(ps.FEATURES), svc)
                if schema:
                    jsonschema.validate(info["matrix"], schema)
            for f in b["matrix"].values():
                self.assertIsInstance(f["supported"], bool)

    def test_capability_je_dienst(self):
        a = self.call("A", "GET", "/v1/partner/capabilities?service_ref=SVC-A-NOEXT")[1]["matrix"]
        m = self.call("A", "GET", "/v1/partner/capabilities?service_ref=SVC-A-MAIN")[1]["matrix"]
        self.assertFalse(a["hold_extension"]["supported"])
        self.assertTrue(m["hold_extension"]["supported"])
        s, b, _, _ = self.call("A", "POST", "/v1/partner/reservations/x/extend", {"extra_ttl_seconds": 10})
        self.assertEqual(s, 404)
        _, r, _ = self.reserve("A", "SVC-A-NOEXT")
        s, b, _, _ = self.call("A", "POST", f"/v1/partner/reservations/{r['reservation_id']}/extend", {"extra_ttl_seconds": 10})
        self.assertEqual((s, b["code"]), (422, "CAPABILITY_NOT_SUPPORTED"))

    def test_capability_endpunkt_liefert_genau_die_wirksame_matrix_nach_abschaltung(self):
        self.call("A", "POST", "/control/config", {"status_enabled": False}, {})
        m = self.call("A", "GET", "/v1/partner/capabilities")[1]["matrix"]
        self.assertFalse(m["status_lookup"]["supported"])
        self.call("A", "POST", "/control/config", {"dedup_enabled": False}, {})
        self.assertFalse(self.call("A", "GET", "/v1/partner/capabilities")[1]["matrix"]["deduplicated_requests"]["supported"])


class Normalfall(Base):
    def test_angebot_hold_confirm_status_cancel(self):
        s, b, _, _ = self.call("A", "POST", "/v1/partner/offers", {"service_ref": "SVC-A-MAIN", "resource_vector": {"seats": 1}})
        self.assertEqual(s, 200)
        self.assertTrue(b["offers"][0]["binding"] and b["offers"][0]["bookable"])
        s, r, _ = self.reserve("A", "SVC-A-MAIN", fed="FED-1", ttl=120)
        self.assertEqual((s, r["state"], r["hold_ttl_seconds"], r["expires_at"]), (201, "HELD", 120, "2026-04-01T08:02:00.000Z"))
        s, c, _, _ = self.call("A", "POST", f"/v1/partner/reservations/{r['reservation_id']}/confirm", {"command_id": "c-conf"})
        self.assertEqual((s, c["state"], c["booking_state"]), (200, "CONSUMED", "CONFIRMED"))
        self.assertTrue(c["ticket_ref"])
        s, st, _, _ = self.status("A", "FED-1")      # Status nach ursprünglicher fachlicher Referenz
        self.assertEqual((s, st["booking_state"], st["matches"]), (200, "CONFIRMED", 1))
        s, x, _, _ = self.call("A", "POST", "/v1/partner/reservations/FED-1/cancel", {"command_id": "c-can"})
        self.assertEqual((s, x["booking_state"]), (200, "CANCELLED"))
        self.assertEqual(self.status("A", "FED-1")[1]["booking_state"], "CANCELLED")

    def test_refund_und_verlaengerung_wenn_capability(self):
        _, r, _ = self.reserve("A", "SVC-A-MAIN", fed="F-R", ttl=60)
        rid = r["reservation_id"]
        s, e, _, _ = self.call("A", "POST", f"/v1/partner/reservations/{rid}/extend", {"extra_ttl_seconds": 60})
        self.assertEqual((s, e["expires_at"]), (200, "2026-04-01T08:02:00.000Z"))
        self.call("A", "POST", f"/v1/partner/reservations/{rid}/confirm", {})
        s, f, _, _ = self.call("A", "POST", f"/v1/partner/reservations/{rid}/refund", {"amount_minor": 500})
        self.assertEqual((s, f["refunded_minor"]), (200, 500))
        s, f, _, _ = self.call("A", "POST", f"/v1/partner/reservations/{rid}/refund", {"amount_minor": 1100})
        self.assertEqual((s, f["code"]), (422, "AMOUNT_EXCEEDS_PRICE"))
        # B kennt keinen Refund
        _, rb, _ = self.reserve("B", "SVC-B-MAIN")
        self.call("B", "POST", f"/v1/partner/reservations/{rb['reservation_id']}/confirm", {})
        self.assertEqual(self.call("B", "POST", f"/v1/partner/reservations/{rb['reservation_id']}/refund", {"amount_minor": 1})[1]["code"], "CAPABILITY_NOT_SUPPORTED")

    def test_nicht_bindender_preis_bei_b_schiene(self):
        o = self.call("B", "POST", "/v1/partner/offers", {"service_ref": "SVC-B-RAIL"})[1]["offers"][0]
        self.assertFalse(o["binding"])


class Negativ(Base):
    def test_confirm_auf_abgelaufenen_hold_grenzzeitpunkt(self):
        _, r, _ = self.reserve("A", fed="F-EXP", ttl=60)
        set_all([self.url("A")], "2026-04-01T08:00:59Z")
        self.assertEqual(self.call("A", "POST", "/v1/partner/reservations/F-EXP/confirm", {})[0], 200)
        _, r2, _ = self.reserve("A", fed="F-EXP2", ttl=60)           # läuft um 08:01:59 ab
        set_all([self.url("A")], "2026-04-01T08:01:59Z")              # now == expires_at gilt als abgelaufen
        s, b, _, _ = self.call("A", "POST", "/v1/partner/reservations/F-EXP2/confirm", {})
        self.assertEqual((s, b["code"]), (409, "HOLD_EXPIRED"))
        self.assertEqual(self.status("A", "F-EXP2")[1]["state"], "EXPIRED")

    def test_unbekannte_referenz_und_storno_unbekannt(self):
        for path in ("confirm", "cancel"):
            s, b, _, _ = self.call("A", "POST", f"/v1/partner/reservations/NIX/{path}", {})
            self.assertEqual((s, b["code"]), (404, "UNKNOWN_REFERENCE"))
        self.assertEqual(self.status("A", "NIX")[0], 404)

    def test_hold_bei_nur_auskunftsfaehigem_partner(self):
        s, b, _ = self.reserve("C", "SVC-C-LINE")
        self.assertEqual((s, b["code"]), (422, "CAPABILITY_NOT_SUPPORTED"))
        s, o, _, _ = self.call("C", "POST", "/v1/partner/offers", {"service_ref": "SVC-C-LINE"})
        self.assertEqual(s, 200)
        self.assertTrue(o["information_only"])
        self.assertFalse(o["offers"][0]["bookable"] or o["offers"][0]["binding"])
        self.assertEqual(self.call("C", "POST", "/v1/partner/reservations/x/confirm", {})[0], 404)

    def test_status_bei_abgeschalteter_statusfaehigkeit(self):
        _, r, _ = self.reserve("A", fed="F-S")
        self.call("A", "POST", "/control/config", {"status_enabled": False}, {})
        s, b, _, _ = self.status("A", "F-S")
        self.assertEqual((s, b["code"]), (422, "CAPABILITY_NOT_SUPPORTED"))

    def test_maschinenidentitaet_eines_anderen_partners(self):
        _, r, _ = self.reserve("A", fed="F-OWN")
        other = {"X-Caller-Id": "partner-x"}
        s, b, _, _ = self.call("A", "GET", "/v1/partner/reservations/F-OWN/status", headers=other)
        self.assertEqual((s, b["code"]), (404, "UNKNOWN_REFERENCE"))      # keine Existenzdetails
        s, b, _, _ = self.call("A", "POST", "/v1/partner/reservations/F-OWN/cancel", {}, other)
        self.assertEqual(s, 404)
        self.assertEqual(self.status("A", "F-OWN")[1]["state"], "HELD")
        s, b, _, _ = self.call("A", "GET", "/v1/partner/capabilities", headers={"X-Caller-Id": "unbekannt"})
        self.assertEqual(s, 403)
        self.assertEqual(self.call("A", "POST", "/v1/partner/offers", {"service_ref": "SVC-A-MAIN"}, headers={})[0], 403)

    def test_hold_ueber_kapazitaet_und_rollstuhl_ersetzt_kein_sitz(self):
        s, b, _ = self.reserve("A", "SVC-A-LAST", seats=2)
        self.assertEqual((s, b["code"]), (409, "NO_CAPACITY"))
        s, b, _ = self.reserve("A", "SVC-A-LAST", seats=0, wc=1)
        self.assertEqual(s, 409)

    def test_ungueltige_eingaben(self):
        self.assertEqual(self.call("A", "POST", "/v1/partner/reservations", {"service_ref": "SVC-A-MAIN"})[0], 400)
        self.assertEqual(self.call("A", "POST", "/v1/partner/reservations", {"command_id": "c", "federation_ref": "f", "service_ref": "SVC-A-MAIN", "resource_vector": {"seats": 1.5}})[0], 400)
        self.assertEqual(self.call("A", "POST", "/v1/partner/reservations", {"command_id": "c", "federation_ref": "f", "service_ref": "UNBEKANNT"})[0], 404)


class Fehlermodi(Base):
    def test_confirm_persistiert_antwort_verworfen_status_findet_genau_eine_reservierung(self):
        _, r, _ = self.reserve("A", fed="F-LOST")
        self.fault("A", "confirm", "lost_response", "F-LOST")
        with self.assertRaises(NoResponse):
            self.call("A", "POST", "/v1/partner/reservations/F-LOST/confirm", {"command_id": "c1"}, timeout=2)
        s, st, _, _ = self.status("A", "F-LOST")
        self.assertEqual((s, st["state"], st["booking_state"], st["matches"]), (200, "CONSUMED", "CONFIRMED", 1))
        # wiederholter Confirm mit gleicher Referenz erzeugt keine zweite Reservierung
        s, c, hd, _ = self.call("A", "POST", "/v1/partner/reservations/F-LOST/confirm", {"command_id": "c1"})
        self.assertEqual((s, hd.get("idempotent-replayed")), (200, "true"))
        self.assertEqual(len(self.sb("A").reservations), 1)
        e = [c for c in self.log("A", "confirm") if c["fault"] == "lost_response"][0]
        self.assertTrue(e["effect_applied"] and e["response_dropped"])

    def test_timeout_ohne_wirkung_je_operation(self):
        for op, path, body in (("reserve", "/v1/partner/reservations", None), ("offers", "/v1/partner/offers", {"service_ref": "SVC-A-MAIN"})):
            self.fault("A", op, "timeout")
            payload = body or {"command_id": "ct", "federation_ref": "F-T", "service_ref": "SVC-A-MAIN"}
            with self.assertRaises(NoResponse):
                self.call("A", "POST", path, payload, timeout=2)
        self.assertEqual(len(self.sb("A").reservations), 0)
        _, r, _ = self.reserve("A", fed="F-T2")
        self.fault("A", "cancel", "timeout", "F-T2")
        with self.assertRaises(NoResponse):
            self.call("A", "POST", "/v1/partner/reservations/F-T2/cancel", {}, timeout=2)
        self.assertEqual(self.status("A", "F-T2")[1]["state"], "HELD")
        self.fault("A", "status", "timeout")
        with self.assertRaises(NoResponse):
            self.status("A", "F-T2")

    def test_wiederholter_confirm_und_hold_dedupliziert(self):
        s1, r1, body = self.reserve("A", fed="F-D", cmd="same-cmd")
        s2, r2, hd, _ = self.call("A", "POST", "/v1/partner/reservations", body)
        self.assertEqual((r1["reservation_id"], hd.get("idempotent-replayed")), (r2["reservation_id"], "true"))
        # gleiche Referenz, neue Command-ID: ebenfalls dieselbe Reservierung
        s3, r3, _, _ = self.call("A", "POST", "/v1/partner/reservations", dict(body, command_id="neu"))
        self.assertEqual(r3["reservation_id"], r1["reservation_id"])
        self.assertEqual(len(self.sb("A").reservations), 1)

    def test_abgeschaltete_deduplikation_erzeugt_duplikate_sichtbar(self):
        self.call("A", "POST", "/control/config", {"dedup_enabled": False}, {})
        _, r1, body = self.reserve("A", fed="F-ND", cmd="x")
        self.call("A", "POST", "/v1/partner/reservations", body)
        self.assertEqual(len(self.sb("A").reservations), 2)
        self.assertEqual(self.status("A", "F-ND")[1]["matches"], 2)

    def test_definitive_ablehnung_und_nichtverfuegbarkeit(self):
        self.fault("A", "reserve", "reject")
        s, b, _ = self.reserve("A")
        self.assertEqual((s, b["code"]), (422, "REJECTED_BY_PARTNER"))
        self.fault("A", "status", "unavailable")
        self.assertEqual(self.status("A", "x")[0], 503)
        self.assertEqual(len(self.sb("A").reservations), 0)

    def test_latenz_einstellbar(self):
        self.call("A", "POST", "/control/config", {"latency_ms": 300}, {})
        with self.assertRaises(NoResponse):
            self.call("A", "GET", "/v1/partner/capabilities", timeout=0.1)
        self.assertEqual(self.call("A", "GET", "/v1/partner/capabilities", timeout=3)[0], 200)


class Konkurrenz(Base):
    def test_100_gleichzeitige_holds_auf_den_letzten_platz_hoechstens_einer(self):
        n, barrier, out = 100, threading.Barrier(100), [None] * 100

        def w(i):
            barrier.wait()
            try:
                out[i] = self.reserve("A", "SVC-A-LAST", fed=f"RACE-{i}", cmd=f"race-{i}")[:2]
            except Exception as e:  # noqa
                out[i] = e
        ts = [threading.Thread(target=w, args=(i,)) for i in range(n)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertTrue(all(isinstance(o, tuple) for o in out), [o for o in out if not isinstance(o, tuple)][:2])
        ok = [o for o in out if o[0] == 201]
        self.assertEqual(len(ok), 1)
        self.assertEqual({o[1]["code"] for o in out if o[0] != 201}, {"NO_CAPACITY"})
        held = [r for r in self.sb("A").reservations.values() if r["state"] == "HELD"]
        self.assertEqual(len(held), 1)

    def test_hold_ablauf_gibt_kapazitaet_frei(self):
        _, r, _ = self.reserve("A", "SVC-A-LAST", fed="L1", ttl=30)
        self.assertEqual(self.reserve("A", "SVC-A-LAST", fed="L2")[0], 409)
        set_all([self.url("A")], "2026-04-01T08:00:30Z")
        self.assertEqual(self.reserve("A", "SVC-A-LAST", fed="L3")[0], 201)

    def test_storno_gibt_kapazitaet_frei(self):
        _, r, _ = self.reserve("A", "SVC-A-LAST", fed="K1")
        self.call("A", "POST", "/v1/partner/reservations/K1/confirm", {})
        self.assertEqual(self.reserve("A", "SVC-A-LAST", fed="K2")[0], 409)
        self.call("A", "POST", "/v1/partner/reservations/K1/cancel", {})
        self.assertEqual(self.reserve("A", "SVC-A-LAST", fed="K3")[0], 201)


class Ereignisse(Base):
    def setUp(self):
        super().setUp()
        self.sbx = self.sb("A")
        _, r, _ = self.reserve("A", fed="EV-1")
        self.call("A", "POST", "/v1/partner/reservations/EV-1/confirm", {})
        keys = self.call("A", "GET", "/control/keys", headers={})[1]
        self.keys, self.binding = keys["keys"], keys["binding"]
        self.recv_inbox = sig.Inbox()
        self.results = []
        self.app = App()

        @self.app.route("POST", r"/v1/federation/partners/partner-a/events")
        def receive(req):
            status, why = sig.verify(req.headers, req.raw_body, self.sbx.clock.epoch(), self.keys, self.binding)
            if status == 200:
                status, why = self.recv_inbox.accept(json.loads(req.raw_body), req.raw_body)
            self.results.append((status, why))
            return Response(status, {"result": why})
        self.app.start()
        self.target = self.app.url + "/v1/federation/partners/partner-a/events"

    def tearDown(self):
        self.app.stop()
        super().tearDown()

    def emit(self, mode, **kw):
        s, b, _, _ = self.call("A", "POST", "/control/events/emit", dict(mode=mode, target_url=self.target, **kw), {})
        self.assertEqual(s, 200, b)
        return b["deliveries"]

    def test_gueltiges_ereignis_und_schema(self):
        ev = self.call("A", "GET", "/control/events", headers={})[1]["events"][-1]
        if jsonschema:
            jsonschema.validate(ev, json.loads((HERE / "schemas" / "partner-event.schema.json").read_text()))
        self.assertEqual(ev["event_type"], "reservation.confirmed")
        self.emit("valid")
        self.assertEqual(self.results, [(202, "ACCEPTED")])

    def test_signatur_und_bindungsfehler_werden_von_korrektem_empfaenger_abgelehnt(self):
        erwartet = {"bad_signature": (401, "BAD_SIGNATURE"), "unknown_key_id": (401, "UNKNOWN_KEY_ID"),
                    "stale_timestamp": (401, "TIMESTAMP_OUT_OF_WINDOW"), "future_timestamp": (401, "TIMESTAMP_OUT_OF_WINDOW"),
                    "oversize": (413, "BODY_TOO_LARGE"), "tenant_mismatch": (403, "TENANT_BINDING_MISMATCH"),
                    "disallowed_type": (422, "EVENT_TYPE_NOT_ALLOWED")}
        for mode, exp in erwartet.items():
            with self.subTest(mode=mode):
                self.results.clear()
                d = self.emit(mode)
                self.assertEqual(len(d), 1)
                self.assertEqual(self.results[-1], exp)
        self.assertEqual(self.recv_inbox.effects, 0)

    def test_zeitfenster_grenzen_300s(self):
        ev = self.sbx.events[-1]
        # exakt innerhalb der Toleranz akzeptiert, knapp außerhalb abgelehnt (Referenzprüfer)
        plan = self.sbx._signed(ev, "valid", ts=self.sbx.clock.epoch() - 300)
        hdr = {k.lower(): v for k, v in plan["headers"].items()}
        self.assertEqual(sig.verify(hdr, plan["body"], self.sbx.clock.epoch(), self.keys, self.binding)[1], "VERIFIED")
        plan = self.sbx._signed(ev, "valid", ts=self.sbx.clock.epoch() - 301)
        hdr = {k.lower(): v for k, v in plan["headers"].items()}
        self.assertEqual(sig.verify(hdr, plan["body"], self.sbx.clock.epoch(), self.keys, self.binding)[1], "TIMESTAMP_OUT_OF_WINDOW")

    def test_100_identische_ereignisse_eine_wirkung(self):
        d = self.emit("duplicate", repeat=100)
        self.assertEqual(len(d), 100)
        self.assertEqual(self.results[0], (202, "ACCEPTED"))
        self.assertEqual(set(self.results[1:]), {(200, "ALREADY_RECEIVED")})
        self.assertEqual(self.recv_inbox.effects, 1)
        self.assertEqual(len(self.call("A", "GET", "/control/log", headers={})[1]["deliveries"]), 100)

    def test_gleiche_id_veraenderter_payload_quarantaene(self):
        self.emit("same_id_changed_payload")
        self.assertEqual(self.results, [(202, "ACCEPTED"), (409, "PAYLOAD_CHANGED_QUARANTINE")])

    def test_reihenfolge_und_versionsluecke(self):
        self.emit("out_of_order")
        self.assertEqual(self.results, [(202, "ACCEPTED_VERSION_GAP_RECONCILE"), (202, "STALE_IGNORED")])
        self.results.clear()
        self.recv_inbox = sig.Inbox()
        self.emit("version_gap")
        self.assertEqual(self.results, [(202, "ACCEPTED"), (202, "ACCEPTED_VERSION_GAP_RECONCILE")])

    def test_rotation_zwei_gleichzeitig_gueltige_schluessel(self):
        d = self.emit("rotation")
        self.assertEqual([x["headers"]["X-Partner-Key-Id"] for x in d], [ps.KEY_CURRENT, ps.KEY_NEXT])
        self.assertEqual([r[0] for r in self.results], [202, 200])      # beide Signaturen verifizierbar; zweite ist Duplikat
        self.assertEqual(self.results[1][1], "ALREADY_RECEIVED")

    def test_dry_run_liefert_signierte_requests(self):
        d = self.emit("bad_signature", dry_run=True, target_url=None) if False else None
        s, b, _, _ = self.call("A", "POST", "/control/events/emit", {"mode": "valid", "dry_run": True}, {})
        self.assertEqual(s, 200)
        x = b["deliveries"][0]
        self.assertTrue(x["headers"]["X-Partner-Signature"].startswith("v1="))
        self.assertEqual(self.results, [])

    def test_ereignis_bei_ablauf_und_storno_erzeugt(self):
        self.call("A", "POST", "/v1/partner/reservations/EV-1/cancel", {})
        types = [e["event_type"] for e in self.sbx.events]
        self.assertEqual(types, ["reservation.confirmed", "reservation.cancelled"])
        _, r, _ = self.reserve("A", fed="EV-2", ttl=10)
        set_all([self.url("A")], "2026-04-01T08:00:10Z")
        self.assertEqual(self.sbx.events[-1]["event_type"], "reservation.expired")


class Determinismus(unittest.TestCase):
    def one_run(self):
        run = ps.launch(("A",), 11, "2026-05-05T05:05:05Z")
        sb, app = run["A"]
        try:
            out = []
            def c(m, p, b=None):
                s, body, _, _ = http_call(m, app.url + p, b, CORE)
                out.append((s, body))
            c("POST", "/v1/partner/offers", {"service_ref": "SVC-A-MAIN"})
            c("POST", "/v1/partner/reservations", {"command_id": "c", "federation_ref": "F", "service_ref": "SVC-A-MAIN"})
            c("POST", "/v1/partner/reservations/F/confirm", {})
            c("GET", "/v1/partner/reservations/F/status")
            plan = sb.plan_deliveries(sb.events[0], "valid")
            return out, sb.events, plan
        finally:
            app.stop()

    def test_gleicher_seed_gleiche_referenzen_zeiten_ergebnisse_signaturen(self):
        a, b = self.one_run(), self.one_run()
        self.assertEqual(a, b)

    def test_anderer_seed_andere_schluessel(self):
        s1 = ps.PartnerSandbox(ps.PRESETS["A"], 1)
        s2 = ps.PartnerSandbox(ps.PRESETS["A"], 2)
        self.assertNotEqual(s1.keys(), s2.keys())


class Vertrag(unittest.TestCase):
    def test_holdautomat_datei_stimmt_mit_code_ueberein(self):
        spec = json.loads((HERE / "schemas" / "hold-state-machine.json").read_text())
        self.assertEqual({k: set(v) for k, v in spec["transitions"].items()}, ps.HOLD_TRANSITIONS)

    def test_keine_schluessel_im_repository(self):
        for f in HERE.rglob("*"):
            if f.is_file() and f.suffix in (".py", ".json", ".yaml", ".md"):
                txt = f.read_text()
                self.assertNotRegex(txt, r"(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----")
                self.assertNotRegex(txt, r"(?i)(secret|password)\s*[:=]\s*['\"][A-Za-z0-9+/]{20,}['\"]")

    def test_openapi_parsbar(self):
        try:
            from openapi_spec_validator import validate
            import yaml
        except ImportError:
            self.skipTest("openapi-spec-validator/PyYAML nicht installiert")
        validate(yaml.safe_load((HERE / "openapi" / "partner-sandbox.yaml").read_text()))


if __name__ == "__main__":
    unittest.main()
