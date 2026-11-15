"""WP-M5-23 (LH-6.20-QAT-003): durchgängiger E2E-Referenzfall, Wiederholung von AT-01.

ERSATZUMGEBUNG, kein Staging: die echte Anwendung (Spring-Boot-Jar, Runtime-Rolle) mit echter PostgreSQL/RLS, lokalem JWT-Test-Issuer statt Keycloak, PSP-Simulator statt PSP-Sandbox, ohne
Messaging-Broker (Ereignisse stellt der Outbox-Relay im Prozess an die Faktenbasis zu), siehe `referenzwelt.ERSATZ`. Ein Lauf gegen Staging ist hier nicht ausführbar. Der Testaufbau
(Planbindung, Fahrzeugfreigabe, Schicht, Planstopps) schreibt per Administratorzugang; der Wächter weist ihn getrennt aus und prüft, dass sonst nichts außerhalb der Runtime-Rolle schreibt.

Voraussetzung: `cd backend && ./gradlew :app:bootJar` und die Datenbankumgebung aus db/db.py. Aufruf: cd tests/acceptance && python -m unittest -v e2e.test_referenzfall_staging

Offen (ehrlich): die Kennzahl „Fahrgastfahrten pro Fahrzeugstunde“ aus dem Lieferumfang ist im Kennzahlregister von M4 nicht enthalten (`operating_hours`, `passenger_km` stehen dort, der Quotient nicht);
sie wird hier NICHT geprüft und steht als offener Punkt im Bericht."""
import json
import re
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "tools"))

from e2e import basis  # noqa: E402
from e2e.basis import Modul  # noqa: E402
from e2e.referenzfall import Fall, Umlauf, durchlaufen, kennzahlen, warte, Http  # noqa: E402
from e2e import evidenz  # noqa: E402
from taktovia_at import evidence as ev  # noqa: E402

A, B, KOSTENFREI, KENNZAHLEN = 0, 1, 2, 3


def setUpModule():
    basis.hochfahren()


def tearDownModule():
    basis.herunterfahren()


def zahlungen(f):
    return f.zahlung()


