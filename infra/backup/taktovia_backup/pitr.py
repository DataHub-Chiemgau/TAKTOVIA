"""Kontinuierliche WAL-Archivierung und Wiederherstellung auf einen Zeitpunkt (PITR) gegen einen echten PostgreSQL-Cluster (WP-M5-14).

Alles hier ruft die echten PostgreSQL-Werkzeuge auf (`initdb`, `pg_ctl`, `pg_basebackup`, Wiederherstellung per `restore_command`); der Cluster kann ein privater Testcluster oder ein
Wiederherstellungsziel im Betrieb sein. Basissicherung und WAL-Segmente werden vor dem Ablegen mit AES-256-GCM verschlüsselt (crypto.py) und einmalig im Backupspeicher abgelegt (store.py).

Schutzregeln (Abnahme 4):
 * Die Umgebungskennung der Quelle steht im signierten Manifest; ein Restore in eine **andere** Umgebung wird technisch abgewiesen (`EnvironmentMismatch`), bevor etwas entschlüsselt oder geschrieben wird.
 * Vor jedem Restore wird die WAL-Kette geprüft (Beschädigung, Lücken); ein Befund blockiert den Restore.
Der Betriebsbenutzer des Clusters ist bei `root`-Ausführung `postgres` (PostgreSQL startet nicht als root); sonst der aufrufende Benutzer.
"""
import glob
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

import psycopg

from . import crypto, dbops, wal
from .environment import KNOWN_ENVIRONMENTS, EnvironmentMismatch, assert_restore_target  # noqa: F401
from .store import BackupStore

BASE_FORMAT = "taktovia-pitr-base.v1"
REPO = Path(__file__).resolve().parents[3]


class PitrFailed(Exception):
    pass


def pg_bindir() -> Path:
    found = shutil.which("initdb")
    if found:
        return Path(found).parent
    cands = sorted(glob.glob("/usr/lib/postgresql/*/bin"))
    if not cands:
        raise PitrFailed("PostgreSQL-Werkzeuge (initdb, pg_ctl, pg_basebackup) nicht gefunden")
    return Path(cands[-1])


def default_run_as() -> list[str]:
    return ["runuser", "-u", "postgres", "--"] if os.geteuid() == 0 else []


def wal_python(run_as: list[str]) -> str:
    """Interpreter, der als Clusterbenutzer `cryptography` importieren kann (nur diese Abhängigkeit braucht `taktovia_backup.wal`)."""
    for cand in (shutil.which("python3"), "/usr/bin/python3", "python3"):
        if cand and subprocess.run(run_as + [cand, "-c", "import cryptography"], capture_output=True).returncode == 0:
            return cand
    raise PitrFailed("kein Python mit `cryptography` für den Clusterbenutzer gefunden")


def _conf_quote(v: str) -> str:
    return "'" + v.replace("'", "''") + "'"


def wal_command(kind: str, store_dir: Path, key_file: Path, run_as: list[str]) -> str:
    """`archive_command` bzw. `restore_command`: laufen im Clusterbenutzer, Betriebsrolle `ops-backup` bzw. `ops-restore` (Prinzipale des Backupspeichers)."""
    principal = "ops-backup" if kind == "archive" else "ops-restore"
    args = "%p %f" if kind == "archive" else "%f %p"
    return " ".join(["env", f"TAKTOVIA_BACKUP_DIR={shlex.quote(str(store_dir))}", f"TAKTOVIA_BACKUP_KEY_FILE={shlex.quote(str(key_file))}", f"TAKTOVIA_BACKUP_PRINCIPAL={principal}",
                     f"PYTHONPATH={shlex.quote(str(REPO / 'infra' / 'backup'))}", shlex.quote(wal_python(run_as)), "-m", "taktovia_backup.wal", kind, args])


