"""Synchrone JSON-Schnittstelle des Routing-Workers für den Kern (WP-M2-10, Empfehlung aus Spike
(b): synchroner Aufruf mit hartem Zeitbudget).

POST /v1/route   {"tenant_id","from","to","profile"}                      -> 200
    {"time_s","distance_m","profile","map_version","provider",...}
POST /v1/matrix  {"tenant_id","from":[...],"to":[...],"profile"}         -> 200
    {"cells":[[{"time_s","distance_m"}|null,...]],...}
GET  /v1/map-version                                                       -> 200 {"map_version"}
POST /v1/walk    {"tenant_id","from","to"}                                -> wie /v1/route mit
    Profil walk
POST /v1/insert  {"tenant_id","request","vehicles","matrix","params","candidate_vehicle_ids",
    "budget_ms"} -> 200 {"variants":[...],"rejection","grounds","partial","stats"} (WP-M2-17, siehe
    insertion.py, contract/insert-request.json); Plan eines fremden Mandanten: 422 SCOPE_VIOLATION.
Fehler: {"error":{"code","message"}} mit 422 (NOT_A_STOP, NO_ROUTE, UNKNOWN_PROFILE), 503
    (PROVIDER_UNAVAILABLE), 504 (PROVIDER_TIMEOUT), 400 (Anfrage).
Der Mandant kommt vom Kern und dient nur dem Cache-Schlüssel; der Worker hat keinen
Datenbankzugriff und trifft keine Berechtigungsentscheidung.
"""

from __future__ import annotations

import json
import sys
import time
import uuid
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

from .adapter import RoutingAdapter
from .diagnose import Telemetry, describe_exception
from .insertion import InputError, ScopeViolation, handle_insert
from .port import (
    MatrixResult,
    NoRoute,
    NotAStop,
    ProviderTimeout,
    ProviderUnavailable,
    RouteResult,
    RoutingError,
    UnknownProfile,
)

STATUS = {
    NotAStop: 422,
    NoRoute: 422,
    UnknownProfile: 422,
    ProviderUnavailable: 503,
    ProviderTimeout: 504,
}
MAX_BODY = 64 * 1024


def _route_json(r: RouteResult) -> dict[str, Any]:
    d = asdict(r)
    d["geometry"] = [list(c) for c in r.geometry]
    return d


def _matrix_json(m: MatrixResult) -> dict[str, Any]:
    return {
        "origins": list(m.origins),
        "destinations": list(m.destinations),
        "cells": [[None if c is None else asdict(c) for c in row] for row in m.cells],
        "profile": m.profile,
        "map_version": m.map_version,
        "provider": m.provider,
    }


def _is_uuid(v: str) -> bool:
    try:
        uuid.UUID(v)
    except ValueError:
        return False
    return True


ROUTE_CLASS = {
    "/v1/route": "route",
    "/v1/walk": "walk",
    "/v1/matrix": "matrix",
    "/v1/insert": "insert",
}


