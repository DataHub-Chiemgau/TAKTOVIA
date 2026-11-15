"""WP-M5-05: die echten Proben gegen die ECHTE Anwendung (Spring-Jar, produktive Verdrahtung, Runtime-Rolle, RLS) mit einem Probemandanten und künstlich erzeugtem Ausfall bekannter Dauer.

Opt-in (`TAKTOVIA_PERF_RUN=1`, braucht `backend/app/build/libs/app.jar` und die Datenbankumgebung wie `db/db.py`; ohne Voraussetzungen übersprungen, mit TAKTOVIA_REQUIRE_DB=true fehlgeschlagen).
Ersatz für Staging: Anwendung und Datenbank laufen lokal; als Anmeldedienst dient ein lokaler JWT-Test-Issuer (JWKS, RS256) mit einem Token-Endpunkt (Ersatz für Keycloak, NICHT ausgeführt: echter IdP).
Der künstliche Ausfall ist echt: der Anwendungsprozess wird für bekannte Zeit angehalten (SIGSTOP), die Proben laufen weiter über HTTP und laufen in die Zeitüberschreitung. Die Zeitachse der
Rohdaten ist virtuell (ein Lauf je virtuelle Minute, damit der Test Sekunden statt Stunden dauert); die Ausfalldauer ist in Probenintervallen exakt vorgegeben.
Grenzen: Die Planbindung des Angebots geschieht im Testaufbau per SQL (Produktionsverdrahtung ohne Kandidatensuche und Solver, siehe tests/performance/README.md); ein 99,9-%-Nachweis über einen
Monat ist nicht Gegenstand (OP-02)."""
import json
import os
import signal
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "infra" / "availability"))
sys.path.insert(0, str(REPO / "tests" / "performance"))
sys.path.insert(0, str(REPO / "db"))

from taktovia_availability import bericht as B  # noqa: E402
from taktovia_availability import proben as P  # noqa: E402
from refload.prereq import require_app  # noqa: E402

T0 = datetime(2026, 11, 10, 0, 0, tzinfo=timezone.utc)


class TokenEndpunkt:
    """Token-Endpunkt des Test-Issuers (Passwort-Grant): `richtig` als Passwort liefert das Token des zugeordneten Subjects."""

    def __init__(self, idp, subjects: dict):
        outer = self
        self.idp, self.subjects = idp, subjects

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b"ok")

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                f = urllib.parse.parse_qs(self.rfile.read(n).decode())
                user, pw = f.get("username", [""])[0], f.get("password", [""])[0]
                if pw != "richtig" or user not in outer.subjects:
                    body, st = b'{"error":"invalid_grant"}', 401
                else:
                    body, st = json.dumps({"access_token": outer.idp.token(outer.subjects[user]), "expires_in": 300}).encode(), 200
                self.send_response(st); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


