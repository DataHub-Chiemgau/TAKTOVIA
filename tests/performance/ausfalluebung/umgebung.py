"""Übungsumgebung (WP-M5-15): privater PostgreSQL-Cluster mit dem echten Schema (db.py migrate), WAL-Archivierung und Basissicherung mit den Betriebswerkzeugen aus infra/backup,
Ereignisstrom und Lösch-/Sperrjournal außerhalb der Datenbank.

Echt: initdb/pg_ctl/pg_basebackup, `archive_command` (verschlüsselt, Betriebsrolle ops-backup) mit dem Produktionswert `archive_timeout = 60s` aus infra/backup/postgresql-archive.conf,
Migrationen des Repositorys, Runtime-Rolle mit RLS. Ersatz (gekennzeichnet): der **Ereignisstrom** (Messaging) ist eine Datei außerhalb des Clusters; der Backupspeicher ein lokales Verzeichnis
(S3 Object Lock nicht ausgeführt); ein Standby über Hosts gibt es nicht (D-05)."""
import json
import os
import socket
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "infra" / "backup"))
sys.path.insert(0, str(REPO / "db"))

from taktovia_backup import crypto, dbops, pitr  # noqa: E402
from taktovia_backup.store import BackupStore  # noqa: E402

ARCHIVE_TIMEOUT_S = 60      # Produktionswert (infra/backup/postgresql-archive.conf)
DB = "taktovia"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def jetzt() -> datetime:
    return datetime.now(timezone.utc)


class UebungsCluster(pitr.PgCluster):
    """Wie `PgCluster`, zusätzlich auf Loopback erreichbar (die Anwendung verbindet per TCP; die Betriebswerkzeuge nutzen weiter den Socket)."""

    def base_settings(self) -> dict[str, str]:
        return {**super().base_settings(), "listen_addresses": "127.0.0.1"}

    def runtime_connect(self, db: str = DB, autocommit: bool = False):
        import psycopg
        return psycopg.connect(host=str(self.sockdir), port=self.port, user="taktovia_runtime", dbname=db, password=os.environ.get("TAKTOVIA_DB_RUNTIME_PASSWORD", "x"), autocommit=autocommit)


class Ereignisstrom:
    """Ersatz für das Messaging (nicht ausgeführt: echter Broker): Anhängedatei außerhalb des Clusters. Der Relay schreibt NACH dem Commit; fällt der Strom aus, geht nichts verloren, was in
    der Outbox steht (mindestens einmal), aber der Abgleich kann das Verlustfenster dann nicht aus dem Strom bestimmen (siehe abgleich.abgleichbericht)."""

    def __init__(self, pfad: Path):
        self.pfad = Path(pfad)
        self.lock = threading.Lock()
        self.verfuegbar = True
        self.pfad.write_text("")

    def publish(self, typ: str, tenant, **kw) -> bool:
        with self.lock:
            if not self.verfuegbar:
                return False
            with open(self.pfad, "a", encoding="utf-8") as f:
                f.write(json.dumps({"zeitpunkt": jetzt().isoformat(), "typ": typ, "tenant": str(tenant), **{k: str(v) for k, v in kw.items()}}) + "\n")
            return True

    def lesen(self) -> list[dict]:
        with self.lock:
            return [json.loads(l) for l in self.pfad.read_text(encoding="utf-8").splitlines() if l.strip()]


class JournalExport(threading.Thread):
    """Fortlaufender Export des Lösch-/Sperrjournals in den Backupspeicher (hash-verkettet, verschlüsselt), unabhängig vom Datenbankbackup (Kap. 01 §1.7)."""

    def __init__(self, cluster, store, key, intervall_s=1.0):
        super().__init__(daemon=True)
        self.cluster, self.store, self.key, self.intervall = cluster, store, key, intervall_s
        self.stop_ev = threading.Event()
        self.fehler: list[str] = []
        self.lock = threading.Lock()

    def run(self):
        while not self.stop_ev.is_set():
            self.einmal()
            self.stop_ev.wait(self.intervall)

    def einmal(self) -> bool:
        """Exportiert alle neuen Journalzeilen; True nur, wenn der Export gelang (Aufrufer bestätigt eine Löschung erst danach)."""
        with self.lock:
            try:
                dbops.export_journal(self.cluster, DB, self.store, self.key)
                return True
            except Exception as e:  # noqa: BLE001 - Cluster ist im Ausfall nicht mehr erreichbar
                self.fehler.append(f"{type(e).__name__}: {str(e)[:120]}")
                return False

    def stop(self):
        self.stop_ev.set()
        self.join(10)


