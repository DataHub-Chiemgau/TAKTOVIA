"""AT-23 „Restore“ (WP-M5-15, LH-6.19-NFR-007): Ausfall der produktnahen Datenbank, Wiederherstellung mit vereinbartem RPO/RTO.

Das Szenario FÜHRT die Ausfallübung aus (tests/performance/ausfalluebung, Plan: tests/performance/ausfalluebung-plan-m5.md) und prüft ihre Messwerte gegen die Zielwerte und Abnahmekriterien.
Die Zeiten sind echte Uhrzeiten dieses Laufs; die Zielwerte (RPO 15 min, RTO 2 h) sind vorläufige Lastenheftziele (D-05 offen). Ersatz/nicht ausgeführt: PSP = Simulator, Ereignisstrom = Datei
(kein Broker), Backupspeicher = lokales Verzeichnis, kein Standby über Hosts, Fachlogik der Last per SQL, Proben gegen die Anwendung nur mit mit_anwendung (JWT-Test-Issuer statt Keycloak); die Ausführung des
Drehbuchs durch eine Person, die es nicht geschrieben hat, und die Übertragbarkeit auf die Produktionsumgebung stehen aus. Die Datenbank des Akzeptanzgerüsts (Zwei-Mandanten-Fixture) wird hier nicht
benutzt; die Übung läuft in einem eigenen privaten Cluster."""
import sys
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "tests" / "performance"))
sys.path.insert(0, str(REPO / "infra" / "backup"))
from taktovia_at.scenario import scenario  # noqa: E402

KEYS = ["LH-6.19-NFR-007"]
CONFIG = {"mit_anwendung": False, "ergebnis": None, "protokoll_out": None}


