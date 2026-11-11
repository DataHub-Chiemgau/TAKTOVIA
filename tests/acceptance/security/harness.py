"""Schwarzkasten-Harness für die Sicherheitstests (WP-M1-38): startet die gebaute Anwendung (`app.jar`) als eigenen Prozess gegen die echte
Datenbank oder spricht ein vorhandenes Ziel an (`TAKTOVIA_BASE_URL`, z. B. Staging für den DAST-Lauf).

Ohne Datenbankzugang (`TAKTOVIA_DB_RUNTIME_PASSWORD`) werden die Tests übersprungen; mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie stattdessen fehl.
Ein Test, der übersprungen wurde, ist kein Nachweis.
"""
import http.client
import json
import os
import re
import socket
import subprocess
import sys
import time
import unittest
import urllib.parse
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAR = REPO / 'backend/app/build/libs/app.jar'
sys.path.insert(0, str(REPO / 'api/tools'))

INTERNAL = re.compile(r'(?i)exception|stack ?trace|\bat (de|org|java|jakarta)\.|\bselect\b|\binsert\b|\bsql\b|postgres|apache tomcat|<html|/home/|\.kt\b|\.java\b')
SECURITY_HEADERS = {
    'x-content-type-options': 'nosniff', 'cache-control': 'no-store', 'x-frame-options': 'DENY', 'referrer-policy': 'no-referrer',
}


class Resp:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, {k.lower(): v for k, v in headers}, body

    @property
    def json(self):
        return json.loads(self.body)

    def is_problem(self):
        return self.headers.get('content-type', '').startswith('application/problem+json')


class Client:
    def __init__(self, port, source='127.0.0.1'):
        self.port, self.source = port, source

    def request(self, method, path, body=None, headers=None, timeout=15):
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=timeout, source_address=(self.source, 0))
        try:
            c.putrequest(method, path, skip_accept_encoding=True)
            hs = dict(headers or {})
            data = body.encode() if isinstance(body, str) else body
            if data is not None:
                hs.setdefault('Content-Type', 'application/json')
                hs['Content-Length'] = str(len(data))
            for k, v in hs.items():
                c.putheader(k, v)
            c.endheaders(data)
            r = c.getresponse()
            return Resp(r.status, r.getheaders(), r.read().decode('utf-8', 'replace'))
        finally:
            c.close()

    def raw(self, text: bytes, timeout=10) -> bytes:
        with socket.create_connection(('127.0.0.1', self.port), timeout=timeout, source_address=(self.source, 0)) as s:
            s.sendall(text)
            s.shutdown(socket.SHUT_WR)
            out = b''
            while True:
                try:
                    chunk = s.recv(65536)
                except OSError:
                    break
                if not chunk:
                    break
                out += chunk
            return out


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class RunningApp:
    """Kontextmanager: Anwendung starten und auf `UP` warten; `TAKTOVIA_BASE_URL` nutzt ein vorhandenes Ziel statt eines Prozesses."""

    def __init__(self, *args):
        self.args, self.proc, self.port = list(args), None, None

    def __enter__(self):
        base = os.environ.get('TAKTOVIA_BASE_URL')
        if base:
            self.port = urllib.parse.urlparse(base).port or 80
            return self
        if not os.environ.get('TAKTOVIA_DB_RUNTIME_PASSWORD'):
            if os.environ.get('TAKTOVIA_REQUIRE_DB') == 'true':
                raise AssertionError('Datenbank erforderlich (TAKTOVIA_REQUIRE_DB=true): TAKTOVIA_DB_RUNTIME_PASSWORD fehlt')
            raise unittest.SkipTest('TAKTOVIA_DB_RUNTIME_PASSWORD nicht gesetzt: kein laufender Dienst, Test nicht ausgeführt')
        if not JAR.exists():
            subprocess.run(['./gradlew', ':app:bootJar', '--console=plain', '-q'], cwd=REPO / 'backend', check=True)
        self.port = free_port()
        self.log = REPO / 'backend/app/build/security-test-app.log'
        self._log = open(self.log, 'w')
        self.proc = subprocess.Popen(
            ['java', '-jar', str(JAR), f'--server.port={self.port}', '--taktovia.api.cursor-secret=schwarzkasten-test-schluessel-1234567890', *self.args],
            stdout=self._log, stderr=subprocess.STDOUT, cwd=REPO)
        deadline = time.time() + 120
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise AssertionError(f'Anwendung beendet (Exit {self.proc.returncode}); Log: {self.log}')
            try:
                if Client(self.port).request('GET', '/actuator/health').status == 200:
                    return self
            except OSError:
                pass
            time.sleep(0.5)
        self.proc.kill()
        raise AssertionError(f'Anwendung wurde nicht bereit; Log: {self.log}')

    def __exit__(self, *exc):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
            self._log.close()

    def client(self, source='127.0.0.1'):
        return Client(self.port, source)
