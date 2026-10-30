# HRM SaaS — M0.1 Backend Foundation

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
- Flyway migration V1 creates tenant, company, branch, and audit_event tables.
- Security denies all business routes. Only health checks are public.
- No seed user, auth, payroll engine, employee CRUD, tenant data API, or frontend exists yet.
- Database isolation is **not yet enforced via RLS**: no business data endpoints should be opened before M0.2.

## Next milestones
- **M0.2**: authenticated users and roles; tenant-scoped request context; PostgreSQL RLS, least-privileged roles, isolation regression tests.
- **M0.3**: company/branch APIs, validation, exception handling, auditing and CI.
- **M1**: Personnel employee management and effective-dated employment/compensation.

## GitHub
Recommended repository: `hrm-saas`, private initially. After extracting:
```bash
git init
git add .
git commit -m "chore(m0.1): bootstrap Spring Boot HRM SaaS backend"
git branch -M main
git remote add origin git@github.com:YOUR_USERNAME/hrm-saas.git
git push -u origin main
```
Create the empty GitHub repository first, without auto-generating a README or .gitignore. Never commit `.env`.

## Production safeguards
The included local database password, database role and networking configuration are for development only. Before deployment, introduce secret management, dedicated migration/application database roles, database RLS, centralized authorization, MFA for privileged users, encryption, backups, and a full security review.