class ProbenGegenAnwendung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_app()
        import db as dbtool
        from refload import world as W
        from refload.appproc import App
        from refload.idp import TestIdp
        cls.W = W
        db_name = os.environ["TAKTOVIA_DB_NAME"]
        cls.idp = TestIdp()
        cls.app = App(cls.idp, db_name=db_name, log=Path(tempfile.gettempdir()) / "taktovia-probe-app.log")
        cls.admin = dbtool.admin(db_name)

        def create_synthetic(admin, name):
            import uuid
            tid = str(uuid.uuid4())
            admin.execute("INSERT INTO taktovia.tenant(id,name,status,synthetic) VALUES (%s,%s,'ACTIVE',true)", (tid, name))
            return tid
        W.create_tenant = create_synthetic          # Probemandant statt Echtmandant (Kennzeichnung bei Anlage, V50050)
        cls.app.start(300)
        cls.w = W.build_tenant(cls.app, cls.idp, cls.admin, "P", 1, 1, name="Probemandant (Verfügbarkeit)")
        cls.vehicle = cls.admin.execute("SELECT vehicle_id FROM taktovia.vehicle_run WHERE id=%s", (cls.w.runs[0],)).fetchone()[0]
        import uuid
        cls.operator_sub = str(uuid.uuid4())
        W.grant(cls.admin, cls.w.tenant_id, cls.idp.issuer, cls.operator_sub, "dispatcher")     # Operatorrolle: `/v1/operator/vehicles` verlangt operator:read (Disposition)
        cls.admin.execute("UPDATE taktovia.membership SET service_scope = ARRAY[%s]::uuid[] WHERE tenant_id=%s AND subject=%s", (cls.w.service_id, cls.w.tenant_id, cls.operator_sub))   # Dienstscope: Probedienst
        cls.tokens = TokenEndpunkt(cls.idp, {"probe-fahrgast": cls.w.passengers[0], "probe-operator": cls.operator_sub})

    @classmethod
    def tearDownClass(cls):
        for name in ("app", "idp", "admin"):
            obj = getattr(cls, name, None)
            if obj is None:
                continue
            try:
                (obj.stop if name == "app" else obj.close)()
            except Exception:  # noqa: BLE001
                pass

    def konfig(self):
        w = self.w

        def binde(angebot):
            run, seg = w.next_run_segment()
            self.W.bind_offer(self.admin, w.tenant_id, angebot["id"], run, seg)
        return P.Konfiguration(
            base_url=self.app.base, token_url=self.tokens.base + "/token", referenz_url=self.tokens.base + "/ref", timeout_s=2.0,
            zugang_kernbuchung={"grant_type": "password", "username": "probe-fahrgast", "password": "env:PROBE_PW"},
            zugang_operator={"grant_type": "password", "username": "probe-operator", "password": "env:PROBE_PW"},
            dienst_id=w.service_id, probefahrzeug_id=str(self.vehicle), probemandant_id=w.tenant_id, angebot_body=self.W.offer_body(w.service_id, w.days, 1, 0), planbindung=binde)

    def test_proben_gegen_anwendung_ausfall_bekannter_dauer_und_wartung_im_bericht(self):
        env = {"PROBE_PW": "richtig"}
        cfg = self.konfig()
        ablauf = []                                               # (virtuelle Minute, Anwendung angehalten?)
        pause = {4, 5, 6}                                         # künstlicher Ausfall: 3 Minuten
        wartung_min = {9, 10}                                     # vorab eingetragenes Wartungsfenster: 2 Minuten (Anwendung angehalten)
        saetze = []
        # Wartungsfenster vorab im Kalender, außerhalb der Messzeit der Anwendung
        wartung = [{"von": (T0 + timedelta(minutes=9)).isoformat(), "bis": (T0 + timedelta(minutes=11)).isoformat(), "grund": "geplante Wartung (Test)", "eingetragen_am": (T0 - timedelta(days=3)).isoformat()}]
        alarme = []
        for i in range(14):
            angehalten = i in pause or i in wartung_min
            if angehalten:
                os.kill(self.app.proc.pid, signal.SIGSTOP)
            try:
                for bereich in P.BEREICHE:
                    s = P.probe_ausfuehren(bereich, cfg, P.urllib_transport, uhr=lambda i=i: (T0 + timedelta(minutes=i)).timestamp(), umgebung=env, alarm=alarme.append)
                    saetze.append(s)
            finally:
                if angehalten:
                    os.kill(self.app.proc.pid, signal.SIGCONT)
            ablauf.append((i, angehalten))
        # 1. gesunde Minuten: beide Proben ok (echte Anmeldung, Angebot, Hold, Bestätigung, Storno; /v1/me; /v1/operator/vehicles)
        ok_minuten = [i for i, a in ablauf if not a]
        for s in saetze:
            minute = int((datetime.fromisoformat(s["zeitpunkt"]) - T0).total_seconds() // 60)
            if minute in ok_minuten:
                self.assertEqual(s["ergebnis"], P.OK, s)
            else:
                self.assertEqual(s["ergebnis"], P.FEHLER_PLATTFORM, s)
        self.assertEqual(alarme, [], "kein Probenalarm: die Probe selbst blieb intakt (Referenzpunkt erreichbar)")
        # 2. Bericht über den Testzeitraum
        ausgabe = os.environ.get("TAKTOVIA_PROBE_BERICHT_OUT")
        Path(ausgabe or tempfile.mkdtemp()).mkdir(parents=True, exist_ok=True)
        roh = Path(ausgabe or tempfile.mkdtemp()) / "probenlauf-anwendung-rohdaten.jsonl"
        roh.write_text("".join(json.dumps(s) + "\n" for s in saetze), encoding="utf-8")
        gelesen, sha = B.lade_rohdaten(roh)
        b = B.erzeuge_bericht(gelesen, T0, T0 + timedelta(minutes=14), B.lade_vertrag(), wartung, roh.name, sha, umgebung="lokal (Ersatz für Staging)")
        for bereich in P.BEREICHE:
            v = b["bereiche"][bereich]
            self.assertEqual(len(v["ausfaelle"]), 2, v["ausfaelle"])
            ausfall = [a for a in v["ausfaelle"] if a["davon_wartung_min"] == 0][0]
            self.assertAlmostEqual(ausfall["dauer_min"], 3.0, delta=1.0, msg="künstlicher Ausfall von 3 Minuten erscheint mit dieser Dauer (Toleranz ein Probenintervall)")
            self.assertEqual(v["ausfallminuten"], 3.0); self.assertEqual(v["wartungsminuten"], 2.0)
            self.assertEqual(v["messminuten"], 14.0)
            self.assertAlmostEqual(v["verfuegbarkeit_prozent"], 100.0 * (12 - 3) / 12, places=3)
            self.assertEqual(v["aussage"], "ziel_verfehlt")
        self.assertTrue(b["wartungsfenster"][0]["herausgerechnet"]); self.assertEqual(b["wartungsfenster"][0]["minuten_im_zeitraum"], 2.0)
        if ausgabe:
            (Path(ausgabe) / "probenlauf-anwendung.json").write_text(json.dumps(b, indent=1, ensure_ascii=False, sort_keys=True), encoding="utf-8")
            (Path(ausgabe) / "probenlauf-anwendung.md").write_text(B.als_markdown(b), encoding="utf-8")
        # 3. Probebuchungen belegen keine Kapazität dauerhaft und sind aus Anfragen/Buchungskonversion ausgeschlossen
        t = self.w.tenant_id
        self.assertEqual(self.admin.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE tenant_id=%s AND state='HELD' AND expires_at > clock_timestamp()", (t,)).fetchone()[0], 0)
        booked = self.admin.execute("SELECT state, count(*) FROM taktovia.booking WHERE tenant_id=%s GROUP BY 1", (t,)).fetchall()
        self.assertEqual({s for s, _ in booked}, {"CANCELED"}, booked)
        self.assertEqual(sum(n for _, n in booked), len(ok_minuten), "je gesunder Minute eine Probebuchung, jede storniert")
        for tabelle in ("fact_attempt", "fact_offer", "fact_booking", "analytics_event_inbox"):
            self.assertEqual(self.admin.execute(f"SELECT count(*) FROM taktovia.{tabelle} WHERE tenant_id=%s", (t,)).fetchone()[0], 0, tabelle)  # noqa: S608


if __name__ == "__main__":
    unittest.main()
