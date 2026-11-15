"""WP-M5-11 / LH-6.19-NFR-002: Standard-API p95 <= 1 s unter Referenzlast, je Endpunktklasse.

Messung gegen die echte Anwendung (Jar, Runtime-Rolle, RLS) mit **zwei Mandanten** und dem synthetischen Datenbestand aus WP-M5-02 (`datagen`, kleine Skala mit 250 Fahrzeugen je Mandant, Referenzskala mit
`TAKTOVIA_PERF_STD_SCALE=reference`); das Lasttestprotokoll (`tests/performance/out/standardapi/protokoll.json` und `.md`, die geprüfte Fassung steht unter `protokolle/standard-api-p95-m5-11.*`) nennt je
Klasse n, p50, p95, p99, Fehler und Statusverteilung, dazu Hardware, Datenvolumen (Zeilen je Tabelle und Mandant), Build, Migrationsstand und Seed. Messpunkt und Grenzen: siehe `refload/standardapi.py`
(Clientzeit auf derselben Maschine = obere Schranke der serverseitigen Zeit; Zielwert vorläufig, D-05 und OP-04 offen). Voraussetzungen wie `test_referenzprofil.py` (Opt-in `TAKTOVIA_PERF_RUN=1`, bootJar,
Datenbankumgebung); ohne sie ist der Test übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlägt er stattdessen fehl, sobald der Opt-in gesetzt ist und eine Voraussetzung fehlt."""
from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
import threading
import uuid
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "db"))

from refload import aload, standardapi as S  # noqa: E402
from refload import world as W  # noqa: E402
from refload.appproc import App  # noqa: E402
from refload.idp import TestIdp  # noqa: E402
from refload.prereq import require_app  # noqa: E402

REPO = HERE.parents[1]
TARGET_MS = 1000.0
_CACHE: dict = {}
PROTOKOLL_VERSION = "1.0"


def out_dir() -> Path:
    return Path(os.environ.get("TAKTOVIA_PERF_OUT", HERE / "out")) / "standardapi"


def _git(*a) -> str:
    return subprocess.run(["git", "-C", str(REPO), *a], capture_output=True, text=True).stdout.strip()


