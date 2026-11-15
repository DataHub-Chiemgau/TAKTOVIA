"""WP-M5-05 / LH-6.19-NFR-001: Probemandant und Probebuchungen gegen die echte Datenbank unter der echten Runtime-Rolle (RLS) und den Modulrollen.

Ausgeführt: Probemandant (V50050, `tenant.synthetic`) mit eigenem Dienst und Probefahrzeug; Datentrennung in beide Richtungen; Probebuchung auf einem Umlauf eines Echtmandanten ist
nicht möglich, auch nicht parallel zur echten Buchung auf dem letzten Platz; Ereignisse und Fakten des Probemandanten gelangen nicht in Anfragen, Buchungskonversion und die übrige
Faktenbasis (Funktionen `funnel_counts`, `funnel_reconciliation`); die Kennzeichnung ist endgültig.
Die Kapazitätsarithmetik des Holds selbst (letzter Platz, zwei Mandanten, echter Hold-Befehl) prüft der Kotlin-Test `ProbeBookingCapacityTest` (booking-dispatch); hier wird bewiesen, dass der
Probemandant diese Arithmetik nie auf einem echten Umlauf auslösen kann (Fremdschlüssel und RLS) und die Datenbank beide Mandanten nebenläufig getrennt bedient.
Nicht ausgeführt: Proben gegen eine laufende Anwendung (siehe test_probe_gegen_anwendung.py, opt-in)."""
import json
import os
import sys
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "infra" / "availability"))
sys.path.insert(0, str(REPO / "db" / "tests"))

REASON = None
try:
    import psycopg
    from helpers import admin, tx, uid, seed_tenant
    from taktovia_availability import probemandant
    if not os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD"):
        REASON = "Datenbank-Zugangsdaten nicht gesetzt"
except ImportError as e:  # pragma: no cover
    REASON = f"Abhängigkeit fehlt: {e}"
if REASON and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true): " + REASON)

FROM, TO = "2026-12-01T00:00:00Z", "2026-12-02T00:00:00Z"
VEC = json.dumps({"schema_version": "1.0", "seats": 1, "wheelchair": 0, "equipment": []})


def echter_mandant(c, name):
    """Mandant, Fahrzeug mit einem Sitz, Umlauf; liefert (tenant, vehicle, run)."""
    t, veh, shift, run = uid(), uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.currency(code,exponent) VALUES ('EUR',2) ON CONFLICT DO NOTHING")
    c.execute("INSERT INTO taktovia.tenant(id,name,status) VALUES (%s,%s,'ACTIVE')", (t, name))
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (%s,%s,'echt',%s::jsonb,'ECHT-1')", (t, veh, json.dumps({"seats": 1, "wheelchair": 0})))
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,'2026-12-01 04:00+00','2026-12-01 14:00+00','Europe/Berlin',1,'PLANNED')", (t, shift))
    c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (t, run, veh, shift))
    return t, veh, run


def hold(c, tenant, run):
    h = uid()
    c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
              "VALUES (%s,%s,%s,1,%s,%s::jsonb,0,1,clock_timestamp()+interval '3 minutes','HELD')", (tenant, h, run, f"ref-{h}", VEC))
    return h


def fakten(c, service):
    """Ein Versuch, ein Angebot und eine bestätigte Buchung samt Eingangsereignissen im Kontext des Mandanten."""
    a, o, b = uid(), uid(), uid()
    at = "2026-12-01T05:00:00Z"
    n = 0

    def inbox(source, etype, agg, payload):
        return c.execute("INSERT INTO taktovia.analytics_event_inbox(tenant_id,event_id,source,event_type,aggregate_id,aggregate_revision,occurred_at,recorded_at,payload_sha256,payload) "
                         "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,1,%s,%s,%s,%s::jsonb)", (uid(), source, etype, agg, at, at, "a" * 64, json.dumps(payload))).rowcount
    n += c.execute("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,time_mode,requested_at,passengers,channel) "
                   "VALUES (taktovia.current_tenant(),%s,%s,'RECORDED','DEPARTURE',%s,1,'WEB')", (a, service, at)).rowcount
    n += inbox("ATTEMPT", "booking.attempt_recorded", a, {"attempt_id": str(a), "service_id": str(service), "outcome": "RECORDED", "requested_at": at})
    n += c.execute("INSERT INTO taktovia.fact_offer(tenant_id,offer_id,attempt_id,service_id,created_at,expires_at,pickup_earliest,pickup_latest,gross_minor,currency) "
                   "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,500,'EUR')", (o, a, service, at, at, at, at)).rowcount
    n += inbox("OFFER", "booking.offer_created", o, {"offer_id": str(o), "attempt_id": str(a)})
    n += c.execute("INSERT INTO taktovia.fact_booking(tenant_id,booking_id,service_id,confirmed_at,offer_id,state) VALUES (taktovia.current_tenant(),%s,%s,%s,%s,'CONFIRMED')", (b, service, at, o)).rowcount
    n += inbox("BOOKING", "booking.confirmed", b, {"booking_id": str(b), "service_id": str(service)})
    return n


