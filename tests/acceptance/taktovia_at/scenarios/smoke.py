"""Technischer Rauchtest ohne Fachbezug (WP-M0-22): Datenbankrolle, Mandantenkontext, Seed, gemeinsame Testzeit.

Läuft gegen die echte Datenbank unter der Runtime-Rolle und gegen PSP-Simulator und Sandbox-Partner (in-process
gestartet oder über TAKTOVIA_AT_PSP_URL / TAKTOVIA_AT_PARTNER_A_URL). Simulator-Anteile sind Simulator-Ergebnisse,
kein Nachweis gegen einen realen PSP oder Partner.
"""
import os

from taktovia_testkit.jsonhttp import http_call

from ..db import tx
from ..scenario import scenario
from ..seed import COST_CENTER_REF


def _sims(ctx):
    """Startet PSP-Simulator und Sandbox-Partner A in-process, falls keine externen URLs gesetzt sind."""
    stop = []
    psp_url, partner_url = os.environ.get("TAKTOVIA_AT_PSP_URL"), os.environ.get("TAKTOVIA_AT_PARTNER_A_URL")
    if not psp_url:
        import psp_sim
        sim = psp_sim.PspSimulator(seed=1, clock_start=ctx.clock_start)
        app = psp_sim.build_app(sim)
        app.start()
        psp_url = app.url
        stop.append(app)
    if not partner_url:
        import partner_sandbox
        run = partner_sandbox.launch(("A",), 1, ctx.clock_start)
        partner_url = run["A"][1].url
        stop.append(run["A"][1])
    return psp_url, partner_url, stop


@scenario("TECH-SMOKE-001", "1.0.0", ["ENABLER:WP-M0-22"], "Technischer Rauchtest (Rolle, Mandantenkontext, Seed, Testzeit)")
def smoke(ctx):
    ids, db = ctx.ids, ctx.db_name
    ctx.use_role("dispatcher", "A", ctx.identities["A"]["dispatcher"])
    ctx.use_role("dispatcher", "B", ctx.identities["B"]["dispatcher"])

    # 1. Anwendungsrolle ohne Sonderrechte (nicht Tabelleneigentümer, kein Superuser, kein BYPASSRLS)
    with tx(db, ids["A"]["tenant"]) as c:
        who = c.execute("SELECT current_user, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user").fetchone()
        ctx.check("Rolle der Verbindung", ["taktovia_runtime", False, False], list(who))
        # 2. Mandantenkontext: A sieht genau seine Zeilen, keine von B
        own = c.execute("SELECT count(*) FROM taktovia.passenger_private").fetchone()[0]
        foreign = c.execute("SELECT count(*) FROM taktovia.passenger_private WHERE id = %s", (ids["B"]["passenger"],)).fetchone()[0]
        ctx.check("A sieht eigene Fahrgäste (Anzahl)", 1, own)
        ctx.check("A sieht B-Fahrgast per bekannter UUID (Anzahl)", 0, foreign)
        cc = c.execute("SELECT kind, business_ref FROM taktovia.cost_object").fetchall()
        ctx.check("Kostenstelle in A", [("COST_CENTER", COST_CENTER_REF)], cc)
    with tx(db, ids["B"]["tenant"]) as c:
        cc = c.execute("SELECT kind, business_ref FROM taktovia.cost_object").fetchall()
        ctx.check("identisch benannte Kostenstelle in B", [("COST_CENTER", COST_CENTER_REF)], cc)
    with tx(db, None) as c:
        ctx.check("ohne Kontext keine Zeile", 0, c.execute("SELECT count(*) FROM taktovia.vehicle").fetchone()[0])
    ctx.check("Seed lief ohne Sonderrechte", False, ctx.seed_info["privileged"])
    ctx.check("Rolle Mandantenanlage (Seed-Verfahren)", ["taktovia_migrator"], ctx.seed_info["tenant_creation_role"])
    ctx.check("Rolle Seed-Daten", ["taktovia_runtime"], ctx.seed_info["data_role"])

    # 3. Gemeinsame Testzeit für Simulator und Sandbox
    psp_url, partner_url, stop = _sims(ctx)
    try:
        ctx.register_sim("psp-simulator", psp_url)
        ctx.register_sim("sandbox-partner-A", partner_url)
        ctx.set_time(ctx.clock_start)
        for name, url in (("PSP-Simulator", psp_url), ("Sandbox-Partner A", partner_url)):
            now = http_call("GET", url + "/control/clock")[1]["now"]
            ctx.check(f"Uhr {name} entspricht Testzeit", ctx.clock.now_iso(), now)
        trace = ctx.new_trace()
        s, pay, _, _ = http_call("POST", psp_url + "/psp/v1/payments",
                                 {"merchant_ref": f"SMOKE-{trace[:8]}", "amount_minor": 1000, "currency": "EUR"},
                                 {"Idempotency-Key": f"smoke-{trace[:8]}"})
        ctx.check("PSP-Simulator Authorize (HTTP, Zustand)", [201, "AUTHORIZED"], [s, pay["state"]])
        ctx.check("PSP-Zeitstempel aus Testzeit", ctx.clock.now_iso(), pay["created_at"])
        evs = http_call("GET", psp_url + "/control/events")[1]["events"]
        ctx.event_ids += [e["event_id"] for e in evs]
        s, r, _, _ = http_call("POST", partner_url + "/v1/partner/reservations",
                               {"command_id": f"c-{trace[:8]}", "federation_ref": f"fed-{trace[:8]}", "service_ref": "SVC-A-MAIN"},
                               {"X-Caller-Id": "taktovia-core"})
        ctx.check("Sandbox-Partner Hold (HTTP, Zustand)", [201, "HELD"], [s, r["state"]])
        ctx.check("Hold-Ablauf aus Sandboxuhr (180 s)", "2026-01-01T00:03:00.000Z" if ctx.clock_start == "2026-01-01T00:00:00Z" else r["expires_at"], r["expires_at"])
    finally:
        for app in stop:
            app.stop()
