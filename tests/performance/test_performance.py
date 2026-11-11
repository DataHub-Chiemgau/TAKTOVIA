"""Tests des Performance-Gerüsts (WP-M0-25)."""
import copy
import csv
import hashlib
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from perfkit import PERF_DIR, profile as pf, report, restore, runner  # noqa: E402
from perfkit.stats import percentile, summarize  # noqa: E402
import run as runmod  # noqa: E402
from taktovia_testkit.jsonhttp import App, Response  # noqa: E402

EXAMPLE = PERF_DIR / "profiles" / "health-example.json"


def server(delay=0.0, status=200):
    import time
    app = App()

    def health(req):
        if delay:
            time.sleep(delay)
        return Response(status, {"status": "UP"})
    app.route("GET", r"/actuator/health")(health)
    app.start()
    return app


def small(profile, duration=0.6, conc=4, warm=0.1):
    p = copy.deepcopy(profile)
    p["load"].update(duration_s=duration, concurrency=conc, warmup_s=warm)
    return p


class Perzentile(unittest.TestCase):
    def test_nearest_rank_bekannte_werte(self):
        v = list(range(1, 101))
        self.assertEqual([percentile(v, p) for p in (50, 90, 95, 99, 100)], [50, 90, 95, 99, 100])
        self.assertEqual(percentile([7], 95), 7)
        self.assertEqual(percentile([1, 2, 3, 4], 50), 2)
        self.assertEqual(percentile([1, 2, 3, 4], 75), 3)
        self.assertEqual(percentile([1, 2, 3, 4, 5], 95), 5)

    def test_ungueltige_eingaben(self):
        for p in (0, -1, 101):
            with self.assertRaises(ValueError):
                percentile([1], p)
        with self.assertRaises(ValueError):
            percentile([], 50)
        self.assertEqual(summarize([]), {"n": 0})

    def test_summary_aus_stichprobe(self):
        s = summarize([float(i) for i in range(1, 1001)])
        self.assertEqual((s["n"], s["p50_ms"], s["p95_ms"], s["p99_ms"], s["max_ms"]), (1000, 500.0, 950.0, 990.0, 1000.0))


class Beispiellauf(unittest.TestCase):
    def test_beispielprofil_erzeugt_bericht_im_festgelegten_format(self):
        app = server()
        try:
            out = Path(tempfile.mkdtemp()) / "lauf"
            rep = runner.run_profile(small(json.loads(EXAMPLE.read_text())), app.url, out)
        finally:
            app.stop()
        report.validate(rep)
        for f in ("bericht.json", "bericht.md", "rohdaten.csv"):
            self.assertTrue((out / f).exists(), f)
        res = rep["results"]
        self.assertGreater(res["overall"]["n"], 10)
        self.assertEqual(res["error_count"], 0)
        self.assertLessEqual(res["overall"]["p50_ms"], res["overall"]["p95_ms"])
        self.assertLessEqual(res["overall"]["p95_ms"], res["overall"]["p99_ms"])
        self.assertFalse(rep["valid_for_acceptance"])
        self.assertEqual({e["verdict"] for e in rep["evaluation"]}, {"nicht bewertet"})   # Beispielprofil: keine Abnahmeaussage
        json.loads((out / "bericht.json").read_text())

    def test_perzentile_stimmen_mit_rohdaten_ueberein(self):
        app = server(delay=0.002)
        try:
            out = Path(tempfile.mkdtemp())
            rep = runner.run_profile(small(json.loads(EXAMPLE.read_text())), app.url, out)
        finally:
            app.stop()
        with (out / "rohdaten.csv").open() as fh:
            rows = list(csv.DictReader(fh))
        lat = sorted(float(r["latency_ms"]) for r in rows if r["warmup"] == "0")
        self.assertEqual(len(lat), rep["header"]["sample"]["n"])
        self.assertEqual(round(lat[math.ceil(0.95 * len(lat)) - 1], 3), rep["results"]["overall"]["p95_ms"])
        self.assertEqual(hashlib.sha256((out / "rohdaten.csv").read_bytes()).hexdigest(), rep["header"]["raw_data"]["sha256"])
        self.assertGreaterEqual(rep["results"]["overall"]["min_ms"], 2.0)    # Server verzögert 2 ms: Messung ist echt
        self.assertGreater(rep["header"]["sample"]["n_warmup_excluded"], 0)

    def test_fehler_werden_gezaehlt(self):
        app = server(status=500)
        try:
            rep = runner.run_profile(small(json.loads(EXAMPLE.read_text())), app.url)
        finally:
            app.stop()
        self.assertEqual(rep["results"]["error_rate"], 1.0)
        self.assertEqual(set(rep["results"]["status_counts"]), {"500"})

    def test_nicht_erreichbares_ziel_ist_fehlerrate_eins(self):
        rep = runner.run_profile(small(json.loads(EXAMPLE.read_text()), 0.3, 2, 0.0), "http://127.0.0.1:9")
        self.assertEqual(rep["results"]["error_rate"], 1.0)

    def test_cli_mit_stub_target_exit_0(self):
        out = tempfile.mkdtemp()
        p = small(json.loads(EXAMPLE.read_text()))
        f = Path(out) / "p.json"
        f.write_text(json.dumps(p))
        self.assertEqual(runmod.main(["--profile", str(f), "--stub-target", "--out", out]), 0)


