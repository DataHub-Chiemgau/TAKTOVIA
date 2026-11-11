#!/usr/bin/env python3
"""Eigener, kurzlebiger PostgreSQL-Cluster für den Spike (a) (WP-M0-26). NUR Testinfrastruktur, nichts davon im Repository.

Grund: Der geteilte lokale Cluster hat max_connections=100 (davon 3 reserviert, einige durch andere Läufe belegt). Für
100 wirklich gleichzeitig offene Transaktionen reicht das nicht, und eine Umstellung würde einen Neustart des geteilten Clusters
erfordern. Dieser Cluster nutzt dieselben Binärdateien (PostgreSQL 16, PostGIS) und Standardeinstellungen außer Port und max_connections.

  python cluster.py start  [--dir D] [--port 5433]   -> legt an (falls nötig), startet, schreibt D/env.sh (Passwörter zufällig, nur lokal)
  python cluster.py stop   [--dir D]
  python cluster.py destroy [--dir D]                 -> stoppt und löscht das Datenverzeichnis
Läuft als root über 'runuser -u postgres'. Standardverzeichnis: $TAKTOVIA_SPIKE_DIR oder /tmp/taktovia-spike-pg.
"""
import argparse
import os
import secrets
import shutil
import subprocess
import sys
from pathlib import Path

BIN = Path("/usr/lib/postgresql/16/bin")
DEFAULT_DIR = Path(os.environ.get("TAKTOVIA_SPIKE_DIR", "/tmp/taktovia-spike-pg"))


def run(cmd, as_postgres=True, **kw):
    if as_postgres and os.geteuid() == 0:
        cmd = ["runuser", "-u", "postgres", "--"] + cmd
    return subprocess.run(cmd, check=True, text=True, capture_output=True, **kw)


def start(d: Path, port: int, max_conn: int):
    data = d / "data"
    d.mkdir(parents=True, exist_ok=True)
    if os.geteuid() == 0:
        shutil.chown(d, "postgres", "postgres")
        os.chmod(d, 0o755)
    envf = d / "env.sh"
    if not (data / "PG_VERSION").exists():
        admin_pw = secrets.token_hex(12)
        pwf = d / "pwfile"
        pwf.write_text(admin_pw)
        if os.geteuid() == 0:
            shutil.chown(pwf, "postgres", "postgres")
        run([str(BIN / "initdb"), "-D", str(data), "-U", "spike_admin", "--auth=scram-sha-256", f"--pwfile={pwf}", "-E", "UTF8"])
        pwf.unlink()
        envf.write_text(
            f"# nur lokal, zufällig erzeugt, nicht ins Repository\nexport TAKTOVIA_DB_HOST=127.0.0.1\nexport TAKTOVIA_DB_PORT={port}\n"
            f"export TAKTOVIA_DB_ADMIN_USER=spike_admin\nexport TAKTOVIA_DB_ADMIN_PASSWORD={admin_pw}\n"
            f"export TAKTOVIA_DB_MIGRATOR_PASSWORD={secrets.token_hex(12)}\nexport TAKTOVIA_DB_RUNTIME_PASSWORD={secrets.token_hex(12)}\n")
        os.chmod(envf, 0o600)
    st = subprocess.run([str(BIN / "pg_ctl"), "-D", str(data), "status"], text=True, capture_output=True) if os.geteuid() != 0 else \
        subprocess.run(["runuser", "-u", "postgres", "--", str(BIN / "pg_ctl"), "-D", str(data), "status"], text=True, capture_output=True)
    if st.returncode != 0:
        run([str(BIN / "pg_ctl"), "-D", str(data), "-l", str(d / "postgres.log"), "-w", "-o",
             f"-p {port} -c listen_addresses=127.0.0.1 -c max_connections={max_conn} -c unix_socket_directories={d}", "start"])
    print(f"Cluster läuft auf 127.0.0.1:{port} (max_connections={max_conn}); Umgebung: source {envf}")


def stop(d: Path):
    run([str(BIN / "pg_ctl"), "-D", str(d / "data"), "-m", "fast", "-w", "stop"])
    print("gestoppt")


def destroy(d: Path):
    if (d / "data" / "PG_VERSION").exists():
        try:
            stop(d)
        except subprocess.CalledProcessError:
            pass
    shutil.rmtree(d, ignore_errors=True)
    print("gelöscht", d)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["start", "stop", "destroy"])
    ap.add_argument("--dir", default=str(DEFAULT_DIR))
    ap.add_argument("--port", type=int, default=5433)
    ap.add_argument("--max-connections", type=int, default=300)
    a = ap.parse_args()
    d = Path(a.dir)
    try:
        {"start": lambda: start(d, a.port, a.max_connections), "stop": lambda: stop(d), "destroy": lambda: destroy(d)}[a.cmd]()
    except subprocess.CalledProcessError as e:
        sys.exit(f"Fehler: {e.cmd}\n{e.stderr}")
