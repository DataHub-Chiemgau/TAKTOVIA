"""WP-M5-10 / LH-6.19-NFR-006: Referenzlastprofil und Skalierungstest gegen die **echte Anwendung** (Spring-Boot-Jar, Runtime-Rolle, RLS, lokaler JWT-Test-Issuer statt Keycloak).

Ein gemeinsamer Lauf (Stufen 50 %, 100 %, Überlast; zwei Mandanten gleichzeitig mit gleichen Objektnamen; 250 Fahrzeuge und 250 Fahrersitzungen je Mandant; 5.000 Lesesitzungen je Mandant
als Arbeitsannahme; Buchungsrate nach 30.000 Buchungen/Tag mal Peakfaktor) speist alle Tests. Die Stufendauer lässt sich mit `TAKTOVIA_PERF_STAGE_SCALE` skalieren (Standard 0,4, damit der
Test in Minuten läuft; der dokumentierte Messlauf nutzt 1,0). Die Tests belegen die **Durchführung und Dokumentation** des Skalierungstests (NFR-006) und die Korrektheitsinvarianten unter Last;
sie prüfen nicht, dass die vorläufigen Latenzziele erreicht werden (das tun NFR-002/-003; die Urteile stehen im Bericht).

Voraussetzungen: Opt-in `TAKTOVIA_PERF_RUN=1`, `cd backend && ./gradlew :app:bootJar`, Datenbankumgebung wie `db/db.py` (migrierte Datenbank, auch Administratorpasswort), Java 21. Ohne Opt-in: übersprungen; ohne
Jar/Datenbank mit Opt-in: übersprungen, mit TAKTOVIA_REQUIRE_DB=true fehlgeschlagen."""
from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import orchestrate, runner, shared  # noqa: E402
from refload.prereq import require_app  # noqa: E402

_CACHE = shared.CACHE
reference_run = shared.reference_run


