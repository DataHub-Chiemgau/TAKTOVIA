"""Führt ein Lastprofil end-to-end aus: Anwendung starten, Mandantenwelten und Fahrer anlegen, Lauf mit Sampler, Konkurrenz- und Trennungsproben, Invariantenabfrage, Bericht im Format
von WP-M0-25 (Schema `report.schema.json`) mit zusätzlichen Auswertungen je Mandant, je Stufe und Sättigung."""
from __future__ import annotations

import asyncio
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from . import aload, invariants, runner
from . import world as W
from .appproc import App
from .idp import TestIdp
from .sampler import Sampler

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "db"))
sys.path.insert(0, str(REPO / "tests" / "performance"))


def _git(*a) -> str:
    import subprocess
    return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True).stdout.strip()


def build_id() -> str:
    head = _git("rev-parse", "HEAD") or "unversioniert"
    return head + ("+lokal" if _git("status", "--porcelain", "--untracked-files=no") else "")


def execute_profile(p: runner.Profile, out_dir: Path, *, title: str, race_stage: str | None = None, app_extra: list[str] | None = None, dataset_note: str = "", cold_first: bool = False) -> dict:
    import db as dbtool
    from perfkit import hardware, report as rpt
    db_name = os.environ["TAKTOVIA_DB_NAME"]
    idp = TestIdp()
    app = App(idp, db_name=db_name, rate_limit=p.rate_limit_per_min, extra=["--server.tomcat.max-connections=40000", "--server.tomcat.accept-count=2000", *(app_extra or [])], log=out_dir / "anwendung.log")
    out_dir.mkdir(parents=True, exist_ok=True)
    admin_factory = lambda: dbtool.admin(db_name)  # noqa: E731
    admin = admin_factory()
    started = datetime.now(timezone.utc)
    try:
        app.start(300)
        t_setup = time.monotonic()
        worlds = [W.build_tenant(app, idp, admin, chr(ord("A") + i), p.passengers_per_tenant, p.vehicles_per_tenant, name=f"Mandant {chr(ord('A') + i)} (Last)") for i in range(p.tenants)]
        rigs = {w.label: W.provision_drivers(app, idp, admin, w, p.vehicles_per_tenant) for w in worlds}
        setup_s = time.monotonic() - t_setup
        tenants = [w.tenant_id for w in worlds]
        runs = [r for w in worlds for r in (*w.runs, w.last_seat_run)]
        sampler = Sampler(app.proc.pid, admin_factory, db_name, tenants)
        rec = aload.Recorder()
        race = None
        if race_stage:
            stage = next(s for s in p.stages if s.name == race_stage)
            race = (stage, lambda st: runner.last_seat_race(st, rec, app, idp, admin_factory, worlds[0], 100))
        sampler.start()
        run = asyncio.run(runner.run_load(app, idp, admin_factory, worlds, rigs, p, rec, race=race))
        samples = sampler.stop()
        iso = asyncio.run(runner.isolation_probe(app, idp, worlds))
        inv = invariants.check(admin, tenants, runs)
        kennz = _kennzahlen(admin, tenants, rec, len(worlds))
        raw, sha = runner.write_raw(rec.recs, run["stages"], out_dir)
        rep = _report(p, title, started, app, worlds, rigs, rec, run, samples, iso, inv, kennz, raw, sha, setup_s, dataset_note, hardware.collect(), rpt)
        rpt.validate(rep)
        import json
        (out_dir / "bericht.json").write_text(json.dumps(rep, indent=1, ensure_ascii=False, default=str), encoding="utf-8")
        (out_dir / "bericht.md").write_text(rpt.render_markdown(rep) + _extra_md(rep), encoding="utf-8")
        return rep
    finally:
        app.stop(); idp.close(); admin.close()


def _stage_stats(recs, bounds, name):
    return aload.summarize([r for r in recs if runner.stage_of(bounds, r.t) == name])


