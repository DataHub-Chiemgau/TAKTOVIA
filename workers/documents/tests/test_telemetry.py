"""WP-M5-03 / WP-M5-06: Telemetrie des Dokumenten-Workers (Aufgabenlauf, Span je Aufgabe, /metrics, Maskierung)."""

# ruff: noqa: E501
from __future__ import annotations

import json
import re
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from taktovia_documents import health
from taktovia_documents.consumer import Consumer, Task
from taktovia_documents.diagnose import Telemetry, klartext_klassen

TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


def collector() -> tuple[HTTPServer, list[dict[str, object]], str]:
    spans: list[dict[str, object]] = []

    class H(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            spans.extend(body["resourceSpans"][0]["scopeSpans"][0]["spans"])
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")

        def log_message(self, *a: object) -> None:
            return

    srv = HTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, spans, f"http://127.0.0.1:{srv.server_address[1]}/v1/traces"


def test_aufgabe_span_metriken_und_metrics_endpunkt() -> None:
    srv, spans, url = collector()
    tel = Telemetry("taktovia-documents", url)
    c = Consumer(tel)
    c.register("malware_scan", lambda t: None)

    def boom(t: Task) -> None:
        raise RuntimeError("OCR-Fehler für erika.mustermann@example.test")

    c.register("ocr", boom)
    try:
        c.handle(Task("t-1", "malware_scan", b"x" * 10, TP))
        with pytest.raises(RuntimeError):
            c.handle(Task("t-2", "ocr", b"", None))
        with pytest.raises(LookupError):
            c.handle(Task("t-3", "unbekannt", b""))
        assert (
            spans[0]["traceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"
            and spans[0]["parentSpanId"] == "00f067aa0ba902b7"
        )
        assert spans[1]["status"] == {"code": 2} and "parentSpanId" not in spans[1]
        health.TELEMETRY = tel
        server = health.make_server("127.0.0.1", 0)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{server.server_address[1]}/metrics", timeout=5
            ) as r:
                text = r.read().decode()
        finally:
            server.shutdown()
            health.TELEMETRY = None
        assert 'taktovia_documents_tasks_total{kind="malware_scan",result="ok"} 1.0' in text
        assert 'taktovia_documents_tasks_total{kind="ocr",result="error"} 1.0' in text
        assert 'taktovia_documents_tasks_total{kind="unknown",result="rejected"} 1.0' in text
        labels = "\n".join(
            re.findall(r"\{[^}]*\}", text)
        )  # Messwerte (Gleitkomma) können zufällig wie Ziffernfolgen wirken
        assert klartext_klassen(labels) == set() and "mustermann" not in text
    finally:
        srv.shutdown()


def test_sammler_gestoppt_aufgabe_laeuft_weiter() -> None:
    srv, _, url = collector()
    srv.shutdown()
    srv.server_close()
    tel = Telemetry("taktovia-documents", url)
    c = Consumer(tel)
    done: list[str] = []
    c.register("ocr", lambda t: done.append(t.task_id))
    c.handle(Task("t-9", "ocr", b"", TP))
    assert done == ["t-9"] and tel.export_failures == 1
