-- M2.5: leave requests and independent review foundation.
-- This migration intentionally does not prescribe statutory leave entitlements,
-- accrual balances, compensated days or payroll handoff.
CREATE TABLE time_leave_type (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 code varchar(40) NOT NULL,
 name varchar(120) NOT NULL,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_leave_type_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_leave_type_company_code UNIQUE(tenant_id,company_id,code),
 CONSTRAINT fk_time_leave_type_company FOREIGN KEY(tenant_id,company_id)
  REFERENCES company(tenant_id,id)
);
CREATE TABLE time_leave_request (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 leave_type_id uuid NOT NULL,
 starts_on date NOT NULL,
 ends_on date NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(trim(reason)) >= 8),
 status varchar(16) NOT NULL DEFAULT 'PENDING'
   CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 requested_by uuid NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(),
 reviewed_by uuid,
 reviewed_at timestamptz,
 cancelled_at timestamptz,
 CONSTRAINT uq_time_leave_request_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_leave_request_employee FOREIGN KEY(tenant_id,employee_id)
  REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_leave_request_type FOREIGN KEY(tenant_id,leave_type_id)
  REFERENCES time_leave_type(tenant_id,id),
 CONSTRAINT fk_time_leave_request_requester FOREIGN KEY(tenant_id,requested_by)
  REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_leave_request_reviewer FOREIGN KEY(tenant_id,reviewed_by)
  REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_leave_dates CHECK(ends_on >= starts_on AND ends_on <= starts_on + 365),
 CONSTRAINT ck_time_leave_independent CHECK(reviewed_by IS NULL OR reviewed_by <> requested_by),
 CONSTRAINT ck_time_leave_decision CHECK(
  (status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND cancelled_at IS NULL)
  OR (status IN ('APPROVED','REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND cancelled_at IS NULL)
  OR (status='CANCELLED' AND reviewed_by IS NULL AND reviewed_at IS NULL AND cancelled_at IS NOT NULL))
);
CREATE INDEX ix_time_leave_by_employee ON time_leave_request(tenant_id,employee_id,starts_on DESC);
CREATE INDEX ix_time_leave_decisions ON time_leave_request(tenant_id,status,requested_at DESC);
ALTER TABLE time_leave_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_leave_type FORCE ROW LEVEL SECURITY;
CREATE POLICY time_leave_type_tenant ON time_leave_type
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE time_leave_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_leave_request FORCE ROW LEVEL SECURITY;
CREATE POLICY time_leave_request_tenant ON time_leave_request
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