def run_measurement() -> dict:
    """Einmal je Prozess: Datenbestand, Anwendung, Lastmix, Zeitseitenkanal und Cache-Probe; schreibt das Protokoll."""
    if "p" in _CACHE:
        return _CACHE["p"]
    require_app()
    import db as dbtool
    from datagen import generator as G
    from datagen import fill as F
    from perfkit import hardware
    db_name = os.environ["TAKTOVIA_DB_NAME"]
    seed = int(os.environ.get("TAKTOVIA_PERF_SEED", G.SEED_DEFAULT))
    scale_name = os.environ.get("TAKTOVIA_PERF_STD_SCALE", "mittel")
    # kleine Skala (80 Buchungen je Mandant) ist für Listen und Paginierung zu klein; Standard „mittel“: 250 Fahrzeuge, 3 Betriebstage, 1.500 Buchungen/Tag je Mandant; „reference“ = Kap. 08 §8.6
    scale = {"reference": G.REFERENCE, "small": G.SMALL}.get(scale_name) or G.Scale(vehicles=250, drivers=250, depots=4, services=3, stops=120, passengers=2000, days=3, bookings_per_day=1500, position_interval_s=1800)
    duration = float(os.environ.get("TAKTOVIA_PERF_STD_SECONDS", "45"))
    warmup = float(os.environ.get("TAKTOVIA_PERF_STD_WARMUP", "8"))
    sessions = int(os.environ.get("TAKTOVIA_PERF_STD_SESSIONS", "24"))
    think = float(os.environ.get("TAKTOVIA_PERF_STD_THINK", "0.3"))
    started = datetime.now(timezone.utc)
    admin_factory = lambda: dbtool.admin(db_name)  # noqa: E731
    admin = admin_factory()
    idp = TestIdp()
    app = App(idp, db_name=db_name, rate_limit=100_000_000, extra=["--server.tomcat.max-connections=20000", "--server.tomcat.accept-count=1000"], log=out_dir() / "anwendung.log")
    out_dir().mkdir(parents=True, exist_ok=True)
    try:
        # 1. Datenbestand (WP-M5-02): synthetische Mandanten, befüllt unter der Runtime-Rolle (nur wenn noch leer)
        ids = G.provision_tenants(admin, seed, 2)
        t_fill = time.monotonic()
        todo = [(i, t) for i, t in enumerate(ids) if admin.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id = %s", (t,)).fetchone()[0] == 0]
        errors: dict = {}

        def fill(i, t):
            try:
                c = F.runtime_connection()
                try:
                    G.fill_tenant(c, t, seed, scale, i + 1)
                finally:
                    c.close()
            except BaseException as e:  # noqa: BLE001
                errors[i] = e
        ts = [threading.Thread(target=fill, args=(i, t)) for i, t in todo]
        [t.start() for t in ts]; [t.join() for t in ts]
        if errors:
            raise RuntimeError(f"Befüllung fehlgeschlagen: {errors}")
        fill_s = round(time.monotonic() - t_fill, 1)
        admin.execute("ANALYZE")                                                  # Planerstatistik nach der Befüllung (wie im Betrieb durch Autovacuum)
        with F.runtime_connection() as c:
            counts = {t: G.row_counts(c, t) for t in ids}
        # 2. Anwendung, Rollen, Fahrer, Buchungswelten
        app.start(300)
        ctxs = []
        for i, t in enumerate(ids):
            ctx = S.TenantCtx("AB"[i], t)
            _with_tenant(admin, t)
            services = [r[0] for r in admin.execute("SELECT id FROM taktovia.service WHERE tenant_id = %s", (t,)).fetchall()]
            for role in ("dispatcher", "planner", "finance"):
                sub = str(uuid.uuid4()); ctx.seed_tokens[role] = sub
                # Disposition mit Dienstscope über alle Dienste des Mandanten (ohne Scope liefert die Auftragsübersicht keine Buchungen)
                admin.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (%s,%s,%s,%s,%s,CAST(%s AS uuid[]))",
                              (t, uuid.uuid4(), idp.issuer, sub, role, "{" + ",".join(str(x) for x in services) + "}" if role == "dispatcher" else None))
            ctx.rigs = W.provision_drivers(app, idp, admin, W.TenantWorld(ctx.label, t), 4, label_prefix="P" + uuid.uuid4().hex[:6])
            ctxs.append(ctx)
        bworlds = [W.build_tenant(app, idp, admin, lab, 10, 20, name=f"Buchungsmandant {lab} (Last)") for lab in ("A", "B")]
        for ctx in ctxs:
            asyncio.run(S.prepare_cursors(app, idp, ctx))
        foreign: dict[str, set] = {}
        for ctx in ctxs:
            admin.execute("SELECT set_config('app.tenant_id', %s, false)", (ctx.tenant_id,))
            ids_ = {r[0].__str__() for tbl in ("service", "vehicle") for r in admin.execute(f"SELECT id FROM taktovia.{tbl} WHERE tenant_id = %s", (ctx.tenant_id,)).fetchall()}  # sql-lint: ok festes Tabellenverzeichnis
            foreign[ctx.label] = ids_ | set(ctx.booking_ids)
        # 3. Lastmix
        run = asyncio.run(S.run_mix(app, idp, admin_factory, ctxs, bworlds, duration_s=duration, warmup_s=warmup, sessions_per_tenant=sessions, think_s=think, seed=seed))
        rec = run["rec"]
        classes = S.per_class(None, rec.measured())
        side = asyncio.run(S.timing_side_channel(app, idp, ctxs[0], ctxs[1]))
        cache = S.cache_probe(app, idp, ctxs[0], ctxs[1], foreign)
        env = F.environment_state(db_name)
        role = admin.execute("SELECT r.rolname, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = 'taktovia_runtime'").fetchone()
        rls = admin.execute("SELECT count(*) FROM pg_class WHERE relnamespace = 'taktovia'::regnamespace AND relkind = 'r' AND relrowsecurity AND relforcerowsecurity").fetchone()[0]
        measured_n = len(rec.measured())
        span = (max(r.t for r in rec.measured()) - min(r.t for r in rec.measured())) or 1.0
        rows = []
        for label, st in classes.items():
            if label.startswith("POST /v1/ride-offers"):
                continue
            rows.append({"klasse": label, **{k: st.get(k) for k in ("n", "p50_ms", "p95_ms", "p99_ms", "max_ms", "error_count", "error_rate", "shed_429", "rps")}, "status": st["status"],
                         "urteil": "erfüllt" if st["p95_ms"] <= TARGET_MS else "verfehlt"})
        verfehlt = [r["klasse"] for r in rows if r["urteil"] == "verfehlt"]
        proto = {
            "protokoll_version": PROTOKOLL_VERSION, "wp": "WP-M5-11", "anforderung": "LH-6.19-NFR-002", "ziel": "p95 <= 1000 ms je Endpunktklasse", "status_zielwert": "vorläufig, D-05 offen",
            "messpunkt": "Clientzeit des Lastgenerators auf derselben Maschine (obere Schranke der serverseitigen Zeit; Gateway nicht vorhanden, OP-04); 202 zählt bis zur Annahme",
            "gestartet": started.isoformat(), "build": (_git("rev-parse", "HEAD") or "unversioniert") + ("+lokal" if _git("status", "--porcelain", "--untracked-files=no") else ""),
            "migrationsstand": {"letzte_versionsmigration": env["letzte_versionsmigration"], "angewendet": env["migrationen_angewendet"], "postgresql": env["postgresql"], "postgis": env["postgis"]},
            "seed": seed, "skala": {"name": scale_name, **scale.__dict__}, "hardware": hardware.collect(),
            "datenvolumen": {"mandanten": counts, "befuellung_s": fill_s, "datenbankgroesse_bytes": env["datenbankgroesse_bytes"]},
            "rolle_und_rls": {"runtime_rolle": role[0], "superuser": role[1], "bypassrls": role[2], "tabellen_mit_erzwungener_rls": rls, "mandanten_in_der_messung": 2},
            "last": {"sitzungen_je_mandant": sessions, "think_s": think, "dauer_s": duration, "aufwaermen_s": warmup, "gemessene_anfragen": measured_n, "durchsatz_rps": round(measured_n / span, 1),
                     "mix_gewichte": S.MIX, "buchungsmandanten_mit_testflotte": 2,
                     "referenzlast_hinweis": "Angebotene Last des Generators auf geteilter Maschine; die Referenzspitze (Kap. 08 §8.6, Annahme OP-01) wird nicht erreicht, wenn durchsatz_rps darunter liegt"},
            "klassen": rows, "klassen_nicht_gemessen": {"POST /v1/inspections": "Prüflauf-Start braucht Checklistenvorlage und Freigabekette (kein Teil des Messaufbaus); gemessen wird der 404-Pfad von GET /v1/inspections/{id}"},
            "zeitseitenkanal": side, "cache_probe": cache,
            "urteil": "erfüllt" if not verfehlt else "verfehlt", "verfehlte_klassen": verfehlt,
        }
        (out_dir() / "protokoll.json").write_text(json.dumps(proto, indent=1, ensure_ascii=False, default=str), encoding="utf-8")
        (out_dir() / "protokoll.md").write_text(_md(proto), encoding="utf-8")
        _CACHE["p"] = proto
        return proto
    finally:
        app.stop(); idp.close(); admin.close()


