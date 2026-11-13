"""WAL-Archiv (PITR, WP-M5-14): verschlüsselte, einmalig abgelegte Segmente, Prüfung der Kette und Aufruf für `archive_command`/`restore_command`.

Bewusst ohne Datenbanktreiber (nur `crypto` und `store`): der Prozess läuft im Datenbankserver-Benutzer und soll nur das Nötigste importieren.
Aufruf: `python -m taktovia_backup.wal archive <pfad> <name>` bzw. `restore <name> <ziel>`; Konfiguration über TAKTOVIA_BACKUP_KEY_FILE, TAKTOVIA_BACKUP_DIR, TAKTOVIA_BACKUP_PRINCIPAL.
Exit-Codes sind die der PostgreSQL-Schnittstelle: 0 Erfolg, sonst Fehler (nie ein stiller Erfolg bei Abweichung).
"""
import os
import re
import sys
import tempfile
from pathlib import Path

from . import crypto
from .store import BackupStore

SEGMENT_RE = re.compile(r"^[0-9A-F]{24}$")
WAL_SEGMENT_SIZE = 16 * 1024 * 1024


class WalArchiveConflict(Exception):
    """Ein Segment gleichen Namens mit anderem Inhalt liegt bereits im Archiv (nie überschreiben, PostgreSQL meldet den Fehler und versucht es erneut)."""


def segment_no(name: str, segment_size: int = WAL_SEGMENT_SIZE) -> tuple[int, int]:
    """(Zeitleiste, laufende Segmentnummer) aus dem Segmentnamen."""
    if not SEGMENT_RE.match(name):
        raise ValueError(f"kein WAL-Segmentname: {name}")
    per_log = 0x100000000 // segment_size
    return int(name[:8], 16), int(name[8:16], 16) * per_log + int(name[16:], 16)


def segment_name(tli: int, segno: int, segment_size: int = WAL_SEGMENT_SIZE) -> str:
    per_log = 0x100000000 // segment_size
    return f"{tli:08X}{segno // per_log:08X}{segno % per_log:08X}"


def archive_wal(src: Path, name: str, store: BackupStore, key: bytes) -> None:
    """archive_command: verschlüsselt ein WAL-Segment (oder Verlaufsdatei) und legt es einmalig ab. Ein zweiter Aufruf mit gleichem Inhalt ist ein Erfolg (PostgreSQL wiederholt nach Abbruch),
    mit abweichendem Inhalt ein Fehler."""
    target = f"wal-{name}.enc"
    if target in store.list():
        with tempfile.TemporaryDirectory(prefix="tkwal-") as tmp:
            old = Path(tmp) / "old"
            crypto.decrypt_file(store.get(target), old, key)
            if old.read_bytes() != Path(src).read_bytes():
                raise WalArchiveConflict(f"{name}: abweichender Inhalt für ein bereits archiviertes Segment")
        return
    with tempfile.TemporaryDirectory(prefix="tkwal-") as tmp:
        enc = Path(tmp) / "wal.enc"
        crypto.encrypt_file(Path(src), enc, key)
        store.put(target, enc)


def restore_wal(name: str, dst: Path, store: BackupStore, key: bytes) -> None:
    """restore_command: entschlüsselt ein WAL-Segment (Authentifizierung inklusive) an den Zielpfad; ein beschädigtes Segment ergibt einen Fehler und nie eine Teildatei."""
    tmp = Path(str(dst) + ".tk-part")
    try:
        crypto.decrypt_file(store.get(f"wal-{name}.enc"), tmp, key)
        os.replace(tmp, dst)
    finally:
        tmp.unlink(missing_ok=True)


def archived_segments(store: BackupStore) -> list[str]:
    return sorted(n[4:-4] for n in store.list() if n.startswith("wal-") and n.endswith(".enc") and SEGMENT_RE.match(n[4:-4]))


def verify_archive(store: BackupStore, key: bytes, start_segment: str, segment_size: int = WAL_SEGMENT_SIZE) -> dict:
    """Prüft die Kette ab `start_segment` (Segment der Basissicherung): jedes Segment muss sich authentifizieren lassen (Beschädigung, falscher Schlüssel), und die Nummern
    müssen lückenlos aufeinanderfolgen. Ergebnis: {ok, checked, corrupt: [...], missing: [...], last_segment}."""
    tli, first = segment_no(start_segment, segment_size)
    names = [n for n in archived_segments(store) if segment_no(n, segment_size)[0] == tli and segment_no(n, segment_size)[1] >= first]
    corrupt, missing = [], []
    expected = first
    for n in names:
        no = segment_no(n, segment_size)[1]
        while expected < no:
            missing.append(segment_name(tli, expected, segment_size)); expected += 1
        try:
            for _ in crypto.decrypt_path_chunks(store.get(f"wal-{n}.enc"), key):
                pass
        except (crypto.BackupIntegrityError, crypto.WrongKeyError) as e:
            corrupt.append(f"{n}: {type(e).__name__}")
        expected = no + 1
    if not names:
        missing.append(start_segment)
    return {"ok": not corrupt and not missing, "checked": len(names), "corrupt": corrupt, "missing": missing, "last_segment": names[-1] if names else None}


def main(argv=None) -> int:
    a = list(sys.argv[1:] if argv is None else argv)
    if len(a) != 3 or a[0] not in ("archive", "restore"):
        print("Aufruf: wal archive <pfad> <name> | wal restore <name> <ziel>", file=sys.stderr)
        return 2
    key = crypto.load_key(Path(os.environ["TAKTOVIA_BACKUP_KEY_FILE"]))
    store = BackupStore(Path(os.environ["TAKTOVIA_BACKUP_DIR"]), os.environ.get("TAKTOVIA_BACKUP_PRINCIPAL", "ops-backup"))
    try:
        if a[0] == "archive":
            archive_wal(Path(a[1]), a[2], store, key)
        else:
            restore_wal(a[1], Path(a[2]), store, key)
    except Exception as e:  # noqa: BLE001 - jede Abweichung ist ein Fehler gegenüber PostgreSQL
        print(f"{a[0]} {a[1]}: {type(e).__name__}: {str(e)[:200]}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
