"""Metriken für Backup, WAL-Archivierung und Standby (WP-M5-14) im Textformat für den Node-Exporter-Textfile-Collector.

Metriken (Namen stehen in den Alarmregeln `infra/observability/alert-rules.d/kritische-szenarien.yml`):
  taktovia_backup_last_status                      1 = letzter Backuplauf erfolgreich, 0 = fehlgeschlagen
  taktovia_backup_last_success_timestamp_seconds   Unix-Zeit des letzten erfolgreichen Backups
  taktovia_wal_archive_lag_seconds                 Alter der ältesten noch nicht archivierten WAL-Daten (0, wenn alles archiviert ist): Obergrenze des RPO
  taktovia_wal_archive_failed_total                fehlgeschlagene Archivierungsversuche (pg_stat_archiver.failed_count)
  taktovia_standby_connected                       1/0, ob der erwartete Standby über Streaming verbunden ist (nur wenn ein Standby erwartet wird)
Die Werte tragen keine Mandanten-, Personen- oder Datenbankinhalte, nur technische Zahlen.
"""
import json
import os
import time
from pathlib import Path

from .wal import WAL_SEGMENT_SIZE, segment_no


def record_backup_result(state_file: Path, ok: bool, now: float | None = None) -> dict:
    """Hält das Ergebnis des letzten Backuplaufs fest (atomar). Ein Fehlschlag überschreibt den Zeitpunkt des letzten Erfolgs nie."""
    now = time.time() if now is None else now
    try:
        state = json.loads(Path(state_file).read_text())
    except (OSError, ValueError):
        state = {"last_success": None}
    state["last_status"] = 1 if ok else 0
    state["last_run"] = now
    if ok:
        state["last_success"] = now
    tmp = Path(str(state_file) + ".tmp")
    tmp.write_text(json.dumps(state))
    os.replace(tmp, state_file)
    return state


def wal_archive_stats(conn, now: float | None = None, segment_size: int = WAL_SEGMENT_SIZE) -> dict:
    """Liest den Archivierungsstand eines Primärservers. Verzug = Alter seit der letzten erfolgreichen Archivierung, solange WAL-Daten jenseits des archivierten Segments
    vorliegen oder die letzte Archivierung fehlgeschlagen ist; sonst 0. Wurde noch nie archiviert, zählt das Alter seit dem Serverstart."""
    r = conn.execute("""SELECT archived_count, failed_count, last_archived_wal, EXTRACT(EPOCH FROM last_archived_time), EXTRACT(EPOCH FROM last_failed_time),
                               pg_current_wal_lsn()::text, EXTRACT(EPOCH FROM clock_timestamp()), EXTRACT(EPOCH FROM pg_postmaster_start_time()) FROM pg_stat_archiver""").fetchone()
    archived, failed, last_wal, last_ok, last_fail, cur_lsn, db_now, started = r
    now = float(db_now) if now is None else now
    hi, lo = cur_lsn.split("/")
    cur = (int(hi, 16) << 32) + int(lo, 16)
    if last_wal is None:
        # noch nie archiviert: seit dem Serverstart aufgelaufene Daten gelten als unarchiviert (Alter ab Start), nie als „kein Verzug“
        return {"archived": archived, "failed": failed, "lag_seconds": max(0.0, now - float(started)) if cur > segment_size else 0.0, "pending_bytes": cur}
    last_no = segment_no(last_wal[:24], segment_size)[1]
    pending = cur - (last_no + 1) * segment_size
    failing = last_fail is not None and (last_ok is None or last_fail > last_ok)
    lag = max(0.0, now - float(last_ok)) if (pending > 0 or failing) else 0.0
    return {"archived": archived, "failed": failed, "lag_seconds": lag, "pending_bytes": max(pending, 0)}


def standby_connected(conn) -> int:
    return conn.execute("SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming'").fetchone()[0]


def render(backup_state: dict | None = None, wal: dict | None = None, standby_expected: bool = False, standby_streaming: int | None = None, now: float | None = None) -> str:
    now = time.time() if now is None else now
    out = []

    def gauge(name, help_, value, kind="gauge"):
        out.append(f"# HELP {name} {help_}\n# TYPE {name} {kind}\n{name} {value:g}")

    if backup_state is not None:
        gauge("taktovia_backup_last_status", "1 wenn der letzte Backuplauf erfolgreich war, 0 wenn er fehlgeschlagen ist", backup_state.get("last_status", 0))
        gauge("taktovia_backup_last_success_timestamp_seconds", "Unix-Zeit des letzten erfolgreichen Backups (0 = noch nie)", backup_state.get("last_success") or 0)
    if wal is not None:
        if wal.get("lag_seconds") is not None:
            gauge("taktovia_wal_archive_lag_seconds", "Alter der aeltesten noch nicht archivierten WAL-Daten in Sekunden", wal["lag_seconds"])
        gauge("taktovia_wal_archive_failed_total", "fehlgeschlagene WAL-Archivierungsversuche", wal["failed"], "counter")
    if standby_expected:
        gauge("taktovia_standby_connected", "1 wenn der erwartete Standby per Streaming verbunden ist", 1 if (standby_streaming or 0) > 0 else 0)
    return "\n".join(out) + "\n"


def write_textfile(path: Path, text: str) -> None:
    tmp = Path(str(path) + ".tmp")
    tmp.write_text(text)
    os.replace(tmp, path)
