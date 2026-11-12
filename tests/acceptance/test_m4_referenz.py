"""WP-M4-02: Referenzdaten für M4 laden reproduzierbar auf leerer, migrierter Datenbank unter der Migrator-Rolle und sind unter der Runtime-Rolle mit Tenantkontext lesbar;
Mandant A sieht nichts von Mandant B; ohne Kontext keine Zeile; Dateien und Sollwerte sind in sich schlüssig und enthalten keine echten Daten.
Aufruf: python -m unittest discover -s tests/acceptance -p 'test_m4_referenz.py' (DB-Umgebung wie db/db.py; legt eine eigene Datenbank taktovia_at_m4ref an)."""
import hashlib
import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at import m4_referenz as rr  # noqa: E402

DB = "taktovia_at_m4ref"
ROOT = Path(__file__).resolve().parents[2]


def _state_hash(dbname):
    """Hash über die Fixture-Zeilen (ohne Uhrzeitspalten): gleicher Seed, gleicher Datenstand."""
    h = hashlib.sha256()
    for label, tid in rr.TENANTS.items():
        c = atdb.runtime_conn(dbname)
        try:
            atdb.set_tenant(c, tid)
            for t in rr.TABLES:
                rows = c.execute(f"SELECT row_to_json(t)::text FROM taktovia.{t} t ORDER BY 1").fetchall()
                dec = [{k: v for k, v in json.loads(r[0]).items() if k not in {"created_at", "updated_at", "received_at"}} for r in rows]
                h.update(json.dumps([label, t, sorted(dec, key=lambda d: json.dumps(d, sort_keys=True))], sort_keys=True).encode())
        finally:
            c.close()
    return h.hexdigest()


