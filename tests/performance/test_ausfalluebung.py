"""WP-M5-15 / LH-6.19-NFR-007: Ausfallübung mit RPO-/RTO-Messung und PSP-Abgleich.

AUSGEFÜHRT (echt): privater PostgreSQL-Cluster mit dem Schema des Repositorys; Last unter der Runtime-Rolle (RLS) mit Buchungen/Kapazitätsholds, Zahlungen gegen den PSP-Simulator, Löschvorgängen und Fahrzeugsperren;
WAL-Archivierung mit `archive_timeout = 60s` (Produktionswert) und verschlüsseltem Archiv; harter Ausfall (Prozess beendet, Datenverzeichnis verworfen, nicht archiviertes WAL verloren); Wiederherstellung NUR aus Basis-
sicherung und archiviertem WAL (`pitr.restore_pitr`); Drehbuch-Schranken, Konsistenzprüfungen, Lösch-/Sperrjournal, Isolationsstichprobe auf einer Kopie, Abgleichbericht gegen den PSP-Simulator; verspätete Anbieter-
ereignisse. Gemessen werden echte Uhrzeiten (UTC).
ERSATZ / NICHT AUSGEFÜHRT: PSP = Simulator (D-03 offen); Ereignisstrom = Datei statt Broker; Backupspeicher = lokales Verzeichnis; kein Standby über Hosts; Fachlogik der Last per SQL (nicht die Kotlin-Anwendung);
die End-to-End-Probe ist ohne `TAKTOVIA_PERF_RUN=1` eine Datenbank-Probe (im Protokoll als ERSATZ gekennzeichnet), mit `TAKTOVIA_PERF_RUN=1` laufen die Proben aus WP-M5-05 gegen die Anwendung auf der wiederherge-
stellten Datenbank (JWT-Test-Issuer statt Keycloak); Ausführung durch eine Person, die das Drehbuch nicht geschrieben hat; Übertragbarkeit auf die Produktionsumgebung (Hosting D-05, OP-21).
Mit TAKTOVIA_PROTOCOL_OUT=<Verzeichnis> wird das Protokoll der Übung dorthin geschrieben."""
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import uuid
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
for p in (REPO / "infra" / "backup", REPO / "db" / "tests", HERE, REPO / "tests" / "sandbox" / "psp"):
    sys.path.insert(0, str(p))

REASON = None
try:
    import psycopg  # noqa: F401
    from taktovia_backup import consistency, drehbuch, pitr
    from ausfalluebung import abgleich as AB
    from ausfalluebung import uebung as UE
    from ausfalluebung.last import seed_mandant
    from ausfalluebung.umgebung import DB, Uebung, UebungsCluster, free_port
    from psp_sim import PspSimulator
    pitr.pg_bindir()
    pitr.wal_python(pitr.default_run_as())
    if os.geteuid() == 0:
        import pwd
        pwd.getpwnam("postgres")
    if not os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD"):
        REASON = "Datenbank-Zugangsdaten nicht gesetzt (Administratorpasswort für db.py im Übungscluster)"
except Exception as e:  # noqa: BLE001
    REASON = f"Voraussetzung fehlt: {type(e).__name__}: {e}"
if REASON and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Ausfallübung erforderlich (TAKTOVIA_REQUIRE_DB=true): " + REASON)

_CACHE: dict = {}


def mit_anwendung() -> bool:
    if os.environ.get("TAKTOVIA_PERF_RUN") != "1":
        return False
    from refload.appproc import available
    sys.path.insert(0, str(HERE))
    return available() is None