class PgCluster:
    """Ein PostgreSQL-Cluster (Datenverzeichnis, Port, Socketverzeichnis), nur über die echten Werkzeuge bedient."""

    def __init__(self, datadir: Path, port: int, sockdir: Path, run_as: list[str] | None = None, bindir: Path | None = None, user: str = "taktovia_admin"):
        self.datadir, self.port, self.sockdir = Path(datadir), int(port), Path(sockdir)
        self.run_as = default_run_as() if run_as is None else run_as
        self.bindir = bindir or pg_bindir()
        self.user = user
        self.log = self.datadir.parent / f"{self.datadir.name}.log"

    def _bin(self, name: str) -> str:
        return str(self.bindir / name)

    def _run(self, args: list[str], **kw) -> subprocess.CompletedProcess:
        return subprocess.run(self.run_as + args, capture_output=True, text=True, **kw)

    def prepare_dirs(self) -> None:
        for d in (self.datadir, self.sockdir):
            d.mkdir(parents=True, exist_ok=True)
            os.chmod(d, 0o700)
            if self.run_as:
                shutil.chown(d, "postgres", "postgres")

    def initdb(self) -> None:
        self.prepare_dirs()
        p = self._run([self._bin("initdb"), "-D", str(self.datadir), "-A", "trust", "-U", self.user, "--no-sync", "-E", "UTF8"])
        if p.returncode != 0:
            raise PitrFailed("initdb fehlgeschlagen: " + p.stderr[-400:])

    def configure(self, settings: dict[str, str]) -> None:
        conf = self.datadir / "postgresql.auto.conf"
        lines = "".join(f"{k} = {_conf_quote(str(v))}\n" for k, v in settings.items())
        conf.write_text(conf.read_text() + lines if conf.exists() else lines)
        if self.run_as:
            shutil.chown(conf, "postgres", "postgres")

    def base_settings(self) -> dict[str, str]:
        return {"port": str(self.port), "unix_socket_directories": str(self.sockdir), "listen_addresses": "", "fsync": "off", "synchronous_commit": "on", "max_wal_senders": "4",
                "wal_level": "replica", "shared_buffers": "32MB"}

    def start(self, timeout: int = 120) -> None:
        p = self._run([self._bin("pg_ctl"), "-D", str(self.datadir), "-w", "-t", str(timeout), "-l", str(self.log), "start"])
        if p.returncode != 0:
            raise PitrFailed("Cluster startet nicht: " + (self.log_tail() or p.stderr)[-600:])

    def stop(self, mode: str = "fast") -> None:
        self._run([self._bin("pg_ctl"), "-D", str(self.datadir), "-m", mode, "-w", "stop"])

    def running(self) -> bool:
        return self._run([self._bin("pg_ctl"), "-D", str(self.datadir), "status"]).returncode == 0

    def log_tail(self) -> str:
        try:
            return self.log.read_text(errors="replace")[-1500:]
        except OSError:
            return ""

    def connect(self, dbname: str = "postgres", autocommit: bool = True):
        return psycopg.connect(host=str(self.sockdir), port=self.port, user=self.user, dbname=dbname, autocommit=autocommit)

    def enable_archiving(self, store_dir: Path, key_file: Path, archive_timeout_s: int = 60) -> None:
        self.configure({"archive_mode": "on", "archive_timeout": f"{archive_timeout_s}s", "archive_command": wal_command("archive", store_dir, key_file, self.run_as)})


def _start_segment_from_head(head: bytes) -> str | None:
    try:
        with tarfile.open(fileobj=io.BytesIO(head), mode="r|") as tf:
            for m in tf:
                if m.name == "backup_label":
                    text = tf.extractfile(m).read().decode()
                    found = re.search(r"\(file ([0-9A-F]{24})\)", text)
                    return found.group(1) if found else None
                break
    except (tarfile.TarError, EOFError):
        return None
    return None


def base_backup(cluster: PgCluster, store: BackupStore, key: bytes, backup_id: str, environment: str) -> dict:
    """Basissicherung des laufenden Clusters (`pg_basebackup`, Tar auf stdout, ohne Klartextdatei) verschlüsselt im Backupspeicher; Manifest mit Umgebungskennung und Startsegment."""
    if environment not in KNOWN_ENVIRONMENTS:
        raise ValueError("unbekannte Umgebungskennung")
    with cluster.connect() as c:
        lower = c.execute("SELECT pg_walfile_name(pg_current_wal_lsn()), current_setting('server_version')").fetchone()
    with tempfile.TemporaryDirectory(prefix="tkbase-") as tmp:
        enc = Path(tmp) / "base.enc"
        cmd = cluster.run_as + [cluster._bin("pg_basebackup"), "-h", str(cluster.sockdir), "-p", str(cluster.port), "-U", cluster.user, "-D", "-", "-Ft", "-X", "none", "-c", "fast", "--no-sync"]
        taken = datetime.now(timezone.utc)
        head = bytearray()

        class Tee(io.RawIOBase):
            def __init__(self, src):
                self.src = src
            def readable(self):
                return True
            def readinto(self, b):
                n = self.src.readinto(b)
                if n and len(head) < 65536:
                    head.extend(bytes(b[:n]))
                return n

        with subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as p:
            with open(enc, "wb") as out:
                crypto.encrypt_stream(io.BufferedReader(Tee(p.stdout)), out, key)
            err = p.stderr.read().decode(errors="replace")
            if p.wait() != 0:
                raise PitrFailed("pg_basebackup fehlgeschlagen: " + err[:400])
        start = _start_segment_from_head(bytes(head)) or lower[0]
        name = f"{backup_id}.base.enc"
        store.put(name, enc)
        manifest = {"format": BASE_FORMAT, "backup_id": backup_id, "environment": environment, "taken_at": taken.isoformat(), "server_version": lower[1],
                    "start_wal_segment": start, "key_id": crypto.key_id(key).hex(), "files": {name: {"sha256": crypto.sha256_file(store.path(name)), "size": enc.stat().st_size}}}
        dbops.sign_manifest(manifest, key)
        mpath = Path(tmp) / "manifest.json"
        mpath.write_text(json.dumps(manifest, indent=1, sort_keys=True))
        store.put(f"{backup_id}.manifest.json", mpath)
    return manifest