class Pflichtparameter(unittest.TestCase):
    def setUp(self):
        self.base = json.loads(EXAMPLE.read_text())

    def test_beispielprofil_gueltig(self):
        pf.validate(self.base)

    def test_peak_und_mandanten_als_offene_pflichtparameter_ausgewiesen(self):
        op = self.base["open_parameters"]
        self.assertEqual(set(op), {"peak_requests_per_second", "active_tenants"})
        self.assertTrue(all(v["value"] is None and "OFFEN" in v["status"] for v in op.values()))
        app = server()
        try:
            rep = runner.run_profile(small(self.base), app.url)
        finally:
            app.stop()
        self.assertFalse(rep["valid_for_acceptance"])
        self.assertIn("OFFEN", report.render_markdown(rep))

    def test_negativ_lauf_ohne_pflichtparameter_wird_abgewiesen(self):
        for key in ("endpoint_mix", "load", "max_error_rate", "dataset", "candidate_fleet", "partner_share", "open_parameters", "reference_targets", "target", "mode"):
            bad = copy.deepcopy(self.base)
            del bad[key]
            with self.assertRaises(pf.ProfileError, msg=key):
                pf.validate(bad)
        for sub in ("concurrency", "duration_s"):
            bad = copy.deepcopy(self.base)
            del bad["load"][sub]
            with self.assertRaises(pf.ProfileError, msg=sub):
                pf.validate(bad)
        bad = copy.deepcopy(self.base)
        del bad["open_parameters"]["peak_requests_per_second"]
        with self.assertRaises(pf.ProfileError):
            pf.validate(bad)

    def test_negativ_messmodus_verlangt_vereinbarte_offene_parameter(self):
        bad = copy.deepcopy(self.base)
        bad["mode"] = "measurement"
        with self.assertRaises(pf.ProfileError) as cm:
            pf.validate(bad)
        self.assertTrue(any("peak_requests_per_second" in p for p in cm.exception.problems))
        bad["open_parameters"]["peak_requests_per_second"]["value"] = 50
        bad["open_parameters"]["active_tenants"]["value"] = 3
        bad["candidate_fleet"]["vehicles_per_tenant"] = 250
        pf.validate(bad)

    def test_negativ_cli_weist_profil_ab_exit_3(self):
        bad = copy.deepcopy(self.base)
        del bad["endpoint_mix"]
        f = Path(tempfile.mkdtemp()) / "bad.json"
        f.write_text(json.dumps(bad))
        self.assertEqual(runmod.main(["--profile", str(f), "--stub-target"]), 3)

    def test_negativ_weitere_ungueltige_profile(self):
        for mut in (lambda p: p["partner_share"].update(local=0.5, partner=0.2),
                    lambda p: p["load"].update(concurrency=0),
                    lambda p: p["endpoint_mix"][0].update(weight=0),
                    lambda p: p["reference_targets"].update(provisional=False),
                    lambda p: p.update(unbekannt=1)):
            bad = copy.deepcopy(self.base)
            mut(bad)
            with self.assertRaises(pf.ProfileError):
                pf.validate(bad)

    def test_referenzziele_als_parameter_vorlaeufig(self):
        r = self.base["reference_targets"]
        self.assertEqual((r["standard_api_p95_s"], r["offer_p95_s"], r["driver_plan_p95_s"], r["vehicles_per_tenant"],
                          r["bookings_per_day"], r["sessions"], r["rpo_min"], r["rto_h"], r["provisional"]),
                         (1.0, 5.0, 10.0, 250, 30000, 5000, 15, 2, True))

    def test_messmodus_bewertet_gegen_ziele(self):
        p = small(self.base)
        p["mode"] = "measurement"
        p["open_parameters"]["peak_requests_per_second"]["value"] = 10
        p["open_parameters"]["active_tenants"]["value"] = 2
        p["candidate_fleet"]["vehicles_per_tenant"] = 250
        app = server()
        try:
            rep = runner.run_profile(p, app.url)
        finally:
            app.stop()
        self.assertTrue(rep["valid_for_acceptance"])
        self.assertEqual({e["verdict"] for e in rep["evaluation"]}, {"erfüllt"})
        p["reference_targets"]["standard_api_p95_s"] = 0.000001
        app = server()
        try:
            rep = runner.run_profile(p, app.url)
        finally:
            app.stop()
        self.assertIn("verfehlt", {e["verdict"] for e in rep["evaluation"]})


