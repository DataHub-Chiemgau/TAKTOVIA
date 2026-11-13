"""Startet die echte Anwendung (Spring-Boot-Jar) als Prozess gegen eine Testdatenbank, mit eingeschaltetem Ressourcenserver gegen den lokalen Test-Issuer."""
import os
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAR = ROOT / "backend" / "app" / "build" / "libs" / "app.jar"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class AppProcess:
    def __init__(self, dbname: str, idp, extra_args=()):
        if not JAR.exists():
            raise FileNotFoundError(f"{JAR} fehlt: cd backend && ./gradlew :app:bootJar")
        self.port = _free_port()
        self.base_url = f"http://127.0.0.1:{self.port}"
        env = dict(os.environ, TAKTOVIA_DB_NAME=dbname, TAKTOVIA_DB_SSLMODE="disable", TAKTOVIA_TLS_ENABLED="false", TAKTOVIA_ENVIRONMENT="local",
                   TAKTOVIA_TRACING_EXPORT="false")
        env.pop("JAVA_TOOL_OPTIONS", None) if False else None
        args = ["java", "-Xmx512m", "-jar", str(JAR), f"--server.port={self.port}",
                "--taktovia.idp.enabled=true", f"--taktovia.idp.issuer-uri={idp.issuer}", f"--taktovia.idp.jwks-uri={idp.jwks_uri}",
                "--taktovia.idp.jwks-min-refresh-seconds=0", "--taktovia.idp.privileged-roles=tenant_admin,dispatcher,finance",
                "--taktovia.api.rate-limit.requests=1000000", "--taktovia.api.cursor-secret=test-cursor-secret-test-cursor-secret-1234",
                "--taktovia.identity.context-secret=test-context-secret-test-context-secret-123456", "--taktovia.geocoding.provider=simulator",
                "--management.tracing.export.enabled=false", *extra_args]
        self.log_path = Path(os.environ.get("TMPDIR", "/tmp")) / f"stammdaten-app-{self.port}.log"
        self._log = open(self.log_path, "wb")
        self.proc = subprocess.Popen(args, env=env, stdout=self._log, stderr=subprocess.STDOUT)
        self._wait_ready()

    def _wait_ready(self, timeout=180):
        end = time.time() + timeout
        while time.time() < end:
            if self.proc.poll() is not None:
                raise RuntimeError(f"Anwendung beendet (Code {self.proc.returncode}): {self.log_path.read_text(errors='replace')[-3000:]}")
            try:
                with urllib.request.urlopen(self.base_url + "/actuator/health", timeout=2) as r:
                    if r.status == 200:
                        return
            except Exception:
                time.sleep(1)
        self.close()
        raise TimeoutError("Anwendung nicht bereit")

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self._log.close()
