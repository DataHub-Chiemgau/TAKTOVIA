"""Minimaler JSON-HTTP-Server und -Client auf Basis der Standardbibliothek (nur Testnetz)."""
import ipaddress
import json
import os
import re
import socket
import threading
import time
from http.client import HTTPConnection, RemoteDisconnected
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlsplit

MAX_REQUEST_BYTES = 8 * 1024 * 1024


class NoResponse(Exception):
    """Client: Timeout oder abgebrochene Verbindung ohne Antwort (Ergebnis unbestimmt)."""


class DropConnection(Exception):
    """Server: Verbindung ohne Antwort schließen (Timeout bzw. verlorene Antwort)."""

    def __init__(self, hold_seconds: float = 0.0):
        self.hold_seconds = hold_seconds


class Request:
    def __init__(self, method, path, query, headers, raw_body, match):
        self.method, self.path, self.query, self.headers, self.raw_body = method, path, query, headers, raw_body
        self.match = match

    def header(self, name, default=None):
        return self.headers.get(name.lower(), default)

    def json(self):
        if not self.raw_body:
            return {}
        try:
            v = json.loads(self.raw_body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            raise BadRequest(f"Body ist kein gültiges JSON: {e}")
        if not isinstance(v, dict):
            raise BadRequest("Body muss ein JSON-Objekt sein")
        return v


class BadRequest(Exception):
    def __init__(self, message, code="BAD_REQUEST"):
        super().__init__(message)
        self.code = code


class Response:
    def __init__(self, status=200, body=None, headers=None):
        self.status, self.body, self.headers = status, body, headers or {}


def assert_testnet_host(host: str):
    """Nur Testnetz: Loopback/private Adressen. 0.0.0.0 nur mit TAKTOVIA_TESTNET=1 (Container im Compose-Testnetz)."""
    if host in ("localhost",):
        return
    if host == "0.0.0.0":
        if os.environ.get("TAKTOVIA_TESTNET") == "1":
            return
        raise SystemExit("Bind auf 0.0.0.0 nur mit TAKTOVIA_TESTNET=1 (Testnetz). Simulatoren sind nie öffentlich erreichbar.")
    ip = ipaddress.ip_address(host)
    if not (ip.is_loopback or ip.is_private):
        raise SystemExit(f"Bind auf {host} verweigert: Simulatoren sind nur im Testnetz erreichbar.")


class App(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 1024

    def __init__(self, host="127.0.0.1", port=0):
        assert_testnet_host(host)
        self.routes = []
        super().__init__((host, port), _Handler)

    def route(self, method, pattern):
        rx = re.compile("^" + pattern + "$")

        def deco(fn):
            self.routes.append((method, rx, fn))
            return fn
        return deco

    @property
    def url(self):
        h, p = self.server_address[:2]
        return f"http://{h}:{p}"

    def start(self):
        t = threading.Thread(target=self.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        t.start()
        return t

    def stop(self):
        self.shutdown()
        self.server_close()


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _drop(self, hold):
        self.close_connection = True
        if hold:
            time.sleep(hold)
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def _handle(self):
        parts = urlsplit(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_REQUEST_BYTES:
            self._send(Response(413, {"code": "BODY_TOO_LARGE"}))
            return
        raw = self.rfile.read(length) if length else b""
        headers = {k.lower(): v for k, v in self.headers.items()}
        for method, rx, fn in self.server.routes:
            if method != self.command:
                continue
            m = rx.match(parts.path)
            if m:
                req = Request(self.command, parts.path, dict(parse_qsl(parts.query)), headers, raw, m)
                try:
                    resp = fn(req)
                except DropConnection as d:
                    self._drop(d.hold_seconds)
                    return
                except BadRequest as e:
                    resp = Response(400, {"code": e.code, "message": str(e)})
                self._send(resp)
                return
        self._send(Response(404, {"code": "NOT_FOUND"}))

    def _send(self, resp):
        body = resp.body
        if body is None:
            payload = b""
        elif isinstance(body, (bytes, bytearray)):
            payload = bytes(body)
        else:
            payload = json.dumps(body, ensure_ascii=False, sort_keys=True).encode("utf-8")
        self.send_response(resp.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        for k, v in resp.headers.items():
            self.send_header(k, v)
        try:
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):  # Client hat vorher aufgegeben (Timeout-Test)
            pass
        self.close_connection = True

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _handle


def http_call(method, url, body=None, headers=None, timeout=10.0, raw_body=None):
    """Gibt (status, geparstes JSON oder None, Header-Dict, Rohbytes) zurück; NoResponse bei Timeout/Abbruch."""
    parts = urlsplit(url)
    conn = HTTPConnection(parts.hostname, parts.port, timeout=timeout)
    h = {"Accept": "application/json"}
    data = raw_body
    if raw_body is None and body is not None:
        data = json.dumps(body).encode("utf-8")
    if data is not None:
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    try:
        conn.request(method, parts.path + ("?" + parts.query if parts.query else ""), body=data, headers=h)
        r = conn.getresponse()
        raw = r.read()
    except (socket.timeout, TimeoutError, RemoteDisconnected, ConnectionError, OSError) as e:
        raise NoResponse(f"{type(e).__name__}: {e}")
    finally:
        conn.close()
    parsed = None
    if raw:
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = None
    return r.status, parsed, {k.lower(): v for k, v in r.getheaders()}, raw
