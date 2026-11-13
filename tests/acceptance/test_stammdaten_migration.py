"""WP-M5-25 (LH-6.20-QAT-005): Stammdateneinspielung vor Betriebsstart mit Migrationsprotokoll.

Echte Anwendung: das Spring-Boot-Jar läuft als Prozess unter der Runtime-Rolle gegen eine eigene Testdatenbank, hinter einem lokalen JWT-Issuer mit JWKS-Endpunkt
(Ersatz für Keycloak, nicht ausgeführt). Mandanten entstehen über `POST /v1/platform/tenants`; das Einspielwerkzeug (`tools/stammdaten_import`) schreibt nur über die
fachlichen Schreibwege der HTTP-API. Die Datenbank wird hier nur lesend (Administratorzugang) zum Nachweis benutzt.

Voraussetzung: `cd backend && ./gradlew :app:bootJar` (erzeugt backend/app/build/libs/app.jar) und die DB-Umgebung aus db/db.py.
Aufruf: cd tests/acceptance && python -m unittest -v test_stammdaten_migration

Abgrenzung (ehrlich): Benutzer werden als Mitgliedschaften mit Rolle eingespielt; das Anlegen der Konten im Identitätsdienst ist nicht Teil des Werkzeugs (Keycloak läuft hier
nicht, offen OP-18). Die Probebuchung läuft über die echte API (Angebot mit eingespieltem Tarifpreis, Hold, Buchung); die Planbindung des Angebots an einen Umlauf setzt der
Testaufbau wie in den Anwendungstests per SQL (die Produktionsverdrahtung der Einfügeheuristik ist nicht Teil dieses Pakets)."""
import hashlib
import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb  # noqa: E402
from stammdaten_testwelt import referenz  # noqa: E402
from stammdaten_testwelt.welt import Welt, ROOT  # noqa: E402
from stammdaten_import import einspielung, quelle  # noqa: E402
from stammdaten_import.client import ApiFehler  # noqa: E402

DB = "taktovia_at_stammdaten"
WELT: Welt | None = None
TMP: tempfile.TemporaryDirectory | None = None
ZIELTABELLEN = ("legal_entity", "organization", "membership", "service", "service_area", "stop_revision", "tariff_revision", "tariff_rule", "vehicle", "vehicle_pool_assignment",
                "driver", "qualification_revision", "config_revision")


def setUpModule():
    global WELT, TMP
    from stammdaten_testwelt.app import JAR
    if not JAR.exists():
        if os.environ.get("TAKTOVIA_REQUIRE_APP_JAR") == "true":
            raise AssertionError(f"{JAR} fehlt (TAKTOVIA_REQUIRE_APP_JAR=true): cd backend && ./gradlew :app:bootJar")
        raise unittest.SkipTest(f"{JAR} fehlt: cd backend && ./gradlew :app:bootJar (nicht ausgeführt)")
    atdb.reset_database(DB)
    TMP = tempfile.TemporaryDirectory(prefix="stammdaten-")
    WELT = Welt(DB)


def tearDownModule():
    if WELT:
        WELT.close()
    if TMP:
        TMP.cleanup()
    atdb.drop_database(DB)


def tmp(name):
    return os.path.join(TMP.name, name)


class Basis(unittest.TestCase):
    @classmethod
    def zaehle(cls, tenant, tabellen=ZIELTABELLEN):
        with WELT.admin() as c:
            return {t: c.execute(f"SELECT count(*) FROM taktovia.{t} WHERE tenant_id = %s", (tenant,)).fetchone()[0] for t in tabellen}

    @classmethod
    def audit(cls, tenant):
        with WELT.admin() as c:
            return dict(c.execute("SELECT action, count(*) FROM taktovia.audit_event WHERE tenant_id = %s GROUP BY 1", (tenant,)).fetchall())

    @classmethod
    def zustandshash(cls, tenant):
        """Hash über alle Zeilen der Zielbestände des Mandanten (inklusive Zeitstempel: unveränderte Zeilen bleiben unverändert)."""
        h = hashlib.sha256()
        with WELT.admin() as c:
            for t in ZIELTABELLEN:
                for (z,) in c.execute(f"SELECT row_to_json(x)::text FROM taktovia.{t} x WHERE tenant_id = %s ORDER BY 1", (tenant,)).fetchall():
                    h.update(z.encode())
        return h.hexdigest()

    def datensatz(self, m, variante="A", mutieren=None):
        d = referenz.datensatz(m.id, WELT.idp.issuer, variante)
        if mutieren:
            mutieren(d)
        return d

    def lauf(self, m, d, name="q", modus="lauf", **kw):
        pfad = referenz.schreiben(d, tmp(f"{name}-{m.id}.json"))
        kw.setdefault("zustand_pfad", tmp(f"{name}-{m.id}.zustand.json"))
        kw.setdefault("protokoll_pfad", tmp(f"{name}-{m.id}.protokoll.json"))
        kw.setdefault("build", "test-build")
        kw.setdefault("migrationsstand", "V50190")
        kw.setdefault("freigeber", "freigeber-test")
        return einspielung.einspielen(pfad, WELT.app.base_url, m.zugaenge(), modus=modus, **kw)

    def neuer_mandant(self, name):
        return WELT.mandant(f"{name} {uuid.uuid4().hex[:6]}")