def _report(p, title, started, app, worlds, rigs, rec, run, samples, iso, inv, kennz, raw, sha, setup_s, dataset_note, hw, rpt) -> dict:
    recs = rec.measured()
    bounds = run["stages"]
    offer_ep = "POST /v1/ride-offers"
    per_ep = {k: aload.summarize(v) for k, v in sorted(aload.group(recs, lambda r: r.endpoint).items())}
    per_tenant = {k: aload.summarize(v) for k, v in sorted(aload.group(recs, lambda r: r.tenant).items())}
    per_tenant_ep = {t: {k: aload.summarize(v) for k, v in sorted(aload.group([r for r in recs if r.tenant == t], lambda r: r.endpoint).items())} for t in per_tenant}
    per_stage = {}
    for b in bounds:
        rs = [r for r in recs if runner.stage_of(bounds, r.t) == b["name"] and r.tag != "last_seat"]
        std = [r for r in rs if r.endpoint != offer_ep and "Überlastakteur" not in r.endpoint]
        offers = [r for r in rs if r.endpoint == offer_ep]
        ss = [s for s in samples if "t" in s and "error" not in s and b["t_start"] <= s["t"] - rec.t0 < b["t_end"]]
        per_stage[b["name"]] = {"faktor": b["factor"], "dauer_s": round(b["t_end"] - b["t_start"], 1), "alle": aload.summarize(rs), "angebote": aload.summarize(offers), "standard_api": aload.summarize(std),
                                "buchungen": sum(1 for r in rs if r.endpoint == "POST /v1/bookings" and r.status == 201), "429": {"anzahl": sum(1 for r in rs if r.status == 429), "mit_retry_after": sum(1 for r in rs if r.status == 429 and r.retry_after)},
                                "saettigung": {"app_cpu_pct_von_einem_kern_max": max((s["app_cpu_pct_of_one_core"] for s in ss), default=None), "maschine_cpu_pct_max": max((s["machine_cpu_pct"] for s in ss), default=None),
                                               "app_rss_mb_max": max((s["app_rss_mb"] for s in ss), default=None), "db_verbindungen_max": max((s["db_runtime_connections"] for s in ss), default=None),
                                               "sperrwartende_max": max((s["db_lock_waiting"] for s in ss), default=None), "outbox_wartend_max": max((s["outbox_pending"] for s in ss), default=None), "proben": len(ss)}}
    overall = aload.summarize(recs)
    dur = max((r.t for r in recs), default=1) - min((r.t for r in recs), default=0) or 1
    ref = next((b for b in bounds if abs(b["factor"] - 1.0) < 1e-9), bounds[0])
    ref_stats = per_stage[ref["name"]]
    over = [b for b in bounds if b["burst_actors"]]
    r429 = sum(per_stage[b["name"]]["429"]["anzahl"] for b in bounds)
    r429_ra = sum(per_stage[b["name"]]["429"]["mit_retry_after"] for b in bounds)
    def verdict(ok): return "erfüllt" if ok else "verfehlt"
    off95, std95 = ref_stats["angebote"].get("p95_ms"), ref_stats["standard_api"].get("p95_ms")
    ev = [
        {"criterion": "Angebote p95 <= 5 s (LH-6.19-NFR-003, vorläufig, D-05 offen)", "target": "<= 5000 ms", "measured": f"p95 {off95} ms (n={ref_stats['angebote'].get('n')}, Stufe {ref['name']})", "verdict": verdict(off95 is not None and off95 <= 5000)},
        {"criterion": "Standard-API p95 <= 1 s (LH-6.19-NFR-002, vorläufig, D-05 offen)", "target": "<= 1000 ms", "measured": f"p95 {std95} ms (n={ref_stats['standard_api'].get('n')}, Stufe {ref['name']})", "verdict": verdict(std95 is not None and std95 <= 1000)},
        {"criterion": "Fehlerrate (Transport, 5xx) in der Referenzstufe", "target": "<= 1 %", "measured": f"{ref_stats['alle'].get('error_rate', 0) * 100:.2f} % ({ref_stats['alle'].get('error_count', 0)} Fehler)", "verdict": verdict(ref_stats["alle"].get("error_rate", 1) <= 0.01)},
        {"criterion": "Invarianten nach dem Lauf (keine Überbuchung, keine Doppelbestätigung, kein Fremdmandant)", "target": "0 Verstöße", "measured": f"{inv['verstoesse_gesamt']} Verstöße bei {inv['geprueft']['buchungen']} Buchungen, {inv['geprueft']['holds']} Holds", "verdict": verdict(inv["verstoesse_gesamt"] == 0)},
        {"criterion": "Mandantentrennung bei gleichen Objektnamen", "target": "keine Fremdreferenz, fremde IDs 404", "measured": f"gleiche Namen: {iso['gleiche_namen']}, fremde IDs in Listen: {iso['fremde_ids_in_listen']}, alle fremden IDs 404: {iso['alle_fremden_ids_404']}",
         "verdict": verdict(iso["gleiche_namen"] and iso["fremde_ids_in_listen"] == 0 and iso["alle_fremden_ids_404"])},
    ]
    if over:
        ev.append({"criterion": "Überlast: kontrollierte Abweisung (429 mit Retry-After)", "target": "429 nur mit Retry-After, keine Datenkorruption", "measured": f"{r429} Antworten 429, davon {r429_ra} mit Retry-After; Invarianten {inv['verstoesse_gesamt']} Verstöße",
                   "verdict": verdict(r429 > 0 and r429 == r429_ra and inv["verstoesse_gesamt"] == 0)})
    if run["race"] is not None:
        rc = run["race"]
        ev.append({"criterion": "100 parallele Bestätigungen auf den letzten Platz unter Grundlast (PH-AT-004)", "target": "genau 1 Hold, genau 1 Buchung", "measured": json_str(rc),
                   "verdict": verdict(rc["holds_201"] == 1 and rc.get("bestaetigungen_201", 0) >= 1 and rc.get("verschiedene_buchungs_ids", 0) == 1)})
    achieved_bookings = sum(1 for r in recs if r.endpoint == "POST /v1/bookings" and r.status == 201 and r.tag != "last_seat")
    ref_dur = ref["t_end"] - ref["t_start"]
    target_rate = p.bookings_per_day / 86400.0 * p.peak_factor * ref["factor"] * len(worlds)
    sess_ok = (p.sessions if p.sessions_scope == "pro_mandant" else p.sessions // len(worlds)) * len(worlds)
    ev.append({"criterion": "Referenzprofil erreicht (Buchungsrate, Sitzungen, Fahrzeuge, Mandanten)", "target": f"{p.bookings_per_day}/Tag je Mandant x Peakfaktor {p.peak_factor} = {target_rate:.2f} Buchungen/s gesamt; {sess_ok} Sitzungen; {p.vehicles_per_tenant} Fahrzeuge je Mandant; {len(worlds)} Mandanten",
               "measured": f"{per_stage[ref['name']]['buchungen'] / max(1, ref_dur):.2f} Buchungen/s in Stufe {ref['name']}; Fahrersitzungen {sum(len(v) for v in rigs.values())}; Lesesitzungen {sess_ok}; Mandanten {len(worlds)}; verworfene Ankünfte (Generatorgrenze) {run['counters'].get('arrivals_dropped_client_limit', 0)}",
               "verdict": "nicht bewertet"})
    sat = [s for s in samples if "error" not in s]
    return {
        "report_version": "1.0", "kind": "load-run", "title": title, "started_at": started.isoformat(),
        "header": {
            "hardware": {**hw, "anmerkung": "Lastgenerator (Python), Anwendung (JVM), PostgreSQL und Test-Issuer laufen auf derselben Maschine und teilen sich die CPUs"},
            "dataset": {"description": f"{len(worlds)} Mandanten mit gleichen Objektnamen (Dienst Rufbus, Haltepunkte Markt/Bahnhof), Testflotte {p.vehicles_per_tenant} Fahrzeuge je Mandant (Umläufe, Schichten, Freigaben), "
                                       f"{p.vehicles_per_tenant} Fahrersitzungen je Mandant, {p.passengers_per_tenant} Fahrgastkonten je Mandant; {dataset_note}",
                        "volume": f"{sum(inv['geprueft'][k] for k in ('buchungen', 'holds'))} Buchungen+Holds erzeugt im Lauf; Aufbau {setup_s:.1f} s"},
            "sample": {"n": overall.get("n", 0), "rule": f"alle Anfragen nach {p.warmup_s:g} s Aufwärmphase; Nearest-Rank-Perzentile"},
            "raw_data": {"path": str(raw.relative_to(REPO)) if str(raw).startswith(str(REPO)) else str(raw), "sha256": sha},
            "parameters": {"profil": p.name, "status_zielwerte": p.status, "build": build_id(), "migrationsstand": _migration_state(), "seed": p.seed, "mandanten": len(worlds), "fahrzeuge_je_mandant": p.vehicles_per_tenant,
                           "buchungen_pro_tag": p.bookings_per_day, "peakfaktor_annahme": p.peak_factor, "sitzungen": p.sessions, "sitzungen_geltung_annahme": p.sessions_scope, "think_time_s": p.session_think_s,
                           "suchen_je_buchung_annahme": p.offers_per_booking, "stufen": [{"name": s.name, "faktor": s.factor, "dauer_s": s.duration_s, "ueberlastakteure": s.burst_actors} for s in p.stages],
                           "anwendung_start_s": round(getattr(app, "startup_s", 0), 1), "quote_je_akteur_pro_min": p.rate_limit_per_min},
        },
        "results": {"overall": overall, "per_endpoint": per_ep, "throughput_rps": round(len(recs) / dur, 2), "peak_rps_1s_bucket": _peak(recs), "error_rate": overall.get("error_rate", 0), "error_count": overall.get("error_count", 0),
                    "per_tenant": per_tenant, "per_tenant_endpoint": per_tenant_ep,
                    "kalter_start": {"beschreibung": f"erste {p.warmup_s:g} s nach dem Start der Anwendung (JVM, Verbindungspools, Caches kalt), nicht in den Perzentilen der Stufen", "alle": aload.summarize([r for r in rec.recs if r.t < p.warmup_s]),
                                     "angebote": aload.summarize([r for r in rec.recs if r.t < p.warmup_s and r.endpoint == offer_ep])}, "per_stage": per_stage, "zaehler": run["counters"], "wettlauf_letzter_platz": run["race"], "isolation": iso, "invarianten": inv, "kennzahlen_gegenprobe": kennz,
                    "saettigung_gesamt": {"proben": len(sat), "app_cpu_pct_von_einem_kern_max": max((s["app_cpu_pct_of_one_core"] for s in sat), default=None), "maschine_cpu_pct_max": max((s["machine_cpu_pct"] for s in sat), default=None),
                                          "app_rss_mb_max": max((s["app_rss_mb"] for s in sat), default=None), "db_verbindungen_max": max((s["db_runtime_connections"] for s in sat), default=None),
                                          "sperrwartende_max": max((s["db_lock_waiting"] for s in sat), default=None), "outbox_wartend_max": max((s["outbox_pending"] for s in sat), default=None)},
                    "nicht_beendete_aufgaben": run["pending_at_stop"]},
        "evaluation": ev,
        "open_parameters": {"peak_requests_per_second": {"value": None, "status": f"OFFEN (D-05): Arbeitsannahme Peakfaktor {p.peak_factor} auf den Tagesdurchschnitt"},
                            "active_tenants": {"value": len(worlds), "status": "Arbeitsannahme zwei Mandanten (D-05 offen)"},
                            "sessions_scope": {"value": p.sessions_scope, "status": "Annahme OP-01: 5.000 Sitzungen je Mandant (Kap. 01 §1.8); nicht bestätigt"}},
        "valid_for_acceptance": False,
        "limitations": _limitations(p, worlds),
    }


def _kennzahlen(admin, tenants: list[str], rec, probe_offers: int) -> dict:
    """Gegenprobe zur Zählung (keine Abnahme der KPI-Definition): die Datenbasis der Kennzahlen `Anfragen`, `Technische Anfragefehler` und `Angebotsquote` (travel_attempt) füllt sich aus dem Lasttestverkehr plausibel,
    d. h. genau ein Versuch je angenommener Angebotsanfrage (HTTP 200; formal fehlerhafte Anfragen erzeugen keinen Versuch). [probe_offers] sind die Anfragen der Isolationsprobe (nicht im Generator erfasst)."""
    sent = sum(1 for r in rec.recs if r.endpoint == "POST /v1/ride-offers" and r.status == 200) + probe_offers
    row = admin.execute("SELECT count(*), count(*) FILTER (WHERE outcome = 'TECHNICAL_ERROR'), count(*) FILTER (WHERE outcome = 'OFFERED'), count(*) FILTER (WHERE outcome = 'REJECTED') FROM taktovia.travel_attempt WHERE tenant_id = ANY(%s::uuid[])", (tenants,)).fetchone()
    total, tech, offered, rejected = row
    return {"anfragen_gesendet_http_200": sent, "anfragen_gezaehlt_travel_attempt": total, "technische_anfragefehler": tech, "angebote": offered, "ablehnungen": rejected,
            "angebotsquote": round(offered / total, 4) if total else None, "gleich": sent == total}


def json_str(x) -> str:
    import json
    return json.dumps(x, ensure_ascii=False, sort_keys=True)


def _peak(recs) -> int:
    from collections import Counter
    return max(Counter(int(r.t) for r in recs).values(), default=0)


def _migration_state() -> str:
    import db as dbtool
    with dbtool.admin(os.environ["TAKTOVIA_DB_NAME"]) as c:
        row = c.execute("SELECT script FROM taktovia_meta.schema_history WHERE script LIKE 'V%%' ORDER BY (regexp_replace(script, '^V([0-9]+)__.*$', '\\1'))::bigint DESC LIMIT 1").fetchone()
        n = c.execute("SELECT count(*) FROM taktovia_meta.schema_history").fetchone()[0]
    return f"{row[0] if row else '?'} ({n} Skripte)"


def _limitations(p, worlds) -> list[str]:
    return [
        "Werte gelten für diese Messumgebung (geteilte Maschine, siehe Hardware); sie sind kein Nachweis für die Zielhardware (D-05 offen) und gelten nicht als Abnahme des Betriebs-Gates.",
        "Zielwerte (p95 <= 5 s Angebote, p95 <= 1 s Standard-API, 250 Fahrzeuge je Mandant, 30.000 Buchungen/Tag, 5.000 Sitzungen) sind vorläufig, D-05 offen; Peakfaktor, Sitzungsdefinition und Geltung je Mandant sind Arbeitsannahmen (OP-01).",
        "Die Produktions-Verdrahtung von `/v1/ride-offers` reicht Anfragen ohne Kandidatensuche, Einfügeheuristik und Routing durch (CandidateGate.PASS_THROUGH, WP-M2-17 bis -19 nicht angeschlossen): die gemessene Angebotslatenz enthält Zulässigkeitsprüfung, Geo, Tarif und Angebotsobjekt, aber nicht Kandidatenfilter, Routingmatrix und Solver.",
        "Hold und Confirm setzen eine Planbindung des Angebots voraus; sie wird im Testaufbau per SQL gesetzt (nicht gemessen) und ersetzt nicht den Nachweis der Verdrahtung.",
        "Die Zahlung nach der Bestätigung (PSP) ist nicht Teil der Last (Buchung bleibt CONFIRMING; Simulator nicht angebunden). Livekanal (SSE/WebSocket) und Benachrichtigungsversand sind nicht als Verbindungslast gebaut.",
        "Messpunkt: Antwortzeit ab Senden bis zum vollständig gelesenen Körper im Lastgenerator (Clientzeit auf derselben Maschine); eine getrennte serverseitige Zeit (Eingang Gateway bis Antwortende) liegt in dieser Verdrahtung nicht vor (OP-05).",
        "Authentifizierung über lokalen Ersatz-Issuer (JWKS, RS256) statt Keycloak; Umgebung `local` ohne TLS.",
    ]


def _extra_md(rep) -> str:
    r = rep["results"]
    L = ["", "## Je Mandant", "", "| Mandant | n | p50 ms | p95 ms | p99 ms | Fehler | 429 |", "|---|---:|---:|---:|---:|---:|---:|"]
    for k, s in r["per_tenant"].items():
        L.append(f"| {k} | {s['n']} | {s['p50_ms']} | {s['p95_ms']} | {s['p99_ms']} | {s['error_count']} | {s['shed_429']} |")
    L += ["", "## Je Stufe", "", "| Stufe | Faktor | Dauer s | Angebote n | Angebote p95 ms | Standard n | Standard p95 ms | Fehler | 429 (mit Retry-After) | App-CPU max % | Maschine-CPU max % | DB-Verbindungen max | Sperrwartende max | Outbox wartend max |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for k, s in r["per_stage"].items():
        z = s["saettigung"]
        L.append(f"| {k} | {s['faktor']} | {s['dauer_s']} | {s['angebote'].get('n', 0)} | {s['angebote'].get('p95_ms', '–')} | {s['standard_api'].get('n', 0)} | {s['standard_api'].get('p95_ms', '–')} | {s['alle'].get('error_count', 0)} | "
                 f"{s['429']['anzahl']} ({s['429']['mit_retry_after']}) | {z['app_cpu_pct_von_einem_kern_max']} | {z['maschine_cpu_pct_max']} | {z['db_verbindungen_max']} | {z['sperrwartende_max']} | {z['outbox_wartend_max']} |")
    L += ["", "## Kalter Start (Warm-up-Fenster, nicht in den Stufen)", "", "```", json_str(r["kalter_start"]), "```"]
    L += ["", "## Invarianten nach dem Lauf", "", "```", json_str(r["invarianten"]), "```", "", "## Kennzahlbasis (Gegenprobe zur Zählung)", "", "```", json_str(r["kennzahlen_gegenprobe"]), "```", "", "## Mandantentrennung", "", "```", json_str(r["isolation"]), "```", "", "## Zähler der Sitzungen", "", "```", json_str(r["zaehler"]), "```"]
    if r.get("wettlauf_letzter_platz"):
        L += ["", "## Wettlauf letzter Platz unter Grundlast", "", "```", json_str(r["wettlauf_letzter_platz"]), "```"]
    return "\n".join(L) + "\n"


class SmallWorld:
    """Kleine Welt für gezielte Konkurrenz- und Zeitproben: Anwendung, Issuer, [tenants] Mandanten mit [passengers] Konten und [runs] Umläufen; schließt alles beim Verlassen."""

    def __init__(self, tenants: int = 1, passengers: int = 100, runs: int = 2, seats: int = 8) -> None:
        self.n_tenants, self.n_pax, self.n_runs, self.seats = tenants, passengers, runs, seats

    def __enter__(self) -> "SmallWorld":
        import db as dbtool
        self.db = os.environ["TAKTOVIA_DB_NAME"]
        self.idp = TestIdp()
        self.app = App(self.idp, db_name=self.db, rate_limit=1_000_000, extra=["--server.tomcat.max-connections=4000"])
        self.admin_factory = lambda: dbtool.admin(self.db)
        self.admin = self.admin_factory()
        self.app.start(300)
        self.worlds = [W.build_tenant(self.app, self.idp, self.admin, chr(ord("A") + i), self.n_pax, self.n_runs, self.seats, name=f"Mandant {chr(ord('A') + i)} (Probe)") for i in range(self.n_tenants)]
        return self

    def __exit__(self, *exc) -> None:
        self.app.stop(); self.idp.close(); self.admin.close()

    def call(self, method: str, path: str, token: str, body=None, if_match: str | None = None):
        return W.call(self.app.base, method, path, token, body, if_match)
