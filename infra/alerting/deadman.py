#!/usr/bin/env python3
"""Unabhängige Totmannprüfung der Alarmierungskette (WP-M5-04, Kriterium 5).

Die Kette Prometheus -> Alertmanager -> Empfängerkanal kann nicht selbst melden, dass sie ausgefallen ist. Deshalb sendet Alertmanager den Herzschlag `Watchdog` (feuert immer) minütlich an
DIESEN Prozess, der auf einem anderen Host/Netz als die Kette laufen muss und einen EIGENEN Meldeweg hat (Secret `deadman_channel_credential`, nicht der Kanal der Kette). Bleibt der Herzschlag
länger als `--timeout` aus, meldet er den Ausfall (einmal je Ausfallphase, `recovered` bei Rückkehr). Er teilt weder Code-Pfad noch Konfiguration mit Prometheus oder Alertmanager.

  deadman.py --port 18444 --timeout 300 --channel-url-file /run/secrets/deadman_channel_credential

Zertifikat und Schlüssel werden wie beim Test-Empfänger zur Laufzeit erzeugt (Entwicklung); in Betrieb stellt die Betriebsumgebung TLS bereit. STATUS: als Prozess in Tests ausgeführt
(tests/acceptance/alerting), nicht in einer echten Betriebsumgebung.
"""
from __future__ import annotations

import argparse
import json
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


class Deadman:
    """Zustand der Prüfung mit austauschbarer Uhr und Meldefunktion (testbar)."""

    def __init__(self, timeout_s: float, notify, clock=time.monotonic):
        self.timeout_s = timeout_s
        self.notify = notify
        self.clock = clock
        self.last_beat: float | None = None
        self.down = False
        self.beats = 0
        self._lock = threading.Lock()

    def beat(self) -> None:
        with self._lock:
            now = self.clock()
            self.last_beat = now
            self.beats += 1
            if self.down:
                self.down = False
                self.notify({"event": "recovered", "component": "alarmkette", "scenario": "ALARMKETTE_AUSGEFALLEN"})

    def check(self) -> bool:
        """Prüft den Herzschlag; meldet genau einmal je Ausfallphase. Liefert `True`, wenn die Kette als ausgefallen gilt."""
        with self._lock:
            now = self.clock()
            since = self.last_beat if self.last_beat is not None else self._started
            if now - since > self.timeout_s and not self.down:
                self.down = True
                self.notify({"event": "alarmkette_ausgefallen", "component": "alarmkette", "scenario": "ALARMKETTE_AUSGEFALLEN", "severity": "critical", "empfaenger": "betrieb-bereitschaft",
                             "seconds_without_heartbeat": round(now - since, 1)})
            return self.down

    def start(self) -> None:
        self._started = self.clock()


def post_json(url: str, payload: dict, cafile: str | None = None) -> int:
    ctx = ssl.create_default_context(cafile=cafile)
    req = urllib.request.Request(url, json.dumps(payload).encode(), {"Content-Type": "application/json"})
    return urllib.request.urlopen(req, context=ctx, timeout=5).status


def serve(port: int, timeout_s: float, notify, clock=time.monotonic, poll_s: float = 1.0):
    """Startet den Herzschlag-Empfänger (HTTPS) und die Prüfschleife im Hintergrund. Gibt (Server, Deadman, Zertifikatsdatei, Stopp-Funktion) zurück."""
    tmp = tempfile.TemporaryDirectory()
    d = Path(tmp.name)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(d / "k.pem"), "-out", str(d / "c.pem"), "-days", "1", "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"], check=True, capture_output=True)
    dm = Deadman(timeout_s, notify, clock)
    dm.start()

    class H(BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", 0)))   # Inhalt wird nicht ausgewertet: nur das Eintreffen zählt
            dm.beat()
            self.send_response(204); self.end_headers()

        def log_message(self, *a):
            pass

    srv = HTTPServer(("127.0.0.1", port), H)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(d / "c.pem", d / "k.pem")
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    srv._tmp = tmp
    stop = threading.Event()

    def loop():
        while not stop.wait(poll_s):
            dm.check()

    threading.Thread(target=srv.serve_forever, daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()

    def shutdown():
        stop.set(); srv.shutdown()
    return srv, dm, d / "c.pem", shutdown


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18444)
    ap.add_argument("--timeout", type=float, default=300.0)
    ap.add_argument("--channel-url-file", required=True, help="Datei mit der URL des EIGENEN Meldewegs (Secret), nicht der Kanal der Kette")
    ap.add_argument("--channel-cafile")
    a = ap.parse_args()
    url = Path(a.channel_url_file).read_text(encoding="utf-8").strip()
    s, _, cert, _ = serve(a.port, a.timeout, lambda p: post_json(url, p, a.channel_cafile))
    print(f"Totmannprüfung auf https://127.0.0.1:{s.server_address[1]}/ , Zertifikat {cert}")
    threading.Event().wait()
