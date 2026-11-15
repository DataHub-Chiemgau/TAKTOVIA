"""WP-M5-28, konkurrierender Test: gestaffeltes Rollout UNTER LAUFENDER BUCHUNGSLAST gegen die echte Anwendung.

Was echt ist: zwei Instanzen des Spring-Boot-Jars (`backend/app/build/libs/app.jar`) gegen dieselbe PostgreSQL unter der Runtime-Rolle, echter HTTP-Verkehr (Angebot, Hold, Bestätigung) aus mehreren
parallelen Threads, ein lokaler JWT-Issuer (Ersatz für Keycloak), Verkehrsverteilung zwischen "alter" und "neuer" Instanz je Stufe, Rauchproben gegen die neue Instanz, Invariantenabfrage nach Kap. 04 §4.5.
Was NICHT echt ist: "alte" und "neue" Version sind DASSELBE Jar (es gibt in dieser Umgebung nur einen Build): belegt ist daher die Nebenläufigkeit zweier gleichzeitig laufender Instanzen und der Abbruch einer Stufe,
nicht die Kompatibilität zweier verschiedener Versionen. Die Verkehrsverteilung ist eine Testweiche (kein Lastverteiler). Die Rauchprobe "synthetische_probe" ist ein Ersatz (Angebotssuche und /v1/me gegen die
neue Instanz; die vollständige Probe aus infra/availability braucht Operatorzugang und Probemandant). "alarmkette" läuft gegen die Nachbildung des Alertmanager-Routings; "archivierungsverzug" ist hier NICHT
ausgeführt (kein WAL-Archiv in dieser Umgebung) und deshalb aus dem Probenplan dieses Tests entfernt. Es entsteht keine Zahlungslast (die Buchung ohne PSP-Anbindung legt hier keine Zahlungen an): die Stabilität
der Zahlungszustände wird für die vorhandenen Zeilen geprüft, nicht unter Zahlungslast belegt.
Voraussetzungen: Jar (`./gradlew :app:bootJar`), Java, Datenbankumgebung; sonst übersprungen, mit TAKTOVIA_REQUIRE_DB=true fehlgeschlagen."""
from __future__ import annotations

import copy
import json
import os
import random
import sys
import threading
import time
import unittest
import urllib.request

import sys as _sys
from pathlib import Path as _P
_sys.path.insert(0, str(_P(__file__).resolve().parent))
import m528_support as S  # noqa: F401  (Pfade)
from m528_support import REPO, datenbank
from taktovia_rollout import stufen

sys.path.insert(0, str(REPO / "tests" / "performance"))
sys.path.insert(0, str(REPO / "tests" / "acceptance"))
sys.path.insert(0, str(REPO / "infra" / "alerting"))
from refload import invariants, world as W  # noqa: E402
from refload.appproc import App, available  # noqa: E402
from refload.idp import TestIdp  # noqa: E402
import db as dbtool  # noqa: E402

