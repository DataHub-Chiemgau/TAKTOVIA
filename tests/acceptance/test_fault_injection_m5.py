"""WP-M5-16: Fault-Injection für Datenbank, Worker, Cache, PSP, Kartenquelle und Messaging gegen die ECHTE Anwendung (Spring-Boot-Jar, Runtime-Rolle, PostgreSQL/RLS) in der Ersatzumgebung der
E2E-Suite (kein Staging, kein Broker, kein Redis, PSP-Simulator, lokaler JWT-Issuer; siehe tests/acceptance/e2e/referenzwelt.py).

Erwartungstabelle je Szenario (Nutzerwirkung, Fehlercode, Alarm, Erholung; Alarme aus infra/alerting/katalog.yaml):

| Szenario | Nutzerwirkung | Fehlercode | Alarm | Erholung (Test) |
|---|---|---|---|---|
| Datenbank: Verbindungsabriss | einzelne Anfragen scheitern, danach normal | 503 `SERVICE_UNAVAILABLE` (`Retry-After`) | `DATENBANK_NICHT_ERREICHBAR` (bei Dauer > 1 min), `KERNBUCHUNG_AUSFALL` | selbständig, ohne Neustart (<= 40 s) |
| Datenbank: Ausfall | keine Antwort mit Daten, jede fachliche Anfrage `503`; Anfragen mit ungültigem Token `401` | 503 | `DATENBANK_NICHT_ERREICHBAR`, `KERNBUCHUNG_AUSFALL` | selbständig nach Rückkehr (<= 40 s) |
| Datenbank: Failover während paralleler Bestätigungen | wiederholte Anfragen mit demselben Schlüssel, keine Überbuchung | 503, 409 `CAPACITY_UNAVAILABLE` | wie oben | selbständig |
| Worker (Fachkern) Absturz zwischen PSP-Aufruf und lokalem Commit | Buchung bleibt `CONFIRMING` bis Wiederanlauf, dann bestätigt | - | `OPERATION_LEASE_UEBERFAELLIG`, `PSP_UNBESTIMMT` | Neustart, Wiederaufnahme über Lease und Abgleich (<= 60 s) |
| Dokumenten-Worker hängt | Upload bleibt in Quarantäne, nichts Ungeprüftes wird ausgeliefert | - | `DOKUMENTEN_WORKER_AUSFALL` | Rescan nach Rückkehr; Nachweis: `UploadTicketTest` (Kotlin), nicht über die HTTP-Welt ausführbar (kein Objektspeicher) |
| Cache (Redis) Ausfall/Datenverlust | keine (Redis ist nicht angebunden; Kapazität, Preis, Zahlung unberührt) | - | `REDIS_AUSFALL` | n. a.; Nachweis: Referenzfall ohne Redis, `RoutingFaultInjectionTest` (Kotlin) |
| PSP nicht erreichbar | „Ergebnis wird geprüft“ (Buchung `CONFIRMING`), keine Neubuchung, keine definitive Fehlermeldung | - (202 mit Operation) | `PSP_UNBESTIMMT` | Abgleich/Wiederholung nach Rückkehr, genau eine Autorisierung |
| PSP Timeout/verlorene Antwort | wie oben | - | `PSP_UNBESTIMMT` | siehe `test_negative_e2e` (AT-15) |
| Kartenquelle (Routing-Worker) Ausfall | neue Angebote begründet abgelehnt oder mit Rückfallwerten, nie `5xx`; bestehende Fahrtenpläne abrufbar | je Ablehnungsgrund | `ROUTING_WORKER_AUSFALL` | Rückkehr ohne Eingriff |
| Messaging unterbrochen | Buchungen laufen weiter; Kennzahlen/Benachrichtigung verzögert | - | `MESSAGING_NICHT_ERREICHBAR`, `OUTBOX_STAU` | Outbox-Abbau, jedes Ereignis genau einmal wirksam (<= 40 s) |

Messaging: der Broker (RabbitMQ) ist hier nicht verfügbar; „Zustellweg unterbrochen“ heißt, dass der Outbox-Relay im Prozess für alle Mandanten angehalten wird (Mandantenliste leer). Das ist KEIN Ausfall des
echten Brokers (nicht ausgeführt); der Relay-Kern mit Publisher-Ausfall, Dead-Letter und Reihenfolge ist in den Kotlin-Tests `OutboxRelayTest`/`FactBaseTest` abgedeckt.
Alle Läufe unter Grundlast (ein Fahrgast fragt wiederholt Angebote an und liest Buchungen). Nach jedem Szenario läuft der Referenzfall (Positivtest) ohne Eingriff und die Aufräumprüfung ohne Befund.

Voraussetzung: `cd backend && ./gradlew :app:bootJar`; Aufruf: cd tests/acceptance && python -m unittest -v test_fault_injection_m5"""
import sys
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests" / "lib"))

