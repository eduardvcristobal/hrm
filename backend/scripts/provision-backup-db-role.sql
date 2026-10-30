-- Run as a privileged PostgreSQL administrator after Flyway migrations.
-- Export HRM_BACKUP_DB_PASSWORD and pass -v schema_owner='hrm_owner' to psql.
-- This account can read every row (including rows protected by tenant RLS) for full backups.
-- It is deliberately separate from the application role and is not a superuser.
\getenv backup_password HRM_BACKUP_DB_PASSWORD
\if :{?backup_password}
\else
\echo 'Export HRM_BACKUP_DB_PASSWORD before running this script.'
\quit 1
\endif
SELECT length(:'backup_password') >= 24 AS backup_password_is_strong \gset
\if :backup_password_is_strong
\else
\echo 'HRM_BACKUP_DB_PASSWORD must contain at least 24 characters.'
\quit 1
\endif
\if :{?schema_owner}
\else
\echo 'Missing -v schema_owner=...'
\quit 1
\endif

SELECT format('CREATE ROLE hrm_backup LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS', :'backup_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hrm_backup') \gexec
SELECT format('ALTER ROLE hrm_backup LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS', :'backup_password') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO hrm_backup', current_database()) \gexec
GRANT USAGE ON SCHEMA public TO hrm_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO hrm_backup;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO hrm_backup;
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON TABLES TO hrm_backup', :'schema_owner') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON SEQUENCES TO hrm_backup', :'schema_owner') \gexec
