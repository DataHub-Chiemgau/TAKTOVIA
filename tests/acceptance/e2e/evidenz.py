"""Evidenz des Referenzfalls als Wiederholung von AT-01 (WP-M5-23, Kap. 08 §8.4): Evidenzdatensatz nach dem Schema des Akzeptanzgerüsts mit Build, Soll/Ist je Schritt, Ereignis- und
Trace-IDs (Korrelationskennungen der Antworten) und dem Wächterbericht. Der Datensatz trägt `acceptance_decision: PENDING` und benennt die Ersatzkomponenten."""
from pathlib import Path

from taktovia_at import db as atdb
from taktovia_at.scenario import execute

from .referenzwelt import ERSATZ

META = {"id": "AT-01", "version": "1.0.0-m5-staging-ersatz", "source_key": ["LH-6.20-QAT-003"], "kind": "AT", "name": "Referenzfall durchgängig (Wiederholung von AT-01, Ersatzumgebung statt Staging)",
        "responsible": "Plattform (QA)"}


def aufzeichnen(faelle, waechter_bericht, kennzahlen_pruefungen, out_dir, seed_db="taktovia_at_e2e_seed"):
    """Führt `execute` mit einer vorbereiteten Funktion aus (die Fälle laufen vorher; hier werden ihre Schritte als Soll/Ist-Prüfungen festgehalten). Liefert (Datensatz, Pfad)."""
    atdb.reset_database(seed_db)
    try:
        def fn(ctx):
            for f in faelle:
                ctx.roles.append({"role": "passenger" if f.fahrgast else "none", "tenant_id": f.mandant.id, "identity": f"e2e.passenger.{f.mandant.name.split()[-1].lower()}"})
                for s in f.schritte:
                    ctx.check(f"[{f.mandant.name}] {s['step']}", _plain(s["soll"]), _plain(s["ist"]))
                    ids = s.get("ids") or {}
                    if ids.get("korrelation"):
                        ctx.trace_ids.append(str(ids["korrelation"]))
                    for k in ("event_id", "booking_id", "hold_id", "operation_id"):
                        if ids.get(k):
                            ctx.event_ids.append(str(ids[k]))
            for name, soll, ist in kennzahlen_pruefungen:
                ctx.check(name, _plain(soll), _plain(ist))
            ctx.check("Wächter: keine Schreibzugriffe außerhalb der Runtime-Rolle und des gekennzeichneten Testaufbaus", [], waechter_bericht["befunde"])
            ctx.check("Wächter: Schreibzugriffe der Anwendung vorhanden (Runtime-Rolle)", True, bool(waechter_bericht["runtime"]))
            ctx.simulated.extend(ERSATZ)
        fn.meta = META
        record, path, error = execute("AT-01", seed_db, out_dir=Path(out_dir), environment="lokale Ersatzumgebung (kein Staging)", fn=fn)
        return record, path
    finally:
        atdb.drop_database(seed_db)


def _plain(x):
    if isinstance(x, tuple):
        return [_plain(i) for i in x]
    return x
