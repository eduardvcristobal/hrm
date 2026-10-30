#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_ENV="$ROOT_DIR/backend/.env"
BACKEND_PID=""
FRONTEND_PID=""
BACKEND_LAUNCHED=0
FRONTEND_LAUNCHED=0

if [[ ! -f "$BACKEND_ENV" ]]; then
  cp "$ROOT_DIR/backend/.env.example" "$BACKEND_ENV"
  echo 'Created backend/.env from backend/.env.example; review it before continuing.'
fi

read_backend_setting() {
  local key="$1" fallback="$2" value=""
  value="$(awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$BACKEND_ENV")"
  printf '%s' "${value:-$fallback}"
}

SERVER_PORT="$(read_backend_setting SERVER_PORT 8080)"
FRONTEND_PORT="$(read_backend_setting FRONTEND_PORT 5173)"
BACKEND_URL="$(read_backend_setting BACKEND_URL "http://localhost:$SERVER_PORT")"
VITE_GOOGLE_CLIENT_ID="$(read_backend_setting GOOGLE_CLIENT_ID "")"
export SERVER_PORT FRONTEND_PORT BACKEND_URL VITE_GOOGLE_CLIENT_ID

if ! [[ "$SERVER_PORT" =~ ^[0-9]+$ && "$FRONTEND_PORT" =~ ^[0-9]+$ ]]; then
  echo 'SERVER_PORT and FRONTEND_PORT must be numeric ports.' >&2
  exit 1
fi

stop_listener() {
  local port="$1" pids pid
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    while IFS= read -r pid; do
      [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
    done <<< "$pids"
  fi
}

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  [[ "$FRONTEND_LAUNCHED" -eq 1 ]] && stop_listener "$FRONTEND_PORT"
  [[ "$BACKEND_LAUNCHED" -eq 1 ]] && stop_listener "$SERVER_PORT"
  [[ -n "$FRONTEND_PID" ]] && wait "$FRONTEND_PID" 2>/dev/null || true
  [[ -n "$BACKEND_PID" ]] && wait "$BACKEND_PID" 2>/dev/null || true
  exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! command -v curl >/dev/null || ! command -v lsof >/dev/null; then
  echo 'curl and lsof are required to run the local stack.' >&2
  exit 1
fi
if ! command -v npm >/dev/null; then
  echo 'npm missing' >&2
  exit 1
fi

if [[ ! -x "$ROOT_DIR/frontend/node_modules/.bin/vite" ]]; then
  echo 'Installing frontend dependencies...'
  (cd "$ROOT_DIR/frontend" && npm install)
fi

echo "Starting backend (http://localhost:$SERVER_PORT)..."
BACKEND_LAUNCHED=1
"$ROOT_DIR/backend/scripts/start-local.sh" &
BACKEND_PID=$!

echo 'Waiting for backend readiness...'
ready=0
for attempt in {1..120}; do
  if curl --silent --fail "http://127.0.0.1:$SERVER_PORT/actuator/health" >/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo 'Backend exited before becoming ready.' >&2
    exit 1
  fi
  sleep 1
done
if [[ "$ready" -ne 1 ]]; then
  echo 'Backend did not become ready within 120 seconds.' >&2
  exit 1
fi

echo "Starting frontend (http://localhost:$FRONTEND_PORT)..."
FRONTEND_LAUNCHED=1
(cd "$ROOT_DIR/frontend" && npm run dev -- --host 127.0.0.1) &
FRONTEND_PID=$!

echo 'Both services are running. Press Ctrl+C to stop the frontend and backend.'
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  echo 'Backend stopped.' >&2
  if wait "$BACKEND_PID"; then exit 0; else exit $?; fi
elif ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
  echo 'Frontend stopped.' >&2
  if wait "$FRONTEND_PID"; then exit 0; else exit $?; fi
fi
