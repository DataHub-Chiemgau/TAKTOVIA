"""WP-M5-24 (LH-6.20-QAT-004): negative E2E-Fälle mit definierten Endstatus und Aufräumprozessen, Wiederholung von AT-03, AT-07, AT-08, AT-09, AT-15, PH-AT-004, PH-AT-011.

ERSATZUMGEBUNG, kein Staging (siehe tests/acceptance/e2e/referenzwelt.py): echte Anwendung (Jar, Runtime-Rolle, PostgreSQL/RLS), lokaler JWT-Test-Issuer, PSP-Simulator, kein Broker (Outbox-Relay im
Prozess). Planbindung, Fahrzeugfreigabe, Schicht, Planstopps und das Rückdatieren eines Holds schreibt der Testaufbau per Administratorzugang (gekennzeichnet `e2e-testaufbau`).

Tabelle Negativfall -> erwarteter Endstatus -> Aufräumprozess -> Frist (die Fristen sind Arbeitsannahmen des Tests, das Pflichtenheft nennt keine, OP-17):

| Negativfall | Endstatus (Buchung / Hold / Zahlung / Plan) | Aufräumprozess | Frist im Test |
|---|---|---|---|
| Doppelbuchung letzter Platz (100 parallel) | genau eine bestätigte Buchung; Verlierer ohne Hold, 409 mit Grund | keiner nötig (Abweisung im Hold) | sofort |
| gleicher Schlüssel, gleicher Befehl | dieselbe Buchung (Replay) | Idempotenzregister | sofort |
| gleicher Schlüssel, anderer Befehl | 409 IDEMPOTENCY_CONFLICT, keine zweite Buchung | Idempotenzregister | sofort |
| No-Show nach Wartefrist | CANCELED (Grund NO_SHOW) / RELEASED / Zahlung nach Regel (`noShowPercent` 0: eingezogen) / Plan ohne Buchung | Wirkung des Fahrerereignisses (NoShowCleanup) | in derselben Transaktion |
| No-Show vor Wartefrist | 422 NO_SHOW_GRACE_NOT_ELAPSED, nichts gespeichert | - | sofort |
| Storno nach Disposition (Betrieb) | CANCELED / RELEASED / REFUNDED / Plan ohne Buchung | Storno-Kern, Erstattungsauftrag | Erstattung nach Settlement (<= 40 s) |
| Hold-Ablauf | Bestätigung 409 HOLD_EXPIRED; Hold EXPIRED | Hold-Ablauf-Zeitgeber | <= 20 s nach Ablauf |
| PSP-Timeout (Autorisierung) | CONFIRMED / eine Autorisierung mit Wirkung / eingezogen | Wiederholung mit demselben Schlüssel und Abgleich (Statusabfrage) | <= 40 s |
| PSP verlorene Antwort | CONFIRMED / genau eine Autorisierung beim PSP | Abgleich über Statusabfrage | <= 40 s |
| Fahrzeugausfall (Sperre) | Sperre aktiv, Incident OPEN, Buchung im Störungsablauf (OPEN_ISSUE -> Betreiberstorno -> CANCELED) | Störungsablauf, Ersatzsuche | <= 20 s |

Voraussetzung: `cd backend && ./gradlew :app:bootJar`; Aufruf: cd tests/acceptance && python -m unittest -v test_negative_e2e"""
import sys
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests" / "lib"))

from e2e import basis, aufraeumen  # noqa: E402
from e2e.basis import Modul  # noqa: E402
from e2e.referenzfall import Fall, Umlauf, Http, durchlaufen, warte, kennzahlen  # noqa: E402
from e2e.referenzwelt import key  # noqa: E402

A, B, KOSTENFREI, KENNZAHLEN = 0, 1, 2, 3
_HOLD_SPERRE = threading.Lock()


def setUpModule():
    basis.hochfahren(dbname="taktovia_at_e2e_negativ")


def tearDownModule():
    basis.herunterfahren()