SKIP = available() or ("TAKTOVIA_DB_ADMIN_PASSWORD nicht gesetzt" if not os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD") else None)
if SKIP and os.environ.get("TAKTOVIA_REQUIRE_DB", "").lower() == "true" and os.environ.get("TAKTOVIA_M528_REQUIRE_APP") == "true":
    raise AssertionError(f"Voraussetzung fehlt: {SKIP}")

LOCAL_PROBEN = ("gesundheit", "migrationsstand", "runtime_rolle", "tabellen_policy", "synthetische_probe", "alarmkette")


def lokaler_plan() -> dict:
    """Der echte Plan, ohne die lokal nicht ausführbare Rauchprobe `archivierungsverzug`; Stufen, Anteile und Stoppkriterien unverändert."""
    plan = copy.deepcopy(stufen.lade_plan())
    for s in plan["stufen"]:
        s["rauchprobe"] = [p for p in s["rauchprobe"] if p in LOCAL_PROBEN]
    return plan


class Weiche:
    """Testweiche: verteilt jede Anfrage nach aktuellem Anteil auf alte oder neue Instanz und zählt Statuscodes je Instanz."""

    def __init__(self, alt):
        self.alt, self.neu, self.anteil_neu, self.lock = alt, None, 0.0, threading.Lock()
        self.status = {"alt": {}, "neu": {}}
        self.rnd = random.Random(5)

    def wahl(self):
        with self.lock:
            return ("neu", self.neu) if self.neu is not None and self.rnd.random() < self.anteil_neu else ("alt", self.alt)

    def zaehlen(self, name, code):
        with self.lock:
            self.status[name][code] = self.status[name].get(code, 0) + 1

    def fehlerrate_neu_pct(self):
        with self.lock:
            s = self.status["neu"]; n = sum(s.values())
            return 0.0 if n == 0 else 100.0 * sum(v for k, v in s.items() if k >= 500) / n


class Last:
    """Mehrere Threads fahren die Buchungskette (Angebot -> Plan-Bindung -> Hold -> Bestätigung) gegen die Weiche."""

    def __init__(self, weiche, idp, world, db_name, threads=6):
        self.w, self.idp, self.world, self.db, self.n = weiche, idp, world, db_name, threads
        self.stop = threading.Event(); self.ts = []
        self.buchungen, self.lock, self.fehler = [], threading.Lock(), []
        self.zaehler = {"angebote": 0, "holds": 0, "bestaetigt": 0, "konflikt": 0}

    def start(self):
        for i in range(self.n):
            t = threading.Thread(target=self.lauf, args=(i,), daemon=True); t.start(); self.ts.append(t)

    def halt(self):
        self.stop.set(); [t.join(60) for t in self.ts]

    def anruf(self, methode, pfad, token, body=None, if_match=None):
        name, app = self.w.wahl()
        try:
            r = W.call(app.base, methode, pfad, token, body, if_match=if_match)
        except Exception as e:  # noqa: BLE001
            self.w.zaehlen(name, 599); raise
        self.w.zaehlen(name, r[0])
        return name, r

    def lauf(self, i):
        rnd = random.Random(100 + i); admin = dbtool.admin(self.db)
        try:
            while not self.stop.is_set():
                sub = rnd.choice(self.world.passengers); tok = self.idp.token(sub)
                try:
                    _, (s, off, _) = self.anruf("POST", "/v1/ride-offers", tok, W.offer_body(self.world.service_id, self.world.days, 1, 0))
                    if s != 200 or not off or not off.get("offers"):
                        continue
                    o = off["offers"][0]
                    with self.lock:
                        self.zaehler["angebote"] += 1
                        run, seg = self.world.next_run_segment()
                    W.bind_offer(admin, self.world.tenant_id, o["id"], run, seg)
                    n1, (s, hold, hd) = self.anruf("POST", f"/v1/ride-offers/{o['id']}/hold", tok, {"max_gross": o["gross"]}, if_match=str(o["revision"]))
                    if s != 201:
                        with self.lock: self.zaehler["konflikt"] += 1
                        continue
                    with self.lock: self.zaehler["holds"] += 1
                    n2, (s, bk, _) = self.anruf("POST", "/v1/bookings", tok, {"hold_id": hold["id"], "max_gross": o["gross"]}, if_match={k.lower(): v for k, v in hd.items()}.get("etag", "1"))
                    if s == 201:
                        with self.lock:
                            self.zaehler["bestaetigt"] += 1; self.buchungen.append((bk["id"], sub, n2))
                except Exception as e:  # noqa: BLE001
                    self.fehler.append(repr(e)[:200])
        finally:
            admin.close()


def http_get(url, token=None, timeout=10):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


class AppZiel:
    """Zielplattform für den Test: die neue Instanz startet beim ersten Stufenbeginn; Anteil je Stufe steuert die Weiche; Rauchproben laufen gegen die neue Instanz bzw. die Datenbank."""

    def __init__(self, weiche, idp, world, db_name, alarm_welt, erwarteter_stand, inject=None):
        self.w, self.idp, self.world, self.db, self.alarm, self.stand, self.inject = weiche, idp, world, db_name, alarm_welt, erwarteter_stand, inject or {}
        self.snapshot, self.rollen = None, []
        self.admin = dbtool.admin(db_name)
        self.runtime = dbtool.conn("taktovia_runtime", dbtool.env("TAKTOVIA_DB_RUNTIME_PASSWORD"), db_name)

    def stufe_starten(self, stufe, build_id):
        if self.w.neu is None:
            self.w.neu = App(self.idp, db_name=self.db).start(240)
        if stufe["id"] == "teilausrollung":
            self.snapshot = self.bestand()          # Zustand vor der Stufe, die abgebrochen wird
        self.w.anteil_neu = stufe["verkehrsanteil_pct"] / 100.0
        time.sleep(4)                                # Verkehr über die neue Instanz fließen lassen

    def bestand(self):
        t = self.world.tenant_id
        buch = dict(self.admin.execute("SELECT id::text, state FROM taktovia.booking WHERE tenant_id=%s", (t,)).fetchall())
        holds = dict(self.admin.execute("SELECT id::text, state FROM taktovia.capacity_hold WHERE tenant_id=%s AND state='CONSUMED'", (t,)).fetchall())
        pay = dict(self.admin.execute("SELECT id::text, state||':'||coalesce(captured_minor,0)||':'||coalesce(authorized_minor,0) FROM taktovia.payment_intent WHERE tenant_id=%s", (t,)).fetchall())
        return {"buchungen": buch, "holds": holds, "zahlungen": pay}

    def rauchprobe(self, stufe, name):
        if (stufe["id"], name) in self.inject:
            return False, self.inject[(stufe["id"], name)]
        neu = self.w.neu
        if name == "gesundheit":
            code = http_get(neu.base + "/actuator/health"); return code == 200, f"HTTP {code}"
        if name == "migrationsstand":
            st = datenbank.migrationsstand(self.admin); return st["sha256"] == self.stand["sha256"], st["sha256"][:12]
        if name == "runtime_rolle":
            b = datenbank.runtime_rolle_befunde(self.runtime); return not b, "; ".join(b) or "ok"
        if name == "tabellen_policy":
            b = datenbank.tabellen_ohne_policy(self.admin); return not b, "; ".join(b) or "ok"
        if name == "synthetische_probe":      # Ersatz, siehe Modulbeschreibung
            tok = self.idp.token(self.world.passengers[0])
            s1 = http_get(neu.base + "/v1/me", tok)
            s2, off, _ = W.call(neu.base, "POST", "/v1/ride-offers", tok, W.offer_body(self.world.service_id, self.world.days))
            return s1 == 200 and s2 == 200 and bool(off and off.get("offers")), f"/v1/me {s1}, Angebotssuche {s2}"
        if name == "alarmkette":
            from alerting.test_alert_scenarios import TRIGGERS, run_trigger
            feed = next(f for n, f, _ in TRIGGERS if n == "FachkernNichtErreichbar")
            vorher = len(self.alarm.received)
            _, firing = run_trigger(self.alarm, "FachkernNichtErreichbar", feed)
            return firing is not None and len(self.alarm.received) > vorher, f"{len(self.alarm.received) - vorher} Zustellung(en) beim Betreiberkanal"
        raise AssertionError(f"Rauchprobe {name} nicht implementiert")

    def metriken(self, stufe):
        outbox = self.admin.execute("SELECT coalesce(extract(epoch FROM now() - min(recorded_at)), 0) FROM taktovia.outbox WHERE published_at IS NULL AND dead_lettered_at IS NULL AND tenant_id=%s", (self.world.tenant_id,)).fetchone()[0]
        unbest = self.admin.execute("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id=%s AND (state LIKE '%%UNKNOWN%%' OR state='RECONCILING')", (self.world.tenant_id,)).fetchone()[0]
        return {"fehlerrate_5xx_pct": self.w.fehlerrate_neu_pct(), "outbox_alter_s": float(outbox), "unbestimmte_zahlungen": unbest, "verfuegbarkeitsprobe_rot": False}

    def stufe_zurueckziehen(self, stufe):
        self.w.anteil_neu = 0.0
        if self.w.neu is not None:
            self.w.neu.stop(); self.w.neu = None

    def stufe_abschliessen(self, stufe):
        pass


@unittest.skipIf(SKIP, SKIP or "")
class RolloutUnterLast(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from alerting.test_alert_scenarios import World
        cls.db = os.environ["TAKTOVIA_DB_NAME"]
        cls.idp = TestIdp()
        cls.alt = App(cls.idp, db_name=cls.db).start(240)
        cls.admin = dbtool.admin(cls.db)
        cls.alarm = World()
        cls.stand = datenbank.migrationsstand(cls.admin)

    @classmethod
    def tearDownClass(cls):
        cls.alt.stop(); cls.alarm.close(); cls.admin.close()

    def neue_welt(self, label):
        return W.build_tenant(self.alt, self.idp, self.admin, label, 30, 6, 8, name=f"Rollout-{label}")

    def lauf(self, label, inject):
        world = self.neue_welt(label)
        weiche = Weiche(self.alt); last = Last(weiche, self.idp, world, self.db)
        ziel = AppZiel(weiche, self.idp, world, self.db, self.alarm, self.stand, inject)
        last.start(); time.sleep(3)
        try:
            protokoll = stufen.gestaffelt_ausrollen(lokaler_plan(), ziel, "m528-last")
            time.sleep(3)
        finally:
            last.halt()
        return world, weiche, last, ziel, protokoll

    def invarianten(self, world):
        inv = invariants.check(self.admin, [world.tenant_id], [*world.runs, world.last_seat_run])
        self.assertEqual(inv["verstoesse_gesamt"], 0, inv)
        return inv

    def test_abbruch_einer_stufe_unter_buchungslast_laesst_bestaetigtes_unveraendert(self):
        world, weiche, last, ziel, p = self.lauf("Abbruch", {("teilausrollung", "synthetische_probe"): "injizierter Fehler der Rauchprobe"})
        self.assertEqual(last.fehler, [])
        self.assertEqual(p["ergebnis"], "GESTOPPT"); self.assertIn("synthetische_probe", p["grund"])
        self.assertEqual([s["id"] for s in p["stufen"]], ["kanarienvogel", "teilausrollung"])
        self.assertIsNone(weiche.neu); self.assertEqual(weiche.anteil_neu, 0.0)
        self.assertGreater(sum(weiche.status["neu"].values()), 0, "die neue Instanz hat vor dem Abbruch Verkehr verarbeitet")
        self.assertGreater(last.zaehler["bestaetigt"], 5, last.zaehler)
        vorher, jetzt = ziel.snapshot, ziel.bestand()
        for tabelle in ("buchungen", "holds", "zahlungen"):
            veraendert = {k: (v, jetzt[tabelle].get(k)) for k, v in vorher[tabelle].items() if jetzt[tabelle].get(k) != v}
            self.assertEqual(veraendert, {}, f"{tabelle}: bestätigter Bestand verändert")
        self.assertGreaterEqual(len(jetzt["buchungen"]), len(vorher["buchungen"]), "keine bestätigte Buchung ging verloren")
        self.assertEqual(sum(1 for v in weiche.status["alt"].values()) > 0, True)
        self.assertEqual(sum(v for k, v in {**weiche.status["alt"]}.items() if k >= 500), 0, "alte Instanz ohne 5xx")
        self.invarianten(world)

    def test_vollstaendiges_rollout_unter_last_ohne_verlorene_oder_doppelte_bestaetigung(self):
        world, weiche, last, ziel, p = self.lauf("Voll", {})
        self.assertEqual(last.fehler, [])
        self.assertEqual(p["ergebnis"], "ABGESCHLOSSEN", p)
        for s in p["stufen"]:
            self.assertTrue(all(r["ok"] for r in s["rauchprobe"].values()), s)
        self.assertEqual(weiche.anteil_neu, 1.0)
        # beide Instanzen haben Buchungen bestätigt (alte und neue Version verarbeiten denselben Bestand)
        via = {n for _, _, n in last.buchungen}
        self.assertEqual(via, {"alt", "neu"}, via)
        # jede bestätigte Buchung steht genau einmal in der Datenbank
        ids = [b for b, _, _ in last.buchungen]
        self.assertEqual(len(ids), len(set(ids)))
        db_ids = {r[0] for r in self.admin.execute("SELECT id::text FROM taktovia.booking WHERE tenant_id=%s AND state <> 'CANCELED'", (world.tenant_id,)).fetchall()}
        self.assertTrue(set(ids) <= db_ids, "bestätigte Buchung fehlt in der Datenbank")
        inv = self.invarianten(world)
        self.assertEqual(inv["geprueft"]["buchungen"], len(db_ids) + self.admin.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s AND state='CANCELED'", (world.tenant_id,)).fetchone()[0])
        # Kompatibilität über Instanzen: eine über die alte Instanz bestätigte Buchung ist über die neue lesbar und umgekehrt
        alt_b = next((b for b in last.buchungen if b[2] == "alt"), None); neu_b = next((b for b in last.buchungen if b[2] == "neu"), None)
        for (bid, sub, _), app in ((alt_b, weiche.alt), (neu_b, weiche.alt)):
            self.assertEqual(http_get(f"{app.base}/v1/bookings/{bid}", self.idp.token(sub)), 200)
        neue = ziel.w.neu
        for (bid, sub, _) in (alt_b, neu_b):
            self.assertEqual(http_get(f"{neue.base}/v1/bookings/{bid}", self.idp.token(sub)), 200)
        neue.stop()


if __name__ == "__main__":
    unittest.main()
