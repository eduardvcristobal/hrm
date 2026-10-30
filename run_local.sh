#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Reuse the regular app launcher, while telling the backend launcher to connect
# directly to PostgreSQL on this machine instead of starting a Compose service.
export HRM_USE_LOCAL_POSTGRES=1
exec "$ROOT_DIR/run.sh" "$@"