class Referenzprofil(unittest.TestCase):
    def test_skalierungslauf_zwei_mandanten_referenzprofil(self):
        rep = reference_run()
        p, r = _CACHE["p"], rep["results"]
        # Profil: Zahlen der Lastenheftreferenz bzw. dokumentierte Arbeitsannahmen
        self.assertEqual((p.tenants, p.vehicles_per_tenant, p.bookings_per_day, p.sessions), (2, 250, 30000, 5000))
        self.assertEqual([s.name for s in p.stages], ["50 %", "100 %", "Überlast"])
        # zwei Mandanten gleichzeitig: Werte je Mandant und gesamt
        self.assertEqual(sorted(r["per_tenant"]), ["A", "B"])
        for t in ("A", "B"):
            self.assertGreater(r["per_tenant"][t]["n"], 100, f"Mandant {t} kaum belastet")
            self.assertGreater(r["per_tenant_endpoint"][t]["POST /v1/bookings"]["n"], 0, f"Mandant {t}: keine Buchung")
            self.assertEqual(r["zaehler"][f"fahrersitzungen_{t}"], 250)
            self.assertEqual(r["zaehler"][f"sitzungen_aktiv_{t}"], 5000)
        self.assertGreater(r["overall"]["n"], r["per_tenant"]["A"]["n"])
        # alle Sitzungsarten und Endpunktklassen kommen im Bericht vor
        for ep in ("POST /v1/ride-offers", "POST /v1/ride-offers/{id}/hold", "POST /v1/bookings", "GET /v1/bookings/{id}", "POST /v1/bookings/{id}/cancel", "GET /v1/bookings", "GET /v1/operator/vehicles",
                   "POST /v1/driver/positions", "GET /v1/driver/shifts/{id}/run"):
            self.assertIn(ep, r["per_endpoint"], f"Endpunkt {ep} nicht gemessen")
        # Berichtsinhalt nach WP-M0-25 / Vertrag: Perzentile, Fehlerrate, Durchsatz, Sättigung, Hardware, Datenvolumen, Build, Migrationsstand, Seed
        for k in ("p50_ms", "p95_ms", "p99_ms"):
            self.assertIn(k, r["overall"])
        self.assertIn("throughput_rps", r)
        self.assertIn("error_rate", r)
        sat = r["saettigung_gesamt"]
        self.assertTrue(all(sat[k] is not None for k in ("app_cpu_pct_von_einem_kern_max", "app_rss_mb_max", "db_verbindungen_max", "sperrwartende_max", "outbox_wartend_max")), sat)
        h = rep["header"]
        self.assertTrue(h["hardware"]["logical_cpus"] and h["dataset"]["volume"] and h["raw_data"]["sha256"])
        for k in ("build", "migrationsstand", "seed", "status_zielwerte"):
            self.assertIn(k, h["parameters"])
        self.assertTrue(Path(_CACHE["out"], "bericht.md").is_file() and Path(_CACHE["out"], "rohdaten.csv").is_file())
        # Abweichungen sind ausgewiesen: das Urteil „Referenzprofil erreicht“ ist nie stillschweigend, Zielwerte tragen den Status D-05
        self.assertFalse(rep["valid_for_acceptance"])
        self.assertTrue(any("D-05" in x for x in rep["limitations"]))
        self.assertEqual(sorted(e["verdict"] for e in rep["evaluation"] if e["criterion"].startswith("Referenzprofil")), ["nicht bewertet"])

    def test_lauf_mit_kaltem_cache_wird_getrennt_ausgewiesen(self):
        rep = reference_run()
        cold = rep["results"]["kalter_start"]
        self.assertGreater(cold["alle"]["n"], 0, "Das Warm-up-Fenster enthält keine Messung")
        self.assertIn("p95_ms", cold["alle"])
        self.assertIn("Kalter Start", Path(_CACHE["out"], "bericht.md").read_text(encoding="utf-8"))

    def test_lasttestverkehr_fuellt_die_kennzahlbasis_plausibel(self):
        """Gegenprobe zur Zählung (keine Abnahme der KPI-Definition): ein Versuch je angenommener Angebotsanfrage; Angebotsquote und technische Fehler aus derselben Basis."""
        k = reference_run()["results"]["kennzahlen_gegenprobe"]
        self.assertGreater(k["anfragen_gezaehlt_travel_attempt"], 100)
        self.assertTrue(k["gleich"], k)
        self.assertEqual(k["angebote"] + k["ablehnungen"] + k["technische_anfragefehler"], k["anfragen_gezaehlt_travel_attempt"], k)
        self.assertGreater(k["angebotsquote"], 0.9)

    def test_ueberlast_liefert_429_ohne_datenkorruption(self):
        rep = reference_run()
        r = rep["results"]
        over = r["per_stage"]["Überlast"]
        self.assertGreater(over["429"]["anzahl"], 0, "Die Überlaststufe löste keine Abweisung aus")
        self.assertEqual(over["429"]["anzahl"], over["429"]["mit_retry_after"], "429 ohne Retry-After")
        # Retry-After ist eine positive ganze Zahl (Sekunden)
        rows = [l.split(";") for l in Path(_CACHE["out"], "rohdaten.csv").read_text(encoding="utf-8").splitlines()[1:]]
        ra = {x[6] for x in rows if x[4] == "429"}
        self.assertTrue(ra and all(v.isdigit() and int(v) > 0 for v in ra), ra)
        # kontrollierte Degradation: Abweisungen betreffen die Überlastakteure, keine Datenkorruption (Invarianten), keine Transportfehler der Anwendung durch Abstürze
        self.assertEqual(r["invarianten"]["verstoesse_gesamt"], 0, r["invarianten"])
        self.assertEqual(r["nicht_beendete_aufgaben"], 0)
        # die Normalstufen bleiben unter der Fehlerschwelle (5xx/Transport)
        self.assertLessEqual(r["per_stage"]["100 %"]["alle"]["error_rate"], 0.01)

    def test_keine_ueberbuchung_nach_lauf_invariantenabfrage(self):
        inv = reference_run()["results"]["invarianten"]
        self.assertGreater(inv["geprueft"]["buchungen"], 50)
        self.assertGreater(inv["geprueft"]["holds"], 50)
        self.assertEqual({k: v for k, v in inv.items() if k not in ("geprueft",)}, {"kapazitaetsabschnitte_ueber_kapazitaet": 0, "doppelt_bestaetigte_holds": 0, "holds_mit_mehreren_aktiven_buchungen": 0,
                                                                                    "buchungen_mit_fremdmandantenbezug": 0, "buchungen_ohne_hold": 0, "verstoesse_gesamt": 0})

    def test_100_parallele_bestaetigungen_letzter_platz_unter_grundlast(self):
        rc = reference_run()["results"]["wettlauf_letzter_platz"]
        self.assertIsNotNone(rc, "Der Wettlauf lief nicht")
        self.assertEqual(rc["angebote"], 100)
        self.assertEqual(rc["holds_201"], 1, rc)                          # genau ein Hold auf den letzten Platz
        self.assertEqual(rc["holds_andere"], 0, rc)                       # alle anderen: 409 CAPACITY_UNAVAILABLE, keine Fehler
        self.assertEqual(rc["verschiedene_buchungs_ids"], 1, rc)          # 100 parallele Bestätigungen: genau eine Buchung
        self.assertEqual(rc["bestaetigungen_201"] + rc["bestaetigungen_409"], 100)
        self.assertGreaterEqual(rc["bestaetigungen_201"], 1)

    def test_gleiche_objektnamen_zweier_mandanten_ohne_fremdreferenzen(self):
        iso = reference_run()["results"]["isolation"]
        self.assertTrue(iso["gleiche_namen"], "Die Mandanten tragen nicht dieselben Objektnamen: der Test prüft sonst nichts")
        self.assertEqual(iso["fremde_ids_in_listen"], 0)
        self.assertTrue(iso["alle_fremden_ids_404"], iso)
        self.assertEqual(reference_run()["results"]["invarianten"]["buchungen_mit_fremdmandantenbezug"], 0)


