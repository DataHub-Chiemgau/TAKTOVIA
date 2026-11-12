"""WP-M2-02: Referenzfixtures laden reproduzierbar auf leerer, migrierter Datenbank unter der Migrator-Rolle und sind unter der Runtime-Rolle mit
Tenantkontext lesbar; Mandant A sieht nichts von Mandant B; Fixture-Daten und Erwartungen sind in sich schlüssig.
Aufruf: python -m unittest discover -s tests/acceptance -p 'test_m2_referenzregion.py' (DB-Umgebung wie db/db.py; legt eine eigene Datenbank taktovia_at_m2ref an)."""
import hashlib
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at import referenzregion as rr  # noqa: E402

DB = "taktovia_at_m2ref"


def _state_hash(dbname):
    """Hash über die Fixture-Zeilen (ohne Uhrzeitspalten): gleicher Seed, gleicher Datenstand."""
    h = hashlib.sha256()
    for label, tid in rr.TENANTS.items():
        c = atdb.runtime_conn(dbname)
        try:
            atdb.set_tenant(c, tid)
            for t in ("service", "vehicle", "staff_shift", "resource_release", "vehicle_pool_assignment"):
                rows = c.execute(f"SELECT row_to_json(t)::text FROM taktovia.{t} t WHERE id IN (SELECT id FROM taktovia.{t}) ORDER BY id").fetchall()
                dec = [{k: v for k, v in json.loads(r[0]).items() if k not in {"created_at", "updated_at"}} for r in rows]
                h.update(json.dumps([label, t, dec], sort_keys=True).encode())
        finally:
            c.close()
    return h.hexdigest()