def uebung_ergebnis():
    if "res" not in _CACHE:
        _CACHE["res"] = UE.fuehre_aus(mit_anwendung=mit_anwendung(), nachlauf_s=8.0)
        out = os.environ.get("TAKTOVIA_PROTOCOL_OUT")
        if out:
            r = _CACHE["res"]
            Path(out).mkdir(parents=True, exist_ok=True)
            doc = {k: v for k, v in r.items() if not k.startswith("_") and k != "abgleich"}
            doc["abgleichbericht"] = r["abgleich"]
            doc["abgleichbericht"]["vorgaenge_gekuerzt"] = f"{len(r['abgleich']['vorgaenge'])} Vorgänge; je Ergebnis die ersten 3 im Protokoll"
            beispiele, seen = [], {}
            for v in r["abgleich"]["vorgaenge"]:
                if seen.get(v["ergebnis"], 0) < 3:
                    beispiele.append(v); seen[v["ergebnis"]] = seen.get(v["ergebnis"], 0) + 1
            doc["abgleichbericht"]["vorgaenge"] = beispiele
            (Path(out) / "ausfalluebung-m5-15.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False, sort_keys=True, default=str), encoding="utf-8")
    return _CACHE["res"]


def tearDownModule():
    for r in _CACHE.values():
        try:
            UE.aufraeumen(r)
        except Exception:  # noqa: BLE001
            pass


@unittest.skipIf(REASON is not None, REASON or "")
class Ausfalluebung(unittest.TestCase):
    # --- positiv ---------------------------------------------------------------------------------------------------------------
    def test_rpo_rto_in_zielzeit_mit_zeitstempeln(self):
        r = uebung_ergebnis()
        p = r["drehbuch"]
        self.assertEqual(p["status"], "ABGESCHLOSSEN")
        zeiten = [datetime.fromisoformat(s["zeitpunkt"]) for s in p["schritte"]]
        self.assertEqual(len(zeiten), 8); self.assertTrue(all(zeiten), "jeder Schritt trägt einen Zeitstempel")
        self.assertEqual(zeiten, sorted(zeiten), "Schritte in der Reihenfolge des Drehbuchs")
        t_aus, t_commit, t_probe = (datetime.fromisoformat(x) for x in (p["ausfallzeitpunkt"], p["letzter_wiederherstellbarer_commit"]["zeitpunkt"], p["zeitpunkt_e2e_probe"]))
        self.assertLess(t_commit, t_aus); self.assertLess(t_aus, t_probe)
        rpo_min, rto_h = (t_aus - t_commit).total_seconds() / 60.0, (t_probe - t_aus).total_seconds() / 3600.0
        b = p["bewertung"]
        self.assertAlmostEqual(b["rpo_min_gemessen"], rpo_min, places=2); self.assertAlmostEqual(b["rto_h_gemessen"], rto_h, places=3)
        self.assertLessEqual(rpo_min, 15.0, "gemessenes RPO"); self.assertLessEqual(rto_h, 2.0, "gemessenes RTO")
        self.assertTrue(b["rpo_erfuellt"] and b["rto_erfuellt"]); self.assertTrue(p["referenzziele"]["provisional"])
        # Das Verlustfenster ist nicht leer: ohne Verlust wäre die RPO-Messung bedeutungslos
        self.assertGreater(r["nachweise"]["verlustfenster"]["bestaetigte_commits_nach_rpo_punkt"], 0)
        self.assertGreater(rpo_min, 0.0)
        # Abnahme 2: die tatsächlich im Betrieb verfügbaren Mittel, keine Abkürzung
        q, ziel = r["_uebung"].cluster, r["_ziel"]
        self.assertFalse(q.datadir.exists(), "Datenverzeichnis der Primärdatenbank ist verworfen (Plattenverlust): kein Zugriff auf nicht archiviertes WAL")
        steps = [s["step"] for s in r["restore"]["steps"]]
        self.assertEqual(steps, ["manifest_verified", "environment_checked", "wal_chain_verified", "base_extracted", "recovery_configured", "recovered_and_promoted"])
        self.assertGreaterEqual(r["restore"]["wal_chain"]["checked"], 2); self.assertTrue(r["restore"]["wal_chain"]["ok"])
        self.assertEqual({k: r["archivierung"][k] for k in ("archive_mode", "archive_timeout")}, {"archive_mode": "on", "archive_timeout": "1min"}, "Archivierung wie in infra/backup/postgresql-archive.conf (60 s)")
        self.assertTrue(r["archivierung"]["archive_command_nutzt_betriebswerkzeug"])
        self.assertFalse((ziel.datadir / "standby.signal").exists())
        # Schranken des Drehbuchs: Konsistenz und Abgleich bestanden, Freigabe erteilt
        self.assertTrue(p["konsistenz"]["release_allowed"]); self.assertEqual(p["konsistenz"]["violations"], [])
        self.assertTrue(r["abgleich"]["abgleich_abgeschlossen"])
        # Abnahme 4 und 6
        n = r["nachweise"]
        self.assertEqual(n["loeschungen_sperren"]["wiederauferstanden"], 0); self.assertEqual(n["loeschungen_sperren"]["sperren_fehlen_oder_aufgehoben"], 0)
        self.assertGreater(r["journal"]["neu_angewendet"], 0, "Löschungen im Verlustfenster wurden aus dem Journal wieder angewendet (ohne Journal wären sie wiederauferstanden)")
        self.assertTrue(r["isolation"]["ok"], r["isolation"]); self.assertEqual(r["isolation"]["runtime_rolle"], {"superuser": False, "bypassrls": False, "tabelleneigentum": 0})
        self.assertTrue(r["e2e_probe"]["ok"], r["e2e_probe"])
        self.assertEqual(r["konsistenz_nach_abgleich"], [])

    def test_psp_abgleich_nach_restore_keine_zweite_belastung(self):
        r = uebung_ergebnis()
        n, a = r["nachweise"], r["abgleich"]
        an = n["anbieter"]
        self.assertGreater(an["referenzen"], 0)
        self.assertEqual((an["mit_mehr_als_einer_autorisierung"], an["mit_mehr_als_einem_einzug"]), (0, 0), "je Händlerreferenz höchstens eine Wirkung beim Anbieter")
        self.assertEqual(an["schreibende_aufrufe_nach_dem_ausfall"], 0, "der Abgleich löst keine Autorisierung, keinen Einzug, keine Stornierung und keine Erstattung aus")
        self.assertGreater(an["statusabfragen_nach_dem_ausfall"], 0, "abgeglichen wird über Statusabfragen mit der Händlerreferenz")
        self.assertEqual(n["zahlungen_beim_anbieter_erfasst_aber_lokal_abweichend"], 0, n.get("abweichende_zahlungen"))
        self.assertEqual(n["kapazitaet"]["buchungen_ohne_verbrauchten_hold"], 0, "keine Buchung ohne Kapazität")
        # Bericht: Zahl der betroffenen Vorgänge und Ergebnis je Vorgang
        self.assertEqual(a["anzahl_vorgaenge"], len(a["vorgaenge"])); self.assertEqual(sum(a["je_ergebnis"].values()), a["anzahl_vorgaenge"])
        self.assertTrue(all(v["ergebnis"] and v["typ"] and v["id"] for v in a["vorgaenge"]))
        self.assertGreater(a["betroffen_nicht_konsistent"], 0, "im Verlustfenster sind Vorgänge betroffen")
        self.assertIn(AB.LOKAL_VERLOREN, a["je_ergebnis"], "beim Anbieter vollzogene, lokal verlorene Zahlungen werden ausgewiesen, nicht neu belastet und nicht stillschweigend verworfen")
        self.assertIn(AB.BUCHUNG_VERLOREN_MIT_ZAHLUNG, a["je_ergebnis"], "Kunden mit verlorener Buchung und vorhandener Zahlung werden nicht als ungebucht markiert, bevor geklärt ist")
        self.assertEqual(a["blockierend"], [])
        # verspätete Anbieterereignisse nach dem Wiederanlauf: genau eine Wirkung je Vorgang
        v = n["verspaetete_ereignisse"]
        self.assertEqual((v["zusaetzliche_wirkungen"], v["zahlungen_mit_mehr_als_einem_ereignis"], v["nachher"] - v["captured_ereignisse_vorher"]), (0, 0, 0))

    # --- negativ ---------------------------------------------------------------------------------------------------------------
    def test_zusaetzlich_ausgefallenes_messaging_sperrt_die_freigabe_bis_der_strom_zurueck_ist(self):
        if "messaging" not in _CACHE:
            _CACHE["messaging"] = UE.fuehre_aus(mit_anwendung=False, nachlauf_s=4.0, messaging_ausfall=True)
        r = _CACHE["messaging"]
        self.assertTrue(r["freigabe_gesperrt_bei_abgleich"], "ohne Ereignisstrom ist das Verlustfenster nicht bestimmbar: der Abgleich gilt nicht als abgeschlossen, die Freigabe bleibt gesperrt")
        p = r["drehbuch"]
        self.assertEqual(p["status"], "ABGESCHLOSSEN", "nach Rückkehr des Messagings wurde der Abgleich wiederholt und die Freigabe erteilt")
        self.assertTrue(r["abgleich"]["abgleich_abgeschlossen"]); self.assertGreater(r["abgleich"]["anzahl_vorgaenge"], 0)
        self.assertEqual(r["nachweise"]["loeschungen_sperren"]["sperren_fehlen_oder_aufgehoben"], 0)
        self.assertEqual(r["nachweise"]["anbieter"]["schreibende_aufrufe_nach_dem_ausfall"], 0)
        self.assertTrue(p["bewertung"]["rpo_erfuellt"] and p["bewertung"]["rto_erfuellt"])

    def test_restore_auf_einen_zeitpunkt_mitten_in_einer_buchungstransaktion(self):
        base = Path(tempfile.mkdtemp(prefix="tkmid-"))
        u = Uebung(base)
        try:
            q = u.quelle_aufbauen()
            adm = q.connect(DB)
            m = seed_mandant(adm, "Mitten-Mandant", journeys=3, fahrzeuge_fuer_sperren=1)
            t, run, svc = m["tenant"], m["run"], m["service"]
            manifest = u.basissicherung()
            # (1) zwei getrennte Commits: Hold, danach Bestätigung; Zielzeitpunkt zwischen ihnen
            h1, b1 = uuid.uuid4(), uuid.uuid4()
            with q.runtime_connect() as c:
                c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(t),))
                c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (%s,%s,%s,1,%s,'{\"seats\":1}'::jsonb,0,1,now()+interval '1 hour','HELD')", (t, h1, run, f"fh-{h1}"))
                c.commit()
            time.sleep(0.4)
            zwischen = adm.execute("SELECT clock_timestamp()").fetchone()[0]
            time.sleep(0.4)
            with q.runtime_connect() as c:
                c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(t),))
                c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE tenant_id=%s AND id=%s", (t, h1))
                c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}'::jsonb,1)", (t, b1, h1, svc, f"fb-{b1}"))
                c.commit()
            # (2) EINE Transaktion (Hold, Bestätigung) über mehr als eine Sekunde; Zielzeitpunkt mitten darin
            h2, b2 = uuid.uuid4(), uuid.uuid4()
            with q.runtime_connect() as c:
                c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(t),))
                c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (%s,%s,%s,1,%s,'{\"seats\":1}'::jsonb,0,1,now()+interval '1 hour','HELD')", (t, h2, run, f"fh-{h2}"))
                mitten = adm.execute("SELECT clock_timestamp()").fetchone()[0] + timedelta(milliseconds=300)
                time.sleep(1.0)
                c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE tenant_id=%s AND id=%s", (t, h2))
                c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}'::jsonb,1)", (t, b2, h2, svc, f"fb-{b2}"))
                c.commit()
            adm.execute("SELECT pg_switch_wal()")
            u.warte_archiv(timeout=60)
            adm.close()
            u.ausfall()

            def restore_zu(zeit, name):
                z = UebungsCluster(base / name, free_port(), base / f"sock-{name}")
                u.clusters.append(z)
                pitr.restore_pitr(u.rstore, u.key, manifest["backup_id"], z, u.umgebung, target_time=zeit.isoformat(), archive_store_dir=u.store_dir, key_file=u.key_file)
                with z.connect(DB) as c:
                    st = {"h1": c.execute("SELECT state FROM taktovia.capacity_hold WHERE id=%s", (h1,)).fetchone(), "b1": c.execute("SELECT state FROM taktovia.booking WHERE id=%s", (b1,)).fetchone(),
                          "h2": c.execute("SELECT state FROM taktovia.capacity_hold WHERE id=%s", (h2,)).fetchone(), "b2": c.execute("SELECT state FROM taktovia.booking WHERE id=%s", (b2,)).fetchone()}
                    rep = consistency.run_all(c, [], REPO / "db" / "migrations")
                return st, rep
            st1, rep1 = restore_zu(zwischen, "zwischen")
            self.assertEqual((st1["h1"], st1["b1"]), (("HELD",), None), "zwischen zwei Commits: Hold gehalten, Buchung noch nicht bestätigt (kein halber Zustand)")
            self.assertEqual((st1["h2"], st1["b2"]), (None, None))
            self.assertNotIn("kapazitaet_nicht_ueberbucht", rep1.as_dict()["violations"]); self.assertNotIn("keine_buchung_ohne_kapazitaet", rep1.as_dict()["violations"])
            st2, rep2 = restore_zu(mitten, "mitten")
            self.assertEqual((st2["h1"], st2["b1"]), (("CONSUMED",), ("CONFIRMED",)), "frühere Buchung vollständig")
            self.assertEqual((st2["h2"], st2["b2"]), (None, None), "mitten in der Transaktion: weder Hold noch Buchung (Atomarität), kein Halbzustand")
            self.assertEqual([v for v in rep2.as_dict()["violations"] if v != "journal_wirksam"], [], "Konsistenzprüfungen ohne blockierenden Befund (das Journal wird in dieser Prüfung nicht übergeben)")
        finally:
            u.aufraeumen()

    # --- konkurrierend (Geld) -------------------------------------------------------------------------------------------------------
    def test_abgleich_parallel_zu_psp_webhook_genau_eine_wirkung(self):
        """Abgleichläufe und Anbieter-Webhooks (verspätet) laufen gleichzeitig gegen dieselbe Zahlung: genau eine Zustandswirkung, ein Ereignis, ein Einzug beim Anbieter. Echte parallele Transaktionen
        unter der Runtime-Rolle gegen die Datenbank des Laufs (nicht gegen den Übungscluster, kein Restore nötig)."""
        from helpers import admin as dbadmin, runtime as dbruntime
        sim = PspSimulator(seed=7)
        with dbadmin() as adm:
            m = seed_mandant(adm, "Webhook-Mandant", journeys=5, fahrzeuge_fuer_sperren=1)
        t = m["tenant"]
        refs = []
        for i, journey in enumerate(m["journeys"][:3]):
            mref = f"w-{str(t)[:8]}-{i:05d}"
            resp, _ = sim.execute("authorize", "", {"merchant_ref": mref, "amount_minor": 950, "currency": "EUR", "payment_method": "pm_ok"}, f"a-{mref}")
            pay = resp.body
            with dbadmin() as adm:   # lokal nur autorisiert (der Einzug beim Anbieter ist weiter)
                adm.execute("INSERT INTO taktovia.payment_intent(tenant_id,id,journey_id,provider,state,currency,authorized_minor,merchant_ref,requested_minor,service_id) VALUES (%s,%s,%s,'simpsp','CREATED','EUR',950,%s,950,%s)",
                            (t, uuid.uuid4(), journey, mref, m["service"]))
                adm.execute("UPDATE taktovia.payment_intent SET state='AUTHORIZED', provider_ref=%s, psp_payment_id=%s, revision=revision+1 WHERE tenant_id=%s AND merchant_ref=%s", (pay["provider_ref"], pay["payment_id"], t, mref))
            resp, _ = sim.execute("capture", pay["payment_id"], {"amount_minor": 950}, f"c-{mref}")
            self.assertEqual(resp.status, 200)
            refs.append(mref)
        psp = AB.SimPsp(sim)
        barrier = threading.Barrier(18)
        wirkung = []

        def webhook(mref):
            barrier.wait(30)
            c = dbruntime()
            try:
                if AB.adopt_provider_state(c, t, mref, psp.status(mref)):
                    wirkung.append(("webhook", mref))
                c.commit()
            finally:
                c.close()

        def sweep():
            barrier.wait(30)
            with dbadmin() as adm:
                b = AB.abgleichbericht(adm, dbruntime, psp, [{"zeitpunkt": datetime.now().astimezone().isoformat(), "typ": "payment.authorized", "tenant": str(t), "merchant_ref": r} for r in refs],
                                       datetime.now().astimezone() - timedelta(minutes=5), datetime.now().astimezone())
            wirkung.extend(("abgleich", v["id"]) for v in b["vorgaenge"] if v["ergebnis"] == AB.UEBERNOMMEN)
        ts = [threading.Thread(target=webhook, args=(r,)) for r in refs for _ in range(3)] + [threading.Thread(target=sweep) for _ in range(9)]
        [x.start() for x in ts]; [x.join(120) for x in ts]
        self.assertFalse(any(x.is_alive() for x in ts))
        with dbadmin() as adm:
            for mref in refs:
                self.assertEqual(adm.execute("SELECT state, captured_minor FROM taktovia.payment_intent WHERE tenant_id=%s AND merchant_ref=%s", (t, mref)).fetchone(), ("CAPTURED", 950))
                pid = adm.execute("SELECT id FROM taktovia.payment_intent WHERE tenant_id=%s AND merchant_ref=%s", (t, mref)).fetchone()[0]
                self.assertEqual(adm.execute("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=%s AND aggregate_id=%s AND event_type='payment.captured'", (t, pid)).fetchone()[0], 1, "genau ein Ereignis je Vorgang")
                self.assertEqual(sum(1 for w in wirkung if w[1] == mref), 1, f"genau eine Wirkung je Vorgang: {[w for w in wirkung if w[1] == mref]}")
        led = UE.ledger(sim)
        self.assertEqual({k: v for k, v in led.items() if k in refs}, {r: {"authorize": 1, "capture": 1} for r in refs}, "beim Anbieter: eine Autorisierung und ein Einzug je Referenz, keine zweite Belastung")


if __name__ == "__main__":
    unittest.main()
