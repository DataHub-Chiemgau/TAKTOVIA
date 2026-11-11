#!/usr/bin/env python3
"""Test-Empfänger für Betreiberalarme (lokal und CI, WP-M1-47). Nimmt JSON per HTTPS-POST an und legt sie in einer Datei ab.

  test_receiver.py --port 18443 --out alerts.jsonl [--fail-first N]

Zertifikat und Schlüssel werden zur Laufzeit mit openssl erzeugt (nichts im Repository). Der Zugang (URL) wird wie in jeder Umgebung
als Secret-Datei eingehängt, hier: https://127.0.0.1:<port>/alert. `--fail-first N` antwortet auf die ersten N Anfragen mit 503 (Wiederholungstest).
"""
import argparse
import json
import ssl
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def serve(port=0, out=None, fail_first=0):
    """Startet den Empfänger im Hintergrund. Gibt (Server, Liste empfangener Alarme, Zertifikatsdatei) zurück."""
    tmp = tempfile.TemporaryDirectory()
    d = Path(tmp.name)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(d / "k.pem"), "-out", str(d / "c.pem"),
                    "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"], check=True, capture_output=True)
    received, state = [], {"left": fail_first}

    class H(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if state["left"] > 0:
                state["left"] -= 1
                self.send_response(503); self.end_headers(); return
            received.append(json.loads(body))
            if out:
                with open(out, "a", encoding="utf-8") as f:
                    f.write(body.decode() + "\n")
            self.send_response(204); self.end_headers()

        def log_message(self, *a):  # keine Zugriffsprotokolle mit Adressen
            pass

    srv = HTTPServer(("127.0.0.1", port), H)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(d / "c.pem", d / "k.pem")
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    srv._tmp = tmp
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, received, d / "c.pem"


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18443)
    ap.add_argument("--out", default="alerts.jsonl")
    ap.add_argument("--fail-first", type=int, default=0)
    a = ap.parse_args()
    s, _, cert = serve(a.port, a.out, a.fail_first)
    print(f"Test-Empfänger auf https://127.0.0.1:{s.server_address[1]}/alert, Zertifikat {cert}")
    threading.Event().wait()