class ReferenzregionDb(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        atdb.reset_database(DB)

    @classmethod
    def tearDownClass(cls):
        atdb.drop_database(DB)

    def test_1_laedt_unter_migrator_ohne_superuser_und_ist_idempotent(self):
        first = rr.load(DB)
        self.assertEqual(first["role"], "taktovia_migrator")
        self.assertFalse(first["privileged"])
        a = first["counts"]["a"]
        self.assertEqual((a["service"], a["vehicle"], a["staff_shift"], a["resource_release"], a["vehicle_pool_assignment"]), (3, 9, 9, 8, 9))
        self.assertEqual(first["counts"]["b"]["vehicle"], 2)
        h1 = _state_hash(DB)
        second = rr.load(DB)   # zweites Laden ändert nichts
        self.assertEqual(second["counts"], first["counts"])
        self.assertEqual(_state_hash(DB), h1)

    def test_2_reproduzierbar_gleicher_datenstand_auf_zweiter_datenbank(self):
        other = "taktovia_at_m2ref_b"
        try:
            atdb.reset_database(other)
            rr.load(other)
            rr.load(DB)
            self.assertEqual(_state_hash(other), _state_hash(DB))
        finally:
            atdb.drop_database(other)

    def test_3_runtime_liest_mit_tenantkontext_und_sieht_keine_fremden_zeilen(self):
        rr.load(DB)
        for label, other in (("a", "b"), ("b", "a")):
            c = atdb.runtime_conn(DB)
            try:
                atdb.set_tenant(c, rr.TENANTS[label])
                ids = {r[0] for r in c.execute("SELECT id FROM taktovia.vehicle").fetchall()}
                self.assertIn(rr.fixture_id(label, "veh-v-wc"), ids)
                self.assertFalse(ids & {rr.fixture_id(other, "veh-v-wc"), rr.fixture_id(other, "veh-v-blocked")}, "fremde Fahrzeuge sichtbar")
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE tenant_id <> %s", (rr.TENANTS[label],)).fetchone()[0], 0)
            finally:
                c.close()
        c = atdb.runtime_conn(DB)   # ohne Kontext: keine Zeilen
        try:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle").fetchone()[0], 0)
        finally:
            c.close()

    def test_4_runtime_pflegt_den_pool_nur_anhaengend_und_nur_im_eigenen_mandanten(self):
        """Seit WP-M3-06 pflegt die Runtime-Rolle die Dienstzuordnung (Anhängen, Ende setzen); löschen und fremde Mandanten bleiben ausgeschlossen."""
        import psycopg
        c = atdb.runtime_conn(DB)
        try:
            atdb.set_tenant(c, rr.TENANTS["a"])
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # kein Löschen
                c.execute("DELETE FROM taktovia.vehicle_pool_assignment WHERE tenant_id = %s", (rr.TENANTS["a"],))
            c.rollback()
            atdb.set_tenant(c, rr.TENANTS["a"])
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Zeile eines fremden Mandanten (RLS)
                c.execute("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (%s,%s,%s,%s,now(),'system:t')",
                          (rr.TENANTS["b"], rr.fixture_id("b", "x"), rr.fixture_id("b", "svc-nord"), rr.fixture_id("b", "veh-v-wc")))
        finally:
            c.rollback(); c.close()

    def test_5_pool_ist_je_dienst_definiert(self):
        rr.load(DB)
        c = atdb.runtime_conn(DB)
        try:
            atdb.set_tenant(c, rr.TENANTS["a"])
            nord = {r[0] for r in c.execute("SELECT vehicle_id FROM taktovia.vehicle_pool_assignment WHERE service_id = %s", (rr.fixture_id("a", "svc-nord"),)).fetchall()}
            sued = {r[0] for r in c.execute("SELECT vehicle_id FROM taktovia.vehicle_pool_assignment WHERE service_id = %s", (rr.fixture_id("a", "svc-sued"),)).fetchall()}
            self.assertEqual(len(nord), 8)
            self.assertEqual(sued, {rr.fixture_id("a", "veh-v-sued")})
            self.assertFalse(nord & sued)
        finally:
            c.close()


class FixtureDateien(unittest.TestCase):
    def test_stops_liegen_auf_graphknoten_und_haben_gueltige_barrierefreiheit(self):
        g = {n["id"]: n for n in rr.graph()["nodes"]}
        schema = json.loads((Path(__file__).resolve().parents[2] / "docs/umsetzungsplan/vertraege/schemas/stop-accessibility.v1.schema.json").read_text())
        from jsonschema import Draft202012Validator
        v = Draft202012Validator(schema)
        for s in rr.region()["stops"]:
            self.assertEqual((s["lat"], s["lon"]), (g[s["node"]]["lat"], g[s["node"]]["lon"]), s["id"])
            v.validate(s["accessibility"])

    def test_graph_enthaelt_sperre_einbahn_restriktion_und_insel(self):
        g = rr.graph()
        e = {x["id"]: x for x in g["edges"]}
        self.assertTrue(e["e23"]["closed"]); self.assertTrue(e["e56"]["oneway"]); self.assertEqual(e["e45"]["max_height_m"], 2.5)
        self.assertEqual(e["f15"]["modes"], ["walk"])
        used = {x["from"] for x in g["edges"]} | {x["to"] for x in g["edges"]}
        self.assertNotIn("N10", used)

    def test_erwartungen_stimmen_mit_einer_unabhaengigen_dijkstra_rechnung_ueberein(self):
        """Kreuzprüfung der von Hand berechneten Werte gegen eine kleine Dijkstra-Rechnung im Test (keine Produktivimplementierung)."""
        import heapq
        g = rr.graph(); node_of = {s["id"]: s["node"] for s in rr.region()["stops"]}
        prof = g["profiles"]
        def solve(a, b, p):
            P = prof[p]; adj = {}
            for e in g["edges"]:
                if e["closed"] or P["mode"] not in e["modes"]: continue
                if P["mode"] == "car" and e["max_height_m"] is not None and P["height_m"] > e["max_height_m"]: continue
                w = e["time_s"] if P["mode"] == "car" else round(e["length_m"] / g["walk_speed_mps"])
                adj.setdefault(e["from"], []).append((e["to"], w, e["length_m"]))
                if not (e["oneway"] and P["mode"] == "car"): adj.setdefault(e["to"], []).append((e["from"], w, e["length_m"]))
            best, q = {}, [(0, 0, a)]
            while q:
                t, d, n = heapq.heappop(q)
                if n in best: continue
                best[n] = (t, d)
                for m, w, l in adj.get(n, ()): heapq.heappush(q, (t + w, d + l, m))
            return best.get(b)
        for r in rr.expectations()["relationen"]:
            got = solve(node_of[r["from"]], node_of[r["to"]], r["profile"])
            if r["time_s"] is None:
                self.assertIsNone(got, r["id"])
            else:
                self.assertEqual(got, (r["time_s"], r["distance_m"]), r["id"])

    def test_tarifrelationen_erfinden_keine_preise(self):
        for r in rr.tariff_relations()["relationen"]:
            self.assertIsNone(r["expected_price_minor"])

    def test_fahrgaeste_ohne_diagnosen_und_kontaktdaten(self):
        text = json.dumps(rr.passengers()["fahrgaeste"])
        for verboten in ("diagn", "krank", "mail", "telefon", "name\""):
            self.assertNotIn(verboten, text.lower())


if __name__ == "__main__":
    unittest.main()
