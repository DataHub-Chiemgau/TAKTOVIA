"""Testhilfen des Clusters wave16-c3 (WP-M5-28): synthetisches, signiertes Release-Artefakt, Datenbankaufbau je Umgebung, Migrator über db.py."""
from __future__ import annotations

import copy
import os
import subprocess
import sys
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
for p in (REPO / "infra" / "rollout", REPO / "tools" / "release_gate", REPO / "db"):
    sys.path.insert(0, str(p))
import gate  # noqa: E402
from taktovia_rollout import datenbank, domaenen  # noqa: E402
sys.path.insert(0, str(REPO / "infra"))
sys.path.insert(0, str(REPO / "infra" / "availability"))
from taktovia_availability import probemandant as availability_probemandant  # noqa: E402

KEY = "k" * 40
GREEN = ["{python}", "-c", "import sys; sys.exit(0)"]
RED = ["{python}", "-c", "import sys; print('absichtlich rot'); sys.exit(1)"]
HAS_DB = bool(os.environ.get("TAKTOVIA_DB_ADMIN_PASSWORD") and os.environ.get("TAKTOVIA_DB_RUNTIME_PASSWORD"))
if not HAS_DB and os.environ.get("TAKTOVIA_REQUIRE_DB") == "true":
    raise AssertionError("Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true)")


def synthetic_config(red_stage: str | None = None) -> dict:
    """Die echten Stufen des Release-Gates, jede mit einer einzigen Befehlsprüfung (grün; in `red_stage` rot)."""
    cfg = copy.deepcopy(gate.load_config())
    for s in cfg["stufen"]:
        s["pruefungen"] = [{"id": f"{s['id']}-probe", "art": "befehl", "argv": RED if s["id"] == red_stage else GREEN}]
    return cfg


def release_artefakt(out: Path, red_stage: str | None = None) -> tuple[Path | None, str, dict]:
    """Erzeugt ein Release-Artefakt mit dem ECHTEN Mechanismus des Release-Gates (CI-Modus, Schlüssel aus der Umgebung); Stufeninhalte sind synthetisch grün/rot."""
    cfg = synthetic_config(red_stage)
    with mock.patch.dict(os.environ, {gate.KEY_ENV: KEY}):
        res = gate.run_gate(cfg, modus="ci")
        path, why = gate.write_release(res, out)
    return path, why, cfg


def env_for(name: str, environment: str) -> dict:
    e = dict(os.environ)
    e["TAKTOVIA_DB_NAME"], e["TAKTOVIA_ENVIRONMENT"] = name, environment
    return e


def db_py(step: str, name: str, environment: str) -> None:
    r = subprocess.run([sys.executable, str(REPO / "db" / "db.py"), step], cwd=REPO, env=env_for(name, environment), capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError(f"db.py {step} ({name}, {environment}): {r.stderr[-600:]}")


def drop_databases(*names: str) -> None:
    import db as dbtool
    import psycopg
    with dbtool.admin("postgres") as c:
        for n in names:
            c.execute(psycopg.sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(psycopg.sql.Identifier(n)))


class DbMigrator:
    """Migrator über `db/db.py migrate` mit dem Migrationszugang (taktovia_migrator); `rolle` benennt die Rolle, unter der migriert wird."""

    def __init__(self, name: str, environment: str, rolle: str = "taktovia_migrator") -> None:
        self.name, self.environment, self.rolle = name, environment, rolle
        self.zugang = domaenen.lade_matrix()[environment]["datenbank"]["migrationszugang"]
        self.aufgerufen = 0

    def migrieren(self) -> None:
        self.aufgerufen += 1
        db_py("migrate", self.name, self.environment)

    def migrationsstand(self) -> dict:
        import db as dbtool
        with dbtool.admin(self.name) as c:
            return datenbank.migrationsstand(c)
