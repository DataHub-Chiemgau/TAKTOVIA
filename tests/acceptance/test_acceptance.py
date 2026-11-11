"""Tests des Akzeptanzgerüsts (WP-M0-22): Seed, Evidenz, Katalog, Testzeit, Mangelklassen."""
import copy
import csv
import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import REPO, catalog, evidence as ev  # noqa: E402
from taktovia_at import db as atdb, scenario as sc, seed  # noqa: E402
from taktovia_at.defects import classify  # noqa: E402
import taktovia_at.scenarios  # noqa: E402,F401

DB1, DB2 = "taktovia_at_selftest_1", "taktovia_at_selftest_2"
CLOCK = "2026-02-02T08:00:00Z"


def setUpModule():
    for n in (DB1, DB2):
        atdb.reset_database(n)


def tearDownModule():
    for n in (DB1, DB2):
        atdb.drop_database(n)


class SeedTest(unittest.TestCase):
    def test_wiederholbar_identische_ids_und_inhalte_in_zwei_frischen_datenbanken(self):
        r1, r2 = seed.seed_two_tenants(DB1), seed.seed_two_tenants(DB2)
        self.assertEqual(r1["data_state_sha256"], r2["data_state_sha256"])
        self.assertEqual(r1, r2)

    def test_idempotent_im_selben_lauf_keine_doppelten_zeilen(self):
        h1 = seed.seed_two_tenants(DB1)["data_state_sha256"]
        h2 = seed.seed_two_tenants(DB1)["data_state_sha256"]
        self.assertEqual(h1, h2)
        for label, tid in seed.TENANTS.items():
            with atdb.tx(DB1, tid) as c:
                for t, n in (("passenger_private", 1), ("vehicle", 1), ("expense_document", 1), ("cost_object", 1),
                             ("legal_entity", 1), ("organization", 1), ("membership", 6)):
                    self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t}").fetchone()[0], n, (label, t))

    def test_ids_sind_festgelegt(self):
        ids = seed.fixture_ids()
        self.assertEqual(ids, seed.fixture_ids())
        flat = [v for d in ids.values() for v in d.values()]
        self.assertEqual(len(flat), len(set(flat)))   # keine Kollision zwischen A und B
        self.assertEqual(str(ids["A"]["tenant"]), "00000000-0000-4000-8000-00000000000a")   # WP-M0-09

    def test_seed_ohne_sonderrechte(self):
        r = seed.seed_two_tenants(DB1)
        self.assertFalse(r["privileged"])
        self.assertEqual(r["tenant_creation_role"], ["taktovia_migrator"])
        self.assertEqual(r["data_role"], ["taktovia_runtime"])

    def test_zwei_mandanten_fixture_inhalt_und_kostenstelle(self):
        seed.seed_two_tenants(DB1)
        ids = seed.fixture_ids()
        rows = {}
        for label in "AB":
            with atdb.tx(DB1, ids[label]["tenant"]) as c:
                rows[label] = c.execute("SELECT kind, business_ref FROM taktovia.cost_object").fetchall()
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.tenant").fetchone()[0], 1)
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.passenger_private WHERE id=%s",
                                           (ids["B" if label == "A" else "A"]["passenger"],)).fetchone()[0], 0)
        self.assertEqual(rows["A"], [("COST_CENTER", "KST-1000")])
        self.assertEqual(rows["A"], rows["B"])    # identisch benannt in A und B

    def test_keine_personendaten_im_seed(self):
        seed.seed_two_tenants(DB1)
        # Die Runtime-Rolle darf die Ciphertext-Spalten nicht lesen (WP-M1-31); der Nachweis „keine Kontaktdaten“ läuft über die Administration.
        with atdb.dbtool.admin(DB1) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.passenger_private WHERE contact_ciphertext IS NOT NULL OR support_ciphertext IS NOT NULL").fetchone()[0], 0)


class EvidenzTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rec, cls.path, cls.err = sc.execute("TECH-SMOKE-001", DB1, CLOCK, tempfile.mkdtemp())

    def test_beispielszenario_erzeugt_gueltige_evidenz_mit_build_und_migrationsstand(self):
        r = self.rec
        self.assertEqual((r["result"], self.err), ("PASSED", None))
        ev.validate(r)
        self.assertTrue(self.path.exists())
        self.assertTrue(r["software_build"]["build_id"])
        import re as _re
        newest = max((p.name for p in (Path(__file__).resolve().parents[2] / "db/migrations").glob("V*__*.sql")), key=lambda n: int(_re.match(r"V(\d+)__", n).group(1)))
        self.assertEqual(r["migration_state"]["latest"], newest)
        self.assertEqual(r["acceptance_decision"], "PENDING")
        self.assertEqual(r["source_key"], ["ENABLER:WP-M0-22"])
        self.assertIn("psp-simulator", r["environment"]["simulated_components"])
        json.loads(self.path.read_text())

    def test_alle_pflichtfelder_aus_kap_8_4_vorhanden(self):
        pflicht = {"source_key", "test", "software_build", "migration_state", "revisions", "seed", "roles_and_tenants",
                   "soll_ist", "event_trace_ids", "result", "defect_class", "responsible", "acceptance_decision"}
        self.assertTrue(pflicht <= set(self.rec))
        self.assertTrue({"id", "version"} <= set(self.rec["test"]))
        self.assertTrue({"config_revision", "tariff_revision", "contract_revision"} <= set(self.rec["revisions"]))

    def test_zwei_laeufe_gleiche_startzeit_identische_zeitstempel_und_evidenz(self):
        rec2, _, _ = sc.execute("TECH-SMOKE-001", DB1, CLOCK, None)
        self.assertEqual(self.rec, rec2)
        self.assertEqual(self.rec["executed_at"], "2026-02-02T08:00:00.000Z")
        stamps = [c["actual"] for c in self.rec["soll_ist"] if "Zeitstempel" in c["step"] or "Hold-Ablauf" in c["step"]]
        self.assertEqual(stamps, ["2026-02-02T08:00:00.000Z", "2026-02-02T08:03:00.000Z"])

    def test_andere_startzeit_andere_zeitstempel(self):
        rec3, _, _ = sc.execute("TECH-SMOKE-001", DB1, "2027-03-03T03:03:03Z", None)
        self.assertEqual(rec3["result"], "PASSED")
        self.assertNotEqual(rec3["executed_at"], self.rec["executed_at"])
        self.assertNotEqual(rec3["run_id"], self.rec["run_id"])

    def test_negativ_unvollstaendige_evidenz(self):
        for feld in ("source_key", "software_build", "migration_state", "seed", "roles_and_tenants", "soll_ist",
                     "event_trace_ids", "result", "defect_class", "responsible", "acceptance_decision", "revisions"):
            bad = copy.deepcopy(self.rec)
            del bad[feld]
            with self.assertRaises(ev.EvidenceError, msg=feld):
                ev.validate(bad)

    def test_negativ_mangelklasse_und_ergebnis_muessen_zusammenpassen(self):
        bad = copy.deepcopy(self.rec)
        bad["result"] = "FAILED"
        with self.assertRaises(ev.EvidenceError):
            ev.validate(bad)           # FAILED ohne Mangelklasse
        bad["defect_class"] = "A"
        ev.validate(bad)
        bad["result"] = "PASSED"
        with self.assertRaises(ev.EvidenceError):
            ev.validate(bad)           # PASSED mit Mangelklasse
        bad["defect_class"], bad["acceptance_decision"] = None, "VIELLEICHT"
        with self.assertRaises(ev.EvidenceError):
            ev.validate(bad)

    def test_negativ_personenbezogene_felder_werden_abgewiesen(self):
        faelle = {
            "markiertes Feld": lambda r: r["soll_ist"].append({"step": "x", "expected": 1, "actual": 1, "ok": True, "kontakt__pii": "x"}),
            "Markierung personal_data": lambda r: r["seed"].update({"personal_data": True}),
            "Feldname E-Mail": lambda r: r["soll_ist"][0].update({"expected": {"passenger_email": "x"}}),
            "E-Mail-Wert": lambda r: r["soll_ist"][0].update({"actual": "kontakt: max.muster@example.com"}),
            "Telefon-Wert": lambda r: r["soll_ist"][0].update({"actual": "+49 170 1234567"}),
            "IBAN-Wert": lambda r: r["soll_ist"][0].update({"actual": "DE89 3704 0044 0532 0130 00"}),
            "Chiffrat": lambda r: r["soll_ist"][0].update({"expected": {"contact_ciphertext": "00"}}),
        }
        for name, mut in faelle.items():
            bad = copy.deepcopy(self.rec)
            mut(bad)
            with self.subTest(name):
                with self.assertRaises(ev.EvidenceError):
                    ev.validate(bad)
        self.assertEqual(ev.check_no_personal_data(self.rec), [])   # der echte Datensatz ist sauber

    def test_evidenz_enthaelt_keine_seed_personenangaben(self):
        txt = json.dumps(self.rec)
        self.assertNotIn("example.test", txt)
        self.assertNotIn("@", txt)

    def test_fehlschlag_erzeugt_mangelklasse_und_blockiert_nicht_stillschweigend(self):
        @sc.scenario("TECH-FAIL-001", "1", ["ENABLER:WP-M0-22"], "Absichtlicher Fehlschlag (Selbsttest)")
        def failing(ctx):
            ctx.use_role("passenger", "A", ctx.identities["A"]["passenger"])
            ctx.check("A sieht B-Daten nicht", 0, 1)
            ctx.fail_with("DATA_DISCLOSURE")
        try:
            r, _, _ = sc.execute("TECH-FAIL-001", DB1, CLOCK, None)
        finally:
            sc.REGISTRY.pop("TECH-FAIL-001")
        self.assertEqual((r["result"], r["defect_class"]), ("FAILED", "A"))

    def test_unerwartete_ausnahme_ist_fehlschlag(self):
        @sc.scenario("TECH-FAIL-002", "1", ["ENABLER:WP-M0-22"], "Ausnahme (Selbsttest)")
        def boom(ctx):
            raise RuntimeError("kaputt")
        try:
            r, _, err = sc.execute("TECH-FAIL-002", DB1, CLOCK, None)
        finally:
            sc.REGISTRY.pop("TECH-FAIL-002")
        self.assertEqual((r["result"], r["defect_class"]), ("FAILED", "C"))
        self.assertIn("RuntimeError", err)


