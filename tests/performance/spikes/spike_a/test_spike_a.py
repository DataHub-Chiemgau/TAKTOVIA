"""Selbsttest des Spike-(a)-Prototyps (verkleinerter Datenbestand, 30 statt 100 Parallelität, damit max_connections=100 genügt).

Die Messung mit 100 parallelen Transaktionen und 1.000 Durchläufen steht in run_spike_a.py und im Bericht
spike-a-hold-confirm.md. Dieser Test sichert Korrektheit und Randfälle des Prototyps, nicht Leistungswerte.
"""
import sys
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from spike_a import capacity as cap, geo, run_spike_a as rs, setup_data as sd  # noqa: E402
from spike_a.harness import Pool  # noqa: E402
from taktovia_at import db as atdb  # noqa: E402

DB = "taktovia_spike_selftest"
ST = cap.STRATEGIES["rc_run_row"]
PAR = 30
pool = None
runs = None


def setUpModule():
    global pool, runs
    sd.VEHICLES_PER_TENANT, sd.SPARE_RUNS_PER_TENANT, sd.HOLDS_PER_RUN = 10, 300, 5
    sd.STOPS_PER_TENANT, sd.SERVICES_PER_TENANT = 3000, 3
    atdb.reset_database(DB)
    sd.SPIKE_DB = DB
    sd.load_base_data(DB)
    pool = Pool(PAR, DB)
    runs = rs.Runs()


def tearDownModule():
    pool.close()
    atdb.drop_database(DB)


def leg(run, frm=0, to=2, seats=1, wc=0, rev=1):
    return {"run": run, "frm": frm, "to": to, "seats": seats, "wheelchair": wc, "expect_rev": rev}


