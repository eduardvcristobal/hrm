-- Run as a privileged PostgreSQL administrator after Flyway migrations.
-- Required psql variable: -v app_password='<secret>' (never commit actual secrets).
-- DB role is NOT a schema owner and must never have BYPASSRLS or CREATEDB.
\if :{?app_password}
\else
\echo 'Missing -v app_password=...'
\quit 1
\endif
SELECT format('CREATE ROLE hrm_runtime LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hrm_runtime') \gexec
SELECT format('ALTER ROLE hrm_runtime LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', :'app_password') \gexec
GRANT CONNECT ON DATABASE hrm_dev TO hrm_runtime;
GRANT USAGE ON SCHEMA public TO hrm_runtime;
-- Runtime has no DDL rights. Lookup of auth tables is exclusively through backend authentication service.
GRANT SELECT ON tenant,auth_identity,auth_identity_role TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE ON company,branch TO hrm_runtime;
GRANT SELECT,INSERT ON audit_event TO hrm_runtime;
GRANT USAGE,SELECT ON SEQUENCE audit_event_id_seq TO hrm_runtime;
REVOKE CREATE ON SCHEMA public FROM hrm_runtime;