class M4ReferenzDb(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        atdb.reset_database(DB)

    @classmethod
    def tearDownClass(cls):
        atdb.drop_database(DB)

    def test_1_laedt_in_leere_datenbank_unter_migrator_ohne_superuser_und_ist_idempotent(self):
        first = rr.load(DB)
        self.assertEqual(first["role"], "taktovia_migrator")
        self.assertFalse(first["privileged"])
        a, b = first["counts"]["a"], first["counts"]["b"]
        self.assertEqual((a["legal_entity"], a["depot"], a["cost_object"], a["vehicle"], a["vehicle_assignment"], a["driver"], a["qualification_revision"], a["staff_shift"]), (2, 2, 2, 2, 4, 2, 3, 3))
        self.assertEqual((a["vehicle_run"], a["route_stop"], a["booking"], a["position_sample"], a["travel_attempt"]), (1, 3, 1, 2, 10))
        self.assertEqual((b["vehicle"], b["driver"], b["staff_shift"], b["booking"], b["travel_attempt"]), (2, 2, 3, 0, 0))
        h1 = _state_hash(DB)
        second = rr.load(DB)
        self.assertEqual(second["counts"], first["counts"])
        self.assertEqual(_state_hash(DB), h1)

    def test_2_reproduzierbar_gleicher_datenstand_und_gleiche_ids_auf_zweiter_datenbank(self):
        other = "taktovia_at_m4ref_b"
        try:
            atdb.reset_database(other)
            rr.load(other); rr.load(DB)
            self.assertEqual(_state_hash(other), _state_hash(DB))
            c = atdb.runtime_conn(other)
            try:
                atdb.set_tenant(c, rr.TENANTS["a"])
                ids = {r[0] for r in c.execute("SELECT id FROM taktovia.vehicle").fetchall()}
                self.assertEqual(ids, {rr.fixture_id("a", "veh-1"), rr.fixture_id("a", "veh-2")})
            finally:
                c.close()
        finally:
            atdb.drop_database(other)

    def test_3_runtime_sieht_nur_den_eigenen_mandanten_und_ohne_kontext_nichts(self):
        rr.load(DB)
        for label, other in (("a", "b"), ("b", "a")):
            c = atdb.runtime_conn(DB)
            try:
                atdb.set_tenant(c, rr.TENANTS[label])
                for t in ("vehicle", "driver", "staff_shift", "qualification_revision", "cost_object", "depot", "legal_entity"):
                    self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t} WHERE tenant_id <> %s", (rr.TENANTS[label],)).fetchone()[0], 0, t)
                ids = {r[0] for r in c.execute("SELECT id FROM taktovia.driver").fetchall()}
                self.assertIn(rr.fixture_id(label, "drv-1"), ids); self.assertNotIn(rr.fixture_id(other, "drv-1"), ids)
            finally:
                c.close()
        c = atdb.runtime_conn(DB)
        try:
            for t in rr.TABLES:
                self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t}").fetchone()[0], 0, f"{t} ohne Tenantkontext")
        finally:
            c.close()

    def test_4_seed_entspricht_dem_katalog_und_den_dokumentierten_kilometern(self):
        rr.load(DB)
        k = rr.katalog()["mandant_a"]; mf = rr.musterfahrt()
        c = atdb.runtime_conn(DB)
        try:
            atdb.set_tenant(c, rr.TENANTS["a"])
            rows = c.execute("SELECT seq, planned_at, leg_duration_sec, leg_distance_m FROM taktovia.route_stop WHERE run_id=%s AND run_revision=1 ORDER BY seq", (rr.fixture_id("a", "run-1"),)).fetchall()
            self.assertEqual(len(rows), len(k["fahrtenplan"]["stopps"]))
            for r, s in zip(rows, k["fahrtenplan"]["stopps"]):
                self.assertEqual(r[1].strftime("%Y-%m-%dT%H:%M:%SZ"), s["planned_at"]); self.assertEqual((r[2], r[3]), (s["dauer_s"], s["strecke_m"]))
            self.assertEqual(sum(r[3] or 0 for r in rows), mf["summe"]["strecke_m"]); self.assertEqual(sum(r[2] or 0 for r in rows), mf["summe"]["dauer_s"])
            promise = c.execute("SELECT promise_snapshot FROM taktovia.booking WHERE id=%s", (rr.fixture_id("a", "bkg-1"),)).fetchone()[0]
            for key, val in k["buchung"]["zusage"].items():
                self.assertEqual(promise[key], val)
            self.assertEqual(rr.sollwerte()["Musterfahrt"]["strecke_m"], mf["summe"]["strecke_m"])
            self.assertEqual(mf["summe"]["besetzt_strecke_m"] + mf["summe"]["leer_strecke_m"], mf["summe"]["strecke_m"])
        finally:
            c.close()

    def test_5_ph_at_021_zehn_versuche_drei_ablehnungen_primaergruende_summe_drei(self):
        rr.load(DB)
        want = rr.sollwerte()["PH-AT-021"]
        c = atdb.runtime_conn(DB)
        try:
            atdb.set_tenant(c, rr.TENANTS["a"])
            n, rej, prim, with_secondary = c.execute("""SELECT count(*), count(*) FILTER (WHERE outcome='REJECTED'), count(primary_rejection),
                                                        count(*) FILTER (WHERE outcome='REJECTED' AND cardinality(secondary_rejections) > 0) FROM taktovia.travel_attempt""").fetchone()
            self.assertEqual((n, rej, prim, with_secondary), (want["versuche"], want["ablehnungen"], want["summe_primaergruende"], want["ablehnungen_mit_nebengruenden"]))
            by = dict(c.execute("SELECT primary_rejection, count(*) FROM taktovia.travel_attempt WHERE primary_rejection IS NOT NULL GROUP BY 1").fetchall())
            self.assertEqual(by, want["primaergruende"])
        finally:
            c.close()

    def test_6_runtime_darf_den_seed_nicht_umschreiben_wo_unveraenderlich(self):
        import psycopg
        rr.load(DB)
        c = atdb.runtime_conn(DB)
        try:
            atdb.set_tenant(c, rr.TENANTS["a"])
            with self.assertRaises(psycopg.errors.Error):
                c.execute("UPDATE taktovia.route_stop SET leg_distance_m = 1 WHERE tenant_id=%s", (rr.TENANTS["a"],))
        finally:
            c.rollback(); c.close()


