#!/usr/bin/env bash
# Smoke-Test der Dienste je Profil; meldet verstaendlich, welcher Dienst fehlt.
# NICHT AUSGEFUEHRT (kein Docker-Daemon). Nur Syntax mit `bash -n` geprueft.
set -uo pipefail
cd "$(dirname "$0")"
set -a; [ -f .env ] && . ./.env; set +a
profiles=("${@:-all}"); want=" ${profiles[*]} "; fail=0
has() { [[ "$want" == *" all "* || "$want" == *" $1 "* ]]; }
check() { # name, befehl...
  local name=$1; shift
  if "$@" >/dev/null 2>&1; then echo "OK      $name"; else echo "FEHLT   $name (nicht erreichbar oder nicht healthy: docker compose ps)"; fail=1; fi
}
dc() { docker compose "$@"; }
if has core; then
  check "postgres/postgis" dc exec -T postgres psql -U "${POSTGRES_USER:-dev_admin}" -d "${POSTGRES_DB:-taktovia}" -c "CREATE EXTENSION IF NOT EXISTS postgis; SELECT postgis_version();"
  check "rabbitmq/quorum-queue" dc exec -T rabbitmq rabbitmqadmin declare queue name=smoke.quorum durable=true arguments='{"x-queue-type":"quorum"}'
  check "redis" dc exec -T redis valkey-cli ping
fi
has auth && check "keycloak/realm" curl -sf "http://127.0.0.1:${KEYCLOAK_PORT:-18080}/realms/taktovia-dev/.well-known/openid-configuration"
if has storage; then
  check "minio/versioned-bucket" dc exec -T minio sh -c 'mc alias set l http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" && mc mb --ignore-existing l/smoke && mc version enable l/smoke && echo x | mc pipe l/smoke/probe.txt'
fi
has routing && check "valhalla/status" curl -sf "http://127.0.0.1:${VALHALLA_PORT:-18002}/status"
if has observability; then
  check "otel-collector" curl -sf "http://127.0.0.1:${OTEL_HEALTH_PORT:-13133}/"
  check "prometheus" curl -sf "http://127.0.0.1:${PROMETHEUS_PORT:-19090}/-/healthy"
fi
exit $fail