class SzenarioKennzeichnung(unittest.TestCase):
    def test_negativ_szenario_ohne_test_id(self):
        for bad in (None, "", "foo", "AT-1", "E2E-12"):
            with self.assertRaises(sc.ScenarioError, msg=repr(bad)):
                sc.scenario(bad, "1", ["ENABLER:WP-M0-22"], "x")

    def test_negativ_szenario_ohne_version_oder_source_key(self):
        with self.assertRaises(sc.ScenarioError):
            sc.scenario("AT-01", "", ["LH-6.1-SYS-001"], "x")
        for keys in ([], None, ["freitext"]):
            with self.assertRaises(sc.ScenarioError):
                sc.scenario("AT-01", "1", keys, "x")

    def test_doppelte_registrierung_abgewiesen(self):
        with self.assertRaises(sc.ScenarioError):
            sc.scenario("TECH-SMOKE-001", "1", ["ENABLER:WP-M0-22"], "x")(lambda c: None)

    def test_registrierte_szenarien_tragen_kennzeichnung(self):
        m = sc.REGISTRY["TECH-SMOKE-001"].meta
        self.assertEqual((m["id"], m["version"], m["source_key"]), ("TECH-SMOKE-001", "1.0.0", ["ENABLER:WP-M0-22"]))


class KatalogTest(unittest.TestCase):
    def test_genau_106_plus_22_szenarien_mit_zielmeilenstein(self):
        cat = catalog.load()
        kinds = [r["kind"] for r in cat]
        self.assertEqual(len(cat), 128)
        self.assertEqual((kinds.count("AT") + kinds.count("E2E"), kinds.count("PH-AT")), (106, 22))
        for r in cat:
            self.assertIn(r["target_milestone"], catalog.MILESTONES, r["test_id"])
        self.assertEqual(len({r["test_id"] for r in cat}), 128)

    def test_ids_entsprechen_e2e_csv_und_ph_at_001_bis_022(self):
        with open(REPO / "docs/pflichtenheft/e2e.csv", newline="") as f:
            e2e = [r["test_id_original"] for r in csv.DictReader(f, delimiter=";")]
        self.assertEqual(len(e2e), 106)
        ids = [r["test_id"] for r in catalog.load()]
        self.assertEqual(ids[:106], e2e)
        self.assertEqual(ids[106:], [f"PH-AT-{i:03d}" for i in range(1, 23)])

    def test_katalog_ist_aus_den_quellen_reproduzierbar(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))
        import build_catalog
        self.assertEqual(catalog.CATALOG_PATH.read_text(), build_catalog.render(build_catalog.build()))

    def test_nicht_implementiert_und_runner_meldet_exit_2(self):
        import run
        for r in catalog.load():
            self.assertEqual(r["status"], "NOT_IMPLEMENTED")
        self.assertEqual(run.main(["--scenario", "PH-AT-001", "--db", DB1, "--out", tempfile.mkdtemp()]), 2)

    def test_ph_at_001_fixture_bezug(self):
        r = next(x for x in catalog.load() if x["test_id"] == "PH-AT-001")
        self.assertIn("LH-6.1-SYS-001", r["source_keys"])


class RunnerTest(unittest.TestCase):
    def test_runner_smoke_exit_0_und_list(self):
        import run
        out = tempfile.mkdtemp()
        self.assertEqual(run.main(["--scenario", "TECH-SMOKE-001", "--db", DB1, "--clock-start", CLOCK, "--out", out]), 0)
        self.assertEqual(len(list(Path(out).glob("TECH-SMOKE-001-run-*.json"))), 1)
        self.assertEqual(run.main(["--list"]), 0)


class MangelklassenTest(unittest.TestCase):
    def test_regel(self):
        for cat in ("TRAFFIC_SAFETY", "DATA_DISCLOSURE", "DOUBLE_BOOKING", "CORE_BOOKING_OUTAGE"):
            self.assertEqual(classify([cat])["defect_class"], "A")
            self.assertEqual(classify([cat], workaround_approved=True, remediation_date=True)["defect_class"], "A")
        b = classify([], impairs_core=True)
        self.assertEqual((b["defect_class"], b["release_blocked"]), ("B", True))
        self.assertFalse(classify([], True, True, True)["release_blocked"])
        self.assertEqual(classify([])["defect_class"], "C")


class DatenbankrolleTest(unittest.TestCase):
    def test_runtime_rolle_unprivilegiert(self):
        with atdb.tx(DB1, None) as c:
            r = c.execute("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname=current_user").fetchone()
        self.assertEqual(r, (False, False))

    def test_reset_fremder_datenbanken_verweigert(self):
        for fn in (atdb.reset_database, atdb.drop_database):
            with self.assertRaises(ValueError):
                fn("taktovia_test")


if __name__ == "__main__":
    unittest.main()
