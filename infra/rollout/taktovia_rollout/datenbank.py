"""Datenbankprüfungen gegen das Produktionsschema (WP-M5-28): Runtime-Rolle, Wächter „Tabelle ohne Policy“, keine Seed-/Testdaten, Migrationsstand.

Alle Funktionen sind lesend und liefern eine Liste von Befunden (leer = in Ordnung). Sie laufen gegen eine übergebene psycopg-Verbindung; die Rolle prüft die Verbindung der Runtime
selbst (`runtime_rolle_befunde`), das Schema und die Daten prüft eine Verbindung mit Leserechten auf die Systemkataloge."""
from __future__ import annotations

import hashlib

SYSTEMMANDANT = "00000000-0000-0000-0000-000000000001"
SCHEMA = "taktovia"

RUNTIME_ROLLE = """
SELECT r.rolname, r.rolsuper, r.rolbypassrls, r.rolcreaterole, r.rolcreatedb, r.rolreplication
FROM pg_roles r WHERE r.rolname = current_user"""

EIGENTUM = """
SELECT n.nspname || '.' || c.relname || ' (' || c.relkind::text || ')'
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('taktovia', 'taktovia_meta', 'public') AND c.relkind IN ('r','p','v','m','S','f')
  AND pg_get_userbyid(c.relowner) = current_user
UNION ALL
SELECT 'Schema ' || n.nspname FROM pg_namespace n WHERE pg_get_userbyid(n.nspowner) = current_user AND n.nspname !~ '^pg_'"""

OHNE_POLICY = """
SELECT c.relname,
       (c.relrowsecurity) AS rls, (c.relforcerowsecurity) AS erzwungen,
       (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid AND p.polqual IS NOT NULL AND pg_get_expr(p.polqual, p.polrelid) LIKE '%%tenant_id%%') AS policies,
       EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped) AS hat_tenant_id,
       has_table_privilege('taktovia_runtime', c.oid, 'INSERT,UPDATE,DELETE') AS runtime_schreibt
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'taktovia' AND c.relkind IN ('r','p') AND NOT c.relispartition
ORDER BY c.relname"""


# Dokumentierte, einzeln begründete Ausnahmen vom Wächter (jede ist im Schema so angelegt, nicht von diesem Paket):
#  - membership_subject_index: RLS erzwungen, Lesepolicy über den transaktionslokalen Subject-Schlüssel statt tenant_id (V1060, Anmeldung vor Mandantenkontext).
#  - rights_matrix_rollout: globales, nur anfügbares Protokoll der Rechtematrix-Versionen (V1070); die Runtime-Rolle darf nur SELECT/INSERT (R__runtime_grants_m1_07).
AUSNAHMEN = {"membership_subject_index": "policy_ohne_tenant_id", "rights_matrix_rollout": "global_nur_anfuegen"}


def runtime_rolle_befunde(conn) -> list[str]:
    """Die Verbindung muss unter der Runtime-Rolle laufen: kein Superuser, kein BYPASSRLS, kein Tabellen-/Schemaeigentum, keine Rollen-/Datenbankanlage (AK 3)."""
    zeile = conn.execute(RUNTIME_ROLLE).fetchone()
    befunde = []
    if zeile is None:
        return ["Rolle der Verbindung nicht ermittelbar"]
    name, su, bypass, crole, cdb, repl = zeile
    for flag, text in ((su, "Superuser"), (bypass, "BYPASSRLS"), (crole, "CREATEROLE"), (cdb, "CREATEDB"), (repl, "REPLICATION")):
        if flag:
            befunde.append(f"Rolle {name} hat {text}")
    befunde += [f"Rolle {name} besitzt {o}" for (o,) in conn.execute(EIGENTUM).fetchall()]
    return befunde


