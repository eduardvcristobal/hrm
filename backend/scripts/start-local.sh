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

# Free the local frontend development port before starting services. The frontend
# port can be overridden in .env; 3000 is the default used by common dev servers.
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
if ! [[ "$FRONTEND_PORT" =~ ^[0-9]+$ ]]; then
  echo "Invalid FRONTEND_PORT: $FRONTEND_PORT" >&2
  exit 1
fi
SERVER_PORT="${SERVER_PORT:-8080}"
command -v lsof >/dev/null || { echo "lsof missing; cannot check local ports" >&2; exit 1; }

free_port() {
  local label="$1" port="$2" pids pid
  if ! [[ "$port" =~ ^[0-9]+$ ]]; then
    echo "Invalid ${label}_PORT: $port" >&2
    exit 1
  fi
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    printf 'Stopping process(es) using %s port %s: %s\n' "$label" "$port" "$(echo "$pids" | tr '\n' ' ')"
    while IFS= read -r pid; do
      [[ -n "$pid" ]] && kill "$pid"
    done <<< "$pids"
  fi
}

free_port frontend "$FRONTEND_PORT"
free_port server "$SERVER_PORT"

docker compose --env-file .env up -d db
printf 'Waiting for database readiness...\n'
for i in {1..30}; do
  if docker compose --env-file .env exec -T db pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then break; fi
  if [[ "$i" -eq 30 ]]; then echo 'Database not ready' >&2; exit 1; fi
  sleep 1
done
mvn spring-boot:run
