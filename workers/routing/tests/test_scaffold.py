"""WP-M0-07: Gerüsttests (Import, OR-Tools-Trivialproblem, Health, Valhalla-Platzhalter)."""

import json
import threading
import urllib.request

import pytest

from taktovia_routing import __version__
from taktovia_routing.health import make_server
from taktovia_routing.selfcheck import solve_trivial_tsp
from taktovia_routing.valhalla import ValhallaAdapter


def test_version() -> None:
    assert __version__ == "0.0.1"


def test_ortools_solves_trivial_problem() -> None:
    nodes, cost = solve_trivial_tsp()
    assert cost == 80  # 0-1-3-2-0 = 10 + 25 + 30 + 15
    assert nodes[0] == 0 and nodes[-1] == 0 and sorted(nodes[:-1]) == [0, 1, 2, 3]


def test_health_endpoint() -> None:
    server = make_server("127.0.0.1", 0)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=5) as resp:
            assert resp.status == 200
            assert json.load(resp) == {"status": "UP", "worker": "routing"}
        with pytest.raises(urllib.error.HTTPError) as err:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/nope", timeout=5)
        assert err.value.code == 404
    finally:
        server.shutdown()
        server.server_close()


def test_valhalla_adapter_is_a_routing_provider() -> None:
    """Der Platzhalter (WP-M0-07) ist ab WP-M2-10 ein Adapter; Verhalten siehe
    test_routing_adapter.py."""
    adapter = ValhallaAdapter("http://127.0.0.1:9", "v1")
    assert adapter.name == "valhalla" and adapter.map_version() == "v1"


def test_no_database_dependency() -> None:
    """AK 3: kein Datenbanktreiber als Abhängigkeit, keine DB-Konfiguration im Worker."""
    import pathlib
    import tomllib

    root = pathlib.Path(__file__).resolve().parents[1]
    data = tomllib.loads((root / "pyproject.toml").read_text())
    deps = " ".join(
        data["project"]["dependencies"] + data["project"]["optional-dependencies"]["dev"]
    )
    for forbidden in ("psycopg", "asyncpg", "sqlalchemy", "pg8000", "psycopg2"):
        assert forbidden not in deps.lower()
    for py in (root / "src").rglob("*.py"):
        text = py.read_text().lower()
        assert "taktovia_db" not in text and "postgresql://" not in text
