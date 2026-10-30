# HRM SaaS — M0.2 Identity & Tenant-Isolation Foundation

Philippines-first multi-tenant HRM platform organized around **Personnel**, **Timekeeping**, and **Payroll**.

## Versions
- Java 17 (compiler target)
- Spring Boot 4.1.1, Maven
- PostgreSQL 17 (Docker Compose)
- Flyway SQL migrations

## Local start (macOS/Linux)
1. Install Java 17, Maven, PostgreSQL client tools (`pg_isready`), and either Docker Desktop or a local PostgreSQL server.
2. Run `cp .env.example .env` and edit `.env` for your local environment. The defaults are **not safe for deployment**.
3. Optional: add `GOOGLE_CLIENT_ID`, `GOOGLE_JWK_SET_URI`, and `PLATFORM_ADMIN_EMAILS` to `.env` to enable Google sign-in for staff and platform administrators. Staff Google sign-in requires a matching active HRM identity; MFA requirements still apply. Keep local values in the ignored `.env` file.
4. Run `./scripts/start-local.sh` to start the backend with Compose PostgreSQL, or run `../run_local.sh` from the repository root to start both app services against local PostgreSQL without Docker. `run_local.sh` defaults to `jdbc:postgresql://localhost:5432/hrm_dev`; set `LOCAL_DB_URL`, `LOCAL_DB_USERNAME`, and `LOCAL_DB_PASSWORD` in your shell to override the local connection. It uses the database and role configured on your local PostgreSQL server, and Flyway applies the app migrations there.
5. Verify: `curl -i http://localhost:8080/actuator/health` (expected 200, status UP).
6. Check database migrations: `docker compose exec db psql -U hrm_app -d hrm_dev -c 'select installed_rank, version, success from flyway_schema_history'`.

To use Compose PostgreSQL directly:
```bash
docker compose up -d db
mvn spring-boot:run
```

## Tests
`mvn test` (unit tests; database/runtime integration verification is still required).

## Current scope
- Flyway migrations create tenant, company, branch, identity, MFA, API-token, and platform-admin token tables.
- `GET /api/v1/me` supports staff credentials and platform-admin bearer tokens; business endpoints remain explicitly denied or role-protected.
- `/api/v1/security/google` verifies Google ID tokens and issues a regular HRM token only for a matching active identity, while preserving MFA requirements.
- `/api/v1/security/signup` creates a workspace and its first tenant administrator. The account can authenticate with its email or unique username; administrator tools require authenticator enrollment.
- `/api/v1/security/signup/google` creates the same workspace and owner account using a verified Google email; the owner can continue using Google sign-in and must enroll MFA before administrator access.
- `/api/v1/security/platform-admin/google` verifies Google ID tokens and issues a tenant-isolated platform-admin token for allowlisted emails.
- Personnel provisioning, employee CRUD, and payroll engine remain future work.
- Production-safe restricted DB roles and two-tenant isolation regression tests remain required.

## Next milestones
- **M0.3**: authentication hardening, restricted application DB role, company/branch APIs, tenant isolation regression tests and CI.
- **M1**: Personnel employee management and effective-dated employment/compensation.

## Platform operations

The platform administrator includes workspace subscriptions, payment requests and QR configuration, plan and module settings, Resend mail configuration, and encrypted PostgreSQL backup/restore. Before using mail settings, set `HRM_MFA_ENCRYPTION_KEY` to a base64-encoded 32-byte key (for local development, `openssl rand -base64 32`). Keep it stable: mail API keys stored in the database cannot be decrypted after losing it.

Backup actions require PostgreSQL client tools (`pg_dump`, `pg_restore`, and `psql`) and `age` on the backend host. Generate an age key pair with `age-keygen -o /path/to/hrm-backup-identity.txt`; set `APP_BACKUP_AGE_IDENTITY` to that protected file path and `APP_BACKUP_AGE_RECIPIENT` to its public `age1...` recipient. Store backups in a protected persistent directory configured by `APP_BACKUP_DIRECTORY`.

`pg_dump` needs to include rows hidden by forced tenant RLS to make a complete platform backup. Provision the dedicated `hrm_backup` login as a PostgreSQL administrator by running `backend/scripts/provision-backup-db-role.sql` after migrations. Generate a strong password, export it as `HRM_BACKUP_DB_PASSWORD`, and run `psql -h localhost -p 5432 -U <postgres-admin> -d hrm_dev -v schema_owner=hrm_owner -f backend/scripts/provision-backup-db-role.sql`. Set that same secret as `APP_BACKUP_DB_PASSWORD` alongside `APP_BACKUP_DB_URL` and `APP_BACKUP_DB_USERNAME=hrm_backup` in `backend/.env`; for local Postgres.app, use the same host, port, and database as `LOCAL_DB_URL`. This account has `BYPASSRLS` and read-only table/sequence grants; it is separate from the app's `hrm_owner` role. Keep its password secret. Restore credentials are supplied for one restore operation and should target a separate empty PostgreSQL database.

## GitHub
Repository: `eduardvcristobal/hrm`.
Development branch: `feature/m0-2-auth-tenant-isolation`.
Never commit `.env`.

## Production safeguards
The local database password, database role and networking configuration are for development only. Before deployment, introduce secret management, dedicated migration/application database roles, database RLS, centralized authorization, MFA for privileged users, encryption, backups, and a full security review.

## M0.2 additions
See [docs/M0.2.md](docs/M0.2.md). `GET /api/v1/me` supports DB-backed Basic authentication. Other business APIs remain closed. Do not expose publicly or use for actual payroll until identity provisioning, login hardening, production DB roles, and tenant isolation integration tests are complete.