def restore_pitr(store: BackupStore, key: bytes, backup_id: str, target: PgCluster, target_environment: str | None, target_time: str | None = None,
                 archive_store_dir: Path | None = None, key_file: Path | None = None, archive_after_restore: bool = False, timeout_s: int = 300) -> dict:
    """Stellt die Basissicherung in das **leere** Datenverzeichnis von `target` her und spielt das WAL aus dem Archiv bis `target_time` (ISO-Zeitstempel mit Zeitzone; ohne Angabe bis zum Ende
    des Archivs) ab; danach wird der Cluster hochgestuft. Reihenfolge: Manifest prüfen, Umgebung prüfen, WAL-Kette prüfen, erst dann entschlüsseln und schreiben.
    Liefert das Protokoll (Schritte mit Zeitpunkten); jeder Verstoß ist eine Ausnahme vor jeder Wirkung."""
    t0 = time.monotonic()
    proto = {"backup_id": backup_id, "target_time": target_time, "steps": [], "result": "FAILED"}

    def step(name, **kw):
        proto["steps"].append({"step": name, "at": datetime.now(timezone.utc).isoformat(), **kw})

    manifest = json.loads(store.get(f"{backup_id}.manifest.json").read_text())
    dbops.verify_manifest(manifest, key)
    if manifest.get("format") != BASE_FORMAT:
        raise PitrFailed("kein PITR-Basisbackup")
    step("manifest_verified", taken_at=manifest["taken_at"])
    assert_restore_target(manifest.get("environment"), target_environment)
    step("environment_checked", environment=manifest["environment"])
    chain = wal.verify_archive(store, key, manifest["start_wal_segment"])
    proto["wal_chain"] = {k: chain[k] for k in ("ok", "checked", "corrupt", "missing", "last_segment")}
    if not chain["ok"]:
        raise PitrFailed(f"WAL-Kette verletzt, Restore blockiert: beschädigt={chain['corrupt']} fehlend={chain['missing']}")
    step("wal_chain_verified", segments=chain["checked"])
    base = f"{backup_id}.base.enc"
    if crypto.sha256_file(store.get(base)) != manifest["files"][base]["sha256"]:
        raise crypto.BackupIntegrityError("Basissicherung verändert (Prüfsumme)")
    if target.datadir.exists() and any(target.datadir.iterdir()):
        raise PitrFailed("Zieldatenverzeichnis ist nicht leer (Restore nur in einen neuen Cluster)")
    target.prepare_dirs()
    with subprocess.Popen(target.run_as + ["tar", "-C", str(target.datadir), "-xf", "-"], stdin=subprocess.PIPE, stderr=subprocess.PIPE) as tar:
        try:
            for chunk in crypto.decrypt_path_chunks(store.get(base), key):
                tar.stdin.write(chunk)
            tar.stdin.close()
        except BaseException:
            tar.kill(); tar.wait(); raise
        err = tar.stderr.read().decode(errors="replace")
        if tar.wait() != 0:
            raise PitrFailed("Basissicherung nicht entpackbar: " + err[:300])
    step("base_extracted")
    kdir = archive_store_dir or store.root
    kfile = key_file or Path(os.environ["TAKTOVIA_BACKUP_KEY_FILE"])
    settings = target.base_settings() | {"restore_command": wal_command("restore", kdir, kfile, target.run_as), "recovery_target_action": "promote", "archive_mode": "off", "hot_standby": "on"}
    if target_time:
        settings["recovery_target_time"] = target_time
        settings["recovery_target_inclusive"] = "on"
    target.configure(settings)
    signal = target.datadir / "recovery.signal"
    signal.write_text("")
    if target.run_as:
        shutil.chown(signal, "postgres", "postgres")
    step("recovery_configured")
    try:
        target.start(timeout=timeout_s)
        deadline = time.monotonic() + timeout_s
        with target.connect() as c:
            while c.execute("SELECT pg_is_in_recovery()").fetchone()[0]:
                if time.monotonic() > deadline:
                    raise PitrFailed("Wiederherstellung wurde nicht rechtzeitig abgeschlossen")
                time.sleep(0.5)
            proto["replay_timestamp"] = (lambda v: v.isoformat() if v else None)(c.execute("SELECT pg_last_xact_replay_timestamp()").fetchone()[0])
    except (psycopg.OperationalError, PitrFailed) as e:
        raise PitrFailed(f"Wiederherstellung fehlgeschlagen: {e}; Log: {target.log_tail()[-500:]}") from e
    if archive_after_restore:
        target.enable_archiving(kdir, kfile)
        target.stop(); target.start()
    step("recovered_and_promoted")
    proto["result"] = "SUCCESS"
    proto["duration_s"] = round(time.monotonic() - t0, 3)
    return proto
