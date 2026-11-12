#!/usr/bin/env python3
"""Migrationsstand einer Datenbank als Prüfsumme (WP-M5-02): ein zweiter Aufbau aus dem Repository muss denselben Wert liefern.

Die Prüfsumme läuft über Skriptname und Prüfsumme der angewendeten Migrationen in Namensreihenfolge (taktovia_meta.schema_history), nicht über Zeitstempel."""
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'db'))
import db as dbtool  # noqa: E402


def migrationsstand(name: str | None = None) -> dict:
    name = name or dbtool.env('TAKTOVIA_DB_NAME', 'taktovia')
    with dbtool.admin(name) as c:
        rows = c.execute('SELECT script, checksum FROM taktovia_meta.schema_history ORDER BY script').fetchall()
        marker = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
    h = hashlib.sha256()
    for script, checksum in rows:
        h.update(f'{script}|{checksum}\n'.encode())
    return {'datenbank': name, 'umgebungskennung': marker[0][0] if len(marker) == 1 else None, 'migrationen': len(rows), 'sha256': h.hexdigest()}


if __name__ == '__main__':
    print(json.dumps(migrationsstand(), ensure_ascii=False))