@scenario("AT-23", "1.0.0", KEYS, "Restore: Ausfall produktnaher Datenbank, Wiederherstellung mit RTO/RPO (Übung)")
def at_23(ctx):
    from ausfalluebung import uebung as UE
    res = UE.fuehre_aus(mit_anwendung=CONFIG["mit_anwendung"], nachlauf_s=8.0)
    CONFIG["ergebnis"] = res
    try:
        ctx.simulated.extend(["PSP-Simulator tests/sandbox/psp (D-03 offen)", "Ereignisstrom als Datei statt Messaging-Broker", "Backupspeicher lokales Verzeichnis statt S3 Object Lock"])
        if CONFIG["mit_anwendung"]:
            ctx.simulated.append("JWT-Test-Issuer statt Keycloak (End-to-End-Probe)")
        else:
            ctx.simulated.append("End-to-End-Probe als Datenbank-Probe (ohne Anwendung)")
        p = res["drehbuch"]
        t = {k: datetime.fromisoformat(v) for k, v in (("ausfall", p["ausfallzeitpunkt"]), ("commit", p["letzter_wiederherstellbarer_commit"]["zeitpunkt"]), ("probe", p["zeitpunkt_e2e_probe"]))}
        rpo_min, rto_h = (t["ausfall"] - t["commit"]).total_seconds() / 60.0, (t["probe"] - t["ausfall"]).total_seconds() / 3600.0
        ctx.check("Ausfallübung abgeschlossen (alle acht Schritte des Drehbuchs mit Zeitstempel)", ("ABGESCHLOSSEN", 8), (p["status"], sum(1 for s in p["schritte"] if s["zeitpunkt"])))
        ctx.check(f"gemessenes RPO {rpo_min:.2f} min (Ausfall {p['ausfallzeitpunkt']} minus letzter wiederherstellbarer Commit {p['letzter_wiederherstellbarer_commit']['zeitpunkt']}) <= 15 min (vorläufig, D-05)", True, rpo_min <= 15.0)
        ctx.check(f"gemessenes RTO {rto_h * 60:.2f} min (End-to-End-Probe {p['zeitpunkt_e2e_probe']} minus Ausfall) <= 2 h (vorläufig, D-05)", True, rto_h <= 2.0)
        ctx.check("Verlustfenster nicht leer (bestätigte Commits nach dem letzten wiederherstellbaren Commit): die RPO-Messung ist aussagekräftig", True, res["nachweise"]["verlustfenster"]["bestaetigte_commits_nach_rpo_punkt"] > 0)
        steps = [s["step"] for s in res["restore"]["steps"]]
        ctx.check("Wiederherstellung nur aus Basissicherung und archiviertem WAL (Manifest, Umgebung, WAL-Kette geprüft; Datenverzeichnis der Quelle verworfen)",
                  ["manifest_verified", "environment_checked", "wal_chain_verified", "base_extracted", "recovery_configured", "recovered_and_promoted"], steps)
        ctx.check("Archivierung wie im Betrieb (archive_mode on, archive_timeout 60 s, archive_command mit Betriebswerkzeug)", ("on", "1min", True),
                  (res["archivierung"]["archive_mode"], res["archivierung"]["archive_timeout"], res["archivierung"]["archive_command_nutzt_betriebswerkzeug"]))
        ctx.check("Konsistenzprüfungen ohne blockierenden Befund, Lösch-/Sperrjournal wirksam", (True, []), (p["konsistenz"]["release_allowed"], p["konsistenz"]["violations"]))
        a, n = res["abgleich"], res["nachweise"]
        ctx.check(f"Abgleichbericht über {a['anzahl_vorgaenge']} Vorgänge im Verlustfenster abgeschlossen (je Ergebnis: {a['je_ergebnis']})", (True, []), (a["abgleich_abgeschlossen"], a["blockierend"]))
        ctx.check("keine zweite Belastung: je Händlerreferenz höchstens eine Autorisierung und ein Einzug beim Anbieter, keine schreibenden Anbieteraufrufe nach dem Ausfall", (0, 0, 0),
                  (n["anbieter"]["mit_mehr_als_einer_autorisierung"], n["anbieter"]["mit_mehr_als_einem_einzug"], n["anbieter"]["schreibende_aufrufe_nach_dem_ausfall"]))
        ctx.check("keine Buchung ohne Kapazität im wiederhergestellten Bestand", 0, n["kapazitaet"]["buchungen_ohne_verbrauchten_hold"])
        ctx.check("verspätete Anbieterereignisse: genau eine Wirkung je Vorgang", (0, 0), (n["verspaetete_ereignisse"]["zusaetzliche_wirkungen"], n["verspaetete_ereignisse"]["zahlungen_mit_mehr_als_einem_ereignis"]))
        ctx.check("Löschungen und Sperren vor dem Ausfall wieder wirksam (keine Wiederauferstehung, keine aufgehobene Sperre)", (0, 0), (n["loeschungen_sperren"]["wiederauferstanden"], n["loeschungen_sperren"]["sperren_fehlen_oder_aufgehoben"]))
        iso = res["isolation"]
        ctx.check("Mandantentrennung nach dem Wiederanlauf: Isolationsstichprobe grün, Runtime-Rolle ohne Superuser, BYPASSRLS und Tabelleneigentum", (True, False, False, 0),
                  (iso["ok"], iso["runtime_rolle"]["superuser"], iso["runtime_rolle"]["bypassrls"], iso["runtime_rolle"]["tabelleneigentum"]))
        ctx.check("End-to-End-Probe erfolgreich (" + res["e2e_probe"]["art"] + ")", True, res["e2e_probe"]["ok"])
    finally:
        out = CONFIG.get("protokoll_out")
        if out:
            import json
            Path(out).parent.mkdir(parents=True, exist_ok=True)
            doc = {k: v for k, v in res.items() if not k.startswith("_") and k != "abgleich"}
            doc["abgleichbericht"] = {k: v for k, v in res["abgleich"].items() if k != "vorgaenge"}
            doc["abgleichbericht"]["vorgaenge_beispiele"] = []
            seen = {}
            for v in res["abgleich"]["vorgaenge"]:
                if seen.get(v["ergebnis"], 0) < 3:
                    doc["abgleichbericht"]["vorgaenge_beispiele"].append(v); seen[v["ergebnis"]] = seen.get(v["ergebnis"], 0) + 1
            Path(out).write_text(json.dumps(doc, indent=1, ensure_ascii=False, sort_keys=True, default=str), encoding="utf-8")
        UE.aufraeumen(res)
