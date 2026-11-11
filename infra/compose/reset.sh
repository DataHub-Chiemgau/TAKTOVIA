#!/usr/bin/env bash
# Setzt die lokale Umgebung zurueck: Container UND Volumes (Datenbank, Queues, Buckets) werden geloescht;
# der Keycloak-Realm wird beim naechsten Start neu importiert. NICHT AUSGEFUEHRT (kein Docker-Daemon).
set -euo pipefail
cd "$(dirname "$0")"
docker compose --profile all --profile observability down --volumes --remove-orphans
echo "Zurueckgesetzt. Neu starten mit ./up.sh"
