-- M1.16.2: personnel-to-payroll handoff; never calculate or disburse wages here.
CREATE TABLE personnel_final_pay_handoff (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 hr_case_id uuid NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACKNOWLEDGED')),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 acknowledged_by uuid,
 acknowledged_at timestamptz,
 CONSTRAINT fk_final_pay_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_final_pay_case FOREIGN KEY(tenant_id,hr_case_id) REFERENCES personnel_hr_case(tenant_id,id),
 CONSTRAINT fk_final_pay_creator FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_final_pay_ack FOREIGN KEY(tenant_id,acknowledged_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT uq_final_pay_case UNIQUE(tenant_id,hr_case_id),
 CONSTRAINT ck_final_pay_state CHECK (
    (status='PENDING' AND acknowledged_by IS NULL AND acknowledged_at IS NULL)
    OR (status='ACKNOWLEDGED' AND acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL))
);
ALTER TABLE personnel_final_pay_handoff ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_final_pay_handoff FORCE ROW LEVEL SECURITY;
CREATE POLICY final_pay_tenant ON personnel_final_pay_handoff USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE INDEX ix_final_pay_employee ON personnel_final_pay_handoff(tenant_id,employee_id,created_at DESC);