class Uebung:
    """Besitzt Verzeichnisse, Schlüssel, Backupspeicher und den Quellcluster."""

    def __init__(self, base: Path, umgebung: str = "development"):
        self.base, self.umgebung = Path(base), umgebung
        os.chmod(self.base, 0o755)
        self.key_file = self.base / "backup.key"
        self.key = crypto.generate_key(self.key_file)
        self.store_dir = self.base / "archiv"
        self.store_dir.mkdir()
        self._chown(self.base)
        self.store = BackupStore(self.store_dir, "ops-backup")
        self.rstore = BackupStore(self.store_dir, "ops-restore")
        self.bus = Ereignisstrom(self.base / "ereignisstrom.jsonl")
        self.cluster: UebungsCluster | None = None
        self.ziel: UebungsCluster | None = None
        self.clusters: list = []
        self.journal: "JournalExport | None" = None

    @staticmethod
    def _chown(path: Path):
        pitr_user = pitr.default_run_as()
        if pitr_user:
            import shutil
            for p in [path, *path.rglob("*")]:
                shutil.chown(p, "postgres", "postgres")

    def env(self, cluster) -> dict:
        return {**os.environ, "TAKTOVIA_DB_HOST": str(cluster.sockdir), "TAKTOVIA_DB_PORT": str(cluster.port), "TAKTOVIA_DB_ADMIN_USER": cluster.user, "TAKTOVIA_DB_NAME": DB,
                "TAKTOVIA_ENVIRONMENT": self.umgebung}

    def quelle_aufbauen(self) -> UebungsCluster:
        c = UebungsCluster(self.base / "quelle", free_port(), self.base / "sock-quelle")
        c.initdb()
        c.configure(c.base_settings())
        c.enable_archiving(self.store_dir, self.key_file, archive_timeout_s=ARCHIVE_TIMEOUT_S)
        c.start()
        self.clusters.append(c)
        self.cluster = c
        env = self.env(c)
        env.setdefault("TAKTOVIA_DB_ADMIN_PASSWORD", "x")
        for schritt in ("reset", "migrate"):
            p = subprocess.run([sys.executable, str(REPO / "db" / "db.py"), schritt], env=env, capture_output=True, text=True)
            if p.returncode != 0:
                raise RuntimeError(f"db.py {schritt} im Übungscluster fehlgeschlagen: {p.stderr[-400:]}")
        return c

    def admin(self, cluster=None, db: str = DB):
        return (cluster or self.cluster).connect(db)

    def basissicherung(self) -> dict:
        bid = "uebung-" + time.strftime("%Y%m%dT%H%M%S")
        return pitr.base_backup(self.cluster, self.store, self.key, bid, self.umgebung)

    def warte_archiv(self, cluster=None, nach: str | None = None, timeout=150.0) -> str:
        """Wartet, bis ein weiteres WAL-Segment archiviert wurde (`pg_stat_archiver`); liefert dessen Namen."""
        cl = cluster or self.cluster
        with cl.connect() as c:
            start = c.execute("SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver").fetchone()[0] if nach is None else nach
            end = time.monotonic() + timeout
            while time.monotonic() < end:
                cur = c.execute("SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver").fetchone()[0]
                if cur > start:
                    return cur
                time.sleep(0.5)
        raise TimeoutError("kein weiteres WAL-Segment archiviert")

    def ausfall(self, cluster=None) -> datetime:
        """Harter Ausfall der Primärdatenbank: Prozess beenden (`pg_ctl -m immediate`, entspricht SIGQUIT ohne Checkpoint) und das Datenverzeichnis verwerfen (Plattenverlust: das
        nicht archivierte WAL ist verloren). Liefert den Ausfallzeitpunkt (unmittelbar vor dem Beenden)."""
        cl = cluster or self.cluster
        t = jetzt()
        cl.stop("immediate")
        import shutil
        shutil.rmtree(cl.datadir, ignore_errors=True)
        return t

    def aufraeumen(self):
        for c in self.clusters:
            try:
                c.stop("immediate")
            except Exception:  # noqa: BLE001
                pass
        import shutil
        shutil.rmtree(self.base, ignore_errors=True)