class Einzeloperationen(unittest.TestCase):
    def setUp(self):
        self.t = sd.tid("A")
        self.c = pool.ctl[0]
        self.run = runs.take("A")

    def count(self, sql, *p):
        cur = rs.tx(self.c, self.t)
        n = cur.execute(sql, p).fetchone()[0]
        self.c.rollback()
        return n

    def test_hold_und_confirm_schreiben_outbox_in_derselben_transaktion(self):
        h = cap.hold(self.c, self.t, {"ref": "u1", "legs": [leg(self.run)]}, ST)
        self.assertEqual((h.kind, len(h.ids)), ("HELD", 1))
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=%s AND event_type='CapacityHeld'", h.ids[0]), 1)
        k = cap.confirm(self.c, self.t, h.ids, ST)
        self.assertEqual(k.kind, "CONFIRMED")
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.booking WHERE hold_id=%s AND state='CONFIRMED'", h.ids[0]), 1)
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=%s AND event_type='BookingConfirmed'", k.ids[0]), 1)

    def test_abgewiesener_hold_hinterlaesst_weder_hold_noch_outbox(self):
        before = self.count("SELECT count(*) FROM taktovia.outbox")
        o = cap.hold(self.c, self.t, {"ref": "u2", "legs": [leg(self.run, rev=99)]}, ST)
        self.assertEqual((o.kind, o.code), ("REJECTED", "REVISION_CONFLICT"))
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.capacity_hold WHERE federation_ref LIKE 'u2/%%'"), 0)
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.outbox"), before)

    def test_ueber_kapazitaet_und_rollstuhl_kein_ersatz(self):
        rs.prefill(self.c, self.t, self.run, seats=3, wc=1)
        o = cap.hold(self.c, self.t, {"ref": "u3a", "legs": [leg(self.run, 0, 8, seats=0, wc=1)]}, ST)
        self.assertEqual((o.kind, o.code), ("REJECTED", "NO_CAPACITY_WHEELCHAIR"))     # ein Sitz ist frei, der Rollstuhlplatz nicht
        ok = cap.hold(self.c, self.t, {"ref": "u3b", "legs": [leg(self.run, 0, 8)]}, ST)
        self.assertEqual(ok.kind, "HELD")
        o = cap.hold(self.c, self.t, {"ref": "u3c", "legs": [leg(self.run, 3, 5)]}, ST)
        self.assertEqual((o.kind, o.code), ("REJECTED", "NO_CAPACITY_SEATS"))

    def test_teilweise_ueberlappende_abschnitte(self):
        rs.prefill(self.c, self.t, self.run, seats=4, nseg=4)           # Abschnitte 0-3 voll
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "u4a", "legs": [leg(self.run, 3, 6)]}, ST).code, "NO_CAPACITY_SEATS")
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "u4b", "legs": [leg(self.run, 4, 8)]}, ST).kind, "HELD")   # [3,4) frei von Konflikt: halb offen

    def test_wiederholte_bestaetigung_findet_dieselbe_buchung(self):
        h = cap.hold(self.c, self.t, {"ref": "u5", "legs": [leg(self.run)]}, ST)
        a, b = cap.confirm(self.c, self.t, h.ids, ST), cap.confirm(self.c, self.t, h.ids, ST)
        self.assertEqual((a.ids, b.replayed, a.replayed), (b.ids, True, False))
        again = cap.hold(self.c, self.t, {"ref": "u5", "legs": [leg(self.run)]}, ST)       # idempotenter Hold
        self.assertEqual((again.replayed, again.ids), (True, h.ids))

    def test_abgelaufen_freigegeben_unbekannt(self):
        cur = rs.tx(self.c, self.t)
        hid = uuid.uuid5(cap.NS, f"t6|{self.run}")
        cur.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                    "VALUES (%s,%s,%s,1,'t6','{\"seats\":1,\"wheelchair\":0}'::jsonb,0,2,clock_timestamp(),'HELD')", (self.t, hid, self.run))
        self.c.commit()
        o = cap.confirm(self.c, self.t, [hid], ST)
        self.assertEqual((o.kind, o.code), ("REJECTED", "HOLD_EXPIRED"))
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.booking WHERE hold_id=%s", hid), 0)
        self.assertEqual(self.count("SELECT count(*) FROM taktovia.capacity_hold WHERE id=%s AND state='EXPIRED'", hid), 1)   # Ablauf wurde festgeschrieben
        self.assertEqual(cap.confirm(self.c, self.t, [hid], ST).code, "HOLD_EXPIRED")
        h = cap.hold(self.c, self.t, {"ref": "t6b", "legs": [leg(self.run, 4, 6)]}, ST)
        self.assertEqual(cap.release(self.c, self.t, h.ids, ST).kind, "CONFIRMED")
        self.assertEqual(cap.confirm(self.c, self.t, h.ids, ST).code, "HOLD_NOT_HELD")
        self.assertEqual(cap.confirm(self.c, self.t, [uuid.uuid4()], ST).code, "UNKNOWN_HOLD")

    def test_freigabe_und_storno_geben_kapazitaet_frei(self):
        rs.prefill(self.c, self.t, self.run, seats=3)
        h = cap.hold(self.c, self.t, {"ref": "t7", "legs": [leg(self.run, 0, 8)]}, ST)
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "t7b", "legs": [leg(self.run, 0, 8)]}, ST).code, "NO_CAPACITY_SEATS")
        cap.confirm(self.c, self.t, h.ids, ST)
        cap.cancel_booking(self.c, self.t, h.ids, ST)
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "t7c", "legs": [leg(self.run, 0, 8)]}, ST).kind, "HELD")

    def test_unbekannter_lauf_nicht_aktiv_und_sicherheitssperre(self):
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "t8", "legs": [leg(uuid.uuid4())]}, ST).code, "UNKNOWN_RUN")
        cur = rs.tx(self.c, self.t)
        cur.execute("UPDATE taktovia.vehicle_run SET state='CLOSED' WHERE tenant_id=%s AND id=%s", (self.t, self.run))
        self.c.commit()
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "t8b", "legs": [leg(self.run)]}, ST).code, "RUN_NOT_ACTIVE")
        run2 = runs.take("A")
        cur = rs.tx(self.c, self.t)
        cur.execute("UPDATE taktovia.vehicle SET active_block=false WHERE tenant_id=%s AND id=(SELECT vehicle_id FROM taktovia.vehicle_run WHERE tenant_id=%s AND id=%s)", (self.t, self.t, run2))
        self.c.commit()
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "t8c", "legs": [leg(run2)]}, ST).code, "SAFETY_BLOCK")
        cur = rs.tx(self.c, self.t)      # Fahrzeug wieder freigeben: die Reserveläufe teilen sich wenige Fahrzeuge
        cur.execute("UPDATE taktovia.vehicle SET active_block=true WHERE tenant_id=%s", (self.t,))
        self.c.commit()
        self.assertEqual(cap.hold(self.c, self.t, {"ref": "t8d", "legs": [leg(run2)]}, ST).kind, "HELD")


