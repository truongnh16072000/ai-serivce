#!/usr/bin/env bash
# Run from a trusted checkout: ./deploy/deploy-compose.sh root@host
set -Eeuo pipefail

target=${1:?Usage: $0 user@host}
port=${SSH_PORT:-22}
release_dir=/opt/ai-service-compose

rsync -az --delete \
  --exclude .git \
  --exclude .gradle \
  --exclude build \
  --exclude /ai-service.jar \
  --exclude .env \
  -e "ssh -p ${port}" \
  ./ "${target}:${release_dir}/"

ssh -p "${port}" "${target}" "
  set -Eeuo pipefail
  cd ${release_dir}
  legacy_stopped=0
  rollback() {
    if [ \"\$legacy_stopped\" = 1 ]; then
      docker compose down || true
      systemctl start ai-service.service || true
    fi
  }
  trap rollback ERR
  AI_SERVICE_ENV_FILE=/etc/ai-service/database.env docker compose config -q
  docker network inspect ai-service-data >/dev/null 2>&1 || docker network create ai-service-data
  docker network connect --alias postgres ai-service-data ai-service-postgres 2>/dev/null || true
  docker compose build --pull ai-service
  systemctl stop ai-service.service
  legacy_stopped=1
  docker compose up -d --force-recreate ai-service
  docker compose ps
  for attempt in 1 2 3 4 5 6; do
    status=\$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:18080/actuator/health || true)
    if [ \"\$status\" = 200 ]; then
      legacy_stopped=0
      trap - ERR
      exit 0
    fi
    sleep 2
  done
  docker compose logs --tail=150 ai-service
  exit 1
"