def _with_tenant(admin, tenant):
    admin.execute("SELECT set_config('app.tenant_id', %s, false)", (tenant,))
    return admin


def _md(p: dict) -> str:
    h = p["hardware"]
    L = [f"# Lasttestprotokoll Standard-API (WP-M5-11, LH-6.19-NFR-002)", "", f"Beginn {p['gestartet']} · Build {p['build']} · Status Zielwert: **{p['status_zielwert']}**", "",
         f"- Ziel: {p['ziel']}; Messpunkt: {p['messpunkt']}",
         f"- Hardware: {h.get('cpu_model')} · {h.get('logical_cpus')} logische CPUs · {h.get('memory_total_mb', '?')} MB RAM · {h.get('os')}",
         f"- Migrationsstand: {p['migrationsstand']['letzte_versionsmigration']} ({p['migrationsstand']['angewendet']} Skripte), PostgreSQL {p['migrationsstand']['postgresql']}, Seed {p['seed']}",
         f"- Datenvolumen ({p['skala']['name']}): " + "; ".join(f"Mandant {i + 1}: " + ", ".join(f"{k} {v}" for k, v in c.items() if k in ("vehicle", "booking", "staff_shift", "passenger_private", "position_sample", "travel_attempt")) for i, c in enumerate(p["datenvolumen"]["mandanten"].values())),
         f"- Rolle und RLS: {p['rolle_und_rls']['runtime_rolle']} (Superuser {p['rolle_und_rls']['superuser']}, BYPASSRLS {p['rolle_und_rls']['bypassrls']}), {p['rolle_und_rls']['tabellen_mit_erzwungener_rls']} Tabellen mit erzwungener RLS, zwei Mandanten",
         f"- Last: {p['last']['sitzungen_je_mandant']} Sitzungen je Mandant, Denkzeit {p['last']['think_s']} s, {p['last']['gemessene_anfragen']} Anfragen in {p['last']['dauer_s']} s ({p['last']['durchsatz_rps']} req/s)", "",
         "| Klasse | n | p50 ms | p95 ms | p99 ms | max ms | Fehler | Urteil |", "|---|---:|---:|---:|---:|---:|---:|---|"]
    L += [f"| {r['klasse']} | {r['n']} | {r['p50_ms']} | {r['p95_ms']} | {r['p99_ms']} | {r['max_ms']} | {r['error_count']} | {r['urteil']} |" for r in p["klassen"]]
    s = p["zeitseitenkanal"]
    L += ["", f"Zeitseitenkanal (fremde UUID gegen eigene unbekannte UUID, je {s['n_je_art']}): Median {s['median_fremd_ms']} ms gegen {s['median_eigen_ms']} ms (Differenz {s['median_diff_ms']} ms), p95 {s['fremd']['p95_ms']} gegen {s['eigen_unbekannt']['p95_ms']} ms, Status {s['status_fremd']} und {s['status_eigen']}.",
          f"Cache-Probe: {p['cache_probe']['anfragen']} identische Pfadanfragen beider Mandanten parallel, {p['cache_probe']['fremde_ids_in_antworten']} Antworten mit Kennungen des anderen Mandanten.", "",
          f"**Urteil: {p['urteil']}**" + (f" (verfehlt: {', '.join(p['verfehlte_klassen'])})" if p["verfehlte_klassen"] else ""), "",
          "Nicht gemessen: " + "; ".join(f"{k}: {v}" for k, v in p["klassen_nicht_gemessen"].items()), "", f"Hinweis: {p['last']['referenzlast_hinweis']}"]
    return "\n".join(L) + "\n"


