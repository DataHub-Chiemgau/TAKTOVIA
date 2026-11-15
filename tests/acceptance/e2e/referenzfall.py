"""Der durchgängige Referenzfall (WP-M5-23, Wiederholung von AT-01) und seine Bausteine, auch für die Negativfälle (WP-M5-24) und die Fault-Injection (WP-M5-16).

Drehbuch (je Schritt erwarteter Status, Detailvertrag LH-6.20-QAT-003): (1) Angebot `POST /v1/ride-offers` -> Angebot mit Preis; (2) Hold `POST /v1/ride-offers/{id}/hold` -> 201;
(3) Buchung `POST /v1/bookings` -> 201, ohne Zahlung (Tarif 0) sofort `CONFIRMED`, mit Zahlung `CONFIRMING`; (3b) Zahlung `POST /v1/bookings/{id}/payment` -> 202, Autorisierung beim PSP durch den
Zahlungsautomaten, Zahlungskopplung bestätigt die Buchung (`CONFIRMED`), danach Einzug; (4) Zuweisung im Fahrtenplan (Planrevision mit Ein- und Ausstieg, Testaufbau); (5) Dienststart
`POST /v1/driver/shift-starts` (Gerät, Pflichtprüfung: Freigabe, Schicht, Fahrzeug) -> Schicht `STARTED`; (6) Fahrerereignisse `ARRIVAL`, `CHECK_IN`, `ARRIVAL`, `CHECK_OUT` -> `APPLIED`, Buchung
`IN_PROGRESS`/`COMPLETED`; (7) Sicht der Leitstelle `GET /v1/operator/bookings`; (8) Auswertung `GET /v1/analytics/metrics` mit den sechs Kennzahlen."""
import json
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone

from .referenzwelt import ApiFehler, key


_BINDE_SPERRE = threading.Lock()   # ALTER TABLE ... DISABLE TRIGGER wirkt tabellenweit: parallele Planbindungen des Testaufbaus laufen hintereinander


class Http:
    """Aufruf mit Rohstatus (auch Fehlerstatus) und Korrelationskennungen; der Client wirft bei Nicht-2xx, hier wird daraus (Status, Körper)."""

    def __init__(self, welt):
        self.welt = welt
        self.korrelation = []

    def __call__(self, methode, pfad, token, body=None, if_match=None, idem=None):
        client = self.welt.client
        try:
            r = client.anfrage(methode, pfad, token, body, if_match=if_match, idem_key=idem, wiederholen=1)
            res = Res(r.status, r.body, r.headers, r.etag_zahl)
        except ApiFehler as e:
            res = Res(e.status, {"code": e.code, "felder": list(e.felder)}, {}, None)
        cid = res.headers.get("X-Correlation-Id") or res.headers.get("X-Request-Id") or res.headers.get("traceresponse") or res.headers.get("Traceparent")
        res.korrelation = cid
        if cid:
            self.korrelation.append(cid)
        return res


class Res:
    def __init__(self, status, body, headers, etag):
        self.status, self.body, self.headers, self.etag = status, body, headers, etag
        self.korrelation = None

    @property
    def code(self):
        return self.body.get("code") if isinstance(self.body, dict) else None


def warte(bedingung, timeout=40.0, intervall=0.4, was="Bedingung"):
    ende = time.time() + timeout
    letzt = None
    while time.time() < ende:
        letzt = bedingung()
        if letzt:
            return letzt
        time.sleep(intervall)
    raise AssertionError(f"Zeitüberschreitung: {was}")


