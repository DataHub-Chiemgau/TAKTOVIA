"""Leerer Health-Endpunkt (WP-M0-07). Nur Standardbibliothek, kein Datenbankzugriff."""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, HTTPServer

from .diagnose import Telemetry

TELEMETRY: Telemetry | None = (
    None  # vom Prozessstart gesetzt (WP-M5-03); ohne Wert gibt es kein /metrics
)


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path == "/metrics" and TELEMETRY is not None:
            body = TELEMETRY.metrics.render().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/healthz":
            body = json.dumps({"status": "UP", "worker": "documents"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def log_message(self, format: str, *args: object) -> None:
        return


def make_server(host: str = "127.0.0.1", port: int = 8082) -> HTTPServer:
    return HTTPServer((host, port), HealthHandler)


if __name__ == "__main__":  # pragma: no cover
    import os

    make_server(os.environ.get("HEALTH_HOST", "0.0.0.0"), 8082).serve_forever()  # noqa: S104