class StandardApiP95(unittest.TestCase):
    def test_p95_standard_api_unter_referenzlast(self):
        p = run_measurement()
        names = {r["klasse"] for r in p["klassen"]}
        for needed in ("GET /v1/me", "GET /v1/bookings (limit 100)", "GET /v1/operator/bookings (limit 100)", "GET /v1/bookings/{id}", "POST /v1/bookings (Confirm)", "POST /v1/bookings/{id}/cancel", "GET /v1/services", "GET /v1/service-areas", "GET /v1/stops",
                       "GET /v1/operator/vehicles", "POST /v1/driver/events", "GET /v1/driver/shifts/{id}/run", "GET /v1/defects", "GET /v1/rosters", "GET /v1/time-events", "GET /v1/config/effective",
                       "GET /v1/analytics/metrics", "GET /v1/inspections/{id} (404-Pfad)", "GET /v1/expenses/{id} (404-Pfad)", "GET /v1/operations/{id} (404-Pfad)"):
            self.assertIn(needed, names, "Endpunktklasse nicht gemessen")
        for r in p["klassen"]:
            self.assertGreater(r["n"], 20, r["klasse"])
            self.assertTrue(r["p50_ms"] <= r["p95_ms"] <= r["p99_ms"], r)                                   # Perzentile je Klasse (AK 1)
            self.assertEqual(r["error_count"], 0, f"{r['klasse']}: Transportfehler oder 5xx")
        self.assertIn("cpu_model", p["hardware"]); self.assertTrue(p["datenvolumen"]["mandanten"]); self.assertTrue(p["build"]); self.assertTrue(p["migrationsstand"]["letzte_versionsmigration"])
        # AK 3: Runtime-Rolle ohne Privilegien, RLS, zwei Mandanten
        self.assertEqual((p["rolle_und_rls"]["superuser"], p["rolle_und_rls"]["bypassrls"], p["rolle_und_rls"]["mandanten_in_der_messung"]), (False, False, 2))
        self.assertGreater(p["rolle_und_rls"]["tabellen_mit_erzwungener_rls"], 50)
        # AK 2: Ziel erreicht oder jede verfehlende Klasse einzeln ausgewiesen (Urteil je Klasse im Protokoll; der Test erzwingt Ehrlichkeit, nicht den Erfolg: Zielwert vorläufig, D-05)
        for r in p["klassen"]:
            self.assertEqual(r["urteil"], "erfüllt" if r["p95_ms"] <= TARGET_MS else "verfehlt", r["klasse"])
        self.assertEqual(p["verfehlte_klassen"], [r["klasse"] for r in p["klassen"] if r["urteil"] == "verfehlt"])
        self.assertEqual(p["urteil"], "erfüllt" if not p["verfehlte_klassen"] else "verfehlt")
        self.assertEqual(p["verfehlte_klassen"], [], f"p95 > 1 s in Klassen {p['verfehlte_klassen']}: Ursache und Maßnahme im Protokoll ausweisen und beheben")

    def test_listenabfrage_limit_100_und_tiefe_paginierung_im_ziel(self):
        p = run_measurement()
        rows = {r["klasse"]: r for r in p["klassen"]}
        for k in ("GET /v1/operator/bookings (limit 100)", "GET /v1/operator/bookings (limit 100, Folgeseiten)", "GET /v1/bookings (limit 100)"):
            self.assertIn(k, rows)
            self.assertLessEqual(rows[k]["p95_ms"], TARGET_MS, k)
            self.assertEqual(set(rows[k]["status"]), {"200"}, rows[k])

    def test_fremde_uuid_404_ohne_zeitseitenkanal(self):
        s = run_measurement()["zeitseitenkanal"]
        self.assertEqual((s["status_fremd"], s["status_eigen"]), ([404], [404]))                          # fremde UUID: 404 wie eine eigene unbekannte
        self.assertLessEqual(s["fremd"]["p95_ms"], TARGET_MS); self.assertLessEqual(s["eigen_unbekannt"]["p95_ms"], TARGET_MS)
        # kein Zeitseitenkanal über die Schwelle hinaus: Mediane weichen um höchstens 15 ms oder 50 % ab
        self.assertLessEqual(s["median_diff_ms"], max(15.0, 0.5 * min(s["median_fremd_ms"], s["median_eigen_ms"])), s)

    def test_cache_liefert_nie_fremden_mandanten_aus(self):
        c = run_measurement()["cache_probe"]
        self.assertGreater(c["anfragen"], 400)
        self.assertEqual(c["fremde_ids_in_antworten"], 0, c["beispiele"])
        self.assertEqual(set(c["status"]), {"200"}, c["status"])


