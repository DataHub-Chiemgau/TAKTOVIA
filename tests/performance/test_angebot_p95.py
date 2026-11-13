"""WP-M5-12 / LH-6.19-NFR-003: Fahrtangebote p95 <= 5 s unter Referenzlast mit dokumentierter Testflotte.

Gemessen werden die **Teile der Angebotskette, die in dieser Umgebung ausführbar sind**, und zusammengesetzt (Protokoll `tests/performance/out/angebotslauf/protokoll.json` und `.md`):
  1. Plattformanteil über HTTP gegen die echte Anwendung unter Referenzlast (Zulässigkeit, Geo, Tarif, Angebotsobjekt): aus dem Skalierungslauf (`refload.shared.reference_run`, derselbe Lauf wie `test_referenzprofil.py`),
  2. Routingmatrix (Adapter `workers/routing`, Cache kalt und warm, langsame Quelle) und Einfügeheuristik auf einer Testflotte von 250 Fahrzeugen mit Vorbelegung, als Worker-Pool mit Warteschlange
     bei Referenzankunftsrate (Kandidatensuche ohne den Kandidatenfilter des Kerns; der Filter und die Verdrahtung von Solver und Routing im Kern fehlen in der Produktionskonfiguration).
Die Gesamtzahl ist eine **Zusammensetzung unabhängiger Ziehungen**, keine Ende-zu-Ende-Messung (im Protokoll so benannt). Zielwert und Messpunkt sind vorläufig (D-05, OP-05).
Voraussetzungen wie `test_referenzprofil.py` (bootJar, Datenbankumgebung)."""
from __future__ import annotations

import json
import os
import random
import sys
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import offerchain, orchestrate, runner, invariants, shared  # noqa: E402
from refload import world as W  # noqa: E402
from refload.prereq import require_app  # noqa: E402

OFFER_EP = "POST /v1/ride-offers"
_CACHE: dict = {}


