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
GRANT SELECT ON tenant TO hrm_runtime;
GRANT SELECT,UPDATE(enabled,password_hash) ON auth_identity TO hrm_runtime;
GRANT SELECT,INSERT,DELETE ON auth_identity_role TO hrm_runtime;
GRANT SELECT,INSERT ON auth_identity TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(used_at) ON auth_invitation TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(used_at) ON auth_password_reset TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON auth_login_attempt TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(revoked_at,last_used_at) ON auth_api_token TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(enabled) ON auth_platform_admin TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(revoked_at,last_used_at) ON auth_platform_admin_token TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(enabled,last_counter,secret_ciphertext) ON auth_totp_factor TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE ON company,branch TO hrm_runtime;
-- M1 personnel operations are guarded by tenant RLS and MFA-aware API roles.
GRANT SELECT,INSERT,UPDATE ON personnel_department,personnel_employee,personnel_position TO hrm_runtime;
GRANT SELECT,INSERT ON personnel_employment_history TO hrm_runtime;
GRANT SELECT,INSERT ON personnel_compensation_history TO hrm_runtime;
GRANT SELECT,INSERT ON personnel_assignment_history TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(status,approved_by,approved_at,cancelled_by,cancelled_at) ON personnel_rehire_request TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE ON personnel_private_data TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(status,decided_by,decided_at) ON personnel_hr_case TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(review_status,reviewed_by,reviewed_at,retain_until,scan_status,scanned_at,legal_hold,legal_hold_reason,deleted_at,deleted_by) ON personnel_document_metadata TO hrm_runtime;
GRANT SELECT,INSERT,DELETE ON personnel_document_content TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(status,evidence_document_id,verified_by,verified_at) ON personnel_onboarding_requirement TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(status,reviewed_by,reviewed_at) ON personnel_separation_clearance TO hrm_runtime;
GRANT SELECT,INSERT ON personnel_final_pay_handoff TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(status,resolved_by,resolved_at) ON personnel_separation_asset TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(enabled,minimum_wait_days,updated_by,updated_at) ON personnel_rehire_policy TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(status,executed_by,executed_at) ON personnel_document_purge_request TO hrm_runtime;
GRANT SELECT,INSERT ON time_shift_template,time_schedule_assignment TO hrm_runtime;
GRANT SELECT,INSERT,UPDATE(clock_out_at,clock_out_by) ON time_attendance_session TO hrm_runtime;
GRANT SELECT,INSERT ON audit_event TO hrm_runtime;
GRANT USAGE,SELECT ON SEQUENCE audit_event_id_seq TO hrm_runtime;
REVOKE CREATE ON SCHEMA public FROM hrm_runtime;
