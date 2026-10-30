#!/usr/bin/env bash
set -euo pipefail
: "${DB_PASSWORD:?DB_PASSWORD required}"
ADMIN_USER="${DB_USERNAME:-hrm_app}"
CONN=(-X -v ON_ERROR_STOP=1 -h localhost -p 5433 -d hrm_dev)
PGPASSWORD="$DB_PASSWORD" psql "${CONN[@]}" -U "$ADMIN_USER" -v app_password='ci_runtime_password_Only' -f scripts/provision-app-db-role.sql
RESULT="$(PGPASSWORD='ci_runtime_password_Only' psql "${CONN[@]}" -U hrm_runtime -Atc "SELECT count(*) FROM company")"
test "$RESULT" = 0 || { echo "Runtime role accessed company without tenant context"; exit 1; }
POLICY="$(PGPASSWORD="$DB_PASSWORD" psql "${CONN[@]}" -U "$ADMIN_USER" -Atc "SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname='hrm_runtime'")"
test "$POLICY" = f || { echo "Runtime role has bypass privilege"; exit 1; }
CAN_CREATE="$(PGPASSWORD="$DB_PASSWORD" psql "${CONN[@]}" -U "$ADMIN_USER" -Atc "SELECT has_schema_privilege('hrm_runtime','public','CREATE')")"
test "$CAN_CREATE" = f || { echo "Runtime role can create tables"; exit 1; }
echo "Restricted runtime user denied schema creation and missing-tenant reads."