def tabellen_ohne_policy(conn) -> list[str]:
    """Wächter „Tabelle ohne Policy“: jede Tabelle des Fachschemas mit `tenant_id` braucht aktivierte und erzwungene RLS und eine Policy auf `tenant_id`.
    Tabellen ohne `tenant_id` (globale Kataloge) dürfen von der Runtime-Rolle nicht geschrieben werden."""
    befunde = []
    for name, rls, erzwungen, policies, hat_tid, schreibt in conn.execute(OHNE_POLICY).fetchall():
        art = AUSNAHMEN.get(name)
        if art == "global_nur_anfuegen":
            if conn.execute("SELECT has_table_privilege('taktovia_runtime', %s::regclass, 'UPDATE,DELETE,TRUNCATE')", (f"taktovia.{name}",)).fetchone()[0]:
                befunde.append(f"{name}: Runtime-Rolle darf ändern oder löschen")
            continue
        if hat_tid:
            if not rls:
                befunde.append(f"{name}: RLS nicht aktiviert")
            elif not erzwungen:
                befunde.append(f"{name}: RLS nicht erzwungen")
            if art == "policy_ohne_tenant_id" and conn.execute("SELECT count(*) FROM pg_policy WHERE polrelid = %s::regclass", (f"taktovia.{name}",)).fetchone()[0] == 0:
                befunde.append(f"{name}: keine Policy")
            if policies == 0 and art != "policy_ohne_tenant_id":
                befunde.append(f"{name}: keine Policy auf tenant_id")
        elif schreibt:
            befunde.append(f"{name}: ohne tenant_id und von der Runtime-Rolle beschreibbar")
    return befunde


def testdaten_befunde(conn, probemandanten_erlaubt: int = 1) -> list[str]:
    """Keine Seed- oder Testdaten außer dem gekennzeichneten Probemandanten (`tenant.synthetic`): jeder andere Mandant außer dem Systemmandanten ist ein Befund.
    Der Probemandant darf höchstens `probemandanten_erlaubt`-mal vorkommen."""
    befunde = []
    for tid, name, synth in conn.execute("SELECT id, name, synthetic FROM taktovia.tenant WHERE id <> %s", (SYSTEMMANDANT,)).fetchall():
        if not synth:
            befunde.append(f"Mandant {tid} ({name}) ist nicht als Probemandant gekennzeichnet")
    n = conn.execute("SELECT count(*) FROM taktovia.tenant WHERE synthetic").fetchone()[0]
    if n > probemandanten_erlaubt:
        befunde.append(f"{n} Probemandanten, erlaubt {probemandanten_erlaubt}")
    return befunde


def umgebungskennung(conn) -> str | None:
    zeilen = conn.execute("SELECT environment FROM taktovia_meta.environment_marker").fetchall()
    return zeilen[0][0] if len(zeilen) == 1 else None


def migrationsstand(conn) -> dict:
    """Prüfsumme über Skriptname und Prüfsumme der angewendeten Migrationen (wie infra/umgebungen/migrationsstand.py)."""
    rows = conn.execute("SELECT script, checksum FROM taktovia_meta.schema_history ORDER BY script").fetchall()
    h = hashlib.sha256()
    for script, checksum in rows:
        h.update(f"{script}|{checksum}\n".encode())
    return {"umgebungskennung": umgebungskennung(conn), "migrationen": len(rows), "sha256": h.hexdigest()}


def schema_befunde(admin_conn, runtime_conn, erwartete_umgebung: str, probemandanten_erlaubt: int = 1) -> list[str]:
    """Gesamtprüfung der Datenbank vor und nach dem Rollout."""
    befunde = [f"Runtime: {b}" for b in runtime_rolle_befunde(runtime_conn)]
    befunde += [f"Policy: {b}" for b in tabellen_ohne_policy(admin_conn)]
    befunde += [f"Daten: {b}" for b in testdaten_befunde(admin_conn, probemandanten_erlaubt)]
    k = umgebungskennung(admin_conn)
    if k != erwartete_umgebung:
        befunde.append(f"Umgebungskennung {k!r}, erwartet {erwartete_umgebung!r}")
    return befunde
