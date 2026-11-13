"""Konsistentes Backup, Journalausleitung und Restore in eine isolierte Datenbank mit Prüfungen (WP-M1-39).

Konsistenz: Der Dump läuft mit `pg_dump --snapshot` auf einem exportierten Snapshot (REPEATABLE READ); die Prüfsummen je Tabelle werden in
derselben Transaktion berechnet. Der Stand ist damit transaktionskonsistent (keine halben Transaktionen) und passt zu den Prüfsummen. Der Zeitpunkt T ist
der Beginn dieses Snapshots (`taken_at`). Kontinuierliches WAL (PITR) ergänzt das (wal.py, pitr.py, `postgresql-archive.conf`; WP-M5-14).
"""
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import psycopg
from psycopg import sql

from . import crypto
from .environment import assert_restore_target
from .store import BackupStore

SCHEMA = "taktovia"
FORMAT = "taktovia-backup.v1"
REPO = Path(__file__).resolve().parents[3]


class RestoreFailed(Exception):
    pass


@dataclass
class Cluster:
    host: str = field(default_factory=lambda: os.environ.get("TAKTOVIA_DB_HOST", "127.0.0.1"))
    port: str = field(default_factory=lambda: os.environ.get("TAKTOVIA_DB_PORT", "5432"))
    admin_user: str = field(default_factory=lambda: os.environ.get("TAKTOVIA_DB_ADMIN_USER", "dev_admin"))
    admin_password: str = field(default_factory=lambda: os.environ["TAKTOVIA_DB_ADMIN_PASSWORD"])

    def connect(self, dbname: str, autocommit: bool = True):
        return psycopg.connect(host=self.host, port=self.port, dbname=dbname, user=self.admin_user, password=self.admin_password, autocommit=autocommit)

    def env(self) -> dict:
        return {**os.environ, "PGHOST": self.host, "PGPORT": str(self.port), "PGUSER": self.admin_user, "PGPASSWORD": self.admin_password}


# --- Prüfsummen und Strukturabdruck -------------------------------------------------------------------------------------------------------

def table_checksums(conn) -> dict:
    """{tabelle: {rows, sha256}} über alle Tabellen des Schemas `taktovia` (Zeilentexte sortiert, Administrator umgeht RLS)."""
    tables = [r[0] for r in conn.execute("SELECT tablename FROM pg_tables WHERE schemaname=%s ORDER BY tablename", (SCHEMA,)).fetchall()]
    out = {}
    for t in tables:
        h = hashlib.sha256()
        n = 0
        with conn.transaction(), conn.cursor(name=f"ck_{uuid.uuid4().hex[:8]}") as cur:
            cur.execute(sql.SQL("SELECT t::text FROM {}.{} t ORDER BY t::text").format(sql.Identifier(SCHEMA), sql.Identifier(t)))
            for (row,) in cur:
                h.update(row.encode("utf-8")); h.update(b"\n"); n += 1
        out[t] = {"rows": n, "sha256": h.hexdigest()}
    return out


def structure_fingerprint(conn) -> dict:
    """Wirksame Schutzmerkmale: RLS (aktiv/erzwungen, Policies), Trigger, Rollenattribute und Rechte der Runtime-Rolle."""
    rls = conn.execute("""SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                          WHERE n.nspname=%s AND c.relkind='r' ORDER BY 1""", (SCHEMA,)).fetchall()
    policies = conn.execute("""SELECT tablename, policyname, permissive, cmd, qual, with_check FROM pg_policies WHERE schemaname=%s ORDER BY 1,2""", (SCHEMA,)).fetchall()
    triggers = conn.execute("""SELECT c.relname, t.tgname, pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                               WHERE n.nspname=%s AND NOT t.tgisinternal ORDER BY 1,2""", (SCHEMA,)).fetchall()
    roles = conn.execute("""SELECT rolname, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname LIKE 'taktovia\\_%' ORDER BY 1""").fetchall()
    grants = conn.execute("""SELECT table_name, privilege_type FROM information_schema.role_table_grants WHERE table_schema=%s AND grantee='taktovia_runtime' ORDER BY 1,2""", (SCHEMA,)).fetchall()
    owners = conn.execute("""SELECT c.relname, pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=%s AND c.relkind='r' ORDER BY 1""", (SCHEMA,)).fetchall()
    return {"rls": [list(r) for r in rls], "policies": [list(map(str, r)) for r in policies], "triggers": [list(r) for r in triggers],
            "roles": [list(r) for r in roles], "runtime_grants": [list(r) for r in grants], "owners": [list(r) for r in owners]}


