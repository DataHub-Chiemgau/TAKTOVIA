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
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

from .adapter import RoutingAdapter
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


def make_handler(adapter: RoutingAdapter) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def _send(self, status: int, body: dict[str, Any]) -> None:
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _error(self, status: int, code: str, message: str) -> None:
            self._send(status, {"error": {"code": code, "message": message}})

        def do_GET(self) -> None:
            if self.path == "/v1/map-version":
                return self._send(200, {"map_version": adapter.map_version()})
            self._error(404, "NOT_FOUND", "unbekannter Pfad")

        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                return self._error(400, "BAD_REQUEST", "Anfrage zu groß")
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                tenant = str(body["tenant_id"])
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
            self._send(200, out)

        def log_message(self, format: str, *args: object) -> None:
            return

    return Handler


def make_server(adapter: RoutingAdapter, host: str = "127.0.0.1", port: int = 8082) -> HTTPServer:
    return HTTPServer((host, port), make_handler(adapter))