class RestoreVorlage(unittest.TestCase):
    def setUp(self):
        self.tpl = json.loads((PERF_DIR / "templates" / "restore-drehbuch-vorlage.json").read_text())

    def test_vorlage_enthaelt_alle_protokollfelder_aus_kap_8_6(self):
        restore.validate_form(self.tpl)
        for k in restore.REQUIRED_FIELDS:
            self.assertIn(k, self.tpl)
        self.assertIn("zeitpunkt", self.tpl["letzter_wiederherstellbarer_commit"])
        self.assertEqual((self.tpl["referenzziele"]["rpo_min"], self.tpl["referenzziele"]["rto_h"]), (15, 2))
        self.assertEqual(self.tpl["status"], "VORLAGE")
        self.assertIn("Providerreferenzen", " ".join(s["schritt"] for s in self.tpl["schritte"]))

    def test_vorlage_ist_nicht_bewertbar(self):
        with self.assertRaises(ValueError):
            restore.evaluate(self.tpl)

    def test_auswertung_rpo_rto_rechenprobe(self):
        r = copy.deepcopy(self.tpl)
        r.update(status="DURCHGEFUEHRT", ausfallzeitpunkt="2026-06-01T10:00:00Z", zeitpunkt_e2e_probe="2026-06-01T11:30:00Z")
        r["letzter_wiederherstellbarer_commit"] = {"zeitpunkt": "2026-06-01T09:50:00Z", "referenz": "Rechenprobe"}
        b = restore.evaluate(r)["bewertung"]
        self.assertEqual((b["rpo_min_gemessen"], b["rto_h_gemessen"], b["rpo_erfuellt"], b["rto_erfuellt"]), (10.0, 1.5, True, True))
        r["zeitpunkt_e2e_probe"] = "2026-06-01T12:30:00Z"
        r["letzter_wiederherstellbarer_commit"]["zeitpunkt"] = "2026-06-01T09:30:00Z"
        b = restore.evaluate(r)["bewertung"]
        self.assertEqual((b["rpo_erfuellt"], b["rto_erfuellt"]), (False, False))

    def test_negativ_fehlende_felder_und_widerspruch(self):
        r = copy.deepcopy(self.tpl)
        r["status"] = "DURCHGEFUEHRT"
        with self.assertRaises(ValueError):
            restore.evaluate(r)
        r.update(ausfallzeitpunkt="2026-06-01T10:00:00Z", zeitpunkt_e2e_probe="2026-06-01T09:00:00Z")
        r["letzter_wiederherstellbarer_commit"]["zeitpunkt"] = "2026-06-01T09:50:00Z"
        with self.assertRaises(ValueError):
            restore.evaluate(r)


class Vorlagen(unittest.TestCase):
    def test_messberichtvorlage_nennt_kopffelder(self):
        t = (PERF_DIR / "templates" / "messbericht-vorlage.md").read_text()
        for f in ("Hardware", "Datenstand", "Stichprobe", "Rohdatenablage"):
            self.assertIn(f, t)

    def test_berichtsschema_lehnt_unvollstaendigen_bericht_ab(self):
        import jsonschema
        with self.assertRaises(jsonschema.ValidationError):
            report.validate({"report_version": "1.0", "kind": "spike", "title": "x"})


if __name__ == "__main__":
    unittest.main()
