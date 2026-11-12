"""Schreibgeschützter Backupspeicher mit getrennter Berechtigungsdomäne (lokaler Ersatz für Object Lock / getrennte IAM-Domäne).

Regeln:
 * Schreiben nur einmal je Name (kein Überschreiben), Datei danach 0440, Sperrdatei mit Aufbewahrungsende.
 * Löschen ist bis zum Aufbewahrungsende für **jeden** verboten; danach nur die Aufbewahrungsrolle.
 * Rollen (Prinzipale): `ops-backup` (schreiben, lesen), `ops-restore` (lesen), `ops-retention` (lesen, abgelaufene löschen).
   Die Anwendungsrolle (`app-runtime`), die Migratorrolle (`app-migrator`) und jede andere Identität haben **keinen** Zugriff, weder lesend noch ändernd.
Im Betrieb entspricht das einem Bucket mit Object Lock (Compliance-Modus) und einer eigenen IAM-Rolle für Betrieb; dort gilt dieselbe Prüfung serverseitig
(nicht ausgeführt, siehe infra/backup/README.md).
"""
import json
import os
import time
from pathlib import Path

PERMISSIONS = {
    "ops-backup": {"put", "get", "list"},
    "ops-restore": {"get", "list"},
    "ops-retention": {"get", "list", "delete_expired"},
}


class StoreDenied(Exception):
    pass


class BackupStore:
    def __init__(self, root: Path, principal: str, retention_days: int = 35):
        self.root = Path(root)
        self.principal = principal
        self.retention_days = retention_days
        self.root.mkdir(parents=True, exist_ok=True)

    def _allow(self, action: str) -> None:
        if action not in PERMISSIONS.get(self.principal, set()):
            raise StoreDenied(f"{self.principal} darf im Backupspeicher nicht '{action}'")

    def path(self, name: str) -> Path:
        if "/" in name or name.startswith("."):
            raise ValueError("ungültiger Name")
        return self.root / name

    def put(self, name: str, src: Path, now: float | None = None) -> Path:
        self._allow("put")
        dst = self.path(name)
        now = time.time() if now is None else now
        # Schreiben einmalig: O_EXCL schlägt fehl, wenn der Name existiert (kein Überschreiben, auch nicht durch dieselbe Rolle)
        fd = os.open(dst, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o440)
        try:
            with open(src, "rb") as s, os.fdopen(fd, "wb") as d:
                for block in iter(lambda: s.read(1 << 20), b""):
                    d.write(block)
                d.flush()
                os.fsync(d.fileno())
        except BaseException:
            dst.unlink(missing_ok=True)
            raise
        os.chmod(dst, 0o440)
        lock = self.root / (name + ".lock.json")
        lock.write_text(json.dumps({"retain_until": now + self.retention_days * 86400, "written_by": self.principal}))
        os.chmod(lock, 0o440)
        return dst

    def get(self, name: str) -> Path:
        self._allow("get")
        p = self.path(name)
        if not p.exists():
            raise FileNotFoundError(name)
        return p

    def list(self) -> list[str]:
        self._allow("list")
        return sorted(p.name for p in self.root.iterdir() if not p.name.endswith(".lock.json"))

    def delete(self, name: str, now: float | None = None) -> None:
        """Löschen: vor Ablauf der Aufbewahrung für niemanden erlaubt (WORM); danach nur `ops-retention`."""
        now = time.time() if now is None else now
        lock = self.root / (name + ".lock.json")
        retain_until = json.loads(lock.read_text())["retain_until"] if lock.exists() else float("inf")
        if now < retain_until:
            raise StoreDenied(f"{name} ist bis zum Ende der Aufbewahrung gegen Änderung und Löschung geschützt (Aufbewahrung bis {retain_until})")
        self._allow("delete_expired")
        os.chmod(self.root, 0o750)
        self.path(name).unlink(missing_ok=True)
        lock.unlink(missing_ok=True)

    def overwrite(self, name: str, src: Path) -> None:
        """Gibt es nicht: ein bestehendes Backup ist unveränderlich."""
        raise StoreDenied("Backups werden nie überschrieben")