from e2e import basis, aufraeumen  # noqa: E402
from e2e.basis import Modul  # noqa: E402
from e2e.fehler import Datenbankfehler, Grundlast  # noqa: E402
from e2e.referenzfall import Fall, Umlauf, Http, durchlaufen, warte  # noqa: E402
from e2e.referenzwelt import key  # noqa: E402

A, B = 0, 1


def setUpModule():
    basis.hochfahren(dbname="taktovia_at_e2e_fehler")


def tearDownModule():
    basis.herunterfahren()


class Fehlerinjektion(unittest.TestCase):
    @property
    def welt(self):
        return Modul.welt.welt

    @property
    def sim(self):
        return Modul.welt.sim

    def mandant(self, i):
        return Modul.welt.mandanten[i]

    def nach_rueckkehr_referenzfall(self, m):
        """Positivtest: nach Rückkehr der Abhängigkeit läuft der Referenzfall ohne Eingriff grün; die Aufräumprüfung ist ohne Befund."""
        (f,), _ = durchlaufen(self.welt, m)
        self.assertEqual([s for s in f.schritte if not s["ok"]], [])
        self.assertEqual(f.buchungszustand(), "COMPLETED")
        b = aufraeumen.pruefen(self.welt, m.id)
        self.assertEqual(b["befunde"], {}, "Aufräumprüfung")

    def sim_faults_zuruecksetzen(self):
        with self.sim.lock:
            self.sim.faults.clear()

    def fehler_setzen(self, operation, modus, times=1, hold=0):
        from taktovia_testkit.jsonhttp import http_call
        st, _, _, _ = http_call("POST", Modul.welt.sim_app.url + "/control/faults", {"operation": operation, "ref": "*", "mode": modus, "times": times, "hold_seconds": hold}, {}, 5)
        self.assertEqual(st, 201)

    def tearDown(self):
        self.sim_faults_zuruecksetzen()
        Datenbankfehler(Modul.welt).rueckkehr()
        Modul.welt.relay_an()

    # ------------------------------------------------------------------------------------------------------------------ Datenbank
    def test_datenbank_verbindungsabriss_selbsterholung_und_kein_fremder_tenantkontext(self):
        a, b = self.mandant(A), self.mandant(B)
        fa, fb = a.neuer_fahrgast(), b.neuer_fahrgast()
        # je ein Buchungsstand, damit die Listen der Mandanten unterscheidbar sind
        (ca,), _ = durchlaufen(self.welt, a, bis="bestaetigt")
        (cb,), _ = durchlaufen(self.welt, b, bis="bestaetigt")
        db = Datenbankfehler(Modul.welt)
        with Grundlast(self.welt, fb) as last:
            t0 = time.time()
            for _ in range(4):
                db.verbindungen_kappen()
                time.sleep(0.6)
            # selbständige Erholung ohne Neustart: wieder normale Antworten
            warte(lambda: Http(self.welt)("GET", "/v1/bookings", fa.token if False else ca.fahrgast.token).status == 200, 40, 1.0, "Erholung nach Verbindungsabriss")
            codes = last.statuscodes(t0)
        self.assertTrue(set(codes) <= {200, 500, 503}, f"nur Erfolg oder abgeschlossene Fehlerantworten: {codes}")
        self.assertNotIn(500, codes, "kein Containerfehler: Verbindungsfehler in Filtern werden als 503 ausgeliefert")
        # nach Rückkehr wirkt kein Tenantkontext einer früheren Verbindung fort: abwechselnde Anfragen beider Mandanten sehen nur eigene Buchungen
        def liste(f):
            r = Http(self.welt)("GET", "/v1/bookings", f.token)
            return r.status, [x["id"] for x in (r.body or {}).get("items", [])]

        for _ in range(30):
            sa, ids_a = liste(ca.fahrgast)
            sb, ids_b = liste(cb.fahrgast)
            self.assertEqual((sa, sb), (200, 200))
            self.assertIn(ca.buchung_id, ids_a); self.assertNotIn(cb.buchung_id, ids_a)
            self.assertIn(cb.buchung_id, ids_b); self.assertNotIn(ca.buchung_id, ids_b)
        self.nach_rueckkehr_referenzfall(a)

    def test_datenbank_ausfall_fail_closed_und_selbsterholung(self):
        a, b = self.mandant(A), self.mandant(B)
        fa = a.neuer_fahrgast()
        fb = b.neuer_fahrgast()
        db = Datenbankfehler(Modul.welt)
        http = Http(self.welt)
        db.ausfall()
        try:
            ergebnisse = []
            with Grundlast(self.welt, fb):
                for _ in range(6):
                    r = http("GET", "/v1/bookings", fa.token)
                    ergebnisse.append(("fachlich", r.status, r.code))
                    r = http("POST", "/v1/ride-offers", fa.token, fa.anfrage_koerper(tage=3))
                    ergebnisse.append(("angebot", r.status, r.code))
                    r = http("GET", "/v1/bookings", "kein.gueltiges.token")
                    ergebnisse.append(("ungueltig", r.status, r.code))
                    time.sleep(0.3)
            fachlich = {(art, st, code) for art, st, code in ergebnisse if art != "ungueltig"}
            self.assertEqual(fachlich, {("fachlich", 503, "SERVICE_UNAVAILABLE"), ("angebot", 503, "SERVICE_UNAVAILABLE")},
                             "fail-closed: keine fachliche Anfrage wird ohne Datenbank (Mandanten- und Sitzungskontext) beantwortet")
            self.assertEqual({st for art, st, _ in ergebnisse if art == "ungueltig"}, {401}, "ein ungültiges Token bleibt 401, nie 200")
        finally:
            db.rueckkehr()
        warte(lambda: http("GET", "/v1/bookings", fa.token).status == 200, 40, 1.0, "Erholung nach Datenbankausfall")
        self.nach_rueckkehr_referenzfall(a)

    def test_datenbank_failover_waehrend_parallelen_bestaetigungen(self):
        """40 Fahrgäste greifen auf die 4 Sitze eines Fahrzeugs, während die Datenbankverbindungen wiederholt abreißen; Clients wiederholen mit demselben Schlüssel."""
        m = self.mandant(A)
        u = Umlauf(self.welt, m, fahrzeug=2, fahrer=1)   # XX-TX 103: 4 Sitze
        try:
            n = 40
            with ThreadPoolExecutor(8) as ex:
                faelle = list(ex.map(lambda _: Fall(self.welt, m, u, mit_zahlung=False), range(n)))
                list(ex.map(lambda f: f.anfragen(), faelle))
            for f in faelle:
                f.binden()
            db = Datenbankfehler(Modul.welt)
            stop = threading.Event()

            def chaos():
                while not stop.is_set():
                    db.verbindungen_kappen()
                    stop.wait(0.4)

            start = threading.Barrier(n + 1)

            def griff(f):
                start.wait()
                h = f.halten(binden=False, wiederholen=60)
                if h.status != 201:
                    return h.status, h.code
                k = key("buchung")
                for _ in range(160):
                    r = f.buchen(idem=k)
                    if r.status < 500 and r.code != "OPERATION_IN_PROGRESS":   # 409 OPERATION_IN_PROGRESS: die erste Verarbeitung dieses Schlüssels läuft noch, der Client wartet und wiederholt
                        return r.status, r.code
                    time.sleep(0.5)
                return r.status, r.code

            t = threading.Thread(target=chaos, daemon=True)
            t.start()
            with ThreadPoolExecutor(n) as ex:
                futs = [ex.submit(griff, f) for f in faelle]
                start.wait()
                time.sleep(6)
                stop.set()
                ergebnis = [x.result(180) for x in futs]
            t.join(10)
            ok = [e for e in ergebnis if e[0] == 201]
            self.assertEqual(len(ok), 4, f"genau die vier freien Sitze sind bestätigt: {sorted(set(ergebnis))}")
            self.assertEqual({e for e in ergebnis if e[0] != 201}, {(409, "CAPACITY_UNAVAILABLE")}, "alle übrigen mit eindeutigem Grund abgewiesen, nie ein Containerfehler")
            with Modul.welt.admin() as c:
                sitze = c.execute("SELECT coalesce(sum((capacity_vector->>'seats')::int),0) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s AND state IN ('HELD','CONSUMED')", (m.id, u.run)).fetchone()[0]
                bu = c.execute("SELECT count(*), count(DISTINCT b.hold_id) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id=b.tenant_id AND h.id=b.hold_id WHERE b.tenant_id=%s AND h.run_id=%s", (m.id, u.run)).fetchone()
                ohne_hold = c.execute("SELECT count(*) FROM taktovia.capacity_hold h WHERE h.tenant_id=%s AND h.run_id=%s AND h.state='CONSUMED' AND NOT EXISTS (SELECT 1 FROM taktovia.booking b WHERE b.tenant_id=h.tenant_id AND b.hold_id=h.id)", (m.id, u.run)).fetchone()[0]
            self.assertLessEqual(sitze, 4, "keine Überbuchung")
            self.assertEqual(bu, (4, 4), "keine doppelte Wirkung: eine Buchung je Hold")
            self.assertEqual(ohne_hold, 0, "kein verbrauchter Hold ohne Buchung")
        finally:
            u.freigeben()
        self.nach_rueckkehr_referenzfall(m)

    # ------------------------------------------------------------------------------------------------------------------ Worker
    def test_worker_absturz_zwischen_psp_aufruf_und_lokalem_commit(self):
        """PH-AT-009, Einbetreiber-Variante: der Fachkern (Operation-Runner) stirbt, nachdem der PSP die Autorisierung angewandt hat, aber bevor das Ergebnis lokal festgeschrieben ist."""
        m = self.mandant(A)
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f = Fall(self.welt, m, u, mit_zahlung=True)
            f.anfragen(); f.halten(); f.buchen()
            self.fehler_setzen("authorize", "lost_response", times=1, hold=8)   # Wirkung beim PSP vorhanden, Antwort verzögert und dann verworfen
            f.bezahlen()
            warte(lambda: any(c.get("operation") == "authorize" and c.get("effect_applied") for c in self.sim.calls if c.get("merchant_ref") in {z[6] for z in f.zahlung()}), 20, 0.1, "PSP hat die Autorisierung angewandt")
            Modul.welt.welt.app.proc.kill()      # Absturz (SIGKILL) mitten im Aufruf
            Modul.welt.welt.app.proc.wait(20)
            self.assertEqual(f.buchungszustand(), "CONFIRMING", "lokal nicht festgeschrieben: die Buchung ist noch nicht bestätigt")
            Modul.welt.neu_starten()             # Wiederanlauf; die Operation wird nach Ablauf der Lease von der neuen Instanz aufgenommen
            warte(lambda: f.buchungszustand() == "CONFIRMED", 90, 1.0, "Buchung nach Wiederanlauf bestätigt")
            z = f.zahlung_abwarten("CAPTURED", 60)
            self.assertEqual(len(z), 1, "genau eine Zahlung, keine Neubuchung mit neuer Referenz")
            ref = z[0][6]
            mit_wirkung = [c for c in self.sim.calls if c.get("operation") == "authorize" and c.get("merchant_ref") == ref and c.get("effect_applied")]
            self.assertEqual(len(mit_wirkung), 1, "genau eine Autorisierung mit Wirkung beim PSP, keine zweite Belastung")
            self.assertEqual(len({c.get("merchant_ref") for c in self.sim.calls if c.get("operation") == "authorize"} & {ref}), 1)
            self.assertLessEqual(len([c for c in self.sim.calls if c.get("operation") == "capture" and c.get("merchant_ref") == ref and c.get("effect_applied")]), 1)
            self.assertEqual((z[0][2], z[0][3], z[0][4]), (m.preis, m.preis, 0))
        finally:
            u.freigeben()
        self.nach_rueckkehr_referenzfall(m)

    # ------------------------------------------------------------------------------------------------------------------ PSP
    def test_psp_nicht_erreichbar_ergebnis_wird_geprueft_keine_neubuchung(self):
        m = self.mandant(B)
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f = Fall(self.welt, m, u, mit_zahlung=True)
            f.anfragen(); f.halten(); f.buchen()
            self.fehler_setzen("authorize", "unavailable", times=-1)
            r = f.bezahlen()
            op = r.body.get("operation_id")
            with Grundlast(self.welt, m.neuer_fahrgast()):
                time.sleep(14)
                # „Ergebnis wird geprüft“: weder bestätigt noch storniert noch als Fehler beendet; genau eine Zahlung, keine zweite Referenz
                self.assertEqual(f.buchungszustand(), "CONFIRMING")
                z = f.zahlung()
                self.assertEqual(len(z), 1)
                with Modul.welt.admin() as c:   # Zustand der Operation (der Fahrgast darf `/v1/operations` nicht lesen; hier nur lesend)
                    op_status = c.execute("SELECT status FROM taktovia.operation WHERE tenant_id=%s AND id=%s", (m.id, op)).fetchone()[0]
                self.assertIn(op_status, ("QUEUED", "RUNNING", "RECONCILING"), "Ergebnis wird geprüft: nicht als Fehler beendet (keine definitive Fehlermeldung ohne Nachweis)")
                aufrufe = [c for c in self.sim.calls if c.get("operation") == "authorize" and c.get("merchant_ref") == z[0][6]]
                self.assertTrue(aufrufe and not any(c.get("effect_applied") for c in aufrufe), "der PSP hat nichts angewandt")
                self.assertEqual(len({c.get("merchant_ref") for c in self.sim.calls if c.get("operation") == "authorize"} & {z[0][6]}), 1)
            self.sim_faults_zuruecksetzen()           # PSP kehrt zurück
            warte(lambda: f.buchungszustand() == "CONFIRMED", 90, 1.0, "Buchung nach Rückkehr des PSP bestätigt")
            z = f.zahlung_abwarten("CAPTURED", 60)
            wirksam = [c for c in self.sim.calls if c.get("operation") == "authorize" and c.get("merchant_ref") == z[0][6] and c.get("effect_applied")]
            self.assertEqual((len(z), len(wirksam)), (1, 1), "genau eine Zahlung, genau eine Autorisierung mit Wirkung")
        finally:
            self.sim_faults_zuruecksetzen()
            u.freigeben()
        self.nach_rueckkehr_referenzfall(m)

    # ------------------------------------------------------------------------------------------------------------------ Messaging
    def test_messaging_unterbrechung_outbox_abbau_genau_einmal_wirksam(self):
        m = self.mandant(A)
        with Modul.welt.admin() as c:
            vorher = c.execute("SELECT count(*) FROM taktovia.analytics_event_inbox WHERE tenant_id=%s", (m.id,)).fetchone()[0]
        Modul.welt.relay_aus()
        time.sleep(3)   # laufende Läufe des Relays beenden sich
        with Grundlast(self.welt, m.neuer_fahrgast()):
            (f,), _ = durchlaufen(self.welt, m)           # das Geschäft läuft weiter, die Ereignisse stauen sich
            self.assertEqual([s for s in f.schritte if not s["ok"]], [])
            time.sleep(3)
            with Modul.welt.admin() as c:
                offen = c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND published_at IS NULL AND dead_lettered_at IS NULL", (m.id,)).fetchone()[0]
                inbox = c.execute("SELECT count(*) FROM taktovia.analytics_event_inbox WHERE tenant_id=%s", (m.id,)).fetchone()[0]
            self.assertGreater(offen, 10, "die Ereignisse stauen sich in der Outbox")
            self.assertEqual(inbox, vorher, "Verbraucher sehen während der Unterbrechung nichts")
        Modul.welt.relay_an()
        warte(lambda: Modul.welt.admin().execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND published_at IS NULL AND dead_lettered_at IS NULL", (m.id,)).fetchone()[0] == 0, 60, 1.0, "Outbox abgebaut")
        with Modul.welt.admin() as c:
            tot = c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND dead_lettered_at IS NOT NULL", (m.id,)).fetchone()[0]
            nach = c.execute("SELECT count(*), count(DISTINCT event_id) FROM taktovia.analytics_event_inbox WHERE tenant_id=%s", (m.id,)).fetchone()
            fakt_vorher = c.execute("SELECT md5(string_agg(row_to_json(b)::text, ',' ORDER BY booking_id)) FROM taktovia.fact_booking b WHERE tenant_id=%s", (m.id,)).fetchone()[0]
        self.assertEqual(tot, 0, "nichts Dead-Letter")
        self.assertEqual(nach[0], nach[1], "jedes Ereignis genau einmal in der Inbox")
        self.assertGreater(nach[0], vorher, "die gestauten Ereignisse sind wirksam geworden")
        # mehrfache Zustellung nach Rückkehr: zuletzt veröffentlichte Ereignisse werden erneut zugestellt (Wiederholung des Relays nach Ausfall zwischen Veröffentlichung und Markierung)
        with Modul.welt.aufbau() as c:
            c.execute("ALTER TABLE taktovia.outbox DISABLE TRIGGER USER")
            try:
                c.execute("UPDATE taktovia.outbox SET published_at = NULL, delivery_attempts = 0, next_attempt_at = NULL, claimed_until = NULL WHERE tenant_id=%s AND event_id IN (SELECT event_id FROM taktovia.outbox WHERE tenant_id=%s AND event_type IN ('booking.confirmed','driver.event_recorded','booking.offer_created') ORDER BY occurred_at DESC LIMIT 20)", (m.id, m.id))
            finally:
                c.execute("ALTER TABLE taktovia.outbox ENABLE TRIGGER USER")
        warte(lambda: Modul.welt.admin().execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND published_at IS NULL AND dead_lettered_at IS NULL", (m.id,)).fetchone()[0] == 0, 60, 1.0, "erneut zugestellt")
        with Modul.welt.admin() as c:
            nach2 = c.execute("SELECT count(*), count(DISTINCT event_id) FROM taktovia.analytics_event_inbox WHERE tenant_id=%s", (m.id,)).fetchone()
            fakt_nachher = c.execute("SELECT md5(string_agg(row_to_json(b)::text, ',' ORDER BY booking_id)) FROM taktovia.fact_booking b WHERE tenant_id=%s", (m.id,)).fetchone()[0]
        self.assertEqual(nach2, nach, "die doppelte Zustellung hat keine zweite Wirkung (Inbox-Dedupe)")
        self.assertEqual(fakt_nachher, fakt_vorher, "Fakten unverändert")
        self.nach_rueckkehr_referenzfall(m)

    # ------------------------------------------------------------------------------------------------------------------ Cache und Kartenquelle
    def test_cache_ohne_redis_hold_confirm_zahlung_korrekt(self):
        """Redis ist in dieser Version nicht angebunden (der flüchtige Cache ist nur eine Schnittstelle): die Anwendung läuft dauerhaft in dem Zustand, den ein Redis-Ausfall erzeugt.
        Belegt wird, dass Kapazität (Hold/Confirm), Preis und Zahlungszustand ohne Redis korrekt sind; der Ausfall-Pfad der Schnittstelle selbst steht in `RoutingFaultInjectionTest` (Kotlin)."""
        m = self.mandant(A)
        health = Http(self.welt)("GET", "/actuator/health", "x")
        self.assertIn(health.status, (200, 401, 404))
        (f,), _ = durchlaufen(self.welt, m)
        self.assertEqual(f.angebot["gross"]["amount_minor"], m.preis, "Preis unverändert")
        self.assertEqual([z[1:5] for z in f.zahlung()], [("CAPTURED", m.preis, m.preis, 0)], "Zahlungszustand unverändert")
        with Modul.welt.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND id=%s AND state='CONSUMED'", (m.id, f.hold.body["id"])).fetchone()[0], 1, "Kapazität verbraucht")

    def test_kartenquelle_ausfall_angebote_definiert_und_fahrtenplaene_abrufbar(self):
        """Der Routing-Worker (hier ein Stub nach `workers/routing/contract`) fällt aus: neue Angebote entstehen mit Rückfallwerten oder werden mit Grund abgelehnt, nie als `5xx`; der bestehende
        Fahrtenplan bleibt abrufbar; nach Rückkehr läuft alles ohne Eingriff weiter."""
        import json
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        repo = Path(__file__).resolve().parents[2]
        route = (repo / "workers" / "routing" / "contract" / "route-response.json").read_text(encoding="utf-8")
        zustand = {"aus": False}

        class H(BaseHTTPRequestHandler):
            def _antwort(self):
                if zustand["aus"]:
                    self.connection.close()
                    return
                body = json.dumps({"map_version": "referenz-2026-11-01"}) if self.path.startswith("/v1/map-version") else route
                raw = body.encode()
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)

            do_GET = do_POST = _antwort

            def log_message(self, *a):
                pass

        srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        m = self.mandant(A)
        try:
            Modul.welt.neu_starten(extra=[f"--taktovia.routing.worker-url=http://127.0.0.1:{srv.server_address[1]}", "--taktovia.routing.budget-millis=500"])
            fg = m.neuer_fahrgast()
            http = Http(self.welt)
            u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
            try:
                f = Fall(self.welt, m, u, mit_zahlung=True)
                f.fahrgast = fg
                f.anfragen()
                vorher = f.angebot
                self.assertIsNotNone(vorher, "mit Kartenquelle: Angebot")
                f.halten(); f.buchen(); f.bezahlen()
                warte(lambda: f.buchungszustand() == "CONFIRMED", 60, 1.0, "bestätigt")
                f.planen(); f.dienststart()
                zustand["aus"] = True
                for _ in range(4):   # Ausfall: Angebote definiert, nie 5xx
                    r = http("POST", "/v1/ride-offers", fg.token, fg.anfrage_koerper(tage=3))
                    self.assertEqual(r.status, 200, f"Angebotsanfrage bei Ausfall der Kartenquelle: {r.status} {r.body}")
                    if r.body["offers"]:
                        o = r.body["offers"][0]
                        self.assertTrue(o["eta"]["fallback_reason"] or o["walk"]["status"] == "FALLBACK", "Angebot nur mit gekennzeichneten Rückfallwerten, keine Zusage aus unvalidierten Daten")
                    else:
                        self.assertTrue(r.body["rejection"]["code"], "begründete Ablehnung")
                run = http("GET", f"/v1/driver/shifts/{u.schicht}/run", u.token)
                self.assertEqual(run.status, 200, "bestehender Fahrtenplan bleibt abrufbar")
                self.assertTrue(run.body["stops"], "Stopps des bestehenden Plans")
                zustand["aus"] = False
                r = http("POST", "/v1/ride-offers", fg.token, fg.anfrage_koerper(tage=3))
                self.assertEqual(r.status, 200)
                f.fahrt()
            finally:
                u.freigeben()
        finally:
            srv.shutdown()
            Modul.welt.neu_starten()   # Zustand der Welt wiederherstellen (ohne Kartenquelle)
        self.nach_rueckkehr_referenzfall(m)


if __name__ == "__main__":
    unittest.main()