@unittest.skipIf(REASON is not None, REASON or "")
class Probebuchung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with admin() as c:
            cls.probe = probemandant.anlegen(c, name="m505-probe")
            cls.echt, cls.echt_vehicle, cls.echt_run = echter_mandant(c, "m505-echt")
        cls.p = cls.probe["tenant"]

    def test_probemandant_hat_eigenen_dienst_und_eigenes_probefahrzeug(self):
        with admin() as c:
            self.assertTrue(c.execute("SELECT synthetic FROM taktovia.tenant WHERE id=%s", (self.p,)).fetchone()[0])
            self.assertFalse(c.execute("SELECT synthetic FROM taktovia.tenant WHERE id=%s", (self.echt,)).fetchone()[0])
        with tx(self.p) as c:
            self.assertEqual([r[0] for r in c.execute("SELECT id FROM taktovia.service")], [self.probe["service"]])
            self.assertEqual([r[0] for r in c.execute("SELECT id FROM taktovia.vehicle")], [self.probe["vehicle"]])
            self.assertNotEqual(self.probe["vehicle"], self.echt_vehicle)

    def test_probemandant_sieht_keine_echtdaten(self):
        with tx(self.echt) as c:
            echte_buchung = self._buchung(c, self.echt, self.echt_run)
        with tx(self.p) as c:
            for tabelle in ("vehicle", "vehicle_run", "capacity_hold", "booking", "service", "staff_shift"):
                fremd = c.execute(f"SELECT count(*) FROM taktovia.{tabelle} WHERE tenant_id <> %s", (self.p,)).fetchone()[0]  # noqa: S608 (feste Tabellenliste)
                self.assertEqual(fremd, 0, f"{tabelle}: Echtdaten im Probemandanten sichtbar")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE id=%s", (echte_buchung,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.tenant").fetchone()[0], 1, "nur der eigene Mandant")
            self.assertEqual(c.execute("UPDATE taktovia.vehicle SET vehicle_label='x' WHERE id=%s", (self.echt_vehicle,)).rowcount, 0)
        with tx(self.echt) as c:    # und umgekehrt: Probedaten sind für Echtmandanten unsichtbar
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE id=%s", (self.probe["vehicle"],)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.tenant WHERE synthetic").fetchone()[0], 0)

    @staticmethod
    def _buchung(c, tenant, run, service=None):
        h = hold(c, tenant, run)
        if service is None:
            return h
        b = uid()
        c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE id=%s", (h,))
        c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}'::jsonb,1)", (tenant, b, h, service, f"fb-{b}"))
        return b

    def test_probebuchung_verdraengt_keine_echte_buchung(self):
        """Konkurrierend: die Probe versucht, parallel zur echten Buchung auf dem letzten Platz, einen Hold auf den Umlauf des Echtmandanten zu legen (Fehlkonfiguration des Probefahrzeugs).
        Der Versuch scheitert in der Datenbank; die echte Buchung erhält den Platz; die Probe hält ausschließlich auf ihrem eigenen Umlauf."""
        barrier = threading.Barrier(3)
        res = {}

        def echt():
            with tx(self.echt) as c:
                barrier.wait(15)
                res["echt"] = hold(c, self.echt, self.echt_run)

        def probe_auf_fremdem_umlauf():
            try:
                with tx(self.p) as c:
                    barrier.wait(15)
                    hold(c, self.p, self.echt_run)          # Umlauf des Echtmandanten
                res["fremd"] = "ANGELEGT"
            except psycopg.errors.ForeignKeyViolation:
                res["fremd"] = "FK"
            except psycopg.errors.Error as e:  # noqa: BLE001
                res["fremd"] = type(e).__name__

        def probe_auf_eigenem_umlauf():
            with tx(self.p) as c:
                barrier.wait(15)
                res["eigen"] = hold(c, self.p, self.probe["run"])
        ts = [threading.Thread(target=f) for f in (echt, probe_auf_fremdem_umlauf, probe_auf_eigenem_umlauf)]
        [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertFalse(any(t.is_alive() for t in ts))
        self.assertEqual(res["fremd"], "FK", res)
        with admin() as c:
            self.assertEqual(c.execute("SELECT state FROM taktovia.capacity_hold WHERE tenant_id=%s AND id=%s", (self.echt, res["echt"])).fetchone()[0], "HELD", "die echte Buchung hält den letzten Platz")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s", (self.p, self.echt_run)).fetchone()[0], 0, "kein Probehold auf dem echten Umlauf")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s", (self.echt, self.probe["run"])).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND run_id=%s", (self.echt, self.echt_run)).fetchone()[0], 1)

    def test_probebuchung_erscheint_nicht_in_anfragen_und_buchungskonversion(self):
        with tx(self.p) as c:
            geschrieben_probe = fakten(c, self.probe["service"])
        with tx(self.echt) as c:
            service = uid()   # Fakten tragen die Dienstkennung nur als Wert (keine Fremdschlüssel)
            geschrieben_echt = fakten(c, service)
        self.assertEqual(geschrieben_probe, 0, "Zeilen des Probemandanten werden in der Faktenbasis nicht gespeichert (auch der Eingang meldet 0 Zeilen: Duplikat)")
        self.assertEqual(geschrieben_echt, 6, "Kontrolle: derselbe Aufbau im Echtmandanten wird gespeichert")
        with tx(self.p) as c:
            cur = c.execute("SELECT * FROM taktovia.funnel_counts(%s,%s,NULL,NULL)", (FROM, TO))
            z = dict(zip([d.name for d in cur.description], cur.fetchone()))
            self.assertEqual((z["requests_all"], z["offers_all"], z["confirmed_all"], z["facts_total"]), (0, 0, 0, 0), z)
            self.assertEqual([r[1:] for r in c.execute("SELECT * FROM taktovia.funnel_reconciliation(%s,%s)", (FROM, TO))], [(0, 0)] * 5, "Abgleich Fakten/Protokoll: beide 0")
            for t in ("fact_attempt", "fact_offer", "fact_booking", "analytics_event_inbox"):
                self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t}").fetchone()[0], 0, t)  # noqa: S608
        with tx(self.echt) as c:
            cur = c.execute("SELECT * FROM taktovia.funnel_counts(%s,%s,NULL,NULL)", (FROM, TO))
            z = dict(zip([d.name for d in cur.description], cur.fetchone()))
            self.assertEqual((z["requests_all"], z["offers_all"], z["confirmed_all"]), (1, 1, 1), "Kontrolle: der Echtmandant zählt seine Vorgänge")

    def test_modulrolle_analytics_speichert_nichts_fuer_den_probemandanten(self):
        from helpers import module_tx
        with module_tx("taktovia_mod_analytics", self.p) as c:
            n = c.execute("INSERT INTO taktovia.analytics_event_inbox(tenant_id,event_id,source,event_type,aggregate_id,aggregate_revision,occurred_at,recorded_at,payload_sha256,payload) "
                          "VALUES (taktovia.current_tenant(),%s,'ATTEMPT','booking.attempt_recorded',%s,1,now(),now(),%s,'{}'::jsonb) ON CONFLICT (tenant_id, event_id) DO NOTHING",
                          (uid(), uid(), "b" * 64)).rowcount
            self.assertEqual(n, 0)

    def test_kennzeichnung_ist_endgueltig(self):
        for tenant, neu in ((self.p, False), (self.echt, True)):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with admin() as c:
                    c.execute("UPDATE taktovia.tenant SET synthetic=%s WHERE id=%s", (neu, tenant))
        with admin() as c:   # andere Änderungen am Mandanten bleiben möglich
            c.execute("UPDATE taktovia.tenant SET name=name WHERE id=%s", (self.p,))


if __name__ == "__main__":
    unittest.main()