class Werkzeug(unittest.TestCase):
    """Ohne Anwendung lauffähig: die Auswertungsbausteine der Messung (kein Ersatz für den Messlauf)."""

    def test_perzentile_je_klasse_nach_nearest_rank_und_gruppierung(self):
        recs = [aload.Rec(i / 10, "A", "T", 200, float(ms)) for i, ms in enumerate(range(1, 101))] + [aload.Rec(0, "B", "T", 200, 5.0)]
        out = S.per_class(None, recs)
        self.assertEqual((out["A"]["n"], out["A"]["p50_ms"], out["A"]["p95_ms"], out["A"]["p99_ms"]), (100, 50.0, 95.0, 99.0))
        self.assertEqual(out["B"]["n"], 1)
        self.assertEqual(out["A"]["status"], {"200": 100})

    def test_alle_geforderten_endpunktklassen_haben_eine_bezeichnung_und_ein_gewicht(self):
        for needed in ("me", "bookings_list", "operator_list", "operator_deep", "bookings_read", "bookings_confirm", "bookings_cancel", "services", "service_areas", "stops", "vehicles", "driver_events", "driver_run", "inspections", "defects",
                       "rosters", "time_events", "expenses", "config", "operations", "metrics"):
            self.assertIn(needed, S.CLASS_LABELS)
        self.assertTrue(set(S.MIX) <= set(S.CLASS_LABELS))
        self.assertEqual(len(S.CLASS_LABELS), 21)


if __name__ == "__main__":
    unittest.main()
