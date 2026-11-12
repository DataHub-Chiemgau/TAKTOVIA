#!/usr/bin/env bash
# Aufbau einer Staging- oder Performance-Umgebung aus dem Repository (WP-M5-02). NICHT gegen reale Umgebungen ausgeführt (kein Hosting, D-05 offen);
# die Schritte laufen lokal gegen eine PostgreSQL mit PostGIS (siehe tests/performance/test_umgebungsaufbau.py).
#
#   TAKTOVIA_ENVIRONMENT=staging|performance TAKTOVIA_DB_NAME=... (Zugangsdaten wie db/db.py aus der Umgebung) infra/umgebungen/aufbau.sh <staging|performance> [reference|small]
#
# 1. bootstrap: Rollen (Migrator, Runtime, Modulzugänge), Datenbank, PostGIS; 2. migrate: Schema und wiederholbare Rechte, setzt die unveränderliche Umgebungskennung;
# 3. Migrationsstand ausgeben (Prüfsumme; ein zweiter Aufbau muss denselben Wert liefern); 4. Mandanten anlegen (Betrieb, Administratorzugang) und mit dem Generator unter der Runtime-Rolle füllen;
# 5. Umgebungsprotokoll schreiben. Produktion ist ausgeschlossen. Secrets kommen aus der Secret-Domäne der Umgebung, nie aus dem Repository.
set -euo pipefail
env_name="${1:?Umgebung angeben: staging oder performance}"
scale="${2:-reference}"
case "$env_name" in staging|performance) ;; *) echo "Umgebung $env_name ist hier nicht zulässig (nur staging, performance)" >&2; exit 2;; esac
if [ "${TAKTOVIA_ENVIRONMENT:-}" != "$env_name" ]; then echo "TAKTOVIA_ENVIRONMENT muss $env_name sein (Umgebungskennung der Datenbank)" >&2; exit 2; fi
root="$(cd "$(dirname "$0")/../.." && pwd)"
py="${PY:-python3}"
"$py" "$root/db/db.py" bootstrap
"$py" "$root/db/db.py" migrate
"$py" "$root/infra/umgebungen/migrationsstand.py"
mkdir -p "$root/tests/performance/out"
"$py" "$root/tests/performance/datagen/fill.py" --scale "$scale" --provision --protocol "$root/tests/performance/out/umgebungsprotokoll-$env_name.json"