class Konkurrenz(unittest.TestCase):
    def test_letzter_platz_unter_30_parallelen_bestaetigungen(self):
        r, _ = rs.exp_race(pool, runs, ST, 10, "seat", 1, "k1", nreq=PAR)
        c = r["counts"]
        for bad in ("iterations_overbooked", "iterations_liveness_failure", "iterations_capacity_violation", "iterations_with_unexpected_outcome",
                    "iterations_booking_count_mismatch", "deadlocks", "serialization_failures"):
            self.assertEqual(c.get(bad, 0), 0, (bad, c))
        self.assertEqual(c["outcome:CONFIRMED"], 10)
        self.assertEqual(c["outcome:REJECTED:NO_CAPACITY_SEATS"], 10 * (PAR - 1))

    def test_rollstuhlvariante_und_routenrevision(self):
        r, _ = rs.exp_race(pool, runs, ST, 6, "wheelchair", 1, "k2", nreq=PAR)
        c = r["counts"]
        self.assertEqual((c.get("iterations_wheelchair_substituted", 0), c["outcome:CONFIRMED"], c.get("outcome:CONFIRMED", 0)), (0, 6, 6))
        self.assertEqual(c["outcome:REJECTED:NO_CAPACITY_WHEELCHAIR"], 6 * (PAR // 2))
        r, _ = rs.exp_race(pool, runs, ST, 4, "revision", 1, "k3", nreq=PAR)
        self.assertEqual(r["counts"]["outcome:REJECTED:REVISION_CONFLICT"], 4 * PAR)
        self.assertEqual(r["counts"].get("outcome:CONFIRMED", 0), 0)

    def test_wiederholte_bestaetigung_parallel_eine_buchung(self):
        r = rs.exp_replay(pool, runs, ST, 5, n_par=PAR)
        c = r["counts"]
        self.assertEqual({k: v for k, v in c.items() if k != "iterations"}, {k: 0 for k in c if k != "iterations"})

    def test_ablauf_gegen_confirm_nie_abgelaufen_und_gebucht(self):
        r = rs.exp_boundary(pool, runs, ST, 6, k=10)
        c = r["counts"]
        self.assertEqual([k for k in c if k.startswith("VIOLATION")], [])
        self.assertEqual(c.get("errors", 0), 0)
        self.assertEqual(c["holds"], 60)

    def test_gegenlaeufige_reihenfolge_mit_stabiler_sperrreihenfolge_ohne_deadlock(self):
        for name in ("rc_run_row", "rc_advisory"):
            r = rs.exp_deadlock(pool, runs, cap.STRATEGIES[name], 4, nreq=PAR)
            self.assertEqual(r["counts"].get("deadlocks", 0), 0, name)
            self.assertEqual(r["counts"].get("EXCEPTION", 0), 0)
            self.assertEqual(r["counts"].get("iterations_capacity_violation", 0), 0)

    def test_mandantentrennung(self):
        o = rs.exp_isolation(pool, runs)
        self.assertEqual(o["a_hold_A_result"], "HELD")
        self.assertLess(o["a_hold_A_while_B_holds_lock_ms"], 500)           # keine Wartezeit auf die Sperre von B
        self.assertEqual((o["b_A_select_for_update_on_B_run_rows"], o["b_A_select_B_run_without_tenant_condition_rows"]), (0, 0))
        self.assertEqual(o["c_hold_A_on_B_run"], "REJECTED:UNKNOWN_RUN")
        self.assertEqual((o["c_A_sees_B_holds_rows"], o["c_A_distinct_tenant_ids_visible"], o["d_no_context_holds_visible"]), (0, 1, 0))
        self.assertEqual((o["c_A_bookings_of_other_tenants_visible"], o["c_A_bookings_on_B_run_visible"]), (0, 0))
        self.assertEqual((o["e_A_stop_foreign_tenant_rows"], o["e_distinct_tenants_of_returned_stops"]), (0, 1))


class PostGIS(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c = pool.ctl[0]
        sd.add_grid_cells(DB)

    def test_gebietspruefung_innerhalb_ausserhalb_grenze_und_wirksamkeit(self):
        res = rs.geo_correctness(self.c)
        self.assertEqual(len(res), 20)
        self.assertEqual([r for r in res if not r["ok"]], [])

    def test_haltepunktsuche_alle_varianten_metrische_entfernung(self):
        res = rs.geo_stop_correctness(self.c)
        self.assertEqual(len(res), 16)
        self.assertEqual([r for r in res if not r["ok"]], [])

    def test_befund_b04_gist_wird_unter_rls_fuer_bereichsabfragen_nicht_genutzt(self):
        rs.geo_correctness(self.c)
        ev = rs.geo_rls_evidence()
        self.assertFalse(ev["leakproof"]["geometry_overlaps/2"])     # Operator nicht leakproof
        self.assertTrue(ev["leakproof"]["uuid_eq/2"])
        self.assertIn("stop_geo", ev["superuser_no_rls"]["indexes_used"])        # ohne RLS wird der GiST genutzt
        self.assertNotIn("stop_geo", ev["runtime_with_rls"]["indexes_used"])     # unter RLS nicht

    def test_knn_und_gitterzelle_nutzen_index_unter_rls(self):
        p = rs.geo_plans(self.c)
        self.assertIn("stop_geo", p["stops_knn_index_A"]["indexes_used"])
        self.assertIn("stop_cell", p["stops_grid_cell_A"]["indexes_used"])


if __name__ == "__main__":
    unittest.main()
