#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null || { echo "Maven missing" >&2; exit 1; }
command -v java >/dev/null || { echo "Java missing" >&2; exit 1; }
if [[ ! -f .env ]]; then cp .env.example .env; echo 'Created .env; review before continuing'; fi
set -a
source .env
set +a

if [[ "${HRM_USE_LOCAL_POSTGRES:-0}" == "1" ]]; then
  # The local launcher targets a developer-installed PostgreSQL by default.
  # Environment overrides let developers use a different local port or role.
  DB_URL="${LOCAL_DB_URL:-jdbc:postgresql://localhost:5432/hrm_dev}"
  DB_USERNAME="${LOCAL_DB_USERNAME:-${DB_USERNAME:-postgres}}"
  DB_PASSWORD="${LOCAL_DB_PASSWORD:-${DB_PASSWORD:-}}"
  export DB_URL DB_USERNAME DB_PASSWORD
  if [[ "$DB_URL" =~ ^jdbc:postgresql://([^:/]+)(:([0-9]+))?/([^?]+) ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[3]:-5432}"
    DB_NAME="${BASH_REMATCH[4]}"
  else
    echo "Local PostgreSQL requires a JDBC URL like jdbc:postgresql://localhost:5432/hrm_dev" >&2
    exit 1
  fi
  command -v pg_isready >/dev/null || { echo "pg_isready missing; install PostgreSQL client tools to check your local database." >&2; exit 1; }
  printf 'Checking local PostgreSQL at %s:%s (database %s)...\n' "$DB_HOST" "$DB_PORT" "$DB_NAME"
  ready=0
  for i in {1..30}; do
    if pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
  done
  if [[ "$ready" -ne 1 ]]; then
    echo "Local PostgreSQL is not accepting connections at $DB_HOST:$DB_PORT/$DB_NAME. Start PostgreSQL and check DB_URL, DB_USERNAME, and DB_PASSWORD." >&2
    exit 1
  fi
else
  command -v docker >/dev/null || { echo "Docker missing" >&2; exit 1; }
fi

# Free the local frontend development port before starting services. The frontend
# port can be overridden in .env; 5173 is Vite's default development port.
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
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

if [[ "${HRM_USE_LOCAL_POSTGRES:-0}" != "1" ]]; then
  docker compose --env-file .env up -d db
  printf 'Waiting for database readiness...\n'
  for i in {1..30}; do
    if docker compose --env-file .env exec -T db pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then break; fi
    if [[ "$i" -eq 30 ]]; then echo 'Database not ready' >&2; exit 1; fi
    sleep 1
  done
fi
mvn spring-boot:run