class Negativ(unittest.TestCase):
    @property
    def welt(self):
        return Modul.welt.welt

    @property
    def sim(self):
        return Modul.welt.sim

    def mandant(self, i):
        return Modul.welt.mandanten[i]

    def sauber(self, m):
        b = aufraeumen.pruefen(self.welt, m.id)
        self.assertEqual(b["befunde"], {}, f"Aufräumprüfung {m.name}")
        return b

    def bestaetigt(self, m, umlauf=None, personen=1, geplant=True, tage=0, zahlen=True):
        """Fall mit bestätigter Buchung (Zahlung bis `CONFIRMED`, wenn der Tarif einen Preis hat) und optional geplanter Fahrt."""
        u = umlauf or Umlauf(self.welt, m)
        f = Fall(self.welt, m, u, mit_zahlung=m.preis > 0, personen=personen)
        f.anfragen(tage=tage)
        f.halten()
        f.buchen()
        if f.mit_zahlung and zahlen:
            f.bezahlen()
            warte(lambda: f.buchungszustand() == "CONFIRMED", 40, was="Buchung bestätigt")
        if geplant:
            f.planen()
        return f, u

    def hold_zustand(self, m, f):
        with Modul.welt.admin() as c:
            return c.execute("SELECT h.state FROM taktovia.capacity_hold h JOIN taktovia.booking b ON b.tenant_id=h.tenant_id AND b.hold_id=h.id WHERE b.tenant_id=%s AND b.id=%s", (m.id, f.buchung_id)).fetchone()[0]

    def historie(self, m, f):
        with Modul.welt.admin() as c:
            return c.execute("SELECT to_state, reason_code, actor_kind, actor_ref FROM taktovia.booking_status_history WHERE tenant_id=%s AND booking_id=%s ORDER BY booking_revision", (m.id, f.buchung_id)).fetchall()

    def plan_enthaelt(self, m, u, f):
        with Modul.welt.admin() as c:
            return c.execute("""SELECT count(*) FROM taktovia.vehicle_run r JOIN taktovia.route_stop s ON s.tenant_id=r.tenant_id AND s.run_id=r.id AND s.run_revision=r.revision
                                WHERE r.tenant_id=%s AND r.id=%s AND (s.boardings @> %s::jsonb OR s.alightings @> %s::jsonb)""",
                             (m.id, u.run, f'[{{"booking_id": "{f.buchung_id}"}}]', f'[{{"booking_id": "{f.buchung_id}"}}]')).fetchone()[0]

    def sim_aufrufe(self, f, operation):
        refs = {z[6] for z in f.zahlung()}
        return [c for c in self.sim.calls if c.get("operation") == operation and c.get("merchant_ref") in refs]

    # ------------------------------------------------------------------------------------------------------------------ (a) Doppelbuchung
    def doppelbuchung(self, m, teilnehmer):
        """Letzter Sitz eines 4-Sitz-Fahrzeugs: ein Fahrgast hat 3 Sitze bestätigt, `teilnehmer` weitere Fahrgäste greifen gleichzeitig auf den letzten."""
        u = Umlauf(self.welt, m, fahrzeug=2, fahrer=1)   # XX-TX 103: 4 Sitze
        try:
            vorab, _ = self.bestaetigt(m, umlauf=u, personen=3, geplant=False, zahlen=False)
            with ThreadPoolExecutor(8) as ex:
                faelle = list(ex.map(lambda _: Fall(self.welt, m, u, mit_zahlung=m.preis > 0), range(teilnehmer)))
                list(ex.map(lambda f: f.anfragen(), faelle))
            for f in faelle:
                f.binden()                              # Testaufbau, nacheinander (tabellenweiter Schalter)
            start = threading.Barrier(teilnehmer)

            def griff(f):
                start.wait()
                return f.halten(binden=False, wiederholen=30)   # 503 (Pool erschöpft) wird wie von einem Client mit Retry-After wiederholt, mit demselben Schlüssel

            with ThreadPoolExecutor(teilnehmer) as ex:
                holds = list(ex.map(griff, faelle))
            ok = [h for h in holds if h.status == 201]
            self.assertEqual(len(ok), 1, [h.status for h in holds])
            abgewiesen = {(h.status, h.code) for h in holds if h.status != 201}
            self.assertEqual(len(abgewiesen), 1, f"alle Abweisungen mit demselben eindeutigen Grund: {abgewiesen}")
            self.assertEqual(next(iter(abgewiesen))[0], 409)
            self.assertIsNotNone(next(iter(abgewiesen))[1], "Abweisung mit Fehlercode")
            sieger = faelle[holds.index(ok[0])]
            self.assertEqual(sieger.buchen().status, 201)
            with Modul.welt.admin() as c:
                sitze = c.execute("SELECT coalesce(sum((capacity_vector->>'seats')::int),0) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s AND state IN ('HELD','CONSUMED')", (m.id, u.run)).fetchone()[0]
                buchungen = c.execute("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id=b.tenant_id AND h.id=b.hold_id WHERE b.tenant_id=%s AND h.run_id=%s", (m.id, u.run)).fetchone()[0]
                abgewiesen_holds = c.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s", (m.id, u.run)).fetchone()[0]
            self.assertEqual((sitze, buchungen), (4, 2), "höchstens eine neue Sitzreservierung: 3 + 1 Sitze, keine negative Belegung")
            self.assertEqual(abgewiesen_holds, 2, "die Verlierer haben keinen Hold hinterlassen")
            return vorab, sieger
        finally:
            u.freigeben()

    def test_doppelbuchung_letzter_platz_hoechstens_eine_bestaetigung(self):
        """PH-AT-004 / AT-03: 100 parallele Griffe auf den letzten Sitz (Mandant A)."""
        m = self.mandant(A)
        self.doppelbuchung(m, 100)
        self.sauber(m)

    def test_gleicher_schluessel_anderer_request_konflikt_ohne_zweite_ausfuehrung(self):
        """PH-AT-011 und doppelt gesendeter Befehl (a): derselbe Schlüssel mit demselben Befehl liefert dieselbe Buchung, mit abweichendem Request den Konflikt ohne zweite Ausführung."""
        m = self.mandant(A)
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f = Fall(self.welt, m, u, mit_zahlung=m.preis > 0)
            f.anfragen()
            f.halten()
            k = key("buchung")
            r1 = f.buchen(idem=k)
            r2 = f.buchen(idem=k)                    # doppelt gesendeter Befehl, gleicher Schlüssel
            self.assertEqual((r1.status, r2.status), (201, 201))
            self.assertEqual(r1.body["id"], r2.body["id"], "der wiederholte Befehl liefert dieselbe Buchung")
            with Modul.welt.admin() as c:
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND hold_id=%s", (m.id, f.hold.body["id"])).fetchone()[0], 1)
            # gleicher Schlüssel, anderer Request (anderer Hold): Konflikt, keine zweite Buchung
            g = Fall(self.welt, m, u, mit_zahlung=m.preis > 0, fahrgast=f.fahrgast)   # derselbe Fahrgast: der Schlüssel gilt je Akteur
            g.anfragen()
            g.halten()
            r3 = g.http("POST", "/v1/bookings", g.fahrgast.token, {"hold_id": g.hold.body["id"], **g.max_gross()}, if_match=g.hold.etag, idem=k)
            self.assertEqual((r3.status, r3.code), (409, "IDEMPOTENCY_CONFLICT"), (r3.status, r3.body))
            with Modul.welt.admin() as c:
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND hold_id=%s", (m.id, g.hold.body["id"])).fetchone()[0], 0, "keine zweite Ausführung")
                self.assertEqual(c.execute("SELECT state FROM taktovia.capacity_hold WHERE tenant_id=%s AND id=%s", (m.id, g.hold.body["id"])).fetchone()[0], "HELD", "der zweite Hold bleibt unberührt")
            # derselbe Schlüssel mit dem Befehl des zweiten Falls unter eigenem Schlüssel funktioniert weiterhin
            self.assertEqual(g.buchen().status, 201)
        finally:
            u.freigeben()
        self.sauber(m)

    # ------------------------------------------------------------------------------------------------------------------ (b) No-Show
    def no_show(self, m):
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f, _ = self.bestaetigt(m, umlauf=u)
            f.dienststart()
            ein = u.stops[0]
            jetzt = f.ereignis("ARRIVAL", ein)
            self.assertEqual((jetzt.status, jetzt.body["state"]), (200, "APPLIED"))
            zu_frueh = f.ereignis("NO_SHOW", ein, extra={"no_show_reason": "NOT_PRESENT", "no_show_wait_seconds": 30})
            self.assertEqual((zu_frueh.status, zu_frueh.code), (422, "VALIDATION_FAILED"), "vor Ablauf der Wartefrist keine Wirkung")
            self.assertEqual(f.buchungszustand(), "CONFIRMED")
            self.assertEqual(self.hold_zustand(m, f), "CONSUMED")
            # zweiter Fahrgast desselben Umlaufs: Ankunft vor 25 Minuten (Gerät offline erfasst), danach gilt die Wartefrist als abgelaufen
            u.freigeben()
        finally:
            u.freigeben()
        u2 = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            g, _ = self.bestaetigt(m, umlauf=u2)
            g.dienststart()
            ein = u2.stops[0]
            vor = datetime.now(timezone.utc) - timedelta(minutes=25)
            self.assertEqual(g.ereignis("ARRIVAL", ein, wann=vor, extra={"offline_recorded": True}).body["state"], "APPLIED")
            r = g.ereignis("NO_SHOW", ein, extra={"no_show_reason": "NOT_PRESENT", "no_show_wait_seconds": 1500})
            self.assertEqual((r.status, r.body["state"]), (200, "APPLIED"))
            self.assertEqual(g.buchungszustand(), "CANCELED")
            h = self.historie(m, g)
            self.assertEqual([(x[0], x[1]) for x in h][-2:], [("CANCEL_PENDING", "NO_SHOW"), ("CANCELED", "CANCEL_COMPLETED")])
            self.assertTrue(all(x[2] and x[3] for x in h), "jeder Statuswechsel mit Akteur bzw. Systemprozess")
            self.assertEqual(self.hold_zustand(m, g), "RELEASED", "Kapazität freigegeben")
            self.assertEqual(self.plan_enthaelt(m, u2, g), 0, "Stopps freigegeben")
            with Modul.welt.admin() as c:
                self.assertEqual(c.execute("SELECT execution_state FROM taktovia.journey_leg l JOIN taktovia.booking b ON b.tenant_id=l.tenant_id AND b.journey_id=l.journey_id WHERE b.tenant_id=%s AND b.id=%s", (m.id, g.buchung_id)).fetchone()[0], "NO_SHOW")
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND event_type='driver.no_show_recorded' AND payload->>'booking_id'=%s", (m.id, g.buchung_id)).fetchone()[0], 1, "Leitstelle und Kennzahlen werden über das Ereignis informiert")
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND event_type='booking.cancelled' AND payload->>'booking_id'=%s", (m.id, g.buchung_id)).fetchone()[0], 0, "das Nicht-Erscheinen ist keine Fahrgaststornierung")
            # Zahlung gemäß Regel: No-Show-Erstattung `noShowPercent` = 0 (Standard), der Einzug bleibt bestehen und ist kein Klärfall
            if m.preis > 0:
                g.zahlung_abwarten("CAPTURED", 40)
                self.assertEqual([(z[1], z[3], z[4]) for z in g.zahlung()], [("CAPTURED", m.preis, 0)])
            return g
        finally:
            u2.freigeben()

    def test_no_show_nach_wartefrist_status_kapazitaet_freigegeben(self):
        """AT-08 (Fahrer No-Show) auf dem Abnahme-Build der Ersatzumgebung (Mandant A)."""
        m = self.mandant(A)
        self.no_show(m)
        self.sauber(m)

    # ------------------------------------------------------------------------------------------------------------------ (c) Storno nach Disposition
    def storno_nach_disposition(self, m):
        u = Umlauf(self.welt, m, fahrzeug=1, fahrer=1)
        try:
            f, _ = self.bestaetigt(m, umlauf=u)
            self.assertEqual(self.plan_enthaelt(m, u, f), 2, "Ein- und Ausstieg stehen im Plan")
            r = f.stornieren("OPERATOR_CANCELLATION", m.leitstelle, "Disposition")
            self.assertEqual((r.status, r.body["state"]), (200, "CANCELED"))
            self.assertEqual(self.hold_zustand(m, f), "RELEASED")
            self.assertEqual(self.plan_enthaelt(m, u, f), 0, "Fahrtenplan ohne stornierte Stopps")
            h = self.historie(m, f)
            self.assertEqual([(x[0], x[1]) for x in h][-2:], [("CANCEL_PENDING", "OPERATOR_CANCELLATION"), ("CANCELED", "CANCEL_COMPLETED")])
            with Modul.welt.admin() as c:
                rows = c.execute("SELECT actor_ref, occurred_at FROM taktovia.audit_event WHERE tenant_id=%s AND action='dispatch.booking.cancelled' AND object_ref=%s", (m.id, f"booking:{f.buchung_id}")).fetchall()
                self.assertEqual(len(rows), 1, f"Akteur im audit_event: {rows}")
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND event_type='booking.cancelled' AND payload->>'booking_id'=%s", (m.id, f.buchung_id)).fetchone()[0], 1)
            if m.preis > 0:   # eine erfolgte Zahlung wird nach der Betreiberregel (100 %) erstattet; der Endzustand ist REFUNDED, nie ein Klärfall
                warte(lambda: [z[1] for z in f.zahlung()] == ["REFUNDED"], 40, was="Erstattung abgeschlossen")
                self.assertEqual([(z[3], z[4]) for z in f.zahlung()], [(m.preis, m.preis)])
            return f
        finally:
            u.freigeben()

    def test_storno_nach_disposition_stopps_freigegeben_zahlung_regelkonform(self):
        """AT-07 (Storno nach Disposition) auf dem Abnahme-Build der Ersatzumgebung (Mandant A)."""
        m = self.mandant(A)
        self.storno_nach_disposition(m)
        self.sauber(m)

    # ------------------------------------------------------------------------------------------------------------------ (d) Timeouts
    def hold_ablauf(self, m):
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f = Fall(self.welt, m, u, mit_zahlung=m.preis > 0)
            f.anfragen()
            f.halten()
            with _HOLD_SPERRE, Modul.welt.aufbau() as c:   # Testaufbau: die Frist des Holds liegt in der Vergangenheit (Rückdatieren statt 3 Minuten Wartezeit; der Inhaltswächter des Holds ist dafür aus)
                c.execute("ALTER TABLE taktovia.capacity_hold DISABLE TRIGGER capacity_hold_core_immutable")
                try:
                    c.execute("UPDATE taktovia.capacity_hold SET expires_at = now() - interval '1 minute' WHERE tenant_id=%s AND id=%s", (m.id, f.hold.body["id"]))
                finally:
                    c.execute("ALTER TABLE taktovia.capacity_hold ENABLE TRIGGER capacity_hold_core_immutable")
            r = f.buchen()
            self.assertEqual((r.status, r.code), (409, "HOLD_EXPIRED"), "ein abgelaufener Hold bestätigt nicht")
            with Modul.welt.admin() as c:
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND hold_id=%s", (m.id, f.hold.body["id"])).fetchone()[0], 0)
            warte(lambda: self._hold(m, f) == "EXPIRED", 30, was="Hold-Ablauf-Zeitgeber")
            return f
        finally:
            u.freigeben()

    def _hold(self, m, f):
        with Modul.welt.admin() as c:
            return c.execute("SELECT state FROM taktovia.capacity_hold WHERE tenant_id=%s AND id=%s", (m.id, f.hold.body["id"])).fetchone()[0]

    def test_hold_ablauf_bestaetigt_nicht(self):
        m = self.mandant(A)
        self.hold_ablauf(m)
        self.sauber(m)

    def psp_fehler(self, m, modus):
        """Fehler beim PSP in der Autorisierung: `timeout` (Wirkung nicht angewandt) oder `lost_response` (Wirkung vorhanden, Antwort verloren)."""
        from taktovia_testkit.jsonhttp import http_call
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f = Fall(self.welt, m, u, mit_zahlung=True)
            f.anfragen()
            f.halten()
            f.buchen()
            st, _, _, _ = http_call("POST", Modul.welt.sim_app.url + "/control/faults", {"operation": "authorize", "ref": "*", "mode": modus, "times": 1}, {}, 5)
            self.assertEqual(st, 201)
            f.bezahlen()
            warte(lambda: f.buchungszustand() == "CONFIRMED", 60, was="Buchung bestätigt nach Abgleich")
            z = f.zahlung_abwarten("CAPTURED", 60)
            self.assertEqual(len(z), 1, "genau eine Zahlung, keine Neubuchung mit neuer Referenz")
            self.assertEqual((z[0][2], z[0][3], z[0][4]), (m.preis, m.preis, 0), "ein Einzug über den Betrag, nie doppelt")
            auth = self.sim_aufrufe(f, "authorize")
            wirksam = [c for c in auth if c.get("effect_applied")]
            self.assertEqual(len(wirksam), 1, f"genau eine Autorisierung mit Wirkung beim PSP: {auth}")
            self.assertTrue(any(c.get("fault") == modus for c in auth), "der Fehler wurde ausgelöst")
            self.assertEqual(len({c.get("merchant_ref") for c in auth}), 1, "stets dieselbe Händlerreferenz")
            caps = [c for c in self.sim_aufrufe(f, "capture") if c.get("effect_applied")]
            self.assertEqual(len(caps), 1, "höchstens ein Einzug beim PSP")
            return f, u
        finally:
            u.freigeben()

    def test_psp_timeout_genau_ein_endzustand_ohne_zweite_belastung(self):
        """AT-15 (Zahlungsfehler und Retry): Timeout und verlorene Antwort enden nach Abgleich in genau einem Endzustand; ein später eintreffendes identisches Ereignis ändert nichts."""
        m = self.mandant(A)
        for modus in ("timeout", "lost_response"):
            f, _ = self.psp_fehler(m, modus)
            vorher = (f.zahlung()[0][1:6], len(self.sim_aufrufe(f, "authorize")), len(self.sim_aufrufe(f, "capture")))
            with Modul.welt.admin() as c:
                n_vorher = c.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND event_type LIKE 'payment.%%'", (m.id,)).fetchone()[0]
            # später eintreffendes identisches Ereignis, mehrfach und mit Zeitversatz: keine zweite Wirkung
            pid = f.zahlung()[0][5]
            ereignis = [e for e in self.sim.events if e["data"]["payment_id"] == pid][-1]
            for modus_zustellung, n in (("valid", 3), ("late", 2)):
                res = self.sim.deliver(ereignis, None, modus_zustellung, n)
                self.assertTrue(all(r["http_status"] in (200, 202, 204, 400, 401, 409, 422) for r in res), res)
            self.assertEqual(f.zahlung_abwarten("CAPTURED", 40)[0][1:6], vorher[0], "Endzustand unverändert")
            self.assertEqual((len(self.sim_aufrufe(f, "authorize")), len(self.sim_aufrufe(f, "capture"))), vorher[1:], "keine zweite Belastung beim PSP")
            with Modul.welt.admin() as c:
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id=%s AND booking_id=%s", (m.id, f.buchung_id)).fetchone()[0], 1)
        self.sauber(m)

    def test_solver_timeout_begruendete_ablehnung(self):
        """Solver-Timeout: der Routing-Worker (workers/routing, Einfügeheuristik) liefert bei erschöpftem Budget eine begründete Ablehnung `TIMEOUT_NO_VALIDATED_PLAN`, nie ein ungeprüfter Plan.
        NICHT über die Anwendung: die Einfügeheuristik ist in der Anwendung nicht an den Angebotspfad verdrahtet (Angebote entstehen ohne Solver); hier wird der echte Worker mit knappem Budget aufgerufen."""
        import random
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "workers" / "routing" / "src"))
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests" / "performance"))
        import test_insertion_budget as tib
        from taktovia_routing.insertion import find_insertions, validate_variant, TIMEOUT_NO_VALIDATED_PLAN
        flotte = tib.build_fleet(60, 4)
        anfrage = tib._request(random.Random(5), 99_999)
        ablehnungen = 0
        for budget in (0.0, 0.0005, 0.001):
            out = find_insertions(flotte, anfrage, tib.PARAMS, tib.MATRIX, budget_s=budget)
            self.assertTrue(out.partial, f"Budget {budget} überschritten: Teilergebnis")
            for var in out.variants:   # nie ein ungeprüfter Plan
                plan = next(f for f in flotte if f.vehicle_id == var.vehicle_id)
                self.assertEqual(validate_variant(plan, anfrage, tib.PARAMS, tib.MATRIX, var), [])
            if not out.variants:
                self.assertEqual(out.rejection, TIMEOUT_NO_VALIDATED_PLAN, "begründete Ablehnung")
                ablehnungen += 1
        self.assertGreaterEqual(ablehnungen, 1, "mindestens ein knappes Budget endet ohne Variante in der begründeten Ablehnung")

    # ------------------------------------------------------------------------------------------------------------------ (e) Fahrzeugausfall
    def fahrzeugausfall(self, m):
        u = Umlauf(self.welt, m, fahrzeug=4, fahrer=1)   # eigenes Fahrzeug: nach einem Ausfall bleibt der Dienststatus des Fahrzeugs gestört
        sperre_aktiv = []
        try:
            f, _ = self.bestaetigt(m, umlauf=u)
            http = Http(self.welt)
            with Modul.welt.admin() as c:
                fz = str(c.execute("SELECT vehicle_id FROM taktovia.vehicle_run WHERE id=%s", (u.run,)).fetchone()[0])
            ansicht = http("GET", f"/v1/operator/vehicles/{fz}", m.leitstelle)
            self.assertEqual(ansicht.status, 200)
            sperre = http("POST", f"/v1/operator/vehicles/{fz}/blocks", m.leitstelle, {"reason_code": "OPERATIONAL_DISRUPTION"}, if_match=ansicht.body["revision"])
            self.assertEqual(sperre.status, 201, sperre.body)
            sperre_aktiv.append((fz, sperre.body))
            ansicht = http("GET", f"/v1/operator/vehicles/{fz}", m.leitstelle)
            self.assertTrue(ansicht.body["active_blocks"], "Fahrzeug gesperrt")
            incidents = warte(lambda: [i for i in (http("GET", "/v1/operator/incidents", m.leitstelle).body or {}).get("items", []) if i["vehicle_id"] == fz], 30, was="Incident aus Fahrzeugsperre")
            inc = incidents[0]
            self.assertEqual(inc["kind"], "VEHICLE_OUTAGE")
            self.assertIn(inc["status"], ("OPEN", "REPLACEMENT_SEARCH"), "offener Incident mit Ersatzfahrzeugsuche")
            def im_ablauf():
                c = http("POST", f"/v1/operator/incidents/{inc['id']}/outage/check", m.leitstelle)
                return next((b for b in (c.body or {}).get("bookings", []) if b["booking_id"] == f.buchung_id), None) if c.status == 200 else None

            zeile = warte(im_ablauf, 40, 1.0, "betroffene Buchung im Störungsablauf")
            self.assertEqual(zeile["state"], "OPEN_ISSUE", "betroffene Buchung im Störungsablauf (kein Ersatzfahrzeug im Testmandanten)")
            self.assertIn("CANCEL", zeile["actions"])
            # kein Ersatzfahrzeug: Betreiberstorno des Störungsablaufs; danach bleibt kein Auftrag dem gesperrten Fahrzeug zugeordnet
            r = http("POST", f"/v1/operator/incidents/{inc['id']}/outage/bookings/{f.buchung_id}/cancel", m.leitstelle, {"reason_code": "VEHICLE_OUTAGE"}, if_match=zeile["revision"])
            self.assertEqual(r.status, 200, r.body)
            self.assertEqual(f.buchungszustand(), "CANCELED")
            h = self.historie(m, f)
            self.assertEqual([(x[0], x[1]) for x in h][-2:], [("CANCEL_PENDING", "VEHICLE_OUTAGE"), ("CANCELED", "CANCEL_COMPLETED")])
            self.assertEqual(self.plan_enthaelt(m, u, f), 0, "kein Auftrag bleibt dem gesperrten Fahrzeug zugeordnet")
            self.assertEqual(self.hold_zustand(m, f), "RELEASED")
            with Modul.welt.admin() as c:
                self.assertEqual(c.execute("SELECT cancel_cause FROM (SELECT payload->>'cause_class' AS cancel_cause FROM taktovia.outbox WHERE tenant_id=%s AND event_type='booking.cancelled' AND payload->>'booking_id'=%s) x", (m.id, f.buchung_id)).fetchone()[0], "FAHRZEUGAUSFALL")
            if m.preis > 0:
                warte(lambda: [z[1] for z in f.zahlung()] == ["REFUNDED"], 40, was="Erstattung bei Fahrzeugausfall")
            return f
        finally:
            for fz, bl in sperre_aktiv:   # Aufräumen der Sperre: der Betrieb hebt sie nach der Störung auf
                lift = Http(self.welt)("POST", f"/v1/operator/vehicles/{fz}/blocks/{bl['id']}/lift", m.leitstelle, {"comment": "Störung behoben"}, if_match=bl["revision"])
                if lift.status != 200:
                    self.fail(f"Sperre nicht aufhebbar: {lift.status} {lift.body}")
            u.freigeben()

    def test_fahrzeugausfall_kein_auftrag_auf_gesperrtem_fahrzeug(self):
        m = self.mandant(A)
        self.fahrzeugausfall(m)
        self.sauber(m)

    # ------------------------------------------------------------------------------------------------------------------ Mandanten, Aufräumprüfung, Kontrolle
    def test_negativfaelle_zwei_mandanten_ohne_fremdzugriff(self):
        """Jeder Negativfall auch im Mandanten B; kein Aufräumprozess greift auf Objekte des anderen Mandanten zu; Statuswechsel mit Akteur."""
        a, b = self.mandant(A), self.mandant(B)
        with Modul.welt.admin() as c:
            vorher_a = c.execute("SELECT count(*), md5(string_agg(state, ',' ORDER BY id::text)) FROM taktovia.booking WHERE tenant_id=%s", (a.id,)).fetchone()
            holds_a = c.execute("SELECT count(*), md5(string_agg(state, ',' ORDER BY id::text)) FROM taktovia.capacity_hold WHERE tenant_id=%s", (a.id,)).fetchone()
        Modul.waechter.start()
        self.doppelbuchung(b, 12)
        self.no_show(b)
        f = self.storno_nach_disposition(b)
        self.hold_ablauf(b)
        self.fahrzeugausfall(b)
        self.sauber(b)
        bericht = Modul.waechter.ende()
        self.assertEqual(bericht["befunde"], [], "keine Schreibzugriffe außerhalb der Runtime-Rolle und des Testaufbaus")
        with Modul.welt.admin() as c:
            nachher_a = c.execute("SELECT count(*), md5(string_agg(state, ',' ORDER BY id::text)) FROM taktovia.booking WHERE tenant_id=%s", (a.id,)).fetchone()
            holds_a2 = c.execute("SELECT count(*), md5(string_agg(state, ',' ORDER BY id::text)) FROM taktovia.capacity_hold WHERE tenant_id=%s", (a.id,)).fetchone()
            self.assertEqual((vorher_a, holds_a), (nachher_a, holds_a2), "die Aufräumprozesse und Szenarien des Mandanten B haben nichts im Mandanten A verändert")
            for t, fremd in ((b.id, a.id),):
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking_status_history h JOIN taktovia.booking x ON x.id = h.booking_id WHERE h.tenant_id <> x.tenant_id").fetchone()[0], 0)
        # die Sicht des Mandanten A kennt die Buchungen des Mandanten B nicht (RLS)
        r = Http(self.welt)("GET", f"/v1/operator/bookings?service_id={b.service}", a.leitstelle)
        self.assertIn(r.status, (200, 404))
        if r.status == 200:
            self.assertEqual([x for x in r.body["items"] if x["id"] == f.buchung_id], [])

    def test_aufraeumpruefung_ohne_befund_nach_jedem_szenario(self):
        """Die Aufräumprüfung selbst: ohne Befund nach einem Ablauf, und sie schlägt an, wenn Ressourcen hängen (Negativkontrolle mit gezielt hinterlassenen Resten)."""
        m = self.mandant(KENNZAHLEN)
        # Positiv: nach Storno ohne Befund
        self.storno_nach_disposition(m)
        self.assertTrue(aufraeumen.pruefen(self.welt, m.id)["ok"])
        # Negativ: ein hängender Hold einer stornierten Buchung und ein unbestimmter Zahlungsstand ohne Abgleich werden gefunden
        u = Umlauf(self.welt, m, fahrzeug=0, fahrer=0)
        try:
            f, _ = self.bestaetigt(m, umlauf=u)
            with Modul.welt.aufbau() as c:   # Rest herstellen: Buchung ohne Aufräumschritt auf CANCELED setzen (Zustandsautomat der Datenbank erlaubt den direkten Übergang nicht ohne Weiteres: Hold wieder CONSUMED lassen)
                c.execute("ALTER TABLE taktovia.booking DISABLE TRIGGER USER")
                try:
                    c.execute("UPDATE taktovia.booking SET state='CANCELED' WHERE tenant_id=%s AND id=%s", (m.id, f.buchung_id))
                finally:
                    c.execute("ALTER TABLE taktovia.booking ENABLE TRIGGER USER")
            b = aufraeumen._einmal(self.welt, m.id, 0)
            self.assertFalse(b["ok"])
            self.assertIn("verwaiste_holds", b["befunde"])
            self.assertIn("stopps_stornierter_buchungen", b["befunde"])
            with Modul.welt.aufbau() as c:   # Reste beseitigen, damit die übrigen Tests unberührt bleiben
                c.execute("ALTER TABLE taktovia.booking DISABLE TRIGGER USER")
                try:
                    c.execute("UPDATE taktovia.booking SET state='CONFIRMED' WHERE tenant_id=%s AND id=%s", (m.id, f.buchung_id))
                finally:
                    c.execute("ALTER TABLE taktovia.booking ENABLE TRIGGER USER")
        finally:
            u.freigeben()

    def test_kennzahlen_gegenprobe_stornoquoten_no_show_kapazitaetsablehnungen(self):
        """Gegenprobe der Zählung: vier bestätigte Buchungen im Mandanten, davon eine Fahrgast-Stornierung, eine Betreiber-Stornierung, ein No-Show, eine abgeschlossen; die Kennzahlen stimmen mit den Fakten überein."""
        m = self.mandant(KENNZAHLEN)
        with Modul.welt.admin() as c:   # Anzahl vor dem Test (der Mandant wird auch von anderen Tests benutzt)
            vor = c.execute("SELECT count(*) FILTER (WHERE state='CANCELED' AND cancel_initiator='PASSENGER'), count(*) FILTER (WHERE state='CANCELED' AND cancel_initiator='OPERATOR'), count(*) FILTER (WHERE state='NO_SHOW'), count(*) FILTER (WHERE confirmed_at IS NOT NULL) FROM taktovia.fact_booking WHERE tenant_id=%s", (m.id,)).fetchone()
        self.assertIsNotNone(vor)
        # Fahrgast-Storno: Abholung in drei Tagen (Frist 24 h), Betreiber-Storno und No-Show wie oben
        from zoneinfo import ZoneInfo
        zehn = (datetime.now(ZoneInfo("Europe/Berlin")) + timedelta(days=3)).replace(hour=10, minute=0, second=0, microsecond=0).astimezone(timezone.utc)
        u = Umlauf(self.welt, m, fahrzeug=3, fahrer=0, schicht_von=zehn - timedelta(hours=2), schicht_bis=zehn + timedelta(hours=6))   # Schicht um die Abholung in drei Tagen
        try:
            f, _ = self.bestaetigt(m, umlauf=u, tage=3, geplant=False)
            r = f.stornieren("PASSENGER_REQUEST")
            self.assertEqual((r.status, r.body["state"]), (200, "CANCELED"), r.body)
        finally:
            u.freigeben()
        self.storno_nach_disposition(m)
        self.no_show(m)
        http = Http(self.welt)

        def konsistent():
            """Wartet, bis die Ereigniszustellung die Fakten nachgezogen hat: Fakten (Analytics) und Buchungshistorie (Fachkern) zählen dasselbe, und die Kennzahlen stehen mit Datenstand."""
            with Modul.welt.admin() as c:
                fakt = c.execute("""SELECT count(*) FILTER (WHERE state='CANCELED' AND cancel_initiator='PASSENGER'), count(*) FILTER (WHERE state='CANCELED' AND cancel_initiator='OPERATOR'),
                                        count(*) FILTER (WHERE state='NO_SHOW'), count(*) FILTER (WHERE confirmed_at IS NOT NULL) FROM taktovia.fact_booking WHERE tenant_id=%s""", (m.id,)).fetchone()
                buchungen = c.execute("""SELECT count(*) FILTER (WHERE s.to_state='CANCELED' AND EXISTS (SELECT 1 FROM taktovia.booking_status_history p WHERE p.booking_id=b.id AND p.reason_code='PASSENGER_REQUEST')),
                                             count(*) FILTER (WHERE s.to_state='CANCELED' AND EXISTS (SELECT 1 FROM taktovia.booking_status_history p WHERE p.booking_id=b.id AND p.reason_code IN ('OPERATOR_CANCELLATION','VEHICLE_OUTAGE'))),
                                             count(*) FILTER (WHERE s.to_state='CANCELED' AND EXISTS (SELECT 1 FROM taktovia.booking_status_history p WHERE p.booking_id=b.id AND p.reason_code='NO_SHOW')),
                                             count(DISTINCT b.id) FILTER (WHERE EXISTS (SELECT 1 FROM taktovia.booking_status_history p WHERE p.booking_id=b.id AND p.to_state='CONFIRMED'))
                                        FROM taktovia.booking b JOIN taktovia.booking_status_history s ON s.booking_id=b.id AND s.to_state='CANCELED' WHERE b.tenant_id=%s""", (m.id,)).fetchone()
            if tuple(fakt[:3]) != tuple(buchungen[:3]):
                return None
            k, as_of = kennzahlen(self.welt, m, http=http)
            if not all(k[x]["state"] in ("OK", "PROVISIONAL") and k[x]["denominator"] for x in ("no_show_rate", "cancellation_rate_passenger", "cancellation_rate_operator")):
                return None
            return fakt, buchungen, k

        fakt, buchungen, k = warte(konsistent, 90, 1.5, "Fakten, Buchungshistorie und Kennzahlen konsistent")
        # Fakten (Analytics) und Buchungshistorie (Fachkern) zählen dasselbe
        self.assertEqual(tuple(fakt[:3]), tuple(buchungen[:3]), "Fakten und Buchungshistorie stimmen überein")
        self.assertEqual(k["cancellation_rate_passenger"]["numerator"], fakt[0])
        self.assertEqual(k["cancellation_rate_operator"]["numerator"], fakt[1])
        self.assertEqual(k["no_show_rate"]["numerator"], fakt[2])
        self.assertEqual(k["capacity_rejections"]["numerator"] or 0, 0, "keine Kapazitätsablehnung im Angebotspfad (die Abweisung am letzten Platz geschieht im Hold und ist ein Hold-Fehler, keine Anfrageablehnung)")
        self.assertGreater(fakt[0] + fakt[1] + fakt[2], 2)
        self.sauber(m)


if __name__ == "__main__":
    unittest.main()
