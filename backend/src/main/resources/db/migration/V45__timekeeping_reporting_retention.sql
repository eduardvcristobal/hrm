-- M2.8: auditable bounded export receipts and non-destructive retention plans.
-- All retention policies are advisory, legal hold defaults to enabled,
-- and there is deliberately no purge/delete route.
CREATE TABLE time_reporting_export_receipt (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 actor_id uuid NOT NULL,
 export_type varchar(32) NOT NULL CHECK(export_type IN ('ATTENDANCE_SUMMARY','TIMEKEEPING_AUDIT')),
 date_from date NOT NULL,
 date_to date NOT NULL,
 record_count integer NOT NULL CHECK(record_count BETWEEN 0 AND 500),
 content_sha256 varchar(64) NOT NULL CHECK(length(content_sha256)=64),
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_export_receipt_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_export_actor FOREIGN KEY(tenant_id,actor_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_export_range CHECK(date_to>=date_from AND date_to<=date_from+30)
);
CREATE INDEX ix_time_export_tenant_created ON time_reporting_export_receipt(tenant_id,created_at DESC);
CREATE TABLE time_retention_plan (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 attendance_min_days integer NOT NULL CHECK(attendance_min_days BETWEEN 365 AND 36500),
 audit_min_days integer NOT NULL CHECK(audit_min_days BETWEEN 365 AND 36500),
 legal_hold boolean NOT NULL DEFAULT true CHECK(legal_hold=true),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_retention_plan_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_retention_plan_company UNIQUE(tenant_id,company_id),
 CONSTRAINT fk_time_retention_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_time_retention_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id)
);
ALTER TABLE time_reporting_export_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_reporting_export_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY time_export_receipt_tenant ON time_reporting_export_receipt
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE time_retention_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_retention_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY time_retention_plan_tenant ON time_retention_plan
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
