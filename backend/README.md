# HRM SaaS — M0.2 Identity & Tenant-Isolation Foundation

Philippines-first multi-tenant HRM platform organized around **Personnel**, **Timekeeping**, and **Payroll**.

## Versions
- Java 17 (compiler target)
- Spring Boot 4.1.1, Maven
- PostgreSQL 17 (Docker Compose)
- Flyway SQL migrations

## Local start (macOS/Linux)
1. Install Java 17, Maven, and Docker Desktop.
2. Run `cp .env.example .env` and edit `.env` for your local environment. The defaults are **not safe for deployment**.
3. Run `./scripts/start-local.sh`.
4. Verify: `curl -i http://localhost:8080/actuator/health` (expected 200, status UP).
5. Check database migrations: `docker compose exec db psql -U hrm_app -d hrm_dev -c 'select installed_rank, version, success from flyway_schema_history'`.

Alternatively:
```bash
docker compose up -d db
mvn spring-boot:run
```

## Tests
`mvn test` (unit tests; database/runtime integration verification is still required).

## Current scope
- Flyway V1 creates tenant, company, branch, and audit_event tables.
- Flyway V2 adds user identities, roles, and RLS policies.
- `GET /api/v1/me` requires authenticated credentials; all other business endpoints are denied.
- No provisioning, employee CRUD, payroll engine, or frontend exists yet.
- Production-safe restricted DB roles and two-tenant isolation regression tests remain required.

## Next milestones
- **M0.3**: authentication hardening, restricted application DB role, company/branch APIs, tenant isolation regression tests and CI.
- **M1**: Personnel employee management and effective-dated employment/compensation.

## GitHub
Repository: `eduardvcristobal/hrm`.
Development branch: `feature/m0-2-auth-tenant-isolation`.
Never commit `.env`.

## Production safeguards
The local database password, database role and networking configuration are for development only. Before deployment, introduce secret management, dedicated migration/application database roles, database RLS, centralized authorization, MFA for privileged users, encryption, backups, and a full security review.

## M0.2 additions
See [docs/M0.2.md](docs/M0.2.md). `GET /api/v1/me` supports DB-backed Basic authentication. Other business APIs remain closed. Do not expose publicly or use for actual payroll until identity provisioning, login hardening, production DB roles, and tenant isolation integration tests are complete.
