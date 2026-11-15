"""Referenzwelt der E2E-Szenarien (WP-M5-23, WP-M5-24, WP-M5-16): die ECHTE Anwendung (Spring-Boot-Jar, Runtime-Rolle) hinter einem lokalen JWT-Test-Issuer, der PSP-Simulator
in-process und zwei Mandanten mit eingespielten Stammdaten (`tools/stammdaten_import`). Ersatz für Staging: KEIN Staging-System, kein Keycloak, kein Broker, kein echter PSP
(Ergebnisse sind Ergebnisse dieser Ersatzumgebung, siehe `ERSATZ`).

Was über die öffentliche HTTP-Schnittstelle läuft: Mandantenanlage, Stammdateneinspielung, Angebot, Hold, Buchung, Zahlung (Autorisierung/Einzug durch den Zahlungsautomaten und die
Zahlungskopplung der Anwendung, PSP-Ereignis mit Signatur), Fahrergeräte, Dienstantritt, Fahrerereignisse, Kennzahlen, Storno. Was der **Testaufbau** per Administratorzugang setzt
(wie in tests/acceptance/test_stammdaten_migration.py und den Anwendungstests; die Verdrahtung der Einfügeheuristik und die Fahrzeugfreigabe sind nicht Teil dieser Pakete): Freigabe des
Fahrzeugs, Schicht samt Ressourcenfreigabe, Umlauf, Planbindung des Angebots und die Stopps der Planrevision. Diese Schreibzugriffe stehen im Wächter-Protokoll als `TESTAUFBAU` und
zählen nicht als Eingriff in den Ablauf (der Wächter prüft die Schreibzugriffe zwischen `start()` und `ende()` des Szenarios, der Aufbau liegt davor)."""
import json
import os
import socket
import sys
import tempfile
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
ROOT = HERE.parents[2]
for p in (ACC, ROOT / "tools", ROOT / "db", ROOT / "tests" / "sandbox" / "psp", ROOT / "tests" / "lib"):
    sys.path.insert(0, str(p))

from taktovia_at import db as atdb  # noqa: E402
from stammdaten_testwelt import referenz  # noqa: E402
from stammdaten_testwelt.app import AppProcess, JAR  # noqa: E402
from stammdaten_testwelt.welt import Welt  # noqa: E402
from stammdaten_import import einspielung  # noqa: E402
from stammdaten_import.client import ApiClient, ApiFehler  # noqa: E402

ERSATZ = ["Anwendung lokal als Prozess statt Staging-Umgebung (Spring-Boot-Jar, Runtime-Rolle)", "lokaler JWT-Test-Issuer statt Keycloak", "PSP-Simulator tests/sandbox/psp statt PSP-Sandbox (D-03 offen)",
          "Planbindung, Fahrzeugfreigabe, Schicht und Planstopps im Testaufbau per Administratorzugang (Einfügeheuristik und Freigabeketten nicht verdrahtet)", "kein Messaging-Broker, kein Redis"]
E2E_DB = os.environ.get("TAKTOVIA_E2E_DB", "taktovia_at_e2e")
MONEY_CHARS = str.maketrans("0123456789", "ghijklmnop")


def key(prefix="k") -> str:
    """Idempotenzschlüssel ohne Ziffernfolgen (CardDataGuard der Anwendung)."""
    return prefix + "-" + uuid.uuid4().hex.translate(MONEY_CHARS)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def jar_vorhanden() -> bool:
    return JAR.exists()


class Mandant:
    """Ein Mandant der Referenzwelt mit seinen Rollen-Tokens und den Kennungen aus der Stammdateneinspielung."""

    def __init__(self, welt, m, daten, name, preis=450):
        self.welt, self.m, self.daten, self.name, self.preis = welt, m, daten, name, preis
        self.id = str(m.id)
        self.admin, self.planer, self.dispo = m.admin_token, m.planer_token, m.dispo_token
        self.fahrer_subjects = [b["subject"] for b in daten["benutzer"] if b["rolle"] == "driver"]
        self.leitstelle = welt.idp.token(next(b["subject"] for b in daten["benutzer"] if b["rolle"] == "dispatcher"), roles=("dispatcher",))   # Disponent der Stammdateneinspielung
        with welt.admin() as c:
            self.service = str(c.execute("SELECT id FROM taktovia.service WHERE tenant_id=%s", (self.id,)).fetchone()[0])
            self.fahrzeuge = [str(r[0]) for r in c.execute("SELECT id FROM taktovia.vehicle WHERE tenant_id=%s ORDER BY vehicle_label", (self.id,)).fetchall()]
            self.fahrer = [str(r[0]) for r in c.execute("SELECT id FROM taktovia.driver WHERE tenant_id=%s ORDER BY id", (self.id,)).fetchall()]
        self._fahrgaeste = 0

    def token(self, subject, rolle):
        return self.welt.idp.token(subject, roles=(rolle,))

    def neuer_fahrgast(self):
        """Fahrgastkonto im Mandanten (Mitgliedschaft per API) samt Fahrgastprofil-Zeile des Identitätsdienstes (Testaufbau: Registrierung läuft im Identitätsdienst)."""
        subject, token = self.welt.grant(self.m, "passenger")
        with self.welt.aufbau() as c:
            c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until,idp_issuer,idp_subject,status) VALUES (%s,%s,%s, now() + interval '5 years',%s,%s,'ACTIVE')",
                      (self.id, uuid.uuid4(), "tok-" + uuid.uuid4().hex, self.welt.idp.issuer, str(subject)))
        return Fahrgast(self, subject, token)


