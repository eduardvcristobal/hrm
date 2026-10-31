-- M2.7: internal attendance/leave reconciliation, NOT a payroll export.
-- These snapshots are deliberately non-payable; they cannot authorize payroll.
CREATE TABLE time_payroll_reconciliation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 period_from date NOT NULL,
 period_to date NOT NULL,
 timezone varchar(80) NOT NULL,
 status varchar(22) NOT NULL DEFAULT 'DRAFT'
   CHECK(status IN ('DRAFT','INTERNALLY_REVIEWED')),
 attendance_sessions integer NOT NULL CHECK(attendance_sessions>=0),
 raw_closed_minutes bigint NOT NULL CHECK(raw_closed_minutes>=0),
 open_sessions integer NOT NULL CHECK(open_sessions>=0),
 pending_corrections integer NOT NULL CHECK(pending_corrections>=0),
 pending_leave_requests integer NOT NULL CHECK(pending_leave_requests>=0),
 approved_leave_requests integer NOT NULL CHECK(approved_leave_requests>=0),
 unconsumed_approved_leave integer NOT NULL CHECK(unconsumed_approved_leave>=0),
 calendar_events integer NOT NULL CHECK(calendar_events>=0),
 blocker_codes text[] NOT NULL DEFAULT '{}',
 source_fingerprint varchar(64) NOT NULL CHECK(length(source_fingerprint)=64),
 source_snapshot text NOT NULL,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 reviewed_by uuid,
 reviewed_at timestamptz,
 CONSTRAINT uq_time_recon_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_recon_employee_period UNIQUE(tenant_id,employee_id,period_from,period_to),
 CONSTRAINT fk_time_recon_employee FOREIGN KEY(tenant_id,employee_id)
  REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_recon_creator FOREIGN KEY(tenant_id,created_by)
  REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_recon_reviewer FOREIGN KEY(tenant_id,reviewed_by)
  REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_recon_period CHECK(period_to>=period_from AND period_to<=period_from+30),
 CONSTRAINT ck_time_recon_actors CHECK(reviewed_by IS NULL OR reviewed_by<>created_by),
 CONSTRAINT ck_time_recon_status CHECK(
   (status='DRAFT' AND reviewed_by IS NULL AND reviewed_at IS NULL)
   OR (status='INTERNALLY_REVIEWED' AND reviewed_by IS NOT NULL
        AND reviewed_at IS NOT NULL AND cardinality(blocker_codes)=0))
);
CREATE INDEX ix_time_recon_tenant_period ON time_payroll_reconciliation(tenant_id,period_from,period_to);
ALTER TABLE time_payroll_reconciliation ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_payroll_reconciliation FORCE ROW LEVEL SECURITY;
CREATE POLICY time_recon_tenant ON time_payroll_reconciliation
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