class M4ReferenzDateien(unittest.TestCase):
    def test_allokation_largest_remainder_summe_exakt_und_stabil(self):
        w = rr.sollwerte()["PH-AT-013"]
        total, keys = w["verteilbar_minor"], w["schluessel"]
        base, rest = divmod(total, len(keys))   # ganzzahlig, kein Float
        shares = {k: base + (1 if i < rest else 0) for i, k in enumerate(keys)}   # Reihenfolge A/B/C stabil
        self.assertEqual(shares, w["anteile_minor"]); self.assertEqual(sum(shares.values()), total)

    def test_steuerrechnung_und_periode_sind_ganzzahlig_konsistent(self):
        s = rr.steuerregel()["beispiel"]
        self.assertEqual(s["netto_minor"] + s["steuer_minor"], s["brutto_minor"]); self.assertEqual(s["netto_minor"] * 19 // 100, s["steuer_minor"])
        w = rr.sollwerte()
        self.assertEqual(w["PH-AT-012"]["brutto_minor"], s["brutto_minor"]); self.assertEqual(w["PH-AT-012"]["kostenbasis_minor"], s["netto_minor"])
        p = w["PH-AT-015"]
        self.assertEqual(p["plan_budget_minor"] + p["ist_kosten_minor"], p["nicht_addieren_minor"]); self.assertEqual(p["gesamtansicht_minor"], p["ist_kosten_minor"])
        self.assertEqual(p["september_kosten_minor"] + p["oktober_gutschrift_minor"], 8000)

    def test_kontenrahmen_ist_vollstaendig_und_als_test_gekennzeichnet(self):
        kr = rr.kontenrahmen()
        self.assertIn("NICHT der freigegebene", kr["_hinweis"])
        nummern = {k["nummer"] for k in kr["konten"]}
        self.assertEqual(len(nummern), len(kr["konten"]))
        for ka in kr["kostenarten"]:
            self.assertIn(ka["konto"], nummern)

    def test_dst_stichtage_stimmen_mit_der_zeitzone_ueberein(self):
        from datetime import datetime, timedelta, timezone
        from zoneinfo import ZoneInfo
        d = rr.dst_stichtage(); z = ZoneInfo(d["zone"])
        def hours(day):
            start = datetime.fromisoformat(day).replace(tzinfo=z); nxt = start + timedelta(days=1)
            return round((nxt.astimezone(timezone.utc) - start.astimezone(timezone.utc)).total_seconds() / 3600)
        self.assertEqual(hours(d["herbst"]["datum"]), d["herbst"]["stunden_am_tag"]); self.assertEqual(hours(d["fruehjahr"]["datum"]), d["fruehjahr"]["stunden_am_tag"])
        for off, utc in zip(d["herbst"]["offsets"], d["herbst"]["instants_utc"]):
            self.assertEqual(datetime.fromisoformat(f"2026-10-25T02:30:00{off}").astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), utc)
        for key, sh in (("herbst", "shift-dst-autumn"), ("fruehjahr", "shift-dst-spring")):
            s = next(x for x in rr.katalog()["je_mandant"]["schichten"] if x["key"] == sh)
            real = (datetime.fromisoformat(s["bis"].replace("Z", "+00:00")) - datetime.fromisoformat(s["von"].replace("Z", "+00:00"))).total_seconds() / 3600
            self.assertEqual(real, d[key]["schicht"]["dauer_real_h"])

    def test_evidenz_enthaelt_keine_echten_personen_oder_finanzdaten(self):
        # alle Namen sind als synthetisch gekennzeichnet; keine IBAN, keine E-Mail-Adresse, keine Telefonnummer, keine Kartennummer im Fixturebestand
        text = "\n".join(p.read_text(encoding="utf-8") for p in sorted((ROOT / "tests/acceptance/fixtures/m4_referenz").glob("*")) if p.suffix in {".sql", ".json", ".md"})
        for pat, what in ((r"\b[A-Z]{2}\d{2}(?: ?\d{4}){3,}", "IBAN"), (r"[\w.+-]+@[\w-]+\.[a-z]{2,}", "E-Mail"), (r"\+\d{2}[\d ()/-]{8,}", "Telefon"), (r"(?<![\w-])\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}(?![\w-])", "Kartennummer"), (r"(?<![\w.-])\d{13,19}(?![\w.-])", "lange Ziffernfolge")):
            self.assertIsNone(re.search(pat, text), what)
        self.assertGreater(len(re.findall(r"\(synthetisch\)", text)), 10)
        # jeder Klarname im Seed trägt die Kennzeichnung (Zeilen mit Namen enthalten „(synthetisch)“)
        lines = [ln for ln in (ROOT / "tests/acceptance/fixtures/m4_referenz/seed.sql").read_text(encoding="utf-8").splitlines() if re.search(r"'(?:Fahrer|Eigentümer|Betreiber|Rechtsträger) ", ln)]
        self.assertTrue(lines); self.assertTrue(all("(synthetisch)" in ln for ln in lines), lines)


if __name__ == "__main__":
    unittest.main()
