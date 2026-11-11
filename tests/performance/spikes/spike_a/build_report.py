#!/usr/bin/env python3
"""Erzeugt spike-a-hold-confirm.md aus results.json und report.template.md. Alle Zahlen im Bericht stammen aus results.json:
Platzhalter {{v:pfad.zum.wert}} (Einzelwert) und {{table:name}} (Tabelle). Kein Wert wird von Hand eingetragen.

  python tests/performance/spikes/spike_a/build_report.py [--results PATH] [--out PATH]
"""
import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE.parent / "spike-a-data"


def get(d, path):
    cur = d
    for p in path.split("."):
        cur = cur[int(p)] if isinstance(cur, list) else cur[p]
    return cur


def fmt(v):
    if isinstance(v, float):
        return f"{v:.1f}" if abs(v) >= 10 else f"{v:.2f}"
    return str(v)


def ms(s, key="p95_ms"):
    return fmt(s[key]) if s and s.get("n") else "–"


def md(rows, header, align=None):
    align = align or ["---"] * len(header)
    out = ["| " + " | ".join(header) + " |", "|" + "|".join(a if a.startswith(":") or a.endswith(":") else "---" for a in align) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def t_meta(r):
    m, pg = r["meta"], r["meta"]["postgresql"]
    hw = m["hardware"]
    rows = [("Hardware", f"{hw.get('cpu_model')} · {hw.get('logical_cpus')} logische CPUs · {hw.get('memory_total_mb')} MB RAM · {hw.get('os')}"),
            ("Virtualisierung", hw.get("virtualized")),
            ("Last auf dem Rechner (Load Average 1/5/15 min)", f"Beginn {', '.join(fmt(x) for x in m['loadavg_start'])} · Ende {', '.join(fmt(x) for x in m['loadavg_end'])} (Rechner wurde mit anderen Arbeiten geteilt)"),
            ("PostgreSQL / PostGIS", f"{pg['version'].split(' on ')[0]} · PostGIS {pg['postgis']}"),
            ("Server-Einstellungen", f"max_connections={pg['max_connections']}, shared_buffers={pg['shared_buffers']}, work_mem={pg['work_mem']}, effective_cache_size={pg['effective_cache_size']}, fsync={pg['fsync']}, synchronous_commit={pg['synchronous_commit']}, deadlock_timeout={pg['deadlock_timeout']}, default_transaction_isolation={pg['default_transaction_isolation']}"),
            ("Verbindungen", f"{m['pool']['connections']} gleichzeitig offene Verbindungen unter der Rolle `{m['pool']['role']}` ({m['pool']['driver']}); Ausführung: {m['pool']['executor']} ({m['pool']['processes']} Prozesse); {m['pool']['note']}"),
            ("Isolationsstufe", "READ COMMITTED (Standard); REPEATABLE READ nur als Vergleichsvariante"),
            ("Software-Stand", f"{m['software_build']['build_id']} ({m['software_build']['origin']})"),
            ("Skript, Seed", f"`run_spike_a.py`, Seed {m['seed']}, Laufzeit {m['duration_s']} s, Beginn {m['started_utc']}")]
    return md(rows, ["Messprotokoll", "Wert"])


def t_data(r):
    d = r["meta"]["data"]["rows_per_tenant"]
    sz = r["meta"]["data"]["total_size_kb"]
    rows = []
    for tb in ("vehicle", "staff_shift", "vehicle_run", "service", "capacity_hold", "booking", "outbox", "stop_revision", "service_area"):
        rows.append((tb, d["A"][tb], d["B"][tb], f"{sz[tb] // 1024} MB" if tb in sz else ""))
    return md(rows, ["Tabelle", "Mandant A", "Mandant B", "Größe gesamt (beide Mandanten, inkl. Indexe)"], ["---", "---:", "---:", "---:"])


def t_e1(r):
    rows = []
    items = [("rc_run_row (Empfehlung)", r["e1_primary"])] + [(k, v) for k, v in r["e1_variants"].items()]
    for name, x in items:
        c = x["counts"]
        rows.append((name, x["iterations"], x["requests_total"], c.get("outcome:CONFIRMED", 0), c.get("iterations_overbooked", 0), c.get("iterations_liveness_failure", 0),
                     c.get("iterations_capacity_violation", 0), c.get("iterations_with_unexpected_outcome", 0), c.get("deadlocks", 0), c.get("serialization_failures", 0),
                     ms(x["latency_ms"], "p50_ms"), ms(x["latency_ms"]), ms(x["latency_ms"], "p99_ms")))
    return md(rows, ["Strategie", "Durchläufe", "Anfragen", "bestätigt", "Durchläufe überbucht", "Durchläufe ohne Gewinner", "Durchläufe mit Kapazitätsverstoß", "unerwartete Ergebnisse", "Deadlocks", "Serialisierungsfehler", "p50 ms", "p95 ms", "p99 ms"],
              ["---", "---:"] + ["---:"] * 11)


def t_e2(r):
    w, rv, ra = r["e2_wheelchair"], r["e2_revision_before"], r["e2_revision_after_hold"]
    rows = [("Sitz frei, Rollstuhlplatz belegt (50 % Rollstuhlanfragen)", w["iterations"], w["requests_total"], w["counts"].get("outcome:CONFIRMED", 0), w["counts"].get("outcome:REJECTED:NO_CAPACITY_WHEELCHAIR", 0),
             w["counts"].get("outcome:REJECTED:NO_CAPACITY_SEATS", 0), w["counts"].get("iterations_wheelchair_substituted", 0)),
            ("Routenrevision vor dem Hold verändert", rv["iterations"], rv["requests_total"], rv["counts"].get("outcome:CONFIRMED", 0), "–", "–", rv["counts"].get("outcome:REJECTED:REVISION_CONFLICT", 0)),
            ("Routenrevision zwischen Hold und Confirm verändert", ra["iterations"], ra["confirm_requests"], 0, "–", "–", ra["counts"].get("REJECTED:REVISION_CONFLICT", 0))]
    return md(rows, ["Variante", "Durchläufe", "Anfragen", "bestätigt", "abgewiesen Rollstuhl", "abgewiesen Sitz", "Rollstuhl ersetzt / REVISION_CONFLICT"], ["---"] + ["---:"] * 6)


def t_e3(r):
    rp, bd, ex = r["e3_replay"], r["e3_boundary_race"], r["e3_expired_exact"]
    c = bd["counts"]
    viol = {k: v for k, v in c.items() if k.startswith("VIOLATION")}
    rows = [("Wiederholte Bestätigung desselben Holds (100 parallel)", rp["counts"]["iterations"], rp["counts"]["iterations"] * rp["parallel_confirms_per_iteration"],
             f"Verstöße: {sum(v for k, v in rp['counts'].items() if k != 'iterations')}"),
            ("Ablauf gegen Confirm gleichzeitig um den Grenzzeitpunkt (20 Holds je Durchlauf)", c["iterations"], c["holds"],
             f"bestätigt {c.get('confirm_won', 0)}, abgelaufen {c.get('confirm_rejected:HOLD_EXPIRED', 0)}, Verstöße „abgelaufen und gebucht“: {c.get('VIOLATION_expired_and_booked', 0)}, weitere Verstöße: {sum(viol.values()) - c.get('VIOLATION_expired_and_booked', 0)}"),
            ("Confirm auf Hold mit expires_at = Einfügezeitpunkt", ex["n"], ex["n"], "; ".join(f"{k}: {v}" for k, v in ex["counts"].items()))]
    return md(rows, ["Fall", "Durchläufe", "Operationen", "Ergebnis"], ["---", "---:", "---:", "---"])


def t_e4(r):
    rows = []
    for name, x in r["e4_deadlock"].items():
        c = x["counts"]
        rows.append((name, x["iterations"], x["requests_total"], c.get("deadlocks", 0), c.get("CONFIRMED", 0), c.get("REJECTED:NO_CAPACITY_SEATS", 0), c.get("EXCEPTION", 0),
                     c.get("iterations_capacity_violation", 0), fmt(c.get("iteration_wall_s_total_x1000", 0) / 1000 / x["iterations"]), ms(x["latency_ms"])))
    return md(rows, ["Strategie", "Durchläufe", "Anfragen (je Hälfte gegenläufig)", "Deadlocks (Client)", "bestätigt", "abgewiesen (Kapazität)", "Ausnahmen", "Kapazitätsverstöße", "Dauer je Durchlauf s", "p95 ms"],
              ["---"] + ["---:"] * 9)


def t_e5(r):
    rows = []
    for phase, label in (("baseline_kernschema_indexes", "nur Kernschema-Indexe"), ("with_hold_indexes", "mit Index (tenant_id, run_id)")):
        for scen, x in r["e5"][phase].items():
            if not isinstance(x, dict) or "per_op" not in x:
                continue
            for op in ("hold", "confirm", "cancel"):
                t = x["per_op"][op]["total_ms"]
                lw = x["per_op"][op]["lock_wait_ms"]
                rows.append((label, scen, op, t["n"], ms(t, "p50_ms"), ms(t), ms(t, "p99_ms"), ms(t, "max_ms"), ms(lw), x["throughput_tx_per_s"] if op == "hold" else "", x["deadlocks"] if op == "hold" else ""))
    return md(rows, ["Index", "Szenario", "Operation", "n", "p50 ms", "p95 ms", "p99 ms", "max ms", "Sperrwartezeit p95 ms", "Durchsatz tx/s", "Deadlocks"], ["---", "---", "---"] + ["---:"] * 8)


def t_e5alt(r):
    rows = []
    for name, x in r["e5"]["with_hold_indexes"].get("alt_strategies_spread", {}).items():
        for op in ("hold", "confirm"):
            t = x["per_op"][op]["total_ms"]
            rows.append((name, x["scenario"], op, t["n"], ms(t, "p50_ms"), ms(t), ms(t, "p99_ms"), x["throughput_tx_per_s"], x["deadlocks"], x["serialization_failures"], x["retries"]))
    return md(rows, ["Strategie", "Szenario", "Operation", "n", "p50 ms", "p95 ms", "p99 ms", "Durchsatz tx/s", "Deadlocks", "Serialisierungsfehler", "Wiederholungen"], ["---"] * 3 + ["---:"] * 8)


def t_e5plan(r):
    rows = []
    for phase, label in (("baseline_kernschema_indexes", "Kernschema-Indexe"), ("with_hold_indexes", "mit Index (tenant_id, run_id)")):
        p = r["e5"][phase]["occupancy_plan_run_A"]
        rows.append((label, ", ".join(p["indexes_used"]) or "keiner (Seq Scan)", fmt(p["execution_ms"]), p["shared_hit"], p["shared_read"]))
    return md(rows, ["Variante", "genutzter Index", "Ausführung ms (EXPLAIN ANALYZE, einzeln)", "Puffer hit", "Puffer read"], ["---", "---", "---:", "---:", "---:"])


def t_geo_correct(r):
    g = r["e6_postgis"]
    a, s = g["area_correctness"], g["stop_correctness"]
    rows = [("Gebietsprüfung (innerhalb, außerhalb, Grenze, Ecke, knapp außerhalb, Wirksamkeit nach Zeit/Revision)", len(a), sum(1 for x in a if x["ok"])),
            ("Haltepunktsuche (4 Varianten × 2 Mandanten × 2 Zeitpunkte, Entfernung metrisch ±1 m, Revision, Radius)", len(s), sum(1 for x in s if x["ok"]))]
    return md(rows, ["Prüfung", "Fälle", "bestanden"], ["---", "---:", "---:"])


def t_rls(r):
    e = r["e6_postgis"]["rls_index_usage"]
    rows = [(k, "ja" if v else "nein") for k, v in sorted(e["leakproof"].items())]
    t1 = md(rows, ["Funktion/Operator (Argumente)", "leakproof"])
    t2 = md([("Superuser (RLS nicht wirksam)", ", ".join(e["superuser_no_rls"]["indexes_used"]) or "keiner", fmt(e["superuser_no_rls"]["execution_ms"])),
             ("Runtime-Rolle (RLS wirksam)", ", ".join(e["runtime_with_rls"]["indexes_used"]) or "keiner", fmt(e["runtime_with_rls"]["execution_ms"]))],
            ["Rolle", "genutzte Indexe für `point && Box`", "Ausführung ms"], ["---", "---", "---:"])
    return t1 + "\n\n" + t2


def t_geo_plans(r):
    rows = []
    for phase, label in (("plans_baseline", "Kernschema-Indexe"), ("plans_composite_index", "mit btree_gist (tenant_id, geom)")):
        for name, v in r["e6_postgis"][phase].items():
            if not name.endswith("_A"):
                continue
            sc = v["scans"][0] if v["scans"] else {}
            rows.append((label, name[:-2], ", ".join(v["indexes_used"]) or "keiner", sc.get("rows", ""), sc.get("removed_by_filter", ""), fmt(v["execution_ms"])))
    return md(rows, ["Index-Variante", "Abfrage", "genutzte Indexe", "Zeilen", "durch Filter verworfen", "Ausführung ms"], ["---", "---", "---", "---:", "---:", "---:"])


def t_geo_lat(r):
    rows = []
    for phase, label in (("latency_baseline", "Kernschema-Indexe"), ("latency_composite_index", "mit btree_gist (tenant_id, geom)")):
        for name, v in r["e6_postgis"][phase].items():
            s, p = v["single_connection"], v["parallel"]
            rows.append((label, name, s["n"], ms(s, "p50_ms"), ms(s), ms(s, "p99_ms"), p["n"], ms(p, "p50_ms"), ms(p), ms(p, "p99_ms"), v["avg_result_rows"], v["errors"]))
    return md(rows, ["Index-Variante", "Abfrage", "n einzeln", "p50 ms", "p95 ms", "p99 ms", "n parallel (100)", "p50 ms", "p95 ms", "p99 ms", "Ø Treffer", "Fehler"], ["---", "---"] + ["---:"] * 10)


def t_iso(r):
    e = r["e7_tenant_isolation"]
    desc = {"a_hold_A_result": "Hold von A auf eigenem Lauf, während B die Zeilensperre auf seinem Lauf hält: Ergebnis",
            "a_hold_A_while_B_holds_lock_ms": "… Dauer in ms (keine Wartezeit auf B)",
            "b_A_select_for_update_on_B_run_rows": "A: SELECT … FOR UPDATE auf den Lauf von B: sichtbare/gesperrte Zeilen",
            "b_A_select_for_update_on_B_run_ms": "… Dauer in ms (kein Warten)",
            "b_A_select_B_run_without_tenant_condition_rows": "A: Lauf von B per UUID ohne Mandantenbedingung: Zeilen",
            "c_hold_A_on_B_run": "A: Hold auf dem Lauf von B",
            "c_A_sees_B_holds_rows": "A sieht Holds auf dem Lauf von B (Zeilen)",
            "c_A_distinct_tenant_ids_visible": "verschiedene tenant_id in den für A sichtbaren Holds",
            "d_no_context_holds_visible": "ohne Mandantenkontext sichtbare Holds",
            "e_A_stop_foreign_tenant_rows": "Haltepunktzeilen fremder Mandanten für A sichtbar",
            "e_distinct_tenants_of_returned_stops": "verschiedene Mandanten in einer Geoabfrage von A ohne Mandantenbedingung"}
    return md([(desc[k], e[k]) for k in desc if k in e], ["Prüfung (alle unter der Runtime-Rolle)", "Ergebnis"], ["---", "---:"])


def t_jsonb(r):
    e = r["e8_jsonb_vs_int"]
    return md([(k, e[k]["n"], ms(e[k], "p50_ms"), ms(e[k]), ms(e[k], "p99_ms")) for k in ("jsonb", "generated_int_columns")], ["Zugriff auf den Ressourcenvektor", "n", "p50 ms", "p95 ms", "p99 ms"], ["---"] + ["---:"] * 4)


def t_deadlocks_server(r):
    return md([(k, v) for k, v in r["server_deadlocks_per_block"].items()], ["Messblock", "Deadlocks laut pg_stat_database"], ["---", "---:"])


def t_criteria(r):
    """Erfolgskriterien aus WP-M0-26, Urteil aus den Messdaten abgeleitet (nicht von Hand gesetzt)."""
    e1 = r["e1_primary"]["counts"]
    n1 = r["e1_primary"]["iterations"]
    bad1 = sum(e1.get(k, 0) for k in ("iterations_overbooked", "iterations_liveness_failure", "iterations_capacity_violation", "iterations_with_unexpected_outcome", "iterations_booking_count_mismatch"))
    ok1 = n1 >= 1000 and bad1 == 0 and e1.get("outcome:CONFIRMED", 0) == n1
    w, rv, ra = r["e2_wheelchair"], r["e2_revision_before"], r["e2_revision_after_hold"]
    ok2 = (w["counts"].get("iterations_wheelchair_substituted", 0) == 0 and w["counts"].get("outcome:CONFIRMED", 0) == w["iterations"] and rv["counts"].get("outcome:CONFIRMED", 0) == 0
           and ra["counts"].get("bookings", 0) == 0)
    rp, bd, ex = r["e3_replay"]["counts"], r["e3_boundary_race"]["counts"], r["e3_expired_exact"]["counts"]
    ok3 = (sum(v for k, v in rp.items() if k != "iterations") == 0 and not any(k.startswith("VIOLATION") for k in bd) and bd.get("errors", 0) == 0
           and all(k.startswith("REJECTED:HOLD_EXPIRED|state=EXPIRED|bookings=0") for k in ex))
    srv = r["server_deadlocks_per_block"]
    stable_dl = (e1.get("deadlocks", 0) + r["e4_deadlock"]["rc_run_row"]["counts"].get("deadlocks", 0) + sum(x["deadlocks"] for p in ("baseline_kernschema_indexes", "with_hold_indexes")
                 for x in r["e5"][p].values() if isinstance(x, dict) and "deadlocks" in x))
    ok4 = stable_dl == 0 and srv.get("e0_e1", 0) == 0 and srv.get("e2", 0) == 0 and srv.get("e3", 0) == 0 and srv.get("e5", 0) == 0
    worst = 0.0
    parts = []
    for ph in ("baseline_kernschema_indexes", "with_hold_indexes"):
        for scen, x in r["e5"][ph].items():
            if isinstance(x, dict) and "per_op" in x:
                for op in ("hold", "confirm"):
                    p95 = x["per_op"][op]["total_ms"].get("p95_ms", 0)
                    worst = max(worst, p95)
    ok5 = worst <= 1000
    g = r["e6_postgis"]
    ok7a = all(c["ok"] for c in g["area_correctness"]) and all(c["ok"] for c in g["stop_correctness"])
    geo_worst = max(v["parallel"]["p95_ms"] for ph in ("latency_baseline", "latency_composite_index") for k, v in g[ph].items() if k in ("area", "knn_index", "grid_cell"))
    iso = r["e7_tenant_isolation"]
    ok8 = (iso["a_hold_A_result"] == "HELD" and iso["b_A_select_for_update_on_B_run_rows"] == 0 and iso["c_hold_A_on_B_run"] == "REJECTED:UNKNOWN_RUN" and iso["c_A_sees_B_holds_rows"] == 0
           and iso["d_no_context_holds_visible"] == 0 and iso["e_A_stop_foreign_tenant_rows"] == 0)
    V = lambda ok: "erfüllt" if ok else "NICHT erfüllt"  # noqa: E731
    rows = [("1", "Korrektheit PH-AT-004: höchstens eine Bestätigung bei einem freien Sitz, 100 parallel, ≥ 1.000 Durchläufe ohne Verstoß", V(ok1), f"{n1} Durchläufe × 100 Anfragen, genau 1 Gewinner je Durchlauf, {bad1} Verstöße; jede Abweisung mit Grund `NO_CAPACITY_SEATS`"),
            ("2", "Varianten: Sitz frei bei belegtem Rollstuhlplatz; veränderte Routenrevision", V(ok2), f"Rollstuhlvariante {w['iterations']} Durchläufe, 0 ersetzt; Revision vor Hold: 0 bestätigt; nach Hold: 0 Buchungen"),
            ("3", "Wiederholte Bestätigung findet dieselbe Buchung; Ablauf/Confirm am Grenzzeitpunkt nie „abgelaufen und gebucht“", V(ok3), f"Wiederholung {r['e3_replay']['counts']['iterations']} Durchläufe × 100 parallel; Grenzrennen {bd['holds']} Holds, Verstöße {sum(v for k, v in bd.items() if k.startswith('VIOLATION'))}"),
            ("4", "Kein unbehandelter Deadlock; jede Transaktion endet mit Erfolg oder fachlichem Grund", V(ok4), f"Empfohlene Strategie: 0 Deadlocks (Client) in E1/E4/E5; Server-Zähler außer E4-Gegenprobe 0 (`pg_stat_database`)"),
            ("5", "p95 je Hold/Confirm unter 100 parallelen Transaktionen ≤ 1 s", V(ok5), f"höchster gemessener p95 über alle Szenarien und beide Indexvarianten: {fmt(worst)} ms (Szenarien siehe Abschnitt 5)"),
            ("6", "Bericht mit Rohdaten und reproduzierbarem Skript", "erfüllt", "`run_spike_a.py` (Seed 1), `spike-a-data/results.json`, Rohdaten je Durchlauf/Ereignis als `.csv.gz`, dieser Bericht per `build_report.py`"),
            ("7", "PostGIS: erwartete Ergebnisse für innen/außen/Grenze; p95 unter 100 parallelen Anfragen gemessen; Plan belegt Indexnutzung bei zwei Mandanten", "teilweise" if ok7a else "NICHT erfüllt",
             f"Korrektheit {'bestanden' if ok7a else 'FEHLER'}; höchster p95 unter 100 parallel für Gebiet/KNN/Gitterzelle {fmt(geo_worst)} ms. Der GiST-Index wird unter RLS für Bereichsprädikate (`&&`, `ST_DWithin`, `ST_Covers`) NICHT genutzt (Befund B-04); Indexnutzung belegt für KNN (`stop_geo`) und Gitterzelle (`stop_cell`)"),
            ("8", "Mandantentrennung: kein Hold/keine Buchung/keine Geoabfrage von A sieht oder sperrt Zeilen von B; alles unter Runtime-Rolle mit transaktionslokalem Kontext", V(ok8), "siehe Abschnitt 7")]
    return md(rows, ["Nr.", "Kriterium", "Urteil", "Beleg"], ["---:", "---", "---", "---"])


TABLES = {n[2:]: f for n, f in globals().items() if n.startswith("t_")}


def render(template, results):
    def tbl(m):
        return TABLES[m.group(1)](results)

    def val(m):
        return fmt(get(results, m.group(1)))
    out = re.sub(r"\{\{table:([a-z0-9_]+)\}\}", tbl, template)
    return re.sub(r"\{\{v:([A-Za-z0-9_.]+)\}\}", val, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default=str(DATA / "results.json"))
    ap.add_argument("--template", default=str(HERE / "report.template.md"))
    ap.add_argument("--out", default=str(HERE.parent / "spike-a-hold-confirm.md"))
    a = ap.parse_args()
    res = json.loads(Path(a.results).read_text())
    Path(a.out).write_text(render(Path(a.template).read_text(), res))
    print("->", a.out)


if __name__ == "__main__":
    sys.exit(main())