def chain() -> dict:
    """Protokoll der Angebotskette (einmal je Prozess)."""
    if "chain" in _CACHE:
        return _CACHE["chain"]
    rep = shared.reference_run()
    stage = rep["results"]["per_stage"]["100 %"]["angebote"]
    fleet = offerchain.fleet(250)
    p = _CACHE.get("profile") or runner.profile_from_json(HERE / "profiles" / "referenz-skalierung.json")
    rate = p.bookings_per_day / 86400.0 * p.peak_factor * p.offers_per_booking * p.tenants          # Angebotsanfragen je Sekunde, Spitze, beide Mandanten
    duration = float(os.environ.get("TAKTOVIA_PERF_CHAIN_SECONDS", "20"))
    workers = int(os.environ.get("TAKTOVIA_PERF_ROUTING_WORKERS", "2"))
    q = offerchain.queue_run(250, rate, duration, workers, budget_s=1.0)
    res = q["ergebnisse"]
    solve, wait, total = [r["solve_s"] for r in res], [r["wait_s"] for r in res], [r["total_s"] for r in res]
    rc = offerchain.routing_cold_warm()
    # Zusammensetzung: HTTP-Anteil (Stichprobe der Referenzstufe), Routing (kalt mit 5 %, sonst warm), Worker (Wartezeit + Lösung) – unabhängige Ziehungen
    rows = [l.split(";") for l in Path(shared.out_root(), "referenzlauf", "rohdaten.csv").read_text(encoding="utf-8").splitlines()[1:]]
    http = [float(x[5]) / 1000.0 for x in rows if x[3] == OFFER_EP and x[1] == "100 %" and x[7] != "last_seat"]
    rnd = random.Random(3)
    cold_s, warm_s = rc["kalt"]["p50_ms"] / 1000.0, rc["warm"]["p50_ms"] / 1000.0
    comp = sorted(rnd.choice(http) + (cold_s if rnd.random() < 0.05 else warm_s) + rnd.choice(total) for _ in range(20000))
    p95_comp = comp[int(0.95 * len(comp)) - 1] * 1000
    out = {
        "protokoll_version": "1.0", "ziel": "p95 <= 5000 ms", "status_zielwert": "vorläufig, D-05 offen", "messpunkt": "Clientzeit des Lastgenerators auf derselben Maschine (serverseitige Zeit liegt nicht getrennt vor, OP-05)",
        "testflotte": offerchain.testflotte_beschreibung(fleet),
        "kandidaten_und_flottengroesse": {"fahrzeuge_im_plan": len(fleet), "kandidaten_je_anfrage": len(fleet), "scope": "alle Fahrzeuge der Flotte (Kandidatenfilter des Kerns nicht Teil der Messung)"},
        "routingdatenstand": offerchain.MAP_VERSION + " (synthetischer Anbieter, kein Valhalla/OSM, D-09 offen)",
        "timeouts": {"zeitbudget_loesung_s": 1.0, "routing_zeitbudget_s": 0.8, "partnerfenster_s": "3 (in P1 ungenutzt)", "lokale_kandidatensuche_planungsdefault_s": 1.0},
        "lokale_anteile": {"plattform_http": {"n": len(http), "p50_ms": round(sorted(http)[len(http) // 2] * 1000, 1), "p95_ms": rep["results"]["per_stage"]["100 %"]["angebote"]["p95_ms"], "p99_ms": rep["results"]["per_stage"]["100 %"]["angebote"]["p99_ms"]},
                           "routing_matrix": rc, "einfuegeheuristik_loesung": offerchain.summarize_ms(solve), "warteschlange_vor_worker": offerchain.summarize_ms(wait), "worker_gesamt": offerchain.summarize_ms(total)},
        "worker": {"anzahl": workers, "ankunftsrate_je_s": round(rate, 2), "dauer_s": duration, "anfragen": q["anfragen"], "mit_variante": sum(1 for r in res if r["variants"]), "teilergebnisse": sum(1 for r in res if r["partial"]),
                   "ablehnungen": {k: sum(1 for r in res if r["rejection"] == k) for k in {r["rejection"] for r in res if r["rejection"]}}, "ungueltige_varianten": sum(r["invalid_variants"] for r in res)},
        "gesamt_zusammengesetzt": {"methode": "unabhängige Ziehungen aus HTTP-Stichprobe, Routing (5 % kalt) und Worker (Wartezeit + Lösung), 20.000 Ziehungen", "p95_ms": round(p95_comp, 1), "p50_ms": round(comp[len(comp) // 2] * 1000, 1)},
        "nicht_gemessen": ["Kandidatenfilter des Kerns auf 250 Fahrzeugen (Kotlin/JDBC)", "Zusammenschaltung Kern, Routing und Solver (Produktionsverdrahtung PASS_THROUGH)", "echter Routinganbieter (Valhalla)", "serverseitige Eingangs-/Ausgangszeit (OP-05)"],
    }
    ok = p95_comp <= 5000
    out["urteil"] = "erfüllt" if ok else "verfehlt"
    if not ok:
        out["abweichung"] = {"ursache": "p95 der zusammengesetzten Angebotszeit über 5 s", "engpass": "siehe lokale Anteile (größter p95-Anteil)", "massnahme": "Worker skalieren, Matrix vorwärmen, Kandidatenscope begrenzen"}
    d = shared.out_root() / "angebotslauf"
    d.mkdir(parents=True, exist_ok=True)
    (d / "protokoll.json").write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    (d / "protokoll.md").write_text(_md(out), encoding="utf-8")
    _CACHE["chain"] = out
    return out


def _md(o: dict) -> str:
    a = o["lokale_anteile"]
    L = ["# Protokoll Angebotskette (WP-M5-12)", "", f"Ziel {o['ziel']} ({o['status_zielwert']}); Messpunkt: {o['messpunkt']}", "", "## Testflotte", ""] + [f"- {k}: {v}" for k, v in o["testflotte"].items()]
    L += ["", f"Kandidaten/Flottengröße: {json.dumps(o['kandidaten_und_flottengroesse'], ensure_ascii=False)}", f"Routingdatenstand: {o['routingdatenstand']}", f"Timeouts: {json.dumps(o['timeouts'], ensure_ascii=False)}", "", "## Lokale Anteile (ms)", "",
          "| Anteil | n | p50 | p95 | p99 |", "|---|---:|---:|---:|---:|"]
    for name, s in (("Plattform (HTTP, Referenzstufe)", a["plattform_http"]), ("Einfügeheuristik (Lösung)", a["einfuegeheuristik_loesung"]), ("Warteschlange vor Worker", a["warteschlange_vor_worker"]), ("Worker gesamt", a["worker_gesamt"])):
        L.append(f"| {name} | {s.get('n', '')} | {s.get('p50_ms')} | {s.get('p95_ms')} | {s.get('p99_ms')} |")
    r = a["routing_matrix"]
    L += ["", f"Routingmatrix 120x120: kalt p50 {r['kalt']['p50_ms']} ms / p95 {r['kalt']['p95_ms']} ms (Anbieterlatenz {r['anbieterlatenz_ms']} ms), warm p50 {r['warm']['p50_ms']} ms / p95 {r['warm']['p95_ms']} ms, Anbieteraufrufe warm: {r['anbieteraufrufe_warm']}; "
              f"langsame Quelle: {r['langsame_quelle']}", "", f"Worker: {json.dumps(o['worker'], ensure_ascii=False)}", "", f"## Gesamt (zusammengesetzt, keine Ende-zu-Ende-Messung): p95 {o['gesamt_zusammengesetzt']['p95_ms']} ms, Urteil **{o['urteil']}**", "",
          "Nicht gemessen: " + "; ".join(o["nicht_gemessen"]), ""]
    return "\n".join(L)


class AngebotP95(unittest.TestCase):
    def test_p95_angebot_mit_dokumentierter_testflotte(self):
        o = chain()
        # Pflichtinhalt des Protokolls (Kap. 08 §8.6): lokale Anteile, Kandidaten-/Flottengröße, Routingdatenstand, Timeouts, Testflottenbeschreibung
        for k in ("lokale_anteile", "kandidaten_und_flottengroesse", "routingdatenstand", "timeouts", "testflotte", "nicht_gemessen", "status_zielwert"):
            self.assertIn(k, o)
        self.assertEqual(o["testflotte"]["fahrzeuge"], 250)
        self.assertGreaterEqual(int(o["testflotte"]["vorbelegung"].split()[0]), 400, "Testflotte ist nicht vorbelegt")
        self.assertIn("synthetisch", o["routingdatenstand"])
        for part in ("plattform_http", "einfuegeheuristik_loesung", "warteschlange_vor_worker", "worker_gesamt"):
            self.assertGreater(o["lokale_anteile"][part]["n"], 10, part)
        self.assertEqual(o["worker"]["ungueltige_varianten"], 0, "Die Heuristik lieferte eine Variante, die der unabhängige Prüfer verwirft")
        # p95 <= 5 s oder Abweichung mit Ursache und Maßnahme; das Urteil ist konsistent zur Zahl
        self.assertEqual(o["urteil"] == "erfüllt", o["gesamt_zusammengesetzt"]["p95_ms"] <= 5000)
        if o["urteil"] == "verfehlt":
            self.assertTrue(o["abweichung"]["ursache"] and o["abweichung"]["massnahme"])
        # kalter Routingcache: genau ein Anbieteraufruf, warm keiner; langsame Quelle liefert nie ein geschätztes Ergebnis
        rc = o["lokale_anteile"]["routing_matrix"]
        self.assertEqual(rc["anbieteraufrufe_warm"], 0)
        self.assertGreater(rc["kalt"]["p50_ms"], rc["warm"]["p50_ms"])
        self.assertTrue(rc["langsame_quelle"]["ergebnis"].startswith("ProviderTimeout"), rc["langsame_quelle"])
        self.assertLess(rc["langsame_quelle"]["dauer_ms"], 1000, "Das Zeitbudget des Routingaufrufs wurde nicht eingehalten")

    def test_solver_timeout_liefert_nur_validierten_plan_oder_ablehnung(self):
        require_app()           # Opt-in: Testflotte von 250 Fahrzeugen aufbauen und Anwendung starten dauern Minuten
        from taktovia_routing.insertion import find_insertions, validate_variant
        from test_insertion_budget import MATRIX, PARAMS, _request
        fleet = offerchain.fleet(250)
        partial_seen = 0
        for budget in (0.0001, 0.001, 0.005, 0.02, 1.0):
            rnd = random.Random(int(budget * 1e6))
            for k in range(25):
                req = _request(rnd, 700_000 + k)
                t0 = __import__("time").perf_counter()
                out = find_insertions(fleet, req, PARAMS, MATRIX, budget_s=budget)
                dauer = __import__("time").perf_counter() - t0
                self.assertLess(dauer, budget + 0.3, f"Budget {budget} s nicht eingehalten ({dauer:.3f} s)")
                for var in out.variants:       # nie ein ungeprüfter Plan; der Prüfer schließt Zeitfenster und unveränderte Zusagen ein
                    plan = next(v for v in fleet if v.vehicle_id == var.vehicle_id)
                    self.assertEqual(validate_variant(plan, req, PARAMS, MATRIX, var), [], f"Variante verletzt Zusagen (Budget {budget})")
                if out.partial:
                    partial_seen += 1
                    if not out.variants:
                        self.assertEqual(out.rejection, "TIMEOUT_NO_VALIDATED_PLAN")
                if not out.variants:
                    self.assertIsNotNone(out.rejection, "weder Variante noch begründete Ablehnung")
        self.assertGreater(partial_seen, 0, "Kein Budget war knapp genug: der Test prüft den Timeout-Pfad nicht")
        # nicht rechtzeitig/abgelaufenes Angebot wird nicht nachträglich zugesagt: Hold auf ein abgelaufenes Angebot = 409 OFFER_EXPIRED, keine Kapazitätssperre
        with orchestrate.SmallWorld(1, 5, 2) as sw:
            w = sw.worlds[0]; tok = sw.idp.token(w.passengers[0])
            s, js, _ = sw.call("POST", "/v1/ride-offers", tok, W.offer_body(w.service_id, w.days)); self.assertEqual(s, 200)
            off = js["offers"][0]
            run, seg = w.next_run_segment(); W.bind_offer(sw.admin, w.tenant_id, off["id"], run, seg)
            with sw.admin.transaction():
                sw.admin.execute("SET LOCAL session_replication_role = replica")
                sw.admin.execute("UPDATE taktovia.ride_offer SET created_at = now() - interval '10 minutes', expires_at = now() - interval '5 minutes' WHERE id = %s", (off["id"],))
            s, js, _ = sw.call("POST", f"/v1/ride-offers/{off['id']}/hold", tok, {"max_gross": off["gross"]}, if_match=str(off["revision"]))
            self.assertEqual((s, js.get("code")), (409, "OFFER_EXPIRED"))
            self.assertEqual(sw.admin.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = %s", (w.tenant_id,)).fetchone()[0], 0)
            self.assertEqual(sw.admin.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id = %s", (w.tenant_id,)).fetchone()[0], 0)

    def test_parallele_angebote_ohne_hold_versprechen_keine_kapazitaet(self):
        require_app()
        n = 100
        with orchestrate.SmallWorld(1, n, 2) as sw:
            w = sw.worlds[0]
            toks = [sw.idp.token(s) for s in w.passengers]
            with ThreadPoolExecutor(32) as ex:
                res = list(ex.map(lambda t: sw.call("POST", "/v1/ride-offers", t, W.offer_body(w.service_id, w.days)), toks))
            offers = [r[1]["offers"][0] for r in res if r[0] == 200 and r[1]["offers"]]
            self.assertEqual(len(offers), n, "alle 100 Anfragen erhalten ein Angebot, solange keine Kapazität gesperrt ist")
            for o in offers:                      # alle 100 Angebote beziehen sich auf denselben letzten Platz (Kapazität 1)
                W.bind_offer(sw.admin, w.tenant_id, o["id"], w.last_seat_run, 0)
            self.assertTrue(all(o["state"] == "OFFERED" and not o.get("hold") for o in offers))
            self.assertEqual(sw.admin.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id = %s", (w.tenant_id,)).fetchone()[0], 0, "Angebote ohne Hold sperren keine Kapazität")
            # Hold/Confirm bleiben korrekt: 100 parallele Holds, genau einer gelingt; 100 parallele Bestätigungen desselben Holds ergeben genau eine Buchung
            with ThreadPoolExecutor(32) as ex:
                holds = list(ex.map(lambda i: sw.call("POST", f"/v1/ride-offers/{offers[i]['id']}/hold", toks[i], {"max_gross": offers[i]["gross"]}, if_match=str(offers[i]["revision"])), range(n)))
            codes = [h[0] for h in holds]
            self.assertEqual(codes.count(201), 1, codes)
            self.assertEqual(codes.count(409), n - 1, codes)
            i = codes.index(201)
            with ThreadPoolExecutor(32) as ex:
                conf = list(ex.map(lambda _: sw.call("POST", "/v1/bookings", toks[i], {"hold_id": holds[i][1]["id"], "max_gross": offers[i]["gross"]}, if_match=holds[i][2].get("ETag", "1")), range(n)))
            ids = {c[1]["id"] for c in conf if c[0] == 201}
            self.assertEqual(len(ids), 1, [c[0] for c in conf])
            inv = invariants.check(sw.admin, [w.tenant_id], [*w.runs, w.last_seat_run])
            self.assertEqual(inv["verstoesse_gesamt"], 0, inv)
            self.assertEqual(inv["geprueft"]["buchungen"], 1)

    def test_solver_timeout_zaehlt_als_technischer_fehler_nicht_als_kapazitaetsablehnung(self):
        """Datenbasis der Kennzahlen: ein technischer Fehler (Solver-/Routing-Timeout) ist ein Versuch mit Ergebnis TECHNICAL_ERROR ohne Primärgrund und zählt nie in `Kapazitätsablehnungen`;
        die Abbildung Timeout -> technischer Fehler im Kern belegt `RejectionReasonTest.tor_ueber_kandidatenfilter_bildet_kategorien_auf_codes_ab` (Backend)."""
        require_app()
        import db as dbtool
        import psycopg
        a = dbtool.admin(os.environ["TAKTOVIA_DB_NAME"])
        tid = str(uuid.uuid4())
        try:
            a.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'Kennzahlprobe','ACTIVE')", (tid,))
            cat = {r[0]: (r[1], r[2]) for r in a.execute("SELECT code, counts_as_rejection, metric FROM taktovia.rejection_reason")}
            self.assertEqual(cat["TECHNICAL"], (False, None))
            self.assertEqual(cat["NO_CAPACITY"], (True, "CAPACITY"))
            rt = dbtool.conn("taktovia_runtime", dbtool.env("TAKTOVIA_DB_RUNTIME_PASSWORD"), os.environ["TAKTOVIA_DB_NAME"], autocommit=False)
            try:
                rt.execute("SELECT set_config('app.tenant_id', %s, true)", (tid,))
                for _ in range(3):      # drei Solver-Timeouts: technische Fehler ohne Ablehnungsgrund
                    rt.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,primary_rejection,secondary_rejections,outcome) VALUES (%s,%s,now(),'WEB',NULL,'{}','TECHNICAL_ERROR')", (tid, str(uuid.uuid4())))
                rt.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,primary_rejection,secondary_rejections,outcome) VALUES (%s,%s,now(),'WEB','NO_CAPACITY','{}','REJECTED')", (tid, str(uuid.uuid4())))
                kap = rt.execute("SELECT count(*) FROM taktovia.travel_attempt t JOIN taktovia.rejection_reason r ON r.code = t.primary_rejection WHERE t.tenant_id = %s AND r.counts_as_rejection AND r.metric = 'CAPACITY'", (tid,)).fetchone()[0]
                tech = rt.execute("SELECT count(*) FROM taktovia.travel_attempt WHERE tenant_id = %s AND outcome = 'TECHNICAL_ERROR' AND primary_rejection IS NULL", (tid,)).fetchone()[0]
                self.assertEqual((kap, tech), (1, 3), "Kapazitätsablehnungen zählen nur die fachliche Ablehnung, die drei Timeouts stehen als technische Fehler")
                rt.commit()
                rt.execute("SELECT set_config('app.tenant_id', %s, true)", (tid,))
                with self.assertRaises(psycopg.errors.CheckViolation, msg="TECHNICAL darf nie Primärgrund sein"):
                    rt.execute("INSERT INTO taktovia.travel_attempt(tenant_id,id,created_at,channel,primary_rejection,secondary_rejections,outcome) VALUES (%s,%s,now(),'WEB','TECHNICAL','{}','REJECTED')", (tid, str(uuid.uuid4())))
                rt.rollback()
            finally:
                rt.close()
        finally:
            with a.transaction():
                a.execute("SET LOCAL session_replication_role = replica")
                a.execute("DELETE FROM taktovia.travel_attempt WHERE tenant_id = %s", (tid,))
                a.execute("DELETE FROM taktovia.tenant WHERE id = %s", (tid,))
            a.close()


if __name__ == "__main__":
    unittest.main()