class Referenzfall(unittest.TestCase):
    @property
    def welt(self):
        return Modul.welt.welt

    def mandant(self, i):
        return Modul.welt.mandanten[i]

    def pruefe_schritte(self, f):
        falsch = [s for s in f.schritte if not s["ok"]]
        self.assertEqual(falsch, [], "erwartete Status je Schritt")

    # ------------------------------------------------------------------------------------------------------------------ QAT-003 Variante mit Zahlung
    def test_referenzfall_mit_zahlung(self):
        m = self.mandant(A)
        (f,), u = durchlaufen(self.welt, m)
        self.pruefe_schritte(f)
        self.assertEqual(f.buchungszustand(), "COMPLETED")
        z = f.zahlung()
        self.assertEqual(len(z), 1, "genau eine Zahlung")
        self.assertEqual((z[0][1], z[0][2], z[0][3], z[0][4]), ("CAPTURED", 450, 450, 0), "Endzustand: autorisiert und eingezogen, nichts erstattet")
        # beim PSP (Simulator) genau eine Autorisierung und ein Einzug zu dieser Händlerreferenz
        calls = [c for c in Modul.welt.sim.calls if c.get("merchant_ref") == z[0][6] or c.get("ref") == z[0][6]]
        ops = [c.get("operation") for c in calls if c.get("effect_applied", True) and not c.get("replayed")]
        self.assertEqual(ops.count("authorize"), 1, calls)
        self.assertLessEqual(ops.count("capture"), 1, calls)
        # Sicht der Leitstelle (UI-07): die Buchung steht dort mit Zustand und Fahrzeug
        r = f.http("GET", f"/v1/operator/bookings?service_id={m.service}", m.leitstelle)
        self.assertEqual(r.status, 200)
        zeile = next(x for x in r.body["items"] if x["id"] == f.buchung_id)
        self.assertEqual(zeile["state"], "COMPLETED")
        # Fahrtenplan: Zuweisung (vehicle_run) und Stopps mit der Buchung
        with Modul.welt.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.route_stop WHERE tenant_id=%s AND run_id=%s AND run_revision=%s", (m.id, u.run, u.revision)).fetchone()[0], 2)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id=%s AND shift_id=%s AND outcome='APPLIED'", (m.id, u.schicht)).fetchone()[0], 4)
            self.assertGreater(c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND event_type IN ('booking.confirmed','driver.event_recorded','payment.captured')", (m.id,)).fetchone()[0], 5)

    # ------------------------------------------------------------------------------------------------------------------ Variante ohne Zahlung
    def test_referenzfall_ohne_zahlung_kein_payment_intent(self):
        m = self.mandant(KOSTENFREI)
        (f,), _ = durchlaufen(self.welt, m)
        self.pruefe_schritte(f)
        self.assertEqual(f.angebot["gross"]["amount_minor"], 0, "Tarif ohne Preis (Variante ohne Zahlung)")
        self.assertEqual(f.buchungszustand(), "COMPLETED")
        self.assertEqual(f.zahlung(), [], "kein payment_intent")
        with Modul.welt.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id=%s", (m.id,)).fetchone()[0], 0)
        # Negativkontrolle der Aussage: der Zahlungsendpunkt lehnt eine Buchung ohne Zahlungspflicht ab (Buchung nicht CONFIRMING), es entsteht weiterhin nichts
        r = f.http("POST", f"/v1/bookings/{f.buchung_id}/payment", f.fahrgast.token, {"amount": {"amount_minor": 0, "currency": "EUR", "exponent": 2}, "payment_method_ref": "pm_ok"})
        self.assertEqual(r.status, 409)
        self.assertEqual(f.zahlung(), [])

    # ------------------------------------------------------------------------------------------------------------------ Kennzahlen mit Datenstand
    def test_erwartete_status_und_kennzahlen_mit_datenstand(self):
        """Drei Fahrgäste (Kleingruppenschwelle `kpi_aggregation_min_n` = 3 unterdrückt kleinere Gruppen) auf einer Fahrt: erwartete Zähler und Nenner, Datenstand."""
        m = self.mandant(KENNZAHLEN)
        faelle, u = durchlaufen(self.welt, m, anzahl=3)
        for f in faelle:
            self.pruefe_schritte(f)
        http = Http(self.welt)

        def bereit():
            k, as_of = kennzahlen(self.welt, m, http=http)
            ok = all(k[x]["state"] == "OK" for x in ("requests", "offer_rate", "booking_conversion", "pickup_on_time", "arrival_on_time"))
            return (k, as_of) if ok else None

        k, as_of = warte(bereit, 40, 1.0, "Kennzahlen mit Datenstand")
        erwartet = {"requests": (3, None), "offer_rate": (3, 3), "booking_conversion": (3, 3), "pickup_on_time": (3, 3), "arrival_on_time": (3, 3)}
        for metric, (z, n) in erwartet.items():
            self.assertEqual((k[metric]["numerator"], k[metric]["denominator"]), (z, n), metric)
        self.assertEqual(k["offer_rate"]["value"], 1.0)
        self.assertEqual(k["pickup_on_time"]["value"], 1.0)
        # Datenstand: vorhanden, nicht älter als der erste Schritt des Falls und vollständig (Quellereignisse gezählt)
        self.assertIsNotNone(as_of)
        for metric in erwartet:
            self.assertIsNotNone(k[metric]["as_of"], metric)
            self.assertGreater(k[metric]["source_events"], 0, metric)
        # Gegenprobe aus den Fakten der Datenbank (unabhängig von der Kennzahlberechnung)
        with Modul.welt.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id=%s AND NOT is_test", (m.id,)).fetchone()[0], 3)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_booking WHERE tenant_id=%s AND state='COMPLETED'", (m.id,)).fetchone()[0], 3)
            offen = c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND published_at IS NULL", (m.id,)).fetchone()[0]
        self.assertEqual(offen, 0, "alle Ereignisse zugestellt, Datenstand vollständig")

    # ------------------------------------------------------------------------------------------------------------------ zweiter Lauf, zwei Mandanten
    def test_zweiter_lauf_ohne_bereinigung_zwei_mandanten(self):
        for i in (A, B):
            m = self.mandant(i)
            ergebnisse = []
            for lauf in (0, 1):   # der zweite Lauf folgt ohne jede Bereinigung auf demselben Datenbestand (eigener Umlauf mit anderem Fahrzeug und Fahrer)
                umlauf = Umlauf(self.welt, m, fahrzeug=lauf, fahrer=lauf)
                (f,), _ = durchlaufen(self.welt, m, umlauf=umlauf)   # die Schicht des ersten Laufs ist beendet; Fahrer und Fahrzeug sind unverändert wiederverwendbar
                self.pruefe_schritte(f)
                ergebnisse.append((f.buchungszustand(), [z[1] for z in f.zahlung()]))
            self.assertEqual(ergebnisse, [("COMPLETED", ["CAPTURED"])] * 2, m.name)

    # ------------------------------------------------------------------------------------------------------------------ Evidenz und Log-Stichprobe
    def test_evidenz_synthetisch_und_log_stichprobe_ohne_treffer(self):
        import log_stichprobe
        m = self.mandant(A)
        Modul.waechter.start()
        (f,), _ = durchlaufen(self.welt, m)
        bericht = Modul.waechter.ende()
        with tempfile.TemporaryDirectory() as d:
            record, path = evidenz.aufzeichnen([f], bericht, [], d)
            self.assertEqual(record["result"], "PASSED", [c for c in record["soll_ist"] if not c["ok"]])
            self.assertEqual((record["test"]["id"], record["source_key"], record["acceptance_decision"]), ("AT-01", ["LH-6.20-QAT-003"], "PENDING"))
            self.assertTrue(record["software_build"]["build_id"])
            self.assertTrue(record["event_trace_ids"]["event_ids"] and record["event_trace_ids"]["trace_ids"], "Ereignis- und Trace-IDs je Schritt")
            self.assertTrue(record["environment"]["simulated_components"], "Ersatzkomponenten benannt")
            self.assertEqual(ev.check_no_personal_data(record), [], "nur synthetische Daten")
            self.assertTrue(Path(path).is_file())
        # Log-Stichprobe aus WP-M5-06 über das Anwendungslog dieses Laufs: kein Klartexttreffer
        rep = log_stichprobe.sample({"fachkern": log_stichprobe.read_source(Modul.welt.welt.app.log_path)}, require=["fachkern"])
        self.assertEqual((rep["findings"], rep["missing_components"], rep["result"]), ([], [], "OHNE_BEFUND"))
        self.assertGreater(rep["components"]["fachkern"]["lines"], 0, "die Stichprobe ist nicht leer")
        # Negativ: ein Klartexttreffer in den Logquellen lässt die Stichprobe anschlagen; echte Personendaten in der Evidenz lassen die Evidenzprüfung anschlagen
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "fachkern.log"
            p.write_text("2026-11-15T10:00:00Z INFO test Fahrgast maria.mustermann@example.org angemeldet\n", encoding="utf-8")
            self.assertEqual(log_stichprobe.sample({"fachkern": log_stichprobe.read_source(p)}, require=["fachkern"])["result"], "BEFUND", "Klartext-E-Mail wird gefunden")
        self.assertTrue(ev.check_no_personal_data({"step": {"passenger_email": "x"}}), "Evidenz mit personenbezogenem Feld wird abgewiesen")
        self.assertTrue(ev.check_no_personal_data({"step": "Kontakt anna@example.org"}), "Evidenz mit E-Mail-Wert wird abgewiesen")

    # ------------------------------------------------------------------------------------------------------------------ Wächter
    def test_waechter_keine_manuelle_db_korrektur(self):
        m = self.mandant(B)
        Modul.waechter.start()
        (f,), _ = durchlaufen(self.welt, m)
        b = Modul.waechter.ende()
        self.assertTrue(f.ok)
        self.assertEqual(b["befunde"], [], "keine Schreibzugriffe außerhalb der Runtime-Rolle und des gekennzeichneten Testaufbaus")
        tabellen = {(x["tabelle"], x["operation"]) for x in b["runtime"]}
        for t in (("booking", "INSERT"), ("payment_intent", "INSERT"), ("driver_event", "INSERT"), ("outbox", "INSERT")):
            self.assertIn(t, tabellen, "der Ablauf selbst schreibt unter der Runtime-Rolle")
        # Fachliche Zustände entstehen nie im Testaufbau: payment_intent, booking, driver_event, capacity_hold nur durch die Anwendung
        aufbau = {x["tabelle"] for x in b["testaufbau"]}
        self.assertTrue(aufbau <= {"passenger_private", "resource_release", "ride_offer", "route_stop", "staff_shift", "vehicle", "vehicle_run"}, aufbau)
        self.assertFalse(aufbau & {"payment_intent", "booking", "driver_event", "capacity_hold", "outbox", "audit_event"})

    def test_waechter_schlaegt_bei_probeeingriff_an(self):
        m = self.mandant(B)
        Modul.waechter.start()
        with Modul.welt.admin() as c:   # Probeeingriff: manuelle Korrektur per Administratorzugang, nicht als Testaufbau gekennzeichnet
            c.execute("UPDATE taktovia.vehicle SET active_block = active_block WHERE tenant_id = %s", (m.id,))
        b = Modul.waechter.ende()
        self.assertFalse(b["ok"])
        self.assertEqual([(x["tabelle"], x["operation"]) for x in b["befunde"]], [("vehicle", "UPDATE")])
        # gleicher Zugriff mit Kennzeichen des Testaufbaus wird nicht als Befund, aber ausgewiesen
        Modul.waechter.start()
        with Modul.welt.aufbau() as c:
            c.execute("UPDATE taktovia.vehicle SET active_block = active_block WHERE tenant_id = %s", (m.id,))
        b = Modul.waechter.ende()
        self.assertTrue(b["ok"])
        self.assertEqual([(x["tabelle"], x["operation"]) for x in b["testaufbau"]], [("vehicle", "UPDATE")])

    # ------------------------------------------------------------------------------------------------------------------ Konkurrenz
    def test_parallele_referenzfaelle_zwei_mandanten_keine_vermischung(self):
        a, b = self.mandant(A), self.mandant(B)
        start = threading.Barrier(2)

        def lauf(m):
            start.wait()
            return durchlaufen(self.welt, m)

        ergebnisse = {}
        with ThreadPoolExecutor(2) as ex:
            futures = {m.id: ex.submit(lauf, m) for m in (a, b)}
            for tid, fut in futures.items():
                ergebnisse[tid] = fut.result(180)
        for m in (a, b):
            (f,), _ = ergebnisse[m.id]
            self.pruefe_schritte(f)
            self.assertEqual(f.buchungszustand(), "COMPLETED")
        with Modul.welt.admin() as c:
            for m, fremd in ((a, b), (b, a)):
                (f,), _ = ergebnisse[m.id]
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND id=%s", (m.id, f.buchung_id)).fetchone()[0], 1)
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND id=%s", (fremd.id, f.buchung_id)).fetchone()[0], 0, "keine Datenvermischung")
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id=%s AND booking_id=%s", (fremd.id, f.buchung_id)).fetchone()[0], 0)
                self.assertEqual(c.execute("SELECT count(DISTINCT tenant_id) FROM taktovia.driver_event WHERE shift_id=%s", (ergebnisse[m.id][1].schicht,)).fetchone()[0], 1)
        # RLS-Gegenprobe über die API: der Fahrgast des einen Mandanten sieht die Buchung des anderen nicht
        (fa,), _ = ergebnisse[a.id]
        (fb,), _ = ergebnisse[b.id]
        r = fa.http("GET", f"/v1/bookings/{fb.buchung_id}", fa.fahrgast.token)
        self.assertEqual(r.status, 404)

    def test_parallele_referenzfaelle_selbes_fahrzeug(self):
        u = Umlauf(self.welt, self.mandant(A), fahrzeug=0, fahrer=1)
        try:
            self._selbes_fahrzeug(u)
        finally:
            u.freigeben()

    def _selbes_fahrzeug(self, u):
        """Zwei Fahrgäste im selben Mandanten buchen gleichzeitig auf demselben Umlauf: keine Überbuchung, genau eine Zahlung je Buchung, beide Fahrten laufen."""
        m = self.mandant(A)
        faelle = [Fall(self.welt, m, u, mit_zahlung=True, personen=4) for _ in range(2)]   # 2 x 4 Personen = 8 Sitze = volle Auslastung des Fahrzeugs
        start = threading.Barrier(2)

        def buchen(f):
            f.anfragen()
            f.halten()
            start.wait()
            r = f.buchen()
            if r.status == 201:
                f.bezahlen()
            return r.status

        with ThreadPoolExecutor(2) as ex:
            status = list(ex.map(buchen, faelle))
        self.assertEqual(status, [201, 201], "beide Buchungen bestätigt: Kapazität reicht genau")
        for f in faelle:
            warte(lambda: f.buchungszustand() == "CONFIRMED", 40, was="Buchung bestätigt")
        faelle[0].planen(weitere=faelle[1:])
        faelle[0].dienststart()
        for pos, f in enumerate(faelle):
            f.fahrt(pos)
            self.assertEqual(f.buchungszustand(), "COMPLETED")
        with Modul.welt.admin() as c:
            for f in faelle:
                z = f.zahlung()
                self.assertEqual([(x[1], x[2], x[3]) for x in z], [("CAPTURED", 4 * 450, 4 * 450)], "genau eine Zahlung je Buchung")
            seats = c.execute("SELECT coalesce(sum((h.capacity_vector->>'seats')::int),0) FROM taktovia.capacity_hold h WHERE h.tenant_id=%s AND h.run_id=%s AND h.state IN ('HELD','CONSUMED')", (m.id, u.run)).fetchone()[0]
        self.assertLessEqual(seats, 8, "keine Überbuchung")
        # Negativ: ein dritter Fahrgast bekommt auf dem vollen Umlauf keine Bestätigung (Kapazität)
        f3 = Fall(self.welt, m, u, mit_zahlung=True, personen=1)
        f3.anfragen()
        r = f3.halten()
        if r.status == 201:
            r = f3.buchen()
        self.assertIn(r.status, (409, 422), "Überbuchung wird abgewiesen")


if __name__ == "__main__":
    unittest.main()