class Umlauf:
    """Fahrzeug, Fahrer, Schicht, Ressourcenfreigabe und Umlauf (Testaufbau per Administratorzugang). `plan()` legt eine Planrevision mit den Stopps der Buchungen an."""

    def __init__(self, welt, mandant, fahrzeug=0, fahrer=0, schicht_von=None, schicht_bis=None):
        self.welt, self.mandant = welt, mandant
        self.fahrzeug = mandant.fahrzeuge[fahrzeug]
        self.fahrer_subject = mandant.fahrer_subjects[fahrer]
        self.token = mandant.token(self.fahrer_subject, "driver")
        self.schicht, self.run = str(uuid.uuid4()), str(uuid.uuid4())
        self.revision = 1
        self.geraet = str(uuid.uuid4())
        now = datetime.now(timezone.utc)
        von, bis = schicht_von or now, schicht_bis or now + timedelta(hours=4)
        t = mandant.id
        with welt.aufbau() as c:
            c.execute("UPDATE taktovia.vehicle SET active_block = false WHERE tenant_id = %s AND id = %s", (t, self.fahrzeug))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,%s,%s,'Europe/Berlin',1,'PUBLISHED')",
                      (t, self.schicht, self.fahrer_subject, self.fahrzeug, von, bis))
            c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (%s,%s,%s,%s,1,%s,1,%s)",
                      (t, uuid.uuid4(), self.fahrzeug, self.schicht, uuid.uuid4(), bis + timedelta(hours=1)))
            c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (t, self.run, self.fahrzeug, self.schicht))

    def binden(self, angebot_id):
        """Planbindung des Angebots an den Umlauf (Testaufbau, wie in den Anwendungstests; Inhaltswächter des Angebots für diese Anweisung aus)."""
        with _BINDE_SPERRE, self.welt.aufbau() as c:
            c.execute("ALTER TABLE taktovia.ride_offer DISABLE TRIGGER ride_offer_guard")
            try:
                c.execute("UPDATE taktovia.ride_offer SET run_id=%s, plan_revision=%s, interval_from=0, interval_to=2 WHERE tenant_id=%s AND id=%s", (self.run, self.revision, self.mandant.id, angebot_id))
            finally:
                c.execute("ALTER TABLE taktovia.ride_offer ENABLE TRIGGER ride_offer_guard")

    def plan(self, buchungen, basis=None):
        """Neue Planrevision: je Buchung ein Einstieg, danach die Ausstiege (Stoppliste wie `RunPlans.appendRevision`). `buchungen`: Liste (booking_id, pickup_rev_id, dropoff_rev_id, personen)."""
        basis = basis or datetime.now(timezone.utc)
        n = len(buchungen)
        stops = []
        for i in range(2 * n):
            b = buchungen[i % n]
            vec = {"schema_version": "1.0", "seats": b[3] if len(b) > 3 else 1, "wheelchair": 0, "equipment": []}
            mv = [{"booking_id": b[0], "vector": vec}]
            last = i == 2 * n - 1
            stops.append((i, b[1] if i < n else b[2], basis + timedelta(minutes=2 + 5 * i), json.dumps(mv if i < n else []), json.dumps([] if i < n else mv), None if last else 240, None if last else 1800))
        with self.welt.aufbau() as c:
            self.revision += 1
            c.execute("UPDATE taktovia.vehicle_run SET revision = %s WHERE tenant_id=%s AND id=%s", (self.revision, self.mandant.id, self.run))
            ids = []
            for s in stops:
                rid = uuid.uuid4()
                c.execute("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at,boardings,alightings,leg_duration_sec,leg_distance_m) VALUES (%s,%s,%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb,%s,%s)",
                          (self.mandant.id, rid, self.run, self.revision, s[0], s[1], s[2], s[3], s[4], s[5], s[6]))
                ids.append(str(rid))
        self.stops = ids
        return self.revision

    def freigeben(self):
        """Gibt Fahrer und Fahrzeug für den nächsten Fall frei (die Schichtüberlappungssperre der Datenbank gilt auch im Test): eine angetretene Schicht endet über die API
        (`POST /v1/driver/shift-ends`), eine nicht angetretene verwirft der Testaufbau."""
        with self.welt.admin() as c:
            state = c.execute("SELECT state FROM taktovia.staff_shift WHERE tenant_id=%s AND id=%s", (self.mandant.id, self.schicht)).fetchone()[0]
        if state == "STARTED":
            r = Http(self.welt)("POST", "/v1/driver/shift-ends", self.token, {"shift_id": self.schicht, "device_id": self.geraet})
            if r.status == 200:   # die beendete Schicht belegt Fahrer und Fahrzeug nur bis jetzt (die Überlappungssperre prüft das geplante Intervall)
                with self.welt.aufbau() as c:
                    c.execute("UPDATE taktovia.staff_shift SET ends_at = clock_timestamp() WHERE tenant_id=%s AND id=%s AND state = 'ENDED'", (self.mandant.id, self.schicht))
                return
        with self.welt.aufbau() as c:
            c.execute("UPDATE taktovia.staff_shift SET state = 'CANCELLED' WHERE tenant_id=%s AND id=%s AND state <> 'ENDED'", (self.mandant.id, self.schicht))

    def stopp(self, seq):
        with self.welt.admin() as c:
            return str(c.execute("SELECT id FROM taktovia.route_stop WHERE tenant_id=%s AND run_id=%s AND run_revision=%s AND seq=%s", (self.mandant.id, self.run, self.revision, seq)).fetchone()[0])