# --- Manifest -------------------------------------------------------------------------------------------------------------------------------

def _canonical(doc: dict) -> bytes:
    return json.dumps({k: v for k, v in doc.items() if k != "manifest_mac"}, sort_keys=True, separators=(",", ":")).encode()


def sign_manifest(doc: dict, key: bytes) -> dict:
    doc["manifest_mac"] = hmac.new(key, _canonical(doc), hashlib.sha256).hexdigest()
    return doc


def verify_manifest(doc: dict, key: bytes) -> None:
    if doc.get("key_id") and doc["key_id"] != crypto.key_id(key).hex():
        raise crypto.WrongKeyError("falscher Backup-Schlüssel")
    expected = hmac.new(key, _canonical(doc), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, doc.get("manifest_mac", "")):
        raise crypto.BackupIntegrityError("Manifest manipuliert (Authentifizierungscode stimmt nicht)")


# --- Backup -----------------------------------------------------------------------------------------------------------------------------------

def create_backup(cluster: Cluster, db_name: str, store: BackupStore, key: bytes, backup_id: str | None = None, objects_dir: Path | None = None, environment: str | None = None) -> dict:
    """Erstellt ein verschlüsseltes, konsistentes Backup der Datenbank (und optional eines Objektspeicherverzeichnisses) im Backupspeicher; liefert das Manifest."""
    backup_id = backup_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    with tempfile.TemporaryDirectory(prefix="tkbk-") as tmp:
        tmp = Path(tmp); os.chmod(tmp, 0o700)
        ctl = cluster.connect(db_name, autocommit=False)
        try:
            ctl.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            snap = ctl.execute("SELECT pg_export_snapshot()").fetchone()[0]
            taken_at, server_version = ctl.execute("SELECT clock_timestamp(), current_setting('server_version')").fetchone()
            enc_path = tmp / "db.dump.enc"
            with subprocess.Popen(["pg_dump", "--format=custom", "--snapshot", snap, "--dbname", db_name], stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=cluster.env()) as dump:
                with open(enc_path, "wb") as out:
                    crypto.encrypt_stream(dump.stdout, out, key)
                err = dump.stderr.read().decode(errors="replace")
                if dump.wait() != 0:
                    raise RuntimeError("pg_dump fehlgeschlagen: " + err[:500])
            checksums = table_checksums(ctl)       # derselbe Snapshot wie der Dump
            fingerprint = structure_fingerprint(ctl)
        finally:
            ctl.rollback(); ctl.close()
        manifest = {
            "format": FORMAT, "backup_id": backup_id, "database": db_name, "taken_at": taken_at.astimezone(timezone.utc).isoformat(), "server_version": server_version,
            "key_id": crypto.key_id(key).hex(), "files": {}, "tables": checksums, "structure_sha256": hashlib.sha256(json.dumps(fingerprint, sort_keys=True).encode()).hexdigest(),
            "objects": {},
        }
        env_id = environment if environment is not None else os.environ.get("TAKTOVIA_ENVIRONMENT")
        if env_id:
            manifest["environment"] = env_id      # Quellumgebung, vom Manifest-Authentifizierungscode mit geschützt (WP-M5-14)
        store.put(f"{backup_id}.db.enc", enc_path)
        manifest["files"][f"{backup_id}.db.enc"] = {"sha256": crypto.sha256_file(store.path(f"{backup_id}.db.enc")), "size": enc_path.stat().st_size}
        if objects_dir is not None:
            ref = tmp / "objects.tar.enc"
            with subprocess.Popen(["tar", "-C", str(objects_dir), "-cf", "-", "."], stdout=subprocess.PIPE) as tar:
                with open(ref, "wb") as out:
                    crypto.encrypt_stream(tar.stdout, out, key)
                if tar.wait() != 0:
                    raise RuntimeError("tar fehlgeschlagen")
            store.put(f"{backup_id}.objects.enc", ref)
            manifest["files"][f"{backup_id}.objects.enc"] = {"sha256": crypto.sha256_file(store.path(f"{backup_id}.objects.enc")), "size": ref.stat().st_size}
            manifest["objects"] = {str(p.relative_to(objects_dir)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(objects_dir.rglob("*")) if p.is_file()}
        sign_manifest(manifest, key)
        mpath = tmp / "manifest.json"
        mpath.write_text(json.dumps(manifest, indent=1, sort_keys=True))
        store.put(f"{backup_id}.manifest.json", mpath)
    return manifest


# --- Lösch-/Sperrjournal (außerhalb der Datenbank) ----------------------------------------------------------------------------------

def export_journal(cluster: Cluster, db_name: str, store: BackupStore, key: bytes) -> int:
    """Hängt die neuen Zeilen des Lösch-/Sperrjournals als weiteres verschlüsseltes Segment an das Journal im Backupspeicher an (hash-verkettet);
    liefert die Zahl der exportierten Zeilen. Das Journal liegt außerhalb der gesicherten Datenbank, damit ein Restore es nicht verliert."""
    names = [n for n in store.list() if n.startswith("journal-") and n.endswith(".jsonl.enc")]
    last_seq, prev = 0, "0" * 64
    for n in sorted(names):
        seg = _read_segment(store, n, key)
        last_seq, prev = seg["last_seq"], seg["chain"]
    with cluster.connect(db_name) as c:
        rows = c.execute("""SELECT tenant_id::text, job_id::text, object_ref, data_class, action, archive_until, applied_at, seq FROM taktovia.erasure_journal
                            WHERE seq > %s ORDER BY seq""", (last_seq,)).fetchall()
    if not rows:
        return 0
    entries = [{"tenant_id": r[0], "job_id": r[1], "object_ref": r[2], "data_class": r[3], "action": r[4],
                "archive_until": r[5].isoformat() if r[5] else None, "applied_at": r[6].isoformat(), "seq": r[7]} for r in rows]
    body = json.dumps({"prev": prev, "entries": entries}, sort_keys=True)
    chain = hashlib.sha256((prev + body).encode()).hexdigest()
    with tempfile.TemporaryDirectory(prefix="tkbk-") as tmp:
        plain = Path(tmp) / "seg.json"
        plain.write_text(json.dumps({"prev": prev, "entries": entries, "chain": chain, "last_seq": entries[-1]["seq"]}, sort_keys=True))
        enc = Path(tmp) / "seg.enc"
        crypto.encrypt_file(plain, enc, key)
        store.put(f"journal-{entries[-1]['seq']:012d}.jsonl.enc", enc)
    return len(entries)


def _read_segment(store: BackupStore, name: str, key: bytes) -> dict:
    data = b"".join(crypto.decrypt_path_chunks(store.get(name), key))
    seg = json.loads(data)
    body = json.dumps({"prev": seg["prev"], "entries": seg["entries"]}, sort_keys=True)
    if hashlib.sha256((seg["prev"] + body).encode()).hexdigest() != seg["chain"]:
        raise crypto.BackupIntegrityError(f"Journalsegment {name} manipuliert (Kettenhash)")
    return seg


def read_journal(store: BackupStore, key: bytes) -> list[dict]:
    out, prev = [], "0" * 64
    for n in sorted(x for x in store.list() if x.startswith("journal-") and x.endswith(".jsonl.enc")):
        seg = _read_segment(store, n, key)
        if seg["prev"] != prev:
            raise crypto.BackupIntegrityError("Journal unvollständig oder umgeordnet (Kette unterbrochen)")
        prev = seg["chain"]
        out.extend(seg["entries"])
    return out


# --- Restore ------------------------------------------------------------------------------------------------------------------------------------

def verify_backup(store: BackupStore, key: bytes, backup_id: str) -> dict:
    """Prüft Manifest, Prüfsumme und Authentizität aller Dateien eines Backups, ohne etwas wiederherzustellen. Wirft bei Manipulation."""
    manifest = json.loads(store.get(f"{backup_id}.manifest.json").read_text())
    verify_manifest(manifest, key)
    if manifest["key_id"] != crypto.key_id(key).hex():
        raise crypto.WrongKeyError("falscher Backup-Schlüssel")
    for name, meta in manifest["files"].items():
        if crypto.sha256_file(store.get(name)) != meta["sha256"]:
            raise crypto.BackupIntegrityError(f"{name}: Prüfsumme weicht ab (Backup verändert)")
        for _ in crypto.decrypt_path_chunks(store.get(name), key):
            pass
    return manifest


def restore(cluster: Cluster, store: BackupStore, key: bytes, backup_id: str, target_db: str, out_dir: Path | None = None, run_isolation_tests: bool = True,
            journal_store: BackupStore | None = None, objects_target: Path | None = None, target_environment: str | None = None) -> dict:
    """Stellt ein Backup in die **neue, isolierte** Datenbank `target_db` her, prüft Prüfsummen, spielt das Lösch-/Sperrjournal wieder ein und prüft RLS, Trigger, Rollen
    und Isolation. Liefert das Protokoll (auch bei Fehlschlag, dann `result` = FAILED und Ausnahme [RestoreFailed] nach dem Schreiben des Protokolls)."""
    started = datetime.now(timezone.utc); t0 = time.monotonic()
    protocol = {"backup_id": backup_id, "target_database": target_db, "started_at": started.isoformat(), "result": "FAILED", "steps": []}
    try:
        manifest = verify_backup(store, key, backup_id)
        if manifest.get("environment") is not None:       # Restore nur in die Quellumgebung; vor jeder Wirkung (WP-M5-14)
            assert_restore_target(manifest["environment"], target_environment)
        protocol["snapshot_t"] = manifest["taken_at"]; protocol["steps"].append("backup_verified")
        with cluster.connect("postgres") as adm:
            if adm.execute("SELECT 1 FROM pg_database WHERE datname=%s", (target_db,)).fetchone():
                raise RestoreFailed("Zieldatenbank existiert bereits (Restore nur in eine neue, isolierte Datenbank)")
            adm.execute(sql.SQL("CREATE DATABASE {} OWNER taktovia_migrator").format(sql.Identifier(target_db)))
        name = f"{backup_id}.db.enc"
        with subprocess.Popen(["pg_restore", "--exit-on-error", "--dbname", target_db], stdin=subprocess.PIPE, stderr=subprocess.PIPE, env=cluster.env()) as pr:
            try:
                for chunk in crypto.decrypt_path_chunks(store.get(name), key):
                    pr.stdin.write(chunk)
                pr.stdin.close()
            except BaseException:
                pr.kill(); pr.wait(); raise
            err = pr.stderr.read().decode(errors="replace")
            if pr.wait() != 0:
                raise RestoreFailed("pg_restore fehlgeschlagen: " + err[:500])
        protocol["steps"].append("database_restored")
        with cluster.connect(target_db) as c:
            actual = table_checksums(c)
            tables = []
            for t, exp in manifest["tables"].items():
                a = actual.get(t)
                tables.append({"table": t, "rows_expected": exp["rows"], "rows_actual": a["rows"] if a else None, "match": bool(a) and a == exp})
            protocol["tables"] = tables
            protocol["extra_tables"] = sorted(set(actual) - set(manifest["tables"]))
            if not all(t["match"] for t in tables) or protocol["extra_tables"]:
                raise RestoreFailed("Prüfsummen der wiederhergestellten Tabellen weichen ab: " + ", ".join(t["table"] for t in tables if not t["match"]))
            protocol["steps"].append("checksums_identical")
            fp = structure_fingerprint(c)
            if hashlib.sha256(json.dumps(fp, sort_keys=True).encode()).hexdigest() != manifest["structure_sha256"]:
                raise RestoreFailed("RLS, Trigger, Rollen oder Rechte weichen vom gesicherten Stand ab")
            protocol["steps"].append("structure_identical")
            # Lösch-/Sperrjournal vor Freigabe wieder einspielen (Kap. 01 §1.7)
            entries = read_journal(journal_store or store, key)
            replayed = 0
            if entries:
                replayed = c.execute("SELECT taktovia.replay_erasure_journal(%s::jsonb)", (json.dumps(entries),)).fetchone()[0]
            protocol["journal"] = {"entries": len(entries), "newly_applied": replayed}
            protocol["steps"].append("journal_replayed")
        if objects_target is not None and f"{backup_id}.objects.enc" in manifest["files"]:
            objects_target.mkdir(parents=True, exist_ok=True)
            with subprocess.Popen(["tar", "-C", str(objects_target), "-xf", "-"], stdin=subprocess.PIPE) as tar:
                for chunk in crypto.decrypt_path_chunks(store.get(f"{backup_id}.objects.enc"), key):
                    tar.stdin.write(chunk)
                tar.stdin.close()
                if tar.wait() != 0:
                    raise RestoreFailed("Objektspeicher konnte nicht entpackt werden")
            bad = [n for n, h in manifest["objects"].items() if hashlib.sha256((objects_target / n).read_bytes()).hexdigest() != h]
            protocol["objects"] = {"expected": len(manifest["objects"]), "mismatch": bad}
            if bad:
                raise RestoreFailed("Objekte weichen ab: " + ", ".join(bad))
            protocol["steps"].append("objects_identical")
        if run_isolation_tests:
            protocol["isolation_tests"] = run_isolation(target_db)
            if not protocol["isolation_tests"]["ok"]:
                raise RestoreFailed("Isolationstest gegen die wiederhergestellte Datenbank fehlgeschlagen")
            protocol["steps"].append("isolation_tests_passed")
        protocol["result"] = "SUCCESS"
    except BaseException as e:
        protocol["error"] = f"{type(e).__name__}: {str(e)[:300]}"
        protocol["finished_at"] = datetime.now(timezone.utc).isoformat(); protocol["duration_s"] = round(time.monotonic() - t0, 3)
        _write_protocol(protocol, out_dir)
        raise
    protocol["finished_at"] = datetime.now(timezone.utc).isoformat(); protocol["duration_s"] = round(time.monotonic() - t0, 3)
    _write_protocol(protocol, out_dir)
    return protocol


def _write_protocol(protocol: dict, out_dir: Path | None) -> None:
    if out_dir is None:
        return
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"restore-protokoll-{protocol['backup_id']}.json").write_text(json.dumps(protocol, indent=1, sort_keys=True))


def run_isolation(db_name: str) -> dict:
    """Führt den Isolationstest aus WP-M1-01 (db/tests: RLS je Tabelle, kein Kontext keine Zeile) gegen die wiederhergestellte Datenbank aus."""
    env = {**os.environ, "TAKTOVIA_DB_NAME": db_name}
    p = subprocess.run([sys.executable, "-m", "unittest", "test_03_rls", "test_isolation_alle_tabellen"], cwd=REPO / "db" / "tests", env=env, capture_output=True, text=True, timeout=600)
    tail = (p.stderr or "").strip().splitlines()[-3:]
    return {"ok": p.returncode == 0, "tests": "db/tests/test_03_rls.py, db/tests/test_isolation_alle_tabellen.py", "summary": tail}


def drop_database(cluster: Cluster, db_name: str) -> None:
    with cluster.connect("postgres") as c:
        c.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(db_name)))


# --- WAL-Archivierung (PITR): Logik in wal.py (ohne Datenbanktreiber), hier nur durchgereicht -----------------------------------------------

from .wal import archive_wal, restore_wal  # noqa: E402,F401
