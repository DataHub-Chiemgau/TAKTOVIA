"""WP-M5-03 / WP-M5-06: Telemetrie des Routing-Workers gegen einen echten lokalen OTLP-Sammler (HTTP) und den echten Server.

Positiv: Trace-Durchstich (traceparent des Kerns -> Worker-Span mit gleicher Trace-ID und Elternspan), Metriken je Aufrufklasse.
Negativ: ungültiger traceparent -> neue Trace-ID; Label mit Kontakt wird verworfen; Logzeile ohne Klartext.
Konkurrierend: Sammler gestoppt -> Aufrufe laufen unverändert weiter.
"""

# ruff: noqa: E501
from __future__ import annotations

import io
import json
import logging
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

from fixture_region import TENANT_A, graph, profiles, stops
from taktovia_routing.adapter import RoutingAdapter
from taktovia_routing.diagnose import JsonFormatter, Registry, Telemetry, klartext_klassen
from taktovia_routing.graph_provider import GraphProvider
from taktovia_routing.server import make_server

TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class Collector:
    def __init__(self) -> None:
        self.spans: list[dict[str, object]] = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                for rs in body["resourceSpans"]:
                    for ss in rs["scopeSpans"]:
                        outer.spans.extend(ss["spans"])
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, *a: object) -> None:
                return

        self.server = HTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/v1/traces"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()


def wait_for(cond: object, seconds: float = 3.0) -> None:
    """Der Span wird nach dem Senden der Antwort exportiert; kurz auf den Export warten."""
    end = time.monotonic() + seconds
    while time.monotonic() < end and not cond():  # type: ignore[operator]
        time.sleep(0.01)


def start(tel: Telemetry) -> tuple[HTTPServer, int]:
    server = make_server(
        RoutingAdapter(GraphProvider(graph()), stops(), profiles()), port=0, telemetry=tel
    )
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


def post(port: int, headers: dict[str, str] | None = None) -> int:
    body = json.dumps({"tenant_id": TENANT_A, "from": "H1", "to": "H4", "profile": "car"}).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/route", data=body, method="POST", headers=headers or {}
    )
    with urllib.request.urlopen(req, timeout=5) as r:
        return int(r.status)


def test_trace_durchstich_und_metriken() -> None:
    col = Collector()
    tel = Telemetry("taktovia-routing", col.url)
    server, port = start(tel)
    try:
        assert (
            post(port, {"traceparent": TP, "X-Enqueued-At": str(int(time.time() * 1000) - 250)})
            == 200
        )
        wait_for(lambda: len(col.spans) == 1)
        assert len(col.spans) == 1
        sp = col.spans[0]
        assert (
            sp["traceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"
            and sp["parentSpanId"] == "00f067aa0ba902b7"
        )
        text = ""
        for _ in range(300):  # Metriken werden nach dem Senden der Antwort erfasst
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=5) as r:
                text = r.read().decode()
            if "taktovia_routing_request_seconds_count" in text:
                break
            time.sleep(0.01)
        assert 'taktovia_routing_requests_total{route_class="route",status_class="2xx"} 1.0' in text
        assert 'taktovia_routing_request_seconds_count{route_class="route"} 1.0' in text
        assert 'taktovia_routing_queue_wait_seconds_count{queue="routing"} 1.0' in text
        assert "taktovia_telemetry_dropped_total 0.0" in text
    finally:
        server.shutdown()
        server.server_close()
        col.stop()


def test_ungueltiger_traceparent_neue_trace_id() -> None:
    col = Collector()
    server, port = start(Telemetry("taktovia-routing", col.url))
    try:
        post(port, {"traceparent": "kaputt"})
        post(port, {"traceparent": "00-" + "0" * 32 + "-00f067aa0ba902b7-01"})
        post(port)
        wait_for(lambda: len(col.spans) == 3)
        ids = {s["traceId"] for s in col.spans}
        assert len(col.spans) == 3 and len(ids) == 3
        assert "4bf92f3577b34da6a3ce929d0e0e4736" not in ids and all(
            "parentSpanId" not in s for s in col.spans
        )
    finally:
        server.shutdown()
        server.server_close()
        col.stop()


def test_label_mit_kontakt_wird_verworfen_und_gezaehlt() -> None:
    reg = Registry()
    reg.inc("taktovia_x_total", {"result": "ok"})
    reg.inc("taktovia_x_total", {"passenger_name": "Erika"})
    reg.inc("taktovia_x_total", {"result": "erika.mustermann@example.test"})
    reg.inc("taktovia_x_total", {"reason": "+49 176 12345678"})
    reg.inc("taktovia_x_total", {"tenant_id": "Mandant A"})
    text = reg.render()
    assert (
        'taktovia_x_total{result="ok"} 1.0' in text
        and "erika" not in text.lower()
        and "12345678" not in text
    )
    assert reg.dropped == 4 and len(reg.violations) == 4
    assert klartext_klassen("\n".join(reg.violations)) == set()


def test_sammler_gestoppt_beeintraechtigt_aufrufe_nicht() -> None:
    col = Collector()
    tel = Telemetry("taktovia-routing", col.url)
    server, port = start(tel)
    try:
        col.stop()  # Sammler ausgefallen
        for _ in range(3):
            assert post(port, {"traceparent": TP}) == 200
        wait_for(lambda: tel.export_failures == 3)
        assert tel.export_failures == 3
    finally:
        server.shutdown()
        server.server_close()


def test_logzeile_ohne_klartext_und_nur_positivliste() -> None:
    buf = io.StringIO()
    h = logging.StreamHandler(buf)
    h.setFormatter(JsonFormatter("routing"))
    log = logging.getLogger("taktovia.test-routing")
    log.addHandler(h)
    log.setLevel(logging.INFO)
    try:
        raise ValueError(
            "Zahlung für erika.mustermann@example.test, IBAN DE89 3704 0044 0532 0130 00"
        )
    except ValueError:
        log.error(
            "Fehler bei Tel +49 176 12345678",
            exc_info=True,
            extra={
                "tenant_id": TENANT_A,
                "passenger_email": "erika.mustermann@example.test",
                "status": "x",
            },
        )
    line = buf.getvalue()
    assert klartext_klassen(line) == set() and "mustermann" not in line and "12345678" not in line
    rec = json.loads(line)
    assert (
        rec["tenant_id"] == TENANT_A
        and rec["dropped_fields"] == ["passenger_email"]
        and rec["error"].startswith("ValueError")
    )
    assert "Traceback" not in line