class Fall:
    """Ein Referenzfall eines Fahrgasts auf einem Umlauf; die Schritte sind einzeln aufrufbar (Negativfälle brechen an definierten Stellen ab)."""

    def __init__(self, welt, mandant, umlauf=None, mit_zahlung=True, personen=1, http=None, fahrgast=None):
        self.welt, self.mandant, self.mit_zahlung, self.personen = welt, mandant, mit_zahlung, personen
        self.http = http or Http(welt)
        self.umlauf = umlauf
        self.fahrgast = fahrgast or mandant.neuer_fahrgast()
        self.schritte = []
        self.hold_key = key("hold")   # ein Schlüssel je Hold: ein wiederholter Aufruf nach 503 hat höchstens eine Wirkung
        self.angebot = self.hold = self.buchung_id = self.buchung = None
        self.ereignisse = []

    # ------------------------------------------------------------------------------------------------------------------ Protokoll
    def schritt(self, name, soll, ist, ids=None):
        ok = soll == ist
        self.schritte.append({"step": name, "soll": soll, "ist": ist, "ok": ok, "ids": ids or {}})
        return ok

    @property
    def ok(self):
        return all(s["ok"] for s in self.schritte)

    # ------------------------------------------------------------------------------------------------------------------ Schritte
    def anfragen(self, minuten=0, tage=0):
        """Fahrtwunsch: ohne `tage` jetzt plus `minuten`, mit `tage` um 10:00 Ortszeit in so vielen Tagen (Fahrgaststorno innerhalb der Frist möglich)."""
        body = self.fahrgast.anfrage_koerper(personen=self.personen, tage=tage)
        from zoneinfo import ZoneInfo
        if not tage:
            body["desired"]["desired_at"] = (datetime.now(ZoneInfo("Europe/Berlin")) + timedelta(minutes=minuten)).isoformat()
        r = self.http("POST", "/v1/ride-offers", self.fahrgast.token, body)
        self.schritt("Angebot (POST /v1/ride-offers)", 200, r.status, {"korrelation": r.korrelation})
        if r.status == 200 and r.body["offers"]:
            self.angebot = r.body["offers"][0]
        return r

    def brutto(self):
        return self.angebot["gross"]["amount_minor"]

    def max_gross(self):
        return {"max_gross": {"amount_minor": self.brutto(), "currency": "EUR", "exponent": 2}}

    gebunden = False

    def binden(self):
        """Planbindung des Angebots an den Umlauf (Testaufbau)."""
        if self.umlauf is not None and not self.gebunden:
            self.umlauf.binden(self.angebot["id"])
            self.gebunden = True

    def halten(self, binden=True, wiederholen=0):
        """Hold; mit `wiederholen` wird bei `5xx` und `OPERATION_IN_PROGRESS` (Pool erschöpft, Datenbank kurz weg) mit Wartezeit wiederholt: so verhält sich ein Client gegenüber `503 Retry-After`."""
        if binden:
            self.binden()
        r = self._hold_aufruf()
        for _ in range(wiederholen):
            if r.status < 500 and r.code != "OPERATION_IN_PROGRESS":   # 409 OPERATION_IN_PROGRESS: die erste Verarbeitung desselben Schlüssels läuft noch (Lease), der Client wartet und wiederholt
                break
            time.sleep(1.0)
            r = self._hold_aufruf()
        self.hold = r
        self.schritt("Hold (POST /v1/ride-offers/{id}/hold)", 201, r.status, {"hold_id": (r.body or {}).get("id"), "korrelation": r.korrelation})
        return r

    def _hold_aufruf(self):
        return self.http("POST", f"/v1/ride-offers/{self.angebot['id']}/hold", self.fahrgast.token, self.max_gross(), if_match=self.angebot["revision"], idem=self.hold_key)

    def buchen(self, idem=None):
        if self.hold is None or self.hold.status != 201:
            raise AssertionError(f"Hold fehlgeschlagen: HTTP {getattr(self.hold, 'status', None)} {getattr(self.hold, 'body', None)}")
        r = self.http("POST", "/v1/bookings", self.fahrgast.token, {"hold_id": self.hold.body["id"], **self.max_gross()}, if_match=self.hold.etag, idem=idem)
        self.buchung = r
        if r.status == 201:
            self.buchung_id = r.body["id"]
        self.schritt("Buchung (POST /v1/bookings)", 201, r.status, {"booking_id": self.buchung_id, "korrelation": r.korrelation})
        return r

    def buchungszustand(self):
        with self.welt.admin() as c:
            row = c.execute("SELECT state FROM taktovia.booking WHERE tenant_id=%s AND id=%s", (self.mandant.id, self.buchung_id)).fetchone()
        return row[0] if row else None

    def revision(self):
        with self.welt.admin() as c:
            return c.execute("SELECT revision FROM taktovia.booking WHERE tenant_id=%s AND id=%s", (self.mandant.id, self.buchung_id)).fetchone()[0]

    def stornieren(self, grund="PASSENGER_REQUEST", token=None, text=None):
        body = {"reason": grund}
        if text:
            body["reason_text"] = text
        return self.http("POST", f"/v1/bookings/{self.buchung_id}/cancel", token or self.fahrgast.token, body, if_match=self.revision())

    def bezahlen(self, methode="pm_ok", idem=None):
        r = self.http("POST", f"/v1/bookings/{self.buchung_id}/payment", self.fahrgast.token,
                      {"amount": {"amount_minor": self.brutto(), "currency": "EUR", "exponent": 2}, "payment_method_ref": methode}, idem=idem)
        self.schritt("Zahlung (POST /v1/bookings/{id}/payment)", 202, r.status, {"operation_id": (r.body or {}).get("id") or (r.body or {}).get("operation_id"), "korrelation": r.korrelation})
        return r

    def zahlung(self):
        with self.welt.admin() as c:
            return c.execute("SELECT id, state, authorized_minor, captured_minor, refunded_minor, psp_payment_id, merchant_ref FROM taktovia.payment_intent WHERE tenant_id=%s AND booking_id=%s",
                             (self.mandant.id, self.buchung_id)).fetchall()

    def zahlung_abwarten(self, zustand="CAPTURED", timeout=40):
        """Wartet, bis die (einzige) Zahlung der Buchung den Endzustand erreicht hat (Einzug und Abgleich laufen asynchron im Zeitgeber der Anwendung)."""
        warte(lambda: [z[1] for z in self.zahlung()] == [zustand], timeout, was=f"Zahlung {zustand}")
        return self.zahlung()

    def planen(self, weitere=()):
        """Zuweisung im Fahrtenplan: Stopps dieser (und weiterer) Buchungen."""
        bs = [(self.buchung_id, self.angebot["pickup_stop"]["revision_id"], self.angebot["dropoff_stop"]["revision_id"], self.personen)]
        for f in weitere:
            bs.append((f.buchung_id, f.angebot["pickup_stop"]["revision_id"], f.angebot["dropoff_stop"]["revision_id"], f.personen))
        rev = self.umlauf.plan(bs)
        return rev

    def dienststart(self):
        u = self.umlauf
        r1 = self.http("POST", "/v1/driver/devices", u.token, {"device_id": u.geraet, "platform": "ANDROID", "app_version": "1.0.0"})
        r2 = self.http("POST", "/v1/driver/shift-starts", u.token, {"shift_id": u.schicht, "vehicle_id": u.fahrzeug, "device_id": u.geraet})
        self.schritt("Dienststart mit Pflichtprüfung (POST /v1/driver/shift-starts)", (201, 200), (r1.status, r2.status), {"shift_id": u.schicht, "korrelation": r2.korrelation})
        return r2

    def ereignis(self, typ, stopp, booking=True, wann=None, event_id=None, extra=None, token=None):
        u = self.umlauf
        wann = (wann or datetime.now(timezone.utc)).isoformat()
        body = {"event_id": event_id or str(uuid.uuid4()), "shift_id": u.schicht, "vehicle_id": u.fahrzeug, "plan_revision": u.revision, "event_type": typ, "route_stop_id": stopp,
                "occurred_at": wann, "device_recorded_at": wann, "offline_recorded": False}
        if booking and typ != "ARRIVAL":
            body["booking_id"] = self.buchung_id
        body.update(extra or {})
        r = self.http("POST", "/v1/driver/events", token or u.token, body)
        self.ereignisse.append((typ, r.status, (r.body or {}).get("state")))
        return r

    def fahrt(self, pos=0):
        """Ankunft, Einstieg, Ankunft, Ausstieg dieser Buchung (`pos`: Position der Buchung in der Planrevision); liefert die Liste der Ergebnisse."""
        u = self.umlauf
        n = len(u.stops) // 2
        s_ein, s_aus = u.stops[pos], u.stops[n + pos]
        out = []
        for typ, st in (("ARRIVAL", s_ein), ("CHECK_IN", s_ein), ("ARRIVAL", s_aus), ("CHECK_OUT", s_aus)):
            r = self.ereignis(typ, st)
            out.append((typ, r.status, (r.body or {}).get("state")))
            self.schritt(f"Fahrerereignis {typ} (POST /v1/driver/events)", ("APPLIED", 200), ((r.body or {}).get("state"), r.status), {"event_id": (r.body or {}).get("event_id"), "korrelation": r.korrelation})
        return out


