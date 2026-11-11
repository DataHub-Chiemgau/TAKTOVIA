"""WP-M0-07: Gerüsttests des Dokumenten-Workers."""

import json
import pathlib
import threading
import tomllib
import urllib.error
import urllib.request

import pytest

from taktovia_documents import __version__
from taktovia_documents.consumer import Consumer, Task
from taktovia_documents.health import make_server
from taktovia_documents.ports import NotConfigured


def test_version() -> None:
    assert __version__ == "0.0.1"


def test_health_endpoint() -> None:
    server = make_server("127.0.0.1", 0)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=5) as resp:
            assert resp.status == 200
            assert json.load(resp) == {"status": "UP", "worker": "documents"}
        with pytest.raises(urllib.error.HTTPError) as err:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/x", timeout=5)
        assert err.value.code == 404
    finally:
        server.shutdown()
        server.server_close()


def test_ports_are_placeholders() -> None:
    p = NotConfigured()
    with pytest.raises(NotImplementedError):
        p.extract(b"", "application/pdf")
    with pytest.raises(NotImplementedError):
        p.scan(b"")
    with pytest.raises(NotImplementedError):
        p.validate(b"<x/>")


def test_consumer_dispatches_and_rejects_unknown() -> None:
    seen: list[str] = []
    c = Consumer()
    c.register("ocr", lambda t: seen.append(t.task_id))
    c.handle(Task("t1", "ocr", b""))
    assert seen == ["t1"]
    with pytest.raises(LookupError):
        c.handle(Task("t2", "unbekannt", b""))


def test_no_database_dependency() -> None:
    """AK 3: kein Datenbanktreiber, keine Datenbankverbindung im Worker."""
    root = pathlib.Path(__file__).resolve().parents[1]
    data = tomllib.loads((root / "pyproject.toml").read_text())
    assert data["project"]["dependencies"] == []
    for py in (root / "src").rglob("*.py"):
        text = py.read_text().lower()
        for forbidden in ("psycopg", "sqlalchemy", "asyncpg", "postgresql://"):
            assert forbidden not in text