class Einspielung(Basis):
    def test_referenzdatensatz_soll_ist_im_migrationsprotokoll(self):
        m = self.neuer_mandant("Referenz")
        vorher = self.zaehle(m.id)
        d = self.datensatz(m)
        prot = self.lauf(m, d)
        self.assertEqual(prot["lauf"]["ergebnis"], "ABGESCHLOSSEN", prot["fehlerliste"])
        # Protokollinhalt laut Vertrag: Quelle, Prüfsumme, Soll/Ist je Objektart, Fehlerliste, Build, Migrationsstand, Ausführender, Freigeber
        quelldatei = tmp(f"q-{m.id}.json")
        self.assertEqual(prot["quelle"]["sha256"], hashlib.sha256(Path(quelldatei).read_bytes()).hexdigest())
        self.assertEqual((prot["build"], prot["migrationsstand"], prot["freigeber"]), ("test-build", "V50190", "freigeber-test"))
        self.assertEqual(prot["ausfuehrender"], str(m.admin_subject))
        self.assertEqual(prot["mandant"]["tenant_id"], str(m.id))
        self.assertEqual(prot["fehlerliste"], [])
        for art, soll in referenz.SOLL.items():
            b = prot["bestaende"][art]
            self.assertEqual((b["soll"], b["ist"], b["abgelehnt"], b["angelegt"]), (soll, soll, 0, soll), art)
            self.assertTrue(b["differenz_erklaert"], art)
        self.assertTrue(all(k["ok"] for k in prot["konsistenz"]), prot["konsistenz"])
        # Die Protokolldatei ist das Artefakt: gleicher Inhalt wie der Rückgabewert
        self.assertEqual(json.loads(Path(tmp(f"q-{m.id}.protokoll.json")).read_text()), prot)
        # Gegenprobe unabhängig vom Werkzeug: Zeilen in den Zielbeständen (Datenbank, nur lesend)
        nachher = self.zaehle(m.id)
        delta = {t: nachher[t] - vorher[t] for t in ZIELTABELLEN}
        self.assertEqual(delta["organization"], 2)
        self.assertEqual(delta["legal_entity"], 2)
        self.assertEqual(delta["service"], 1)
        self.assertEqual(delta["service_area"], 1)
        self.assertEqual(delta["stop_revision"], 3)
        self.assertEqual((delta["tariff_revision"], delta["tariff_rule"]), (1, 1))
        self.assertEqual((delta["vehicle"], delta["vehicle_pool_assignment"]), (3, 3))
        self.assertEqual(delta["membership"], 4)
        self.assertEqual((delta["driver"], delta["qualification_revision"]), (2, 2))
        self.assertEqual(delta["config_revision"], 2, "Pilotwerte plus die Konfigurationsrevision, die die Anwendung beim Anlegen des Dienstes erzeugt")
        with WELT.admin() as c:
            self.assertEqual(c.execute("SELECT state FROM taktovia.tariff_revision WHERE tenant_id=%s", (m.id,)).fetchone()[0], "VEROEFFENTLICHT")
            self.assertEqual(c.execute("SELECT state FROM taktovia.config_revision WHERE tenant_id=%s AND scope_kind='TENANT' AND values_json ? 'max_wait_min'", (m.id,)).fetchone()[0], "PUBLISHED")

    def test_ungueltige_datensaetze_landen_in_fehlerliste_uebrige_unberuehrt(self):
        m = self.neuer_mandant("Fehler")

        def schlecht(d):
            d["bediengebiete"].append({"key": "g-schlecht", "dienst": "d-rufbus", "valid_from": "2026-02-01T00:00:00+01:00", "zone_role": "BOTH",
                                       "area": {"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 1], [1, 0], [0, 1], [0, 0]]]]}})      # Schleife: ungültige Geometrie
            d["haltepunkte"] += [
                {**d["haltepunkte"][0], "key": "h-aussen", "name": "Weit weg", "point": {"latitude": 52.5, "longitude": 13.74}},               # außerhalb des Gebiets
                {**d["haltepunkte"][0], "key": "h-an-schlechtem-gebiet", "name": "Folgefehler", "gebiet": "g-schlecht"},                        # abhängig abgelehnt
                {**d["haltepunkte"][0], "key": "h-server-lehnt-ab", "name": "Serverfehler", "accessibility": {"schema_version": "9.9", "step_free": "JA"}},  # lehnt die Anwendung ab
            ]
            d["fahrzeuge"].append({**d["fahrzeuge"][0], "key": "f-doppelt"})                                                                    # doppeltes Kennzeichen
            d["tarife"].append({**d["tarife"][0], "key": "t-ohne-gueltigkeit", "tariff_class": "RABATT", "valid_from": None})                    # Tarif ohne Gültigkeit
            d["benutzer"].append({"key": "u-ohne-rolle", "subject": str(uuid.uuid4())})                                                         # Benutzer ohne Rolle
            d["fahrer"].append({**d["fahrer"][0], "key": "p-ohne-benutzer", "benutzer": "u-ohne-rolle", "contact_value": "x.y@example.invalid"})    # abhängig abgelehnt

        vorher = self.zaehle(m.id)
        prot = self.lauf(m, self.datensatz(m, mutieren=schlecht))
        self.assertEqual(prot["lauf"]["ergebnis"], "ABGESCHLOSSEN_MIT_FEHLERN")
        gründe = {(f["objektart"], f["schluessel"]): f["grund"] for f in prot["fehlerliste"]}
        self.assertEqual(gründe[("bediengebiete", "g-schlecht")], "GEOMETRIE_UNGUELTIG")
        self.assertEqual(gründe[("haltepunkte", "h-aussen")], "HALTEPUNKT_AUSSERHALB_GEBIET")
        self.assertEqual(gründe[("haltepunkte", "h-an-schlechtem-gebiet")], "ABHAENGIG_ABGELEHNT")
        self.assertTrue(gründe[("haltepunkte", "h-server-lehnt-ab")].startswith("API_"), gründe)
        self.assertEqual(gründe[("fahrzeuge", "f-doppelt")], "KENNZEICHEN_DOPPELT")
        self.assertEqual(gründe[("tarife", "t-ohne-gueltigkeit")], "TARIF_OHNE_GUELTIGKEIT")
        self.assertEqual(gründe[("benutzer", "u-ohne-rolle")], "BENUTZER_OHNE_ROLLE")
        self.assertEqual(gründe[("fahrer", "p-ohne-benutzer")], "ABHAENGIG_ABGELEHNT")
        self.assertEqual(len(prot["fehlerliste"]), 8)
        # Soll = Ist + abgelehnt je Objektart; die Differenz ist vollständig durch Fehlereinträge erklärt
        erwartet_abgelehnt = {"bediengebiete": 1, "haltepunkte": 3, "fahrzeuge": 1, "tarife": 1, "benutzer": 1, "fahrer": 1}
        for art, b in prot["bestaende"].items():
            self.assertEqual(b["abgelehnt"], erwartet_abgelehnt.get(art, 0), art)
            self.assertEqual(b["soll"] - b["ist"], b["abgelehnt"], art)
            self.assertTrue(b["differenz_erklaert"], art)
            self.assertEqual(b["ist"], referenz.SOLL[art], f"{art}: alle übrigen Datensätze sind geladen")
        # übrige Datensätze unberührt: genau der Bestand des Referenzlaufs, nichts von den abgelehnten
        delta = {t: self.zaehle(m.id)[t] - vorher[t] for t in ZIELTABELLEN}
        self.assertEqual((delta["service_area"], delta["stop_revision"], delta["vehicle"], delta["tariff_revision"], delta["membership"], delta["driver"]), (1, 3, 3, 1, 4, 2))
        with WELT.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=%s AND vehicle_label='XX-TX 101'", (m.id,)).fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.stop_revision WHERE tenant_id=%s AND name IN ('Weit weg','Folgefehler','Serverfehler')", (m.id,)).fetchone()[0], 0)
        # Die Fehlerliste nennt Gründe, keine Datenwerte
        self.assertNotIn("x.y@example.invalid", json.dumps(prot))

    def test_zwei_mandanten_parallel_keine_datenvermischung(self):
        a, b = self.neuer_mandant("ParallelA"), self.neuer_mandant("ParallelB")
        da, db_ = self.datensatz(a, "P"), self.datensatz(b, "Q")
        vor = {a.id: self.zaehle(a.id), b.id: self.zaehle(b.id)}
        start = threading.Barrier(2)

        def los(m, d):
            start.wait()
            return self.lauf(m, d)

        with ThreadPoolExecutor(2) as ex:
            fa, fb = ex.submit(los, a, da), ex.submit(los, b, db_)
            pa, pb = fa.result(180), fb.result(180)
        self.assertEqual((pa["lauf"]["ergebnis"], pb["lauf"]["ergebnis"]), ("ABGESCHLOSSEN", "ABGESCHLOSSEN"), (pa["fehlerliste"], pb["fehlerliste"]))
        for m, name_teil, fremd in ((a, "(P)", "(Q)"), (b, "(Q)", "(P)")):
            n = self.zaehle(m.id)
            self.assertEqual((n["organization"] - vor[m.id]["organization"], n["vehicle"] - vor[m.id]["vehicle"], n["stop_revision"] - vor[m.id]["stop_revision"]), (2, 3, 3))
            with WELT.admin() as c:
                tid = str(m.id)
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.organization WHERE tenant_id=%s AND name LIKE %s", (tid, f"%{fremd}%")).fetchone()[0], 0, "keine Daten des anderen Mandanten")
                self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id=%s AND vehicle_label LIKE %s", (tid, fremd[1:-1] * 2 + "-%")).fetchone()[0], 0)
                self.assertGreater(c.execute("SELECT count(*) FROM taktovia.organization WHERE tenant_id=%s AND name LIKE %s", (tid, f"%{name_teil}%")).fetchone()[0], 0)
        with WELT.admin() as c:   # Verweise über Mandantengrenzen gibt es nicht
            self.assertEqual(c.execute("""SELECT count(*) FROM taktovia.vehicle_pool_assignment p JOIN taktovia.service s ON s.id = p.service_id AND s.tenant_id <> p.tenant_id""").fetchone()[0], 0)
            self.assertEqual(c.execute("""SELECT count(*) FROM taktovia.service s JOIN taktovia.organization o ON o.id = s.operator_id AND o.tenant_id <> s.tenant_id""").fetchone()[0], 0)

    def test_einspielung_parallel_zu_buchungsbetrieb_anderer_mandant(self):
        a, b = self.neuer_mandant("Einspielung"), self.neuer_mandant("Betrieb")
        self.assertEqual(self.lauf(b, self.datensatz(b, "Q"))["lauf"]["ergebnis"], "ABGESCHLOSSEN")
        _subject, fahrgast = WELT.grant(b, "passenger")
        with WELT.admin() as c:
            sid = c.execute("SELECT id FROM taktovia.service WHERE tenant_id=%s", (b.id,)).fetchone()[0]
        vor_b, vor_hash = self.zaehle(b.id), self.zustandshash(b.id)
        audit_b = self.audit(b.id)
        ergebnisse, stop = [], threading.Event()

        def buchungsbetrieb():
            while not stop.is_set():
                try:
                    r = WELT.client.anfrage("POST", "/v1/ride-offers", fahrgast, angebotsanfrage(sid, tage=3))
                    ergebnisse.append((r.status, len(r.body["offers"])))
                except ApiFehler as e:
                    ergebnisse.append((e.status, e.code))

        t = threading.Thread(target=buchungsbetrieb)
        t.start()
        try:
            prot = self.lauf(a, self.datensatz(a, "P"))
        finally:
            stop.set()
            t.join(60)
        self.assertEqual(prot["lauf"]["ergebnis"], "ABGESCHLOSSEN", prot["fehlerliste"])
        self.assertGreaterEqual(len(ergebnisse), 5, "Der Buchungsbetrieb lief während der Einspielung")
        self.assertTrue(all(e == (200, 1) for e in ergebnisse), f"Buchungsbetrieb des anderen Mandanten wurde beeinflusst: {set(ergebnisse)}")
        # keine Datenvermischung: Bestände des Betriebsmandanten unverändert (außer den Angebotsanfragen selbst), keine Daten aus A
        nach_b = self.zaehle(b.id)
        self.assertEqual(vor_b, nach_b)
        self.assertEqual(vor_hash, self.zustandshash(b.id), "Stammdaten des Mandanten B unverändert")
        self.assertEqual({k: v for k, v in self.audit(b.id).items() if not k.startswith("booking.") and not k.startswith("offer.")},
                         {k: v for k, v in audit_b.items() if not k.startswith("booking.") and not k.startswith("offer.")}, "kein Audit der Einspielung in B")
        with WELT.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.organization WHERE tenant_id=%s AND name LIKE '%%(P)%%'", (b.id,)).fetchone()[0], 0)
            self.assertGreater(c.execute("SELECT count(*) FROM taktovia.service WHERE tenant_id=%s", (a.id,)).fetchone()[0], 0)

    def test_rolle_falscher_mandant_scheitert_und_audit_event(self):
        a, b = self.neuer_mandant("Ziel"), self.neuer_mandant("Fremd")
        vor_a, vor_b = self.zaehle(a.id), self.zaehle(b.id)
        audit_b_vorher = self.audit(b.id)
        # 1. Werkzeug: Quelle für A, Zugänge von B: Abbruch vor dem ersten Schreiben, Protokoll nennt den Grund
        pfad = referenz.schreiben(self.datensatz(a), tmp("falsch.json"))
        with self.assertRaises(einspielung.MandantAbweichung):
            einspielung.einspielen(pfad, WELT.app.base_url, b.zugaenge(), protokoll_pfad=tmp("falsch.protokoll.json"), freigeber="f", zustand_pfad=tmp("falsch.zustand.json"))
        p = json.loads(Path(tmp("falsch.protokoll.json")).read_text())
        self.assertEqual(p["lauf"]["ergebnis"], "NICHT_GESTARTET")
        self.assertEqual(p["fehlerliste"][0]["grund"], "MANDANT_ABWEICHUNG")
        self.assertEqual((self.zaehle(a.id), self.zaehle(b.id)), (vor_a, vor_b), "nichts geschrieben")
        # 2. Auch ohne Vorabprüfung scheitert die Rolle des falschen Mandanten an der Anwendung: B kann in A nichts anlegen oder lesen
        ok = einspielung.einspielen(referenz.schreiben(self.datensatz(a), tmp("ziel.json")), WELT.app.base_url, a.zugaenge(), protokoll_pfad=tmp("ziel.protokoll.json"),
                                    zustand_pfad=tmp("ziel.zustand.json"), freigeber="f", build="t", migrationsstand="t")
        self.assertEqual(ok["lauf"]["ergebnis"], "ABGESCHLOSSEN")
        with WELT.admin() as c:
            sid_a = c.execute("SELECT id FROM taktovia.service WHERE tenant_id=%s", (a.id,)).fetchone()[0]
            area_a = c.execute("SELECT id FROM taktovia.service_area WHERE tenant_id=%s", (a.id,)).fetchone()[0]
        flaechen_a = self.zaehle(a.id)["service_area"]
        quadrat = {"type": "MultiPolygon", "coordinates": [[[[1, 1], [2, 1], [2, 2], [1, 2], [1, 1]]]]}
        for versuch in (lambda: WELT.client.anfrage("POST", "/v1/service-areas", b.admin_token, {"service_id": str(sid_a), "area": quadrat, "valid_from": "2026-03-01T00:00:00+01:00"}),
                        lambda: WELT.client.get(f"/v1/service-areas/{area_a}", b.admin_token),
                        lambda: WELT.client.get(f"/v1/services/{sid_a}/operating-hours", b.admin_token)):
            with self.assertRaises(ApiFehler) as cm:
                versuch()
            self.assertIn(cm.exception.status, (403, 404, 422), cm.exception.grund)
        self.assertEqual(self.zaehle(a.id)["service_area"], flaechen_a)
        # 3. Nachvollzug in audit_event: Einspielung von A ist im Mandanten A protokolliert (Akteur = Administrator von A), in B entsteht nichts
        audit_a = self.audit(a.id)
        for aktion in ("identity.organization.changed", "identity.service.changed", "geo.service_area.changed", "geo.stop.changed", "tariff.revision.published",
                       "fleet.vehicle.created", "identity.membership.granted", "staff.driver.created", "config.revision.published"):
            self.assertGreater(audit_a.get(aktion, 0), 0, aktion)
        with WELT.admin() as c:
            akteure = {r[0] for r in c.execute("SELECT DISTINCT actor_ref FROM taktovia.audit_event WHERE tenant_id=%s AND action='identity.organization.changed'", (a.id,)).fetchall()}
        self.assertEqual(akteure, {f"user:{m}" for m in {a.admin_subject}})
        self.assertEqual(self.audit(b.id), audit_b_vorher, "keine Auditereignisse der Einspielung in Mandant B")

    def test_konsistenz_keine_verwaisten_referenzen(self):
        m = self.neuer_mandant("Konsistenz")
        prot = self.lauf(m, self.datensatz(m))
        regeln = {k["regel"]: k for k in prot["konsistenz"]}
        self.assertEqual(set(regeln), {"keine_verwaisten_referenzen", "bediengebiet_valides_polygon", "haltepunkt_im_gebiet", "tarif_mit_gueltigkeit",
                                       "fahrzeug_mit_betreiber_dienst_ressourcenvektor", "benutzer_mit_mitgliedschaft_und_rolle"})
        self.assertTrue(all(k["ok"] and k["geprueft"] > 0 for k in regeln.values()), regeln)
        t = str(m.id)
        with WELT.admin() as c:
            q1 = lambda sql: c.execute(sql, (t,)).fetchone()[0]  # noqa: E731
            self.assertEqual(q1("SELECT count(*) FROM taktovia.service s LEFT JOIN taktovia.organization o ON o.id=s.operator_id WHERE s.tenant_id=%s AND o.id IS NULL"), 0)
            self.assertEqual(q1("SELECT count(*) FROM taktovia.organization o LEFT JOIN taktovia.legal_entity l ON l.id=o.legal_entity_id WHERE o.tenant_id=%s AND l.id IS NULL"), 0)
            self.assertEqual(q1("SELECT count(*) FROM taktovia.service_area WHERE tenant_id=%s AND NOT ST_IsValid(area)"), 0)
            self.assertEqual(q1("SELECT count(*) FROM taktovia.service_area a LEFT JOIN taktovia.service s ON s.id=a.service_id WHERE a.tenant_id=%s AND s.id IS NULL"), 0)
            self.assertEqual(q1("""SELECT count(*) FROM taktovia.stop_revision r WHERE r.tenant_id=%s AND NOT EXISTS
                                   (SELECT 1 FROM taktovia.service_area a WHERE a.tenant_id=r.tenant_id AND ST_Covers(a.area, r.point::geometry))"""), 0)
            self.assertEqual(q1("SELECT count(*) FROM taktovia.tariff_revision WHERE tenant_id=%s AND (valid_from IS NULL OR state <> 'VEROEFFENTLICHT')"), 0)
            self.assertEqual(q1("SELECT count(*) FROM taktovia.tariff_rule r LEFT JOIN taktovia.tariff_revision v ON v.id=r.revision_id WHERE r.tenant_id=%s AND v.id IS NULL"), 0)
            self.assertEqual(q1("""SELECT count(*) FROM taktovia.vehicle v WHERE v.tenant_id=%s AND v.vehicle_label LIKE 'XX-%%' AND (v.capacity_config IS NULL OR v.owner_org_ref IS NULL
                                   OR NOT EXISTS (SELECT 1 FROM taktovia.vehicle_pool_assignment p JOIN taktovia.service s ON s.id=p.service_id WHERE p.vehicle_id=v.id))"""), 0)
            self.assertEqual(q1("SELECT count(*) FROM (SELECT vehicle_label FROM taktovia.vehicle WHERE tenant_id=%s AND vehicle_label IS NOT NULL GROUP BY 1 HAVING count(*) > 1) x"), 0, "Kennzeichen eindeutig")
            self.assertEqual(q1("SELECT count(*) FROM taktovia.driver d LEFT JOIN taktovia.membership m ON m.id=d.membership_id WHERE d.tenant_id=%s AND m.id IS NULL"), 0)
            self.assertEqual(q1("SELECT count(*) FROM taktovia.membership WHERE tenant_id=%s AND (role_code IS NULL OR revoked_at IS NOT NULL)"), 0)

    def test_abgebrochener_lauf_fortsetzbar(self):
        m = self.neuer_mandant("Abbruch")
        d = self.datensatz(m)
        vorher = self.zaehle(m.id)
        with self.assertRaises(einspielung.LaufAbgebrochen):
            self.lauf(m, d, abbruch_nach=5)
        zustand = json.loads(Path(tmp(f"q-{m.id}.zustand.json")).read_text())
        self.assertEqual(zustand["status"], "ABGEBROCHEN")
        teil = json.loads(Path(tmp(f"q-{m.id}.protokoll.json")).read_text())
        self.assertEqual(teil["lauf"]["ergebnis"], "ABGEBROCHEN")
        teil_n = self.zaehle(m.id)
        self.assertEqual(teil_n["organization"] - vorher["organization"], 2)
        self.assertLess(teil_n["stop_revision"] - vorher["stop_revision"], 3, "mitten in den Haltepunkten unterbrochen")
        self.assertEqual(teil_n["vehicle"], vorher["vehicle"])
        # ein neuer Lauf derselben Quelle statt Fortsetzung wird verweigert
        with self.assertRaises(ValueError):
            self.lauf(m, d)
        # Wiederaufnahme: vollständig, ohne Duplikate, Zählung über beide Teile
        prot = self.lauf(m, d, modus="fortsetzen")
        self.assertEqual(prot["lauf"]["ergebnis"], "ABGESCHLOSSEN", prot["fehlerliste"])
        self.assertEqual(prot["lauf"]["fortsetzungen"], 1)
        self.assertEqual(prot["lauf"]["id"], teil["lauf"]["id"])
        n = self.zaehle(m.id)
        self.assertEqual((n["organization"] - vorher["organization"], n["stop_revision"] - vorher["stop_revision"], n["vehicle"] - vorher["vehicle"],
                          n["membership"] - vorher["membership"], n["driver"] - vorher["driver"]), (2, 3, 3, 4, 2))
        for art, soll in referenz.SOLL.items():
            b = prot["bestaende"][art]
            self.assertEqual((b["soll"], b["ist"], b["angelegt"]), (soll, soll, soll), art)
        # geänderte Quelle verhindert die Wiederaufnahme einer anderen Unterbrechung
        m2 = self.neuer_mandant("Abbruch2")
        d2 = self.datensatz(m2)
        with self.assertRaises(einspielung.LaufAbgebrochen):
            self.lauf(m2, d2, abbruch_nach=2)
        d2["haltepunkte"][0]["name"] = "Geändert"
        with self.assertRaises(ValueError):
            self.lauf(m2, d2, modus="fortsetzen")

    def test_zweiter_lauf_idempotent(self):
        m = self.neuer_mandant("Idempotenz")
        d = self.datensatz(m)
        p1 = self.lauf(m, d, name="r1")
        n1, h1, a1 = self.zaehle(m.id), self.zustandshash(m.id), self.audit(m.id)
        p2 = self.lauf(m, d, name="r2", zustand_pfad=tmp(f"r2-{m.id}.zustand2.json"))
        self.assertEqual(p2["lauf"]["ergebnis"], "ABGESCHLOSSEN")
        self.assertEqual(self.zaehle(m.id), n1)
        self.assertEqual(self.zustandshash(m.id), h1, "keine Zeile geändert oder ergänzt")
        self.assertEqual(self.audit(m.id), a1, "keine neuen Auditereignisse")
        for art, soll in referenz.SOLL.items():
            b = p2["bestaende"][art]
            self.assertEqual((b["angelegt"], b["vorhanden"], b["ist"]), (0, soll, soll), art)
        self.assertEqual(p1["quelle"]["sha256"], p2["quelle"]["sha256"])
        self.assertNotEqual(p1["lauf"]["id"], p2["lauf"]["id"])

    def test_trockenlauf_schreibt_nichts_und_meldet_fehler(self):
        m = self.neuer_mandant("Trocken")
        d = self.datensatz(m, mutieren=lambda x: x["fahrzeuge"].append({**x["fahrzeuge"][0], "key": "f-doppelt"}))
        vorher, audit = self.zaehle(m.id), self.audit(m.id)
        prot = self.lauf(m, d, modus="trockenlauf", freigeber=None)
        self.assertEqual(prot["lauf"]["ergebnis"], "TROCKENLAUF")
        self.assertEqual(self.zaehle(m.id), vorher)
        self.assertEqual(self.audit(m.id), audit)
        self.assertEqual(prot["bestaende"]["fahrzeuge"]["wuerde_anlegen"], 3)
        self.assertEqual([f["grund"] for f in prot["fehlerliste"]], ["KENNZEICHEN_DOPPELT"])
        self.assertFalse(Path(tmp(f"q-{m.id}.zustand.json")).exists(), "Trockenlauf legt keinen Wiederaufnahmezustand an")
        # schreibender Lauf ohne Freigeber wird verweigert
        with self.assertRaises(ValueError):
            self.lauf(m, d, freigeber=None)
        self.assertEqual(self.zaehle(m.id), vorher)


class DatenschutzUndProbebuchung(Basis):
    def test_protokoll_und_logs_ohne_kontaktdaten(self):
        m = self.neuer_mandant("Datenschutz")
        d = self.datensatz(m, mutieren=lambda x: x["fahrer"].append({**x["fahrer"][0], "key": "p-3", "benutzer": "u-fahrer-2", "contact_value": "dritter.fahrer@example.invalid",
                                                                         "display_name": "Testfahrer Drei"}))
        geheim = [f["contact_value"] for f in d["fahrer"]] + [f["display_name"] for f in d["fahrer"]] + [b["subject"] for b in d["benutzer"]]
        logs = []

        class Sammler(logging.Handler):
            def emit(self, record):
                logs.append(self.format(record))

        h = Sammler(level=logging.DEBUG)
        wurzel = logging.getLogger()
        alt = wurzel.level
        wurzel.addHandler(h)
        wurzel.setLevel(logging.DEBUG)
        try:
            prot = self.lauf(m, d)
        finally:
            wurzel.removeHandler(h)
            wurzel.setLevel(alt)
        text = json.dumps(prot, ensure_ascii=False) + Path(tmp(f"q-{m.id}.protokoll.json")).read_text() + "\n".join(logs)
        self.assertTrue(logs, "es wurde protokolliert")
        self.assertEqual([(f["objektart"], f["schluessel"], f["grund"]) for f in prot["fehlerliste"]], [("fahrer", "p-3", "FAHRER_DOPPELT")], "Fehlerliste mit Grund statt Datenwerten")
        for wert in geheim:
            self.assertNotIn(wert, text)
        self.assertIsNone(re.search(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}", text), "keine E-Mail-Adressen")
        self.assertIsNone(re.search(r"\+\d[\d ]{7,}", text), "keine Telefonnummern")
        # Kommandozeile: Ausgabe und Fehlerausgabe ebenfalls ohne Kontaktdaten
        pfad = referenz.schreiben(d, tmp("cli-quelle.json"))
        z = tmp("cli-zugaenge.json")
        zg = m.zugaenge()
        Path(z).write_text(json.dumps(zg.alle()))
        r = subprocess.run([sys.executable, str(ROOT / "tools" / "stammdaten_import" / "cli.py"), "--quelle", pfad, "--api", WELT.app.base_url, "--zugang-datei", z, "--trockenlauf",
                            "--protokoll", tmp("cli.protokoll.json"), "--zustand", tmp("cli.zustand.json")], capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stderr)
        for wert in geheim:
            self.assertNotIn(wert, r.stdout + r.stderr + Path(tmp("cli.protokoll.json")).read_text())
        # Die im Repository liegenden Beispieldaten sind synthetisch (reservierte Domäne .invalid)
        for datei in (ROOT / "tests" / "acceptance" / "fixtures" / "stammdaten_referenz").glob("*.json"):
            for adresse in re.findall(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]+", datei.read_text()):
                self.assertTrue(adresse.endswith(".invalid"), adresse)

    def test_probebuchung_im_eingespielten_gebiet(self):
        m = self.neuer_mandant("Probe")
        prot = self.lauf(m, self.datensatz(m))
        self.assertEqual(prot["lauf"]["ergebnis"], "ABGESCHLOSSEN", prot["fehlerliste"])
        subject, token = WELT.grant(m, "passenger")
        tid = str(m.id)
        with WELT.admin() as c:
            sid = c.execute("SELECT id FROM taktovia.service WHERE tenant_id=%s", (tid,)).fetchone()[0]
            fahrzeug = c.execute("SELECT id FROM taktovia.vehicle WHERE tenant_id=%s AND vehicle_label='XX-TX 101'", (tid,)).fetchone()[0]
            fahrer = c.execute("SELECT id FROM taktovia.driver WHERE tenant_id=%s ORDER BY id LIMIT 1", (tid,)).fetchone()[0]
        # 1. Angebot im eingespielten Gebiet (Haltepunkte, Betriebszeiten) mit dem eingespielten Tarif (Festpreis 450 je Person)
        r = WELT.client.anfrage("POST", "/v1/ride-offers", token, angebotsanfrage(sid, tage=3, personen=2))
        self.assertEqual(r.status, 200)
        angebot = r.body["offers"][0]
        brutto = angebot["gross"]["amount_minor"]
        self.assertEqual(brutto, 900, "2 Personen x 450 laut eingespieltem Tarif")
        # 2. Außerhalb des eingespielten Gebiets gibt es kein Angebot
        ausserhalb = WELT.client.anfrage("POST", "/v1/ride-offers", token, angebotsanfrage(sid, tage=3, ziel=(52.5, 13.78)))
        self.assertEqual((ausserhalb.body["offers"], ausserhalb.body["rejection"]["code"]), ([], "OUTSIDE_AREA"))
        # 3. Hold und Buchung auf dem eingespielten Fahrzeug (Planbindung per SQL im Testaufbau, siehe Modul-Docstring)
        von = datetime.fromisoformat(angebot["pickup_window"]["earliest_at"].replace("Z", "+00:00")) - timedelta(hours=1)
        bis = datetime.fromisoformat(angebot["arrival_window"]["latest_at"].replace("Z", "+00:00")) + timedelta(hours=1)
        schicht, umlauf = uuid.uuid4(), uuid.uuid4()
        with WELT.admin() as c:
            # Eingespielte Fahrzeuge sind bis zur Fahrzeugfreigabe (Prüfung/Freigabe, M3) gesperrt (`active_block`); die Freigabe ist nicht Teil der Einspielung und wird hier gesetzt.
            c.execute("UPDATE taktovia.vehicle SET active_block = false WHERE tenant_id = %s AND id = %s", (tid, fahrzeug))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,%s,%s,'Europe/Berlin',1,'PUBLISHED')",
                      (tid, schicht, fahrer, fahrzeug, von, bis))
            c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (%s,%s,%s,%s,1,%s,1,%s)",
                      (tid, uuid.uuid4(), fahrzeug, schicht, uuid.uuid4(), bis))
            c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'ACTIVE')", (tid, umlauf, fahrzeug, schicht))
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until,idp_issuer,idp_subject,status) VALUES (%s,%s,%s, now() + interval '5 years',%s,%s,'ACTIVE')",
                      (tid, uuid.uuid4(), "tok-" + uuid.uuid4().hex, WELT.idp.issuer, str(subject)))
            c.execute("ALTER TABLE taktovia.ride_offer DISABLE TRIGGER ride_offer_guard")
            try:
                c.execute("UPDATE taktovia.ride_offer SET run_id=%s, plan_revision=1, interval_from=0, interval_to=2 WHERE id=%s", (umlauf, angebot["id"]))
            finally:
                c.execute("ALTER TABLE taktovia.ride_offer ENABLE TRIGGER ride_offer_guard")
        maxi = {"max_gross": {"amount_minor": brutto, "currency": "EUR", "exponent": 2}}
        hold = WELT.client.anfrage("POST", f"/v1/ride-offers/{angebot['id']}/hold", token, maxi, if_match=angebot["revision"])
        self.assertEqual(hold.status, 201)
        buchung = WELT.client.anfrage("POST", "/v1/bookings", token, {"hold_id": hold.body["id"], **maxi}, if_match=hold.etag_zahl)
        self.assertEqual(buchung.status, 201, buchung.body)
        with WELT.admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE tenant_id=%s", (tid,)).fetchone()[0], 1)


