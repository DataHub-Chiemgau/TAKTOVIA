"""Startet die echte Anwendung (`backend/app/build/libs/app.jar`, Spring Boot) als Prozess gegen die Datenbank unter der Runtime-Rolle und den lokalen Test-Issuer.
Es ist die produktive Verdrahtung (keine Prüfendpunkte); nur Umgebung `local` (kein TLS) und der Ersatz-Issuer unterscheiden sich von einem Betrieb. Nicht verwendbar ohne
vorherigen `./gradlew :app:bootJar` und ohne laufende PostgreSQL-Instanz."""
from __future__ import annotations

import os
import shutil
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAR = REPO / "backend" / "app" / "build" / "libs" / "app.jar"
DEFAULT_HEAP = "-Xmx1g"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def available() -> str | None:
    """None, wenn Jar, Java und Datenbankzugang vorhanden sind, sonst der Grund."""
    if not JAR.is_file():
        return f"{JAR.relative_to(REPO)} fehlt (./gradlew :app:bootJar)"
    if shutil.which("java") is None:
        return "java nicht installiert"
    if not os.environ.get("TAKTOVIA_DB_RUNTIME_PASSWORD"):
        return "TAKTOVIA_DB_RUNTIME_PASSWORD nicht gesetzt (Datenbankumgebung wie db/db.py)"
    return None


class App:
    def __init__(self, idp, db_name: str | None = None, rate_limit: int = 100_000_000, extra: list[str] | None = None, heap: str = DEFAULT_HEAP, log: Path | None = None) -> None:
        self.idp, self.port = idp, free_port()
        self.db_name = db_name or os.environ["TAKTOVIA_DB_NAME"]
        self.rate_limit, self.extra, self.heap = rate_limit, extra or [], heap
        self.log_path = log or Path(os.environ.get("TMPDIR", "/tmp")) / f"taktovia-app-{self.port}.log"
        self.proc: subprocess.Popen | None = None
        self.started_at = 0.0

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def start(self, timeout: float = 180.0) -> "App":
        env = dict(os.environ, TAKTOVIA_DB_NAME=self.db_name, TAKTOVIA_DB_HOST=os.environ.get("TAKTOVIA_DB_HOST", "127.0.0.1"), TAKTOVIA_ENVIRONMENT="local",
                   TAKTOVIA_TLS_ENABLED="false", TAKTOVIA_DB_SSLMODE="disable", TAKTOVIA_TRACING_EXPORT="false", PORT=str(self.port))
        args = ["java", self.heap, "-jar", str(JAR), "--server.port=%d" % self.port, "--management.tracing.export.enabled=false",
                "--taktovia.api.cursor-secret=load-cursor-secret-load-cursor-secret-1234",
                "--taktovia.idp.enabled=true", f"--taktovia.idp.issuer-uri={self.idp.issuer}", f"--taktovia.idp.jwks-uri={self.idp.jwks_uri}", f"--taktovia.idp.audience={self.idp.AUDIENCE}",
                "--taktovia.idp.jwks-min-refresh-seconds=0", "--taktovia.idp.privileged-roles=tenant_admin,dispatcher,finance",
                f"--taktovia.api.rate-limit.requests={self.rate_limit}", "--taktovia.identity.context-secret=load-context-secret-load-context-secret-123456",
                "--taktovia.geocoding.provider=simulator", *self.extra]
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self._log = self.log_path.open("wb")
        self.started_at = time.time()
        self.proc = subprocess.Popen(args, env=env, stdout=self._log, stderr=subprocess.STDOUT)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"Anwendung beendet (Exit {self.proc.returncode}); Log: {self.log_path}\n" + self.log_path.read_text(errors="replace")[-3000:])
            try:
                with urllib.request.urlopen(self.base + "/actuator/health", timeout=2) as r:
                    if r.status == 200:
                        self.startup_s = time.time() - self.started_at
                        return self
            except Exception:  # noqa: BLE001 - noch nicht bereit
                time.sleep(1)
        self.stop()
        raise RuntimeError(f"Anwendung nicht bereit nach {timeout}s; Log: {self.log_path}")

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if getattr(self, "_log", None):
            self._log.close()

    def __enter__(self) -> "App":
        return self.start()

    def __exit__(self, *exc) -> None:
        self.stop()