class InvariantenabfrageNegativ(unittest.TestCase):
    """Die Invariantenabfrage erkennt eine absichtlich erzeugte Überbelegung und Fremdbezüge (Negativkontrolle; ohne sie wäre „0 Verstöße“ wertlos)."""

    def test_abfrage_erkennt_ueberbelegung_und_fremdbezug_doppelbestaetigung_verhindert_die_datenbank(self):
        require_app()
        import uuid
        import db as dbtool
        from refload import invariants
        c = dbtool.admin(os.environ["TAKTOVIA_DB_NAME"])
        tid, veh, shift, run, h1, h2 = (str(uuid.uuid4()) for _ in range(6))
        try:
            c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,'Negativkontrolle','ACTIVE')", (tid,))
            c.execute("SELECT set_config('app.tenant_id', %s, false)", (tid,))
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (%s,%s,'x','{\"schema_version\":\"1.0\",\"seats\":1,\"wheelchair\":0}',false,1)", (tid, veh))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '5 hours','Europe/Berlin',1,'PUBLISHED')",
                      (tid, shift, str(uuid.uuid4()), veh))
            c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (tid, run, veh, shift))
            self.assertEqual(invariants.check(c, [tid], [run])["verstoesse_gesamt"], 0, "Ausgangslage ohne Verstoß")
            with c.transaction():
                c.execute("SET LOCAL session_replication_role = replica")        # Wächtertrigger ruhen: so entsteht der Verstoß, den die Datenbank sonst verhindert
                for h in (h1, h2):
                    c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                              "VALUES (%s,%s,%s,1,%s,'{\"schema_version\":\"1.0\",\"seats\":1,\"wheelchair\":0}',0,1,now()+interval '1 hour','HELD')", (tid, h, run, f"neg-{h}"))
                # eine Buchung auf einen Hold, den es im Mandanten nicht gibt (Fremdbezug): die Fremdschlüssel ruhen in dieser Transaktion
                c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,'nb-fremd','CONFIRMED','{}',1)",
                          (tid, str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())))
            inv = invariants.check(c, [tid], [run])
            self.assertEqual(inv["kapazitaetsabschnitte_ueber_kapazitaet"], 1, inv)
            self.assertEqual((inv["buchungen_mit_fremdmandantenbezug"], inv["buchungen_ohne_hold"]), (1, 1), inv)
            self.assertGreater(inv["verstoesse_gesamt"], 0)
            # Doppelbestätigung verhindert schon die Datenbank (eindeutig je Mandant und Hold); die Abfrage ist die zweite Sicherung
            import psycopg
            with c.transaction():
                c.execute("SET LOCAL session_replication_role = replica")        # Fremdschlüssel ruhen, die Eindeutigkeit je Mandant und Hold gilt weiter
                c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,'nb-a','CONFIRMED','{}',1)",
                          (tid, str(uuid.uuid4()), h1, str(uuid.uuid4())))
                with self.assertRaises(psycopg.errors.UniqueViolation):
                    with c.transaction():
                        c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,'nb-b','CONFIRMED','{}',1)",
                                  (tid, str(uuid.uuid4()), h1, str(uuid.uuid4())))
        finally:
            with c.transaction():       # Negativdaten wieder entfernen (Wächtertrigger ruhen nur in dieser Transaktion)
                c.execute("SET LOCAL session_replication_role = replica")
                for t in ("booking", "capacity_hold", "vehicle_run", "staff_shift", "vehicle"):
                    c.execute(f"DELETE FROM taktovia.{t} WHERE tenant_id = %s", (tid,))  # sql-lint: ok Tabellennamen aus fester Liste, Negativkontrolle
                c.execute("DELETE FROM taktovia.tenant WHERE id = %s", (tid,))
            c.close()


if __name__ == "__main__":
    unittest.main()
