"""Attrappe der Plattform-Schnittstelle für die Probentests (WP-M5-05): ein lokaler HTTP-Server (echter Transport über Loopback), der die Pfade der Proben mit steuerbaren Fehlern beantwortet.

NICHT die Anwendung: Die Attrappe prüft, wie die Proben Antworten klassifizieren, nicht die Plattform. Der Lauf der Proben gegen die echte Anwendung (Spring-Jar, Testissuer) ist ein eigener,
gekennzeichneter Test (test_probe_gegen_anwendung.py, nur mit TAKTOVIA_PERF_RUN=1)."""
import json
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TENANT = str(uuid.uuid4())
VEHICLE = str(uuid.uuid4())
SERVICE = str(uuid.uuid4())


class PlattformAttrappe:
    def __init__(self):
        self.token = "ok"        # ok | ungueltig (401) | ausfall (503)
        self.api = "ok"          # ok | plattform_500 | psp_down | leeres_angebot | falscher_inhalt | forbidden
        self.me_fahrzeug = True
        self.log: list[str] = []
        self.buchungen: dict[str, str] = {}   # id -> Zustand
        self.lock = threading.Lock()
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, status, body=None, headers=None):
                raw = json.dumps(body).encode() if body is not None else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(raw)

            def _body(self):
                n = int(self.headers.get("Content-Length") or 0)
                return self.rfile.read(n) if n else b""

            def do_GET(self):
                with outer.lock:
                    outer.log.append(f"GET {self.path}")
                if self.path == "/ref":
                    return self._send(200, {"ok": True})
                if outer.api == "plattform_500":
                    return self._send(500, {"code": "INTERNAL_ERROR"})
                if outer.api == "forbidden":
                    return self._send(403, {"code": "FORBIDDEN"})
                if self.path.startswith("/v1/me"):
                    return self._send(200, {"tenant_id": TENANT, "roles": ["operator"]} if outer.api != "falscher_inhalt" else {"subject": "x"})
                if self.path.startswith("/v1/operator/vehicles"):
                    return self._send(200, {"items": [{"vehicle_id": VEHICLE}] if outer.me_fahrzeug and outer.api != "falscher_inhalt" else []})
                self._send(404, {"code": "NOT_FOUND"})

            def do_POST(self):
                raw = self._body()
                with outer.lock:
                    outer.log.append(f"POST {self.path}")
                if self.path == "/token":
                    if outer.token == "ausfall":
                        return self._send(503, {"error": "unavailable"})
                    f = urllib.parse.parse_qs(raw.decode())
                    if outer.token == "ungueltig" or f.get("password", [""])[0] != "richtig":
                        return self._send(401, {"error": "invalid_grant"})
                    return self._send(200, {"access_token": "T-" + uuid.uuid4().hex, "expires_in": 300})
                if outer.api == "plattform_500":
                    return self._send(500, {"code": "INTERNAL_ERROR"})
                if self.path == "/v1/ride-offers":
                    if outer.api == "leeres_angebot":
                        return self._send(200, {"offers": [], "rejection": {"primary": "NO_CAPACITY"}})
                    return self._send(200, {"offers": [{"id": str(uuid.uuid4()), "gross": {"amount_minor": 950, "currency": "EUR", "exponent": 2}, "revision": 1}]})
                if self.path.endswith("/hold"):
                    if outer.api == "psp_down":
                        return self._send(503, {"code": "PROVIDER_UNAVAILABLE"})
                    return self._send(201, {"id": str(uuid.uuid4())}, {"ETag": '"1"'})
                if self.path == "/v1/bookings":
                    bid = str(uuid.uuid4())
                    with outer.lock:
                        outer.buchungen[bid] = "CONFIRMED"
                    return self._send(201, {"id": bid}, {"ETag": '"2"'})
                if self.path.startswith("/v1/bookings/") and self.path.endswith("/cancel"):
                    bid = self.path.split("/")[3]
                    with outer.lock:
                        outer.buchungen[bid] = "CANCELED"
                    return self._send(200, {"id": bid}, {"ETag": '"3"'})
                self._send(404, {"code": "NOT_FOUND"})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.server.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *a):
        self.server.shutdown()
        self.server.server_close()

    def konfiguration(self, **over):
        from taktovia_availability.proben import Konfiguration
        kw = dict(base_url=self.base, token_url=self.base + "/token",
                  zugang_kernbuchung={"grant_type": "password", "username": "probe", "password": "env:ATTRAPPE_PW"},
                  zugang_operator={"grant_type": "password", "username": "probe-op", "password": "env:ATTRAPPE_PW"},
                  dienst_id=SERVICE, probefahrzeug_id=VEHICLE, probemandant_id=TENANT, referenz_url=self.base + "/ref", timeout_s=3.0)
        kw.update(over)
        return Konfiguration(**kw)
