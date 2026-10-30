-- M1.11.2 controlled rehire: request and approval must be separate actors.
CREATE TABLE personnel_rehire_request (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 effective_date date NOT NULL,
 employment_type varchar(25) NOT NULL,
 reason varchar(250) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING',
 requested_by uuid NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(),
 approved_by uuid,
 approved_at timestamptz,
 CONSTRAINT fk_rehire_employee FOREIGN KEY (tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_rehire_requester FOREIGN KEY (tenant_id,requested_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_rehire_approver FOREIGN KEY (tenant_id,approved_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_rehire_status CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 CONSTRAINT ck_rehire_separate_actors CHECK(approved_by IS NULL OR approved_by<>requested_by),
 CONSTRAINT ck_rehire_approval_state CHECK (
   (status='PENDING' AND approved_by IS NULL AND approved_at IS NULL)
   OR (status IN ('APPROVED','REJECTED') AND approved_by IS NOT NULL AND approved_at IS NOT NULL)),
 CONSTRAINT ck_rehire_employment_type CHECK (employment_type IN
   ('REGULAR','PROBATIONARY','CONTRACTUAL','PROJECT','SEASONAL','CASUAL','PART_TIME'))
);
CREATE UNIQUE INDEX uq_rehire_pending_per_employee ON personnel_rehire_request(tenant_id,employee_id)
 WHERE status='PENDING';
CREATE INDEX ix_rehire_employee ON personnel_rehire_request(tenant_id,employee_id,requested_at DESC);
ALTER TABLE personnel_rehire_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_rehire_request FORCE ROW LEVEL SECURITY;
CREATE POLICY rehire_tenant ON personnel_rehire_request
 USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
