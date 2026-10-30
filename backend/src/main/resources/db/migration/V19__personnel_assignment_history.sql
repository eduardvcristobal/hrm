CREATE TABLE personnel_assignment_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 company_id uuid NOT NULL,
 branch_id uuid,
 department_id uuid,
 effective_date date NOT NULL,
 reason varchar(250) NOT NULL,
 recorded_by uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_assignment_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_assignment_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_assignment_branch FOREIGN KEY(tenant_id,company_id,branch_id) REFERENCES branch(tenant_id,company_id,id),
 CONSTRAINT fk_assignment_department FOREIGN KEY(tenant_id,company_id,department_id) REFERENCES personnel_department(tenant_id,company_id,id),
 CONSTRAINT uq_assignment_effective UNIQUE(tenant_id,employee_id,effective_date)
);
CREATE INDEX ix_personnel_assignment_history ON personnel_assignment_history(tenant_id,employee_id,effective_date DESC);
ALTER TABLE personnel_assignment_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_assignment_history FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_assignment_tenant ON personnel_assignment_history USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
