#!/usr/bin/env bash
# Startet die lokale Umgebung. Aufruf: ./up.sh [profil ...]   (Standard: all)
# NICHT AUSGEFUEHRT in der Entwicklungsumgebung (kein Docker-Daemon).
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || { echo "Keine .env: cp .env.example .env (nur lokale Entwicklungswerte)" >&2; exit 1; }
profiles=("${@:-all}")
args=(); for p in "${profiles[@]}"; do args+=(--profile "$p"); done
docker compose "${args[@]}" up -d --wait
./smoke.sh "${profiles[@]}"