def durchlaufen(welt, mandant, anzahl=1, umlauf=None, personen=1, http=None, bis="ende", freigeben=True):
    """Der Referenzfall für `anzahl` Fahrgäste auf einem Umlauf: Angebot, Hold, Buchung, (Zahlung bis `CONFIRMED`), Plan, Dienststart, Fahrerereignisse. Mit Zahlung, wenn der Tarif des
    Mandanten einen Preis hat; sonst sofort `CONFIRMED` und kein `payment_intent`. `bis`: 'ende' (alles), 'bestaetigt' (nur bis zur bestätigten Buchung). Die Schicht wird am Ende freigegeben
    (`freigeben`), damit der nächste Fall denselben Fahrer und dasselbe Fahrzeug nutzen kann. Liefert (Fälle, Umlauf)."""
    umlauf = umlauf or Umlauf(welt, mandant)
    faelle = [Fall(welt, mandant, umlauf, mit_zahlung=mandant.preis > 0, personen=personen, http=http) for _ in range(anzahl)]
    try:
        for f in faelle:
            f.anfragen()
            f.halten()
            f.buchen()
        for f in faelle:
            if f.mit_zahlung:
                f.bezahlen()
        for f in faelle:
            warte(lambda: f.buchungszustand() == "CONFIRMED", 40, was="Buchung bestätigt")
            f.schritt("Buchung bestätigt (Zahlungskopplung bzw. tariflich kostenfrei)", "CONFIRMED", f.buchungszustand(), {"booking_id": f.buchung_id})
        if bis == "bestaetigt":
            return faelle, umlauf
        faelle[0].planen(weitere=faelle[1:])
        faelle[0].dienststart()
        for pos, f in enumerate(faelle):
            f.fahrt(pos)
            f.schritt("Buchung abgeschlossen", "COMPLETED", f.buchungszustand(), {"booking_id": f.buchung_id})
            if f.mit_zahlung:
                f.schritt("Zahlung im Endzustand (genau eine, eingezogen)", ["CAPTURED"], [z[1] for z in f.zahlung_abwarten()], {"booking_id": f.buchung_id})
        return faelle, umlauf
    finally:
        if freigeben:
            umlauf.freigeben()


def kennzahlen(welt, mandant, tag=None, http=None):
    """`GET /v1/analytics/metrics` des Berichtstags (Ortszeit Berlin) für den Dienst; Rückgabe {metric_id: Wert}. Wartet nicht; der Aufrufer wartet auf den Datenstand."""
    from zoneinfo import ZoneInfo
    tag = tag or datetime.now(ZoneInfo("Europe/Berlin")).date().isoformat()
    r = (http or Http(welt))("GET", f"/v1/analytics/metrics?from={tag}&to={tag}&service_id={mandant.service}", mandant.leitstelle)
    if r.status != 200:
        raise AssertionError(f"Kennzahlen: HTTP {r.status} {r.body}")
    return {v["metric_id"]: v for v in r.body["values"]}, r.body.get("as_of")
