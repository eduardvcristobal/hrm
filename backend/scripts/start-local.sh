#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v docker >/dev/null || { echo "Docker missing" >&2; exit 1; }
command -v mvn >/dev/null || { echo "Maven missing" >&2; exit 1; }
command -v java >/dev/null || { echo "Java missing" >&2; exit 1; }
if [[ ! -f .env ]]; then cp .env.example .env; echo 'Created .env; review before continuing'; fi
set -a
source .env
set +a
docker compose --env-file .env up -d db
printf 'Waiting for database readiness...\n'
for i in {1..30}; do
  if docker compose --env-file .env exec -T db pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then break; fi
  if [[ "$i" -eq 30 ]]; then echo 'Database not ready' >&2; exit 1; fi
  sleep 1
done
mvn spring-boot:run
