"""Szenario-Kennzeichnung, Ausführungskontext und Evidenzerzeugung."""
import hashlib
import re
from pathlib import Path

from . import AT_DIR, REPO
from . import evidence as ev
from .defects import classify
from .seed import fixture_ids, test_identities
from taktovia_testkit.testclock import SimClock, set_all

TEST_ID_RE = re.compile(r"^(AT-\d{2}|E2E-\d{3}|PH-AT-\d{3}|TECH-[A-Z0-9-]+)$")
SOURCE_KEY_RE = re.compile(r"^(LH-[0-9A-Za-z.]+-[A-Z]+-[0-9]+|ENABLER:WP-M[0-9a-z]+-[0-9]+)$")
DEFAULT_CLOCK_START = "2026-01-01T00:00:00Z"
REGISTRY = {}


class ScenarioError(Exception):
    pass


def kind_of(test_id):
    return "PH-AT" if test_id.startswith("PH-AT-") else "E2E" if test_id.startswith("E2E-") else "AT" if test_id.startswith("AT-") else "TECH"


def scenario(test_id, version, source_keys, name, responsible="Plattform (QA)"):
    """Kennzeichnet ein Szenario. Ohne gültige Test-ID, Version oder source_key[] wird es abgewiesen."""
    if not isinstance(test_id, str) or not TEST_ID_RE.match(test_id):
        raise ScenarioError(f"Szenario ohne gültige Test-ID: {test_id!r}")
    if not version:
        raise ScenarioError(f"{test_id}: Version fehlt")
    if not source_keys or any(not SOURCE_KEY_RE.match(k) for k in source_keys):
        raise ScenarioError(f"{test_id}: source_key[] fehlt oder ungültig: {source_keys!r}")

    def deco(fn):
        if test_id in REGISTRY:
            raise ScenarioError(f"{test_id} doppelt registriert")
        fn.meta = {"id": test_id, "version": version, "source_key": list(source_keys), "name": name,
                   "kind": kind_of(test_id), "responsible": responsible}
        REGISTRY[test_id] = fn
        return fn
    return deco


class Context:
    """Ausführungskontext eines Szenarios. Sammelt Soll/Ist, Rollen, Ereignis-/Trace-IDs."""

    def __init__(self, test_id, db_name, clock_start=DEFAULT_CLOCK_START, environment="local"):
        self.test_id, self.db_name, self.clock_start = test_id, db_name, clock_start
        self.clock = SimClock(clock_start)
        self.environment = environment
        self.checks, self.roles, self.event_ids, self.trace_ids = [], [], [], []
        self.simulated, self.sim_urls = [], []
        self.defect_categories, self.impairs_core = set(), False
        self.seed_info = None
        self.ids = fixture_ids()
        self.identities = test_identities()
        self._trace_n = 0

    # Testzeit (gemeinsame Vereinbarung): eigene Uhr und alle angemeldeten Simulatoren/Sandboxen
    def register_sim(self, name, url):
        self.simulated.append(name)
        self.sim_urls.append(url)

    def set_time(self, iso_time):
        from taktovia_testkit.testclock import parse_iso
        self.clock.set(parse_iso(iso_time))
        if self.sim_urls:
            set_all(self.sim_urls, iso_time)

    def check(self, step, expected, actual, ok=None):
        ok = (expected == actual) if ok is None else bool(ok)
        self.checks.append({"step": step, "expected": expected, "actual": actual, "ok": ok})
        return ok

    def use_role(self, role, tenant_label, identity):
        self.roles.append({"role": role, "tenant_id": str(self.ids[tenant_label]["tenant"]), "identity": identity})

    def new_trace(self):
        self._trace_n += 1
        t = hashlib.sha256(f"{self.test_id}|{self.clock_start}|trace|{self._trace_n}".encode()).hexdigest()[:32]
        self.trace_ids.append(t)
        return t

    def fail_with(self, *categories, impairs_core=False):
        self.defect_categories |= set(categories)
        self.impairs_core = self.impairs_core or impairs_core


def execute(test_id, db_name, clock_start=DEFAULT_CLOCK_START, out_dir=None, environment="local", fn=None):
    """Führt ein registriertes Szenario aus und liefert den (validierten) Evidenzdatensatz."""
    from .seed import seed_two_tenants
    fn = fn or REGISTRY.get(test_id)
    if fn is None:
        raise ScenarioError(f"unbekanntes Szenario {test_id}")
    meta = fn.meta
    ctx = Context(test_id, db_name, clock_start, environment)
    ctx.seed_info = seed_two_tenants(db_name)
    error = None
    try:
        fn(ctx)
    except Exception as e:  # noqa: BLE001 - unerwartete Ausnahme ist ein Fehlschlag des Szenarios
        error = f"{type(e).__name__}: {e}"
        ctx.check("unerwartete Ausnahme", "keine", error, ok=False)
    passed = bool(ctx.checks) and all(c["ok"] for c in ctx.checks)
    if not ctx.checks:
        ctx.check("mindestens eine Prüfung", ">= 1", 0, ok=False)
    result = "PASSED" if passed else "FAILED"
    defect = classify(ctx.defect_categories, ctx.impairs_core)["defect_class"] if result == "FAILED" else None
    s = ctx.seed_info
    record = {
        "schema_version": "1.0",
        "run_id": ev.run_id(test_id, s["data_state_sha256"], clock_start),
        "test": {"id": test_id, "version": meta["version"], "kind": meta["kind"], "name": meta["name"]},
        "source_key": meta["source_key"],
        "software_build": ev.software_build(),
        "migration_state": ev.migration_state(db_name),
        "revisions": {"config_revision": "NONE_M0", "tariff_revision": "NONE_M0", "contract_revision": "NONE_M0"},
        "seed": {"name": s["seed"], "version": s["seed_version"], "data_state_sha256": s["data_state_sha256"], "tenants": s["tenants"]},
        "roles_and_tenants": ctx.roles or [{"role": "none", "tenant_id": s["tenants"]["A"], "identity": "none.passenger"}],
        "soll_ist": ctx.checks,
        "event_trace_ids": {"event_ids": ctx.event_ids, "trace_ids": ctx.trace_ids},
        "result": result,
        "defect_class": defect,
        "responsible": meta["responsible"],
        "acceptance_decision": "PENDING",
        "executed_at": ctx.clock.now_iso(),
        "clock": {"start": clock_start, "mode": ctx.clock.mode},
        "environment": {"name": environment, "simulated_components": sorted(set(ctx.simulated))},
    }
    ev.validate(record)
    path = ev.write(record, Path(out_dir)) if out_dir else None
    return record, path, error
