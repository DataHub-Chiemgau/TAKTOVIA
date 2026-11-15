#!/usr/bin/env python3
"""Migrationsstufe des Release-Gates (WP-M5-22): Migration frisch und aus dem freigegebenen Vorstand (db/migrations V1/V2, Kap. 08 §8.4).

  python tools/release_gate/migration_check.py [--vorstand GIT-REF]

1. frisch: leere Datenbank, bootstrap, alle Migrationen; ein zweiter Lauf ändert nichts (idempotent).
2. aus dem Vorstand: leere Datenbank, nur die Migrationen des freigegebenen Standes (Git-Referenz aus `freigegebener_stand.txt`), danach die heutigen.
   Eine nach der Freigabe geänderte Migration endet im Prüfsummenfehler von `db/db.py`; das Schema muss am Ende dem frischen Aufbau entsprechen
   (Spalten, Indizes, Constraints, Policies, Trigger, Funktionen) und dieselbe Migrationshistorie tragen.

Es werden ausschließlich eigene Datenbanken `<TAKTOVIA_DB_NAME>_relgate_frisch` und `_relgate_vorstand` angelegt und am Ende verworfen. Umgebung wie `db/db.py`.
Exit 0 nur, wenn beide Wege grün sind und das Schema übereinstimmt.
"""
import argparse
import contextlib
import hashlib
import io
import os
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "db"))
import db  # noqa: E402

FINGERPRINT_SQL = {
    "spalten": "SELECT table_name, column_name, data_type, is_nullable, coalesce(column_default,'') FROM information_schema.columns WHERE table_schema IN ('taktovia','taktovia_meta') ORDER BY 1,2",
    "indizes": "SELECT schemaname, tablename, indexname, indexdef FROM pg_indexes WHERE schemaname IN ('taktovia','taktovia_meta') ORDER BY 1,2,3",
    "constraints": "SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace IN (SELECT oid FROM pg_namespace WHERE nspname IN ('taktovia','taktovia_meta')) ORDER BY 1,2",
    "policies": "SELECT tablename, policyname, cmd, coalesce(qual,''), coalesce(with_check,'') FROM pg_policies WHERE schemaname='taktovia' ORDER BY 1,2",
    "trigger": "SELECT event_object_table, trigger_name, action_timing, event_manipulation FROM information_schema.triggers WHERE trigger_schema='taktovia' ORDER BY 1,2,3,4",
    "funktionen": "SELECT p.proname, md5(p.prosrc) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('taktovia','taktovia_meta') ORDER BY 1,2",
}


def fingerprint(dbname):
    parts = {}
    with db.admin(dbname) as c:
        for k, q in FINGERPRINT_SQL.items():
            rows = [tuple(str(x) for x in r) for r in c.execute(q).fetchall()]
            parts[k] = (hashlib.sha256(repr(rows).encode()).hexdigest(), rows)
        history = [r[0] for r in c.execute("SELECT script FROM taktovia_meta.schema_history ORDER BY 1").fetchall()]
    return parts, history


def build(dbname, migrations_dir=None):
    """Legt die Datenbank neu an und migriert; Rückgabe: Ausgabe von migrate."""
    os.environ["TAKTOVIA_DB_NAME"] = dbname
    orig = db.MIG
    if migrations_dir:
        db.MIG = Path(migrations_dir)
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            db.reset()
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            db.migrate()
        return buf.getvalue().strip()
    finally:
        db.MIG = orig


def baseline_dir(ref, target):
    names = subprocess.run(["git", "-C", str(REPO), "ls-tree", "-r", "--name-only", ref, "db/migrations/"], capture_output=True, text=True)
    if names.returncode != 0 or not names.stdout.strip():
        raise SystemExit(f"Vorstand {ref!r} nicht auflösbar oder ohne Migrationen (git fetch --unshallow?)")
    for n in names.stdout.split():
        data = subprocess.run(["git", "-C", str(REPO), "show", f"{ref}:{n}"], capture_output=True).stdout
        (Path(target) / Path(n).name).write_bytes(data)
    return len(names.stdout.split())


def drop(dbname):
    with db.admin("postgres") as c:
        c.execute(db.sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(db.sql.Identifier(dbname)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vorstand", default=None, help="Git-Referenz des freigegebenen Standes (Vorgabe: erste Zeile von freigegebener_stand.txt)")
    a = ap.parse_args()
    ref = a.vorstand or next(l.strip() for l in (Path(__file__).parent / "freigegebener_stand.txt").read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#"))
    base = os.environ.get("TAKTOVIA_DB_NAME", "taktovia")
    fresh, upg = f"{base}_relgate_frisch", f"{base}_relgate_vorstand"
    problems = []
    try:
        out = build(fresh)
        print("frisch:", out.split(":", 1)[0], f"({len(out.split(',')) - 1} Skripte)")
        again = subprocess.run([sys.executable, str(REPO / "db" / "db.py"), "migrate"], capture_output=True, text=True, env={**os.environ, "TAKTOVIA_DB_NAME": fresh})
        if again.returncode != 0 or "nichts zu tun" not in again.stdout:
            problems.append("zweiter Migrationslauf ist nicht idempotent: " + (again.stdout + again.stderr).strip()[:300])
        f_fresh, h_fresh = fingerprint(fresh)

        with tempfile.TemporaryDirectory() as tmp:
            n = baseline_dir(ref, tmp)
            print(f"Vorstand {ref}: {n} Dateien")
            build(upg, tmp)
            _, h_base = fingerprint(upg)
            os.environ["TAKTOVIA_DB_NAME"] = upg
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                db.migrate()
            print("Vorstand -> heute:", buf.getvalue().strip()[:120])
        f_upg, h_upg = fingerprint(upg)
        if len(h_base) >= len(h_upg):
            problems.append(f"der Vorstand ({len(h_base)} Skripte) ist nicht älter als der heutige Stand ({len(h_upg)}): kein Upgradepfad geprüft")
        if h_fresh != h_upg:
            problems.append(f"Migrationshistorie weicht ab: nur frisch {sorted(set(h_fresh) - set(h_upg))[:5]}, nur Upgrade {sorted(set(h_upg) - set(h_fresh))[:5]}")
        for k in FINGERPRINT_SQL:
            if f_fresh[k][0] != f_upg[k][0]:
                diff = sorted(set(f_fresh[k][1]) ^ set(f_upg[k][1]))[:3]
                problems.append(f"Schema {k} weicht zwischen frisch und Upgrade ab: {diff}")
    except SystemExit as e:
        problems.append(str(e.code))
    finally:
        for d in (fresh, upg):
            drop(d)
    if problems:
        print("MIGRATION ROT:\n- " + "\n- ".join(problems))
        return 1
    print(f"MIGRATION GRÜN: frisch und Upgrade aus {ref} ergeben dasselbe Schema ({len(h_upg)} Skripte)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