def make_handler(
    adapter: RoutingAdapter, telemetry: Telemetry | None = None
) -> type[BaseHTTPRequestHandler]:
    """Mit ``telemetry`` (WP-M5-03): Metriken je Aufrufklasse, Span je Aufruf, ``GET /metrics``.

    Die Instrumentierung wirft nie und ändert keine Antwort. Sendet der Kern ``X-Enqueued-At``
    (Epochenmillisekunden), wird die Wartezeit bis zum Beginn der Bearbeitung gemessen (der Server
    ist einfädig, Wartezeit entsteht im Socket-Rückstand).
    """

    class Handler(BaseHTTPRequestHandler):
        status_sent = 0
        tenant_seen = ""
        correlation_seen = ""
        code_seen = ""

        def _send(self, status: int, body: dict[str, Any]) -> None:
            self.status_sent = status
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _error(self, status: int, code: str, message: str) -> None:
            self.code_seen = code
            self._send(status, {"error": {"code": code, "message": message}})

        def do_GET(self) -> None:
            if self.path == "/metrics" and telemetry is not None:
                raw = telemetry.metrics.render().encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; version=0.0.4")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
                return
            if self.path == "/v1/map-version":
                return self._send(200, {"map_version": adapter.map_version()})
            self._error(404, "NOT_FOUND", "unbekannter Pfad")

        def do_POST(self) -> None:
            if telemetry is None:
                return self._post()
            klass = ROUTE_CLASS.get(self.path, "other")
            t0 = time.monotonic()
            self._observe_queue_wait(telemetry)
            try:
                with telemetry.span(
                    f"routing {klass}", self.headers.get("traceparent"), {"route_class": klass}
                ) as sp:
                    try:
                        self._post()
                    finally:
                        sp.set("http_status", self.status_sent)
                        sp.set("correlation_id", sp.ctx.correlation_id)
                        self.correlation_seen = sp.ctx.correlation_id
            finally:
                dt = time.monotonic() - t0
                telemetry.log.info(
                    "Aufruf bearbeitet",
                    extra={
                        "route_class": klass,
                        "http_status": self.status_sent,
                        "duration_ms": round(dt * 1000, 1),
                        "tenant_id": self.tenant_seen,
                        "correlation_id": self.correlation_seen,
                        "code": self.code_seen,
                    },
                )
                labels = {"route_class": klass, "status_class": f"{self.status_sent // 100}xx"}
                telemetry.metrics.inc("taktovia_routing_requests_total", labels)
                telemetry.metrics.observe(
                    "taktovia_routing_request_seconds", dt, {"route_class": klass}
                )
                if klass == "insert":
                    telemetry.metrics.observe(
                        "taktovia_routing_solver_seconds", dt, {"route_class": klass}
                    )
                if self.status_sent == 504:
                    telemetry.metrics.inc("taktovia_routing_timeouts_total", {"kind": "provider"})

        def _observe_queue_wait(self, tel: Telemetry) -> None:
            try:
                enq = float(self.headers.get("X-Enqueued-At", ""))
                tel.metrics.observe(
                    "taktovia_routing_queue_wait_seconds",
                    max(0.0, time.time() - enq / 1000.0),
                    {"queue": "routing"},
                )
            except (TypeError, ValueError):
                return

        def _post(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                return self._error(400, "BAD_REQUEST", "Anfrage zu groß")
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                tenant = str(body["tenant_id"])
                self.tenant_seen = tenant if _is_uuid(tenant) else ""
                if self.path == "/v1/route":
                    out = _route_json(
                        adapter.route(tenant, body["from"], body["to"], body["profile"])
                    )
                elif self.path == "/v1/walk":
                    out = _route_json(adapter.walk(tenant, body["from"], body["to"]))
                elif self.path == "/v1/matrix":
                    out = _matrix_json(
                        adapter.matrix(tenant, body["from"], body["to"], body["profile"])
                    )
                elif self.path == "/v1/insert":
                    out = handle_insert(body)
                else:
                    return self._error(404, "NOT_FOUND", "unbekannter Pfad")
            except ScopeViolation as e:
                return self._error(422, "SCOPE_VIOLATION", str(e))
            except InputError as e:
                return self._error(400, "BAD_REQUEST", str(e))
            except RoutingError as e:
                return self._error(STATUS.get(type(e), 500), e.code, str(e))
            except (KeyError, ValueError, TypeError):
                return self._error(400, "BAD_REQUEST", "Anfrage ungültig")
            except Exception as e:  # noqa: BLE001 - unerwartet: generische Antwort, Beschreibung maskiert ins Log (NFR-015)
                if telemetry is not None:
                    telemetry.log.error("Aufruf fehlgeschlagen: %s", describe_exception(e))
                return self._error(500, "INTERNAL", "interner Fehler")
            self._send(200, out)
            if (
                telemetry is not None
                and self.path == "/v1/insert"
                and isinstance(out, dict)
                and out.get("partial")
            ):
                telemetry.metrics.inc("taktovia_routing_timeouts_total", {"kind": "solver_budget"})

        def log_message(self, format: str, *args: object) -> None:
            return

    return Handler


def make_server(
    adapter: RoutingAdapter,
    host: str = "127.0.0.1",
    port: int = 8082,
    telemetry: Telemetry | None = None,
) -> HTTPServer:
    class _Server(HTTPServer):
        def handle_error(self, request: Any, client_address: Any) -> None:
            # Standard: Stapelausgabe mit Meldungstext auf stderr. Mit Telemetrie nur die maskierte Beschreibung (NFR-015).  # noqa: E501
            if telemetry is None:
                return super().handle_error(request, client_address)
            exc = sys.exc_info()[1]
            telemetry.log.error(
                "Verbindung fehlgeschlagen: %s", describe_exception(exc) if exc else "unbekannt"
            )

    return _Server((host, port), make_handler(adapter, telemetry))