class Fahrgast:
    def __init__(self, mandant, subject, token):
        self.mandant, self.subject, self.token = mandant, subject, token

    def anfrage_koerper(self, personen=1, tage=3, stunde=10, ziel=(51.0801, 13.7801)):
        wunsch = (datetime.now(ZoneInfo("Europe/Berlin")) + timedelta(days=tage)).replace(hour=stunde, minute=0, second=0, microsecond=0)
        return {"service_id": self.mandant.service, "origin": {"point": {"latitude": 51.0501, "longitude": 13.7401}}, "destination": {"point": {"latitude": ziel[0], "longitude": ziel[1]}},
                "time_kind": "DEPARTURE", "desired": {"desired_at": wunsch.isoformat(), "time_zone": "Europe/Berlin"}, "passengers": personen}


class Referenzwelt:
    """Anwendung + Test-Issuer + PSP-Simulator + Mandanten. `starten()` baut alles auf, `stoppen()` räumt auf (Datenbank, Prozesse, Secrets)."""

    def __init__(self, dbname=E2E_DB, mit_psp=True, mandanten=(("Referenz A", 450), ("Referenz B", 450), ("Referenz Kostenfrei", 0), ("Referenz Kennzahlen", 450)), app_args=()):
        """`mandanten`: (Name, Festpreis je Person in Cent); Preis 0 ergibt die Variante ohne Zahlung (tariflich kostenfrei, sofort `CONFIRMED`)."""
        self.dbname, self.mit_psp, self.mandanten_namen, self.app_args = dbname, mit_psp, mandanten, tuple(app_args)
        self.mandanten: list[Mandant] = []
        self.welt: Welt | None = None
        self.sim = self.sim_app = None
        self.tmp = tempfile.TemporaryDirectory(prefix="e2e-welt-")
        self.tenant_datei = Path(self.tmp.name) / "tenants.txt"
        self.relay_datei = Path(self.tmp.name) / "relay-tenants.txt"       # Mandanten der Ereigniszustellung (leer = Zustellweg unterbrochen)

    # ------------------------------------------------------------------------------------------------------------------ Aufbau
    def starten(self):
        if not jar_vorhanden():
            raise FileNotFoundError(f"{JAR} fehlt: cd backend && ./gradlew :app:bootJar")
        atdb.reset_database(self.dbname)
        self.welt = Welt(self.dbname)
        self.welt.aufbau = self.aufbau                        # Phase 1: Anwendung ohne Zahlungsanbindung, Mandanten und Stammdaten über die API
        for i, (name, preis) in enumerate(self.mandanten_namen):
            m = self.welt.mandant(f"{name} {uuid.uuid4().hex[:6]}")
            d = referenz.datensatz(m.id, self.welt.idp.issuer, "ABCDEFGH"[i % 8])
            d["tarife"][0]["rules"][0]["amount_minor"] = preis
            d["benutzer"][0]["dienste"] = ["d-rufbus"]      # Disponent mit Dienstscope (Leitstellensicht)
            for n in (4, 5):                                  # zwei weitere Fahrzeuge (acht Sitze, ein Rollstuhlplatz): Szenarien mit gestörtem Dienststatus brauchen ein eigenes Fahrzeug
                d["fahrzeuge"].append({**d["fahrzeuge"][0], "key": f"f-10{n}", "kennzeichen": d["fahrzeuge"][0]["kennzeichen"].replace("101", f"10{n}")})
            pfad = referenz.schreiben(d, Path(self.tmp.name) / f"quelle-{m.id}.json")
            prot = einspielung.einspielen(pfad, self.welt.app.base_url, m.zugaenge(), modus="lauf", zustand_pfad=str(Path(self.tmp.name) / f"z-{m.id}.json"),
                                          protokoll_pfad=str(Path(self.tmp.name) / f"p-{m.id}.json"), build="e2e-lokal", migrationsstand="aktuell", freigeber="e2e-freigeber")
            if prot["lauf"]["ergebnis"] != "ABGESCHLOSSEN":
                raise RuntimeError(f"Stammdateneinspielung {name}: {prot['fehlerliste']}")
            self.mandanten.append(Mandant(self.welt, m, d, name, preis))
        self.tenant_datei.write_text("\n".join(m.id for m in self.mandanten) + "\n")
        self.relay_an()
        self.neu_starten()
        return self

    def sim_starten(self):
        import psp_sim
        self.sim = psp_sim.PspSimulator(4711, "2026-11-01T10:00:00Z")
        self.sim_app = psp_sim.build_app(self.sim)
        self.sim_app.start()

    def neu_starten(self, extra=(), nur_app=False):
        """Phase 2 (und jeder Neustart): Anwendung mit Zahlungsanbindung, Zeitgebern und Mandantenliste des Betriebs. Zustand liegt in der Datenbank."""
        w = self.welt
        w.app.close()
        args = list(self.app_args) + list(extra)
        if self.mit_psp:
            if self.sim is None:
                self.sim_starten()
            sec = Path(self.tmp.name) / "secrets" / "taktovia-local-secrets"
            sec.mkdir(parents=True, exist_ok=True)
            (sec / "db_runtime").write_text("taktovia_runtime\n" + os.environ["TAKTOVIA_DB_RUNTIME_PASSWORD"])
            (sec / "psp_webhook").write_text(self.sim.webhook_secret)
            args += [f"--taktovia.secrets.dir={Path(self.tmp.name) / 'secrets'}", "--taktovia.secrets.domain=taktovia-local-secrets",
                     "--taktovia.secrets.db-credential=secret://taktovia-local-secrets/db_runtime",
                     f"--taktovia.psp.base-url={self.sim_app.url}", "--taktovia.psp.provider=simpsp", "--taktovia.psp.timeout-seconds=4",
                     "--taktovia.operations.worker-enabled=true", "--taktovia.operations.poll-seconds=1", "--taktovia.operations.reconcile-delay-seconds=1", "--taktovia.payments.settlement-poll-seconds=2",
                     "--taktovia.payments.coupling-enabled=true", "--taktovia.payments.coupling-poll-seconds=1", f"--taktovia.workers.tenant-file={self.tenant_datei}",
                     "--taktovia.events.local-loopback=true", f"--taktovia.events.loopback-tenant-file={self.relay_datei}", "--taktovia.operations.lease-seconds=8", "--spring.datasource.hikari.connection-timeout=3000", "--taktovia.idempotency.lease-seconds=15", "--taktovia.booking.hold-expiry.enabled=true", "--taktovia.booking.hold-expiry.poll-seconds=1"]
            for i, m in enumerate(self.mandanten):
                args += [f"--taktovia.payments.providers.simpsp[{i}].tenant={m.id}", f"--taktovia.payments.providers.simpsp[{i}].secret-ref=secret://taktovia-local-secrets/psp_webhook"]
        w.app = AppProcess(self.dbname, w.idp, extra_args=args)
        w.client = ApiClient(w.app.base_url)
        if self.mit_psp:
            self.sim.config["webhook_url"] = f"{w.app.base_url}/v1/payments/provider-events/simpsp"
        return w.app

    def relay_an(self):
        self.relay_datei.write_text("\n".join(m.id for m in self.mandanten) + "\n")

    def relay_aus(self):
        self.relay_datei.write_text("# Zustellweg unterbrochen (Fault-Injection)\n")

    @property
    def client(self):
        return self.welt.client

    @property
    def base_url(self):
        return self.welt.app.base_url

    def admin(self):
        return self.welt.admin()

    def aufbau(self):
        """Administratorverbindung des Testaufbaus (für den Wächter als `e2e-testaufbau` erkennbar)."""
        c = self.welt.admin()
        c.execute("SET application_name = 'e2e-testaufbau'")
        return c

    def stoppen(self):
        for stop in (lambda: self.welt and self.welt.close(), lambda: self.sim_app and self.sim_app.stop(), lambda: self.tmp.cleanup(), lambda: atdb.drop_database(self.dbname)):
            try:
                stop()
            except Exception:
                pass
