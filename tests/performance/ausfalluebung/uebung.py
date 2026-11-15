"""Durchführung der Ausfallübung (WP-M5-15) nach dem Wiederanlaufdrehbuch (tests/performance/wiederanlauf-drehbuch-m5.md) mit Messung von RPO und RTO.

Ablauf: Basissicherung -> Last (Runtime-Rolle, RLS) auf dem Quellcluster -> Ausfall der Primärdatenbank (Prozess hart beendet, Datenverzeichnis verworfen: nicht archiviertes WAL ist verloren) ->
Wiederherstellung **nur aus Basissicherung und archiviertem WAL** (`pitr.restore_pitr`, keine Abkürzung über das Datenverzeichnis der Quelle) -> Struktur-, Rollen- und Isolationsprüfung ->
Lösch-/Sperrjournal wieder einspielen, Konsistenzprüfungen -> Abgleich der Zahlungen mit dem Anbieter über Händlerreferenzen (Abgleichbericht) -> Freigabe -> End-to-End-Probe (synthetische
Buchung aus WP-M5-05 gegen die Anwendung auf der wiederhergestellten Datenbank) -> verspätete Anbieterereignisse. Zeiten sind echte Uhrzeiten (UTC) der ausführenden Maschine.

Ersatz und Grenzen: siehe `umgebung.py` (Ereignisstrom, Backupspeicher, kein Standby) und `last.py` (Fachlogik per SQL). Die Übung läuft auf EINER Maschine; sie belegt das Verfahren und die Zielzeiten
für diese Datenmenge und Hardware, nicht für die Produktionsumgebung (Hostingentscheid D-05, OP-21)."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from . import abgleich as AB
from .last import Last, seed_mandant
from .umgebung import ARCHIVE_TIMEOUT_S, DB, REPO, JournalExport, Uebung, UebungsCluster, free_port, jetzt

from taktovia_backup import consistency, dbops, drehbuch, pitr  # noqa: E402

sys.path.insert(0, str(REPO / "tests" / "sandbox" / "psp"))
from psp_sim import PspSimulator  # noqa: E402


def isolation_stichprobe(u: Uebung, ziel: UebungsCluster) -> dict:
    """Stichprobe der Isolationssuite (db/tests: RLS je Tabelle, kein Kontext keine Zeile) auf einer KOPIE der wiederhergestellten Datenbank (Vorlagenkopie), damit die Testmandanten den
    wiederhergestellten Bestand nicht verändern; dazu die Eigenschaften der Runtime-Rolle."""
    kopie = "isolation_kopie"
    with ziel.connect("postgres") as c:
        c.execute(f'DROP DATABASE IF EXISTS "{kopie}" WITH (FORCE)')
        c.execute(f'CREATE DATABASE "{kopie}" TEMPLATE "{DB}"')
    env = {**u.env(ziel), "TAKTOVIA_DB_NAME": kopie, "TAKTOVIA_DB_ADMIN_PASSWORD": os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD", "x")}
    p = subprocess.run([sys.executable, "-m", "unittest", "test_03_rls", "test_isolation_alle_tabellen"], cwd=REPO / "db" / "tests", env=env, capture_output=True, text=True, timeout=900)
    summary = [l for l in (p.stderr or "").strip().splitlines() if l.startswith(("Ran", "OK", "FAILED"))]
    with ziel.connect("postgres") as c:
        c.execute(f'DROP DATABASE IF EXISTS "{kopie}" WITH (FORCE)')
    with ziel.connect(DB) as c:
        rolle = c.execute("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname='taktovia_runtime'").fetchone()
        eigentum = c.execute("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles o ON o.oid=c.relowner WHERE n.nspname IN ('taktovia','taktovia_meta') AND o.rolname='taktovia_runtime'").fetchone()[0]
        ohne_rls = c.execute("SELECT count(*) FROM information_schema.columns c JOIN pg_class t ON t.relname=c.table_name AND t.relnamespace='taktovia'::regnamespace WHERE c.table_schema='taktovia' AND c.column_name='tenant_id' AND t.relkind='r' AND NOT t.relforcerowsecurity").fetchone()[0]
    return {"ok": p.returncode == 0 and rolle == (False, False) and eigentum == 0 and ohne_rls == 0, "tests": "db/tests/test_03_rls.py, db/tests/test_isolation_alle_tabellen.py (auf Kopie der wiederhergestellten Datenbank)",
            "summary": summary, "runtime_rolle": {"superuser": rolle[0], "bypassrls": rolle[1], "tabelleneigentum": eigentum}, "tabellen_ohne_erzwungene_rls": ohne_rls}


def struktur_pruefen(u: Uebung, ziel: UebungsCluster):
    with ziel.connect(DB) as c:
        r = consistency.Report()
        consistency.check_migrations(c, r, REPO / "db" / "migrations")
        env = c.execute("SELECT environment FROM taktovia_meta.environment_marker").fetchone()[0]
    ok = not r.violations and env == u.umgebung
    return ok, f"Migrationsstand {'vollständig' if not r.violations else 'abweichend'}, Umgebungskennung {env}"


def baue_probewelt(u: Uebung, qcluster: UebungsCluster, app_log: Path, idp) -> dict:
    """Probemandant (WP-M5-05) samt Dienst, Tarif, Haltepunkten, Flotte und Konten über die API der Anwendung (JWT-Test-Issuer statt Keycloak); die Anwendung läuft nur für den Aufbau."""
    sys.path.insert(0, str(REPO / "tests" / "performance"))
    sys.path.insert(0, str(REPO / "db"))
    from refload import world as W
    from refload.appproc import App
    saved = {k: os.environ.get(k) for k in ("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_ADMIN_USER")}
    os.environ.update({"TAKTOVIA_DB_HOST": "127.0.0.1", "TAKTOVIA_DB_PORT": str(qcluster.port), "TAKTOVIA_DB_ADMIN_USER": qcluster.user})
    app = App(idp, db_name=DB, log=app_log)
    adm = psycopg_connect(qcluster)
    try:
        def create_synthetic(admin, name):
            tid = str(uuid.uuid4())
            admin.execute("INSERT INTO taktovia.tenant(id,name,status,synthetic) VALUES (%s,%s,'ACTIVE',true)", (tid, name))
            return tid
        W.create_tenant = create_synthetic
        app.start(300)
        w = W.build_tenant(app, idp, adm, "P", 1, 1, name="Probemandant (Ausfallübung)")
        op_sub = str(uuid.uuid4())
        W.grant(adm, w.tenant_id, idp.issuer, op_sub, "dispatcher")
        adm.execute("UPDATE taktovia.membership SET service_scope = ARRAY[%s]::uuid[] WHERE tenant_id=%s AND subject=%s", (w.service_id, w.tenant_id, op_sub))
        vehicle = adm.execute("SELECT vehicle_id FROM taktovia.vehicle_run WHERE id=%s", (w.runs[0],)).fetchone()[0]
        return {"world": w, "operator_sub": op_sub, "vehicle": str(vehicle), "idp_pw": "richtig", "W": W}
    finally:
        app.stop()
        adm.close()
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def psycopg_connect(cluster: UebungsCluster, db: str = DB):
    import psycopg
    return psycopg.connect(host=str(cluster.sockdir), port=cluster.port, user=cluster.user, dbname=db, autocommit=True)


def e2e_probe_anwendung(u: Uebung, ziel: UebungsCluster, probewelt: dict, app_log: Path, idp) -> dict:
    """End-to-End-Probe: die Proben aus WP-M5-05 (Kernbuchung und Operator) gegen die Anwendung auf der wiederhergestellten Datenbank."""
    sys.path.insert(0, str(REPO / "tests" / "performance"))
    sys.path.insert(0, str(REPO / "infra" / "availability"))
    sys.path.insert(0, str(REPO / "tests" / "performance" / "availability"))
    from refload.appproc import App
    from taktovia_availability import proben as P
    from test_probe_gegen_anwendung import TokenEndpunkt
    W, w = probewelt["W"], probewelt["world"]
    saved = {k: os.environ.get(k) for k in ("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_ADMIN_USER")}
    os.environ.update({"TAKTOVIA_DB_HOST": "127.0.0.1", "TAKTOVIA_DB_PORT": str(ziel.port), "TAKTOVIA_DB_ADMIN_USER": ziel.user})
    app = App(idp, db_name=DB, log=app_log)
    adm = psycopg_connect(ziel)
    try:
        app.start(300)
        tokens = TokenEndpunkt(idp, {"probe-fahrgast": w.passengers[0], "probe-operator": probewelt["operator_sub"]})

        def binde(angebot):
            run, seg = w.next_run_segment()
            W.bind_offer(adm, w.tenant_id, angebot["id"], run, seg)
        cfg = P.Konfiguration(base_url=app.base, token_url=tokens.base + "/token", referenz_url=tokens.base + "/ref", timeout_s=10.0,
                              zugang_kernbuchung={"grant_type": "password", "username": "probe-fahrgast", "password": "env:PROBE_PW"},
                              zugang_operator={"grant_type": "password", "username": "probe-operator", "password": "env:PROBE_PW"}, dienst_id=w.service_id,
                              probefahrzeug_id=probewelt["vehicle"], probemandant_id=w.tenant_id, angebot_body=W.offer_body(w.service_id, w.days, 1, 0), planbindung=binde)
        env = {"PROBE_PW": "richtig"}
        saetze = [P.probe_ausfuehren(b, cfg, P.urllib_transport, umgebung=env) for b in P.BEREICHE]
        return {"art": "Proben aus WP-M5-05 gegen die Anwendung auf der wiederhergestellten Datenbank (JWT-Test-Issuer statt Keycloak)", "ok": all(s["ergebnis"] == P.OK for s in saetze), "proben": saetze,
                "anwendung_start_s": round(getattr(app, "startup_s", 0.0), 1)}
    finally:
        app.stop()
        adm.close()
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def e2e_probe_datenbank(ziel: UebungsCluster, mandant: dict) -> dict:
    """Ersatz ohne Anwendung (gekennzeichnet): Schreib- und Lesezugriff unter der Runtime-Rolle mit Mandantenkontext, Buchung mit Kapazitätshold auf der wiederhergestellten Datenbank."""
    t = mandant["tenant"]
    c = ziel.runtime_connect()
    try:
        c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(t),))
        h, b = uuid.uuid4(), uuid.uuid4()
        c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (%s,%s,%s,1,%s,'{\"seats\":1}'::jsonb,0,1,now()+interval '1 hour','HELD')",
                  (t, h, mandant["run"], f"probe-{h}"))
        c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE tenant_id=%s AND id=%s", (t, h))
        c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMED','{}'::jsonb,1)", (t, b, h, mandant["service"], f"probe-b-{b}"))
        n = c.execute("SELECT count(*) FROM taktovia.booking WHERE id=%s", (b,)).fetchone()[0]
        c.commit()
        return {"art": "ERSATZ ohne Anwendung: Datenbank-Probe unter der Runtime-Rolle", "ok": n == 1}
    finally:
        c.close()


def ledger(sim) -> dict:
    """Wirkungen beim Anbieter je Händlerreferenz (Simulatorprotokoll): Autorisierung und Einzug höchstens einmal."""
    eff = {}
    for call in sim.calls:
        if call.get("effect_applied") and call.get("merchant_ref") and call["operation"] in ("authorize", "capture"):
            eff.setdefault(call["merchant_ref"], {"authorize": 0, "capture": 0})[call["operation"]] += 1
        elif call.get("effect_applied") and call["operation"] == "capture" and call.get("payment_id"):
            pid = call["payment_id"]
            mref = next((p["merchant_ref"] for p in sim.payments.values() if p["payment_id"] == pid), None)
            if mref:
                eff.setdefault(mref, {"authorize": 0, "capture": 0})["capture"] += 1
    return eff


def fuehre_aus(base: Path | None = None, mit_anwendung: bool = False, nachlauf_s: float = 8.0, mandanten: int = 2, messaging_ausfall: bool = False, log=print) -> dict:
    """Führt die komplette Übung aus und liefert das Ergebnis (Drehbuchprotokoll, Abgleichbericht, Prüfungen, Kriterien). Ressourcen werden vom Aufrufer über `ergebnis['_uebung']` freigegeben."""
    base = Path(base or tempfile.mkdtemp(prefix="tkuebung-"))
    u = Uebung(base)
    res: dict = {"_uebung": u, "hinweise": []}
    sim = PspSimulator(seed=15)
    t_start = jetzt()
    q = u.quelle_aufbauen()
    log(f"Quellcluster bereit ({(jetzt() - t_start).total_seconds():.0f} s)")
    adm = q.connect(DB)
    ms = [seed_mandant(adm, f"Übungsmandant {i + 1}") for i in range(mandanten)]
    adm.close()
    probewelt, idp = None, None
    if mit_anwendung:
        sys.path.insert(0, str(REPO / "tests" / "performance"))
        from refload.idp import TestIdp
        idp = TestIdp()                                       # derselbe Aussteller (URL und Schlüssel) vor und nach dem Ausfall: die Mitgliedschaften des Probemandanten nennen ihn unveränderlich
        res["_idp"] = idp
        probewelt = baue_probewelt(u, q, base / "app-aufbau.log", idp)
        log("Probemandant über die Anwendung angelegt")
    manifest = u.basissicherung()
    res["basissicherung"] = {"backup_id": manifest["backup_id"], "taken_at": manifest["taken_at"], "start_wal_segment": manifest["start_wal_segment"]}
    log(f"Basissicherung {manifest['backup_id']}")
    with q.connect() as c:
        res["archivierung"] = {k: c.execute(f"SHOW {k}").fetchone()[0] for k in ("archive_mode", "archive_timeout", "wal_level", "synchronous_commit")} | {
            "archive_command_nutzt_betriebswerkzeug": "taktovia_backup.wal archive" in c.execute("SHOW archive_command").fetchone()[0]}
    export = JournalExport(q, u.store, u.key)
    u.journal = export
    export.start()
    last = Last(u, ms, sim)
    last.start()
    start_archiv = u.warte_archiv()                       # mindestens ein Segmentwechsel unter Last: das Archiv steht, ein Teil der Last liegt danach
    log(f"WAL archiviert bis {start_archiv}; Nachlauf {nachlauf_s} s unter Last")
    time.sleep(nachlauf_s)
    # --- Ausfall ---------------------------------------------------------------------------------------------------------
    d = drehbuch.Drehbuch(u.umgebung, "Übungsautomat (WP-M5-15; Drehbuchausführung durch Dritte steht aus)")
    t_ausfall = u.ausfall()
    if messaging_ausfall:
        u.bus.verfuegbar = False                              # zusätzlich zur Datenbank fällt das Messaging aus (bleibt es bis zum Abgleich)
    d.ausfall(t_ausfall)
    log("Primärdatenbank ausgefallen")
    d.stoppen(lambda: (last.stop(), export.stop(), "Schreiber und Journalexport angehalten")[2])
    with last.lock:
        acks = list(last.acks)
    res["last"] = {"zaehler": dict(last.zaehler), "bestaetigte_commits": len(acks), "letzter_bestaetigter_commit": acks[-1][0].isoformat() if acks else None,
                   "fehler": sorted(set(last.fehler))[:8], "journalexport_fehler": sorted(set(export.fehler))[:3]}
    res["psp_vor_restore"] = {"zahlungen": len(sim.payments), "autorisierungen": sum(1 for p in sim.payments.values()), "erfasst": sum(1 for p in sim.payments.values() if p["state"] == "CAPTURED")}
    calls_vor = len(sim.calls)
    # --- Wiederherstellung ----------------------------------------------------------------------------------------------------
    ziel = UebungsCluster(u.base / "ziel", free_port(), u.base / "sock-ziel")
    u.clusters.append(ziel)
    u.ziel = ziel
    state = {}

    def restore():
        state["proto"] = pitr.restore_pitr(u.rstore, u.key, manifest["backup_id"], ziel, u.umgebung, archive_store_dir=u.store_dir, key_file=u.key_file)
        rp = state["proto"]["replay_timestamp"]
        state["rpo_punkt"] = datetime.fromisoformat(rp)
        return {"last_commit": state["rpo_punkt"], "referenz": manifest["backup_id"]}
    d.wiederherstellen(restore)
    res["restore"] = {k: state["proto"].get(k) for k in ("result", "duration_s", "replay_timestamp", "wal_chain", "steps")}
    log(f"Wiederhergestellt bis {state['rpo_punkt'].isoformat()} in {state['proto']['duration_s']} s")
    # --- Struktur, Rollen, Isolation ---------------------------------------------------------------------------------------------
    iso = {}

    def struktur():
        ok, detail = struktur_pruefen(u, ziel)
        iso.update(isolation_stichprobe(u, ziel))
        return ok and iso["ok"], detail + f"; Isolationsstichprobe {'bestanden' if iso['ok'] else 'FEHLGESCHLAGEN'}"
    d.struktur_pruefen(struktur)
    res["isolation"] = iso
    # --- Journal, Konsistenz ----------------------------------------------------------------------------------------------------
    entries = dbops.read_journal(u.rstore, u.key)
    with ziel.connect(DB) as c:
        replayed = c.execute("SELECT taktovia.replay_erasure_journal(%s::jsonb)", (json.dumps(entries),)).fetchone()[0] if entries else 0
        rep = consistency.run_all(c, entries, REPO / "db" / "migrations")
    res["journal"] = {"eintraege": len(entries), "neu_angewendet": replayed}
    d.konsistenz(rep)
    # --- Abgleich -----------------------------------------------------------------------------------------------------------------
    holder = {}

    def abgleich():
        adm2 = psycopg_connect(ziel)
        try:
            stream = u.bus.lesen() if u.bus.verfuegbar else None
            b = AB.abgleichbericht(adm2, ziel.runtime_connect, AB.SimPsp(sim), stream, state["rpo_punkt"], t_ausfall) if stream is not None else None
            if b is None:
                holder["bericht"] = {"abgleich_abgeschlossen": False, "grund": "Ereignisstrom (Messaging) nicht verfügbar: Verlustfenster nicht vollständig bestimmbar", "vorgaenge": []}
                return {"done": False, "by": "Übungsautomat", "vorgaenge": []}
            holder["bericht"] = b
            return {"done": b["abgleich_abgeschlossen"], "by": "Übungsautomat (Bestätigung durch die fachverantwortliche Person steht aus)", "vorgaenge": [{k: v[k] for k in ("typ", "id", "ergebnis")} for v in b["vorgaenge"]]}
        finally:
            adm2.close()
    try:
        d.abgleich(abgleich)
    except drehbuch.ReleaseBlocked:
        res["freigabe_gesperrt_bei_abgleich"] = True
        if messaging_ausfall:
            u.bus.verfuegbar = True                           # Messaging kehrt zurück, der Abgleich wird wiederholt (Schritt 6 ist wiederholbar)
            d.abgleich(abgleich)
        else:
            raise
    res["abgleich"] = holder["bericht"]
    with ziel.connect(DB) as c:
        res["konsistenz_nach_abgleich"] = consistency.run_all(c, entries, REPO / "db" / "migrations").as_dict()["violations"]
    d.freigeben()
    # --- End-to-End-Probe (RTO-Ende) ---------------------------------------------------------------------------------------------
    probe = {}

    def e2e():
        probe.update(e2e_probe_anwendung(u, ziel, probewelt, base / "app-restore.log", idp) if probewelt else e2e_probe_datenbank(ziel, ms[0]))
        return probe["ok"]
    d.e2e_probe(e2e)
    res["e2e_probe"] = probe
    res["drehbuch"] = d.p
    # --- danach: verspätete Anbieterereignisse, genau eine Wirkung je Vorgang ----------------------------------------------------------
    res["nachweise"] = nachpruefungen(u, ziel, sim, ms, acks, state["rpo_punkt"], calls_vor)
    res["_ziel"], res["_manifest"], res["_sim"] = ziel, manifest, sim
    return res


def nachpruefungen(u: Uebung, ziel: UebungsCluster, sim, ms, acks, rpo_punkt, calls_vor) -> dict:
    """Prüfungen gegen die wiederhergestellte Datenbank nach der Freigabe (unabhängig vom Abgleichwerkzeug, mit Administratorrechten)."""
    out = {}
    with psycopg_connect(ziel) as c:
        # (1) verspätete Anbieterereignisse: erneute Übernahme für ALLE Zahlungen ist wirkungslos; je Zahlung höchstens ein Ereignis payment.captured
        vor = c.execute("SELECT count(*) FROM taktovia.outbox WHERE event_type='payment.captured'").fetchone()[0]
        zahlungen = c.execute("SELECT tenant_id, merchant_ref FROM taktovia.payment_intent WHERE merchant_ref IS NOT NULL").fetchall()
        gewinner = []

        def webhook(t, m):
            conn = ziel.runtime_connect()
            try:
                r = AB.SimPsp(sim).status(m)
                if r and AB.adopt_provider_state(conn, t, m, r):
                    gewinner.append(m)
                conn.commit()
            finally:
                conn.close()
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=16) as pool:                  # je Zahlung zwei gleichzeitige Zustellungen (Webhook und Wiederholung)
            list(pool.map(lambda tm: webhook(*tm), [tm for tm in zahlungen for _ in range(2)]))
        nach = c.execute("SELECT count(*) FROM taktovia.outbox WHERE event_type='payment.captured'").fetchone()[0]
        doppelt = c.execute("SELECT count(*) FROM (SELECT aggregate_id FROM taktovia.outbox WHERE event_type='payment.captured' GROUP BY 1 HAVING count(*) > 1) d").fetchone()[0]
        out["verspaetete_ereignisse"] = {"zahlungen": len(zahlungen), "zusaetzliche_wirkungen": len(gewinner), "captured_ereignisse_vorher": vor, "nachher": nach, "zahlungen_mit_mehr_als_einem_ereignis": doppelt}
        # (2) Anbieter: je Händlerreferenz höchstens eine Autorisierung und ein Einzug; nach dem Ausfall keine neue Belastung
        led = ledger(sim)
        nach_ausfall = [x for x in sim.calls[calls_vor:] if x["operation"] in ("authorize", "capture", "void", "refund")]
        out["anbieter"] = {"referenzen": len(led), "mit_mehr_als_einer_autorisierung": sum(1 for v in led.values() if v["authorize"] > 1), "mit_mehr_als_einem_einzug": sum(1 for v in led.values() if v["capture"] > 1),
                           "schreibende_aufrufe_nach_dem_ausfall": len(nach_ausfall), "statusabfragen_nach_dem_ausfall": sum(1 for x in sim.calls[calls_vor:] if x["operation"] == "status")}
        # (3) lokal CAPTURED und Anbieter CAPTURED stimmen überein (Betrag)
        diff, abw = 0, []
        for t, m in zahlungen:
            lok = c.execute("SELECT state, captured_minor FROM taktovia.payment_intent WHERE tenant_id=%s AND merchant_ref=%s", (t, m)).fetchone()
            r = AB.SimPsp(sim).status(m)
            if r and r["state"] == "CAPTURED" and lok != ("CAPTURED", r["captured_minor"]):
                diff += 1
                abw.append({"merchant_ref": m, "lokal": [str(x) for x in (lok or [])], "anbieter": r["state"], "erfasst": r["captured_minor"]})
        out["zahlungen_beim_anbieter_erfasst_aber_lokal_abweichend"] = diff
        out["abweichende_zahlungen"] = abw
        # (4) Löschungen und Sperren vor dem Ausfall sind wieder wirksam
        erased = [a[2] for a in acks if a[1] == "passenger.erased"]
        blocks = [a[2] for a in acks if a[1] == "vehicle.blocked"]
        lebt = c.execute("SELECT count(*) FROM taktovia.passenger_private WHERE id = ANY(%s::uuid[]) AND erased_at IS NULL", (erased,)).fetchone()[0]
        fehlt = len(blocks) - c.execute("SELECT count(*) FROM taktovia.vehicle_block WHERE id = ANY(%s::uuid[]) AND status='ACTIVE'", (blocks,)).fetchone()[0]
        out["loeschungen_sperren"] = {"bestaetigte_loeschungen": len(erased), "wiederauferstanden": lebt, "bestaetigte_sperren": len(blocks), "sperren_fehlen_oder_aufgehoben": fehlt}
        # (5) bestätigte Commits des Clients, die im wiederhergestellten Bestand fehlen (Verlustfenster)
        verloren = [a for a in acks if a[0] > rpo_punkt]
        out["verlustfenster"] = {"bestaetigte_commits_nach_rpo_punkt": len(verloren), "je_art": {k: sum(1 for a in verloren if a[1] == k) for k in sorted({a[1] for a in verloren})}}
        # (6) Kapazität: keine Überbelegung, jede bestätigte Buchung hat einen verbrauchten Hold
        out["kapazitaet"] = {"buchungen_ohne_verbrauchten_hold": c.execute("SELECT count(*) FROM taktovia.booking b JOIN taktovia.capacity_hold h ON h.tenant_id=b.tenant_id AND h.id=b.hold_id WHERE b.state IN ('CONFIRMED','PARTIAL','CANCEL_PENDING','COMPLETED') AND h.state<>'CONSUMED'").fetchone()[0]}
    return out


def aufraeumen(res: dict):
    if res.get("_idp") is not None:
        res["_idp"].close()
    res["_uebung"].aufraeumen()