def angebotsanfrage(service_id, tage=3, personen=2, ziel=(51.0801, 13.7801)):
    """Fahrtwunsch im eingespielten Gebiet: von Markt (51.05, 13.74) nach Bahnhof (51.08, 13.78), Abfahrt in [tage] Tagen um 10:00 Ortszeit Berlin."""
    wunsch = (datetime.now(ZoneInfo("Europe/Berlin")) + timedelta(days=tage)).replace(hour=10, minute=0, second=0, microsecond=0)
    return {"service_id": str(service_id), "origin": {"point": {"latitude": 51.0501, "longitude": 13.7401}}, "destination": {"point": {"latitude": ziel[0], "longitude": ziel[1]}},
            "time_kind": "DEPARTURE", "desired": {"desired_at": wunsch.isoformat(), "time_zone": "Europe/Berlin"}, "passengers": personen}


class QuellFormat(unittest.TestCase):
    """Lokale Prüfung ohne Anwendung: Quellformat und Konsistenzregeln der Quelldatei."""

    def test_ungueltige_quellen_werden_als_ganzes_abgelehnt(self):
        for inhalt in ("kein json", json.dumps({"format": "anderes"}), json.dumps({"format": quelle.FORMAT}), json.dumps({"format": quelle.FORMAT, "mandant": {"tenant_id": "x"}}),
                       json.dumps({"format": quelle.FORMAT, "mandant": {"tenant_id": str(uuid.uuid4())}, "unbekannt": []})):
            pfad = os.path.join(tempfile.gettempdir(), f"stammdaten-q-{uuid.uuid4().hex}.json")
            Path(pfad).write_text(inhalt)
            try:
                with self.assertRaises(quelle.QuelleFehler):
                    quelle.laden(pfad)
            finally:
                os.unlink(pfad)


if __name__ == "__main__":
    unittest.main()
