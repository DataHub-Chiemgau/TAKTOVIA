"""python -m taktovia_backup <Befehl>: keygen | backup | journal-export | verify | restore-test | archive-wal | restore-wal | consistency | metrics

Konfiguration über Umgebungsvariablen (nie Secrets im Repository): TAKTOVIA_DB_HOST/PORT/ADMIN_USER/ADMIN_PASSWORD, TAKTOVIA_BACKUP_KEY_FILE (0600),
TAKTOVIA_BACKUP_DIR (Backupspeicher), TAKTOVIA_BACKUP_PRINCIPAL (Betriebsrolle: ops-backup | ops-restore | ops-retention),
TAKTOVIA_BACKUP_STATE_FILE (Ergebnis des letzten Backuplaufs für die Metrik `taktovia_backup_last_status`, WP-M5-14), TAKTOVIA_METRICS_FILE (Ausgabe `metrics`, Textfile-Collector),
TAKTOVIA_ENVIRONMENT (Umgebungskennung der Quelle bzw. des Ziels; ein Restore in eine andere Umgebung wird abgewiesen).
"""
import argparse
import json
import os
import sys
import uuid
from pathlib import Path

from . import consistency, crypto, dbops, metrics
from .store import BackupStore


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="taktovia_backup")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("keygen").add_argument("path")
    b = sub.add_parser("backup"); b.add_argument("--database", required=True); b.add_argument("--objects-dir")
    sub.add_parser("journal-export").add_argument("--database", required=True)
    v = sub.add_parser("verify"); v.add_argument("backup_id")
    r = sub.add_parser("restore-test"); r.add_argument("backup_id"); r.add_argument("--out-dir", default="restore-protokolle"); r.add_argument("--keep", action="store_true")
    cs = sub.add_parser("consistency"); cs.add_argument("--database", required=True); cs.add_argument("--no-migrations", action="store_true")
    mt = sub.add_parser("metrics"); mt.add_argument("--database", default="postgres"); mt.add_argument("--standby-expected", action="store_true")
    w = sub.add_parser("archive-wal"); w.add_argument("path"); w.add_argument("name")
    rw = sub.add_parser("restore-wal"); rw.add_argument("name"); rw.add_argument("dest")
    a = p.parse_args(argv)
    if a.cmd == "keygen":
        crypto.generate_key(Path(a.path)); print("Schlüssel angelegt (Modus 0600)"); return 0
    key = crypto.load_key(Path(os.environ["TAKTOVIA_BACKUP_KEY_FILE"]))
    store = BackupStore(Path(os.environ["TAKTOVIA_BACKUP_DIR"]), os.environ.get("TAKTOVIA_BACKUP_PRINCIPAL", "ops-backup"))
    if a.cmd == "archive-wal":
        dbops.archive_wal(Path(a.path), a.name, store, key); return 0
    if a.cmd == "restore-wal":
        dbops.restore_wal(a.name, Path(a.dest), store, key); return 0
    cluster = dbops.Cluster()
    state_file = os.environ.get("TAKTOVIA_BACKUP_STATE_FILE")
    if a.cmd == "backup":
        try:
            m = dbops.create_backup(cluster, a.database, store, key, objects_dir=Path(a.objects_dir) if a.objects_dir else None)
        except BaseException:
            if state_file:
                metrics.record_backup_result(Path(state_file), False)     # ein fehlgeschlagenes Backup wird sichtbar (Alarm BACKUP_FEHLGESCHLAGEN), nie verschwiegen
            raise
        if state_file:
            metrics.record_backup_result(Path(state_file), True)
        print(json.dumps({"backup_id": m["backup_id"], "taken_at": m["taken_at"]})); return 0
    if a.cmd == "consistency":
        with cluster.connect(a.database) as c:
            rep = consistency.run_all(c, dbops.read_journal(store, key), None if a.no_migrations else dbops.REPO / "db" / "migrations")
        print(json.dumps(rep.as_dict(), indent=1, ensure_ascii=False)); return 0 if rep.release_allowed else 1
    if a.cmd == "metrics":
        with cluster.connect(a.database) as c:
            st = metrics.wal_archive_stats(c)
            standby = metrics.standby_connected(c) if a.standby_expected else None
        text = metrics.render(backup_state=json.loads(Path(state_file).read_text()) if state_file and Path(state_file).exists() else None, wal=st,
                              standby_expected=a.standby_expected, standby_streaming=standby)
        out = os.environ.get("TAKTOVIA_METRICS_FILE")
        metrics.write_textfile(Path(out), text) if out else print(text, end="")
        return 0
    if a.cmd == "journal-export":
        print(dbops.export_journal(cluster, a.database, store, key), "Zeilen ausgeleitet"); return 0
    if a.cmd == "verify":
        dbops.verify_backup(store, key, a.backup_id); print("Backup unverändert und authentisch"); return 0
    if a.cmd == "restore-test":
        target = "restore_" + uuid.uuid4().hex[:10]
        try:
            proto = dbops.restore(cluster, store, key, a.backup_id, target, out_dir=Path(a.out_dir), target_environment=os.environ.get("TAKTOVIA_ENVIRONMENT"))
            print(json.dumps({"result": proto["result"], "duration_s": proto["duration_s"]})); return 0
        except Exception as e:
            print("Restore-Test FEHLGESCHLAGEN:", e, file=sys.stderr); return 1
        finally:
            if not a.keep:
                dbops.drop_database(cluster, target)
    return 2


if __name__ == "__main__":
    sys.exit(main())
