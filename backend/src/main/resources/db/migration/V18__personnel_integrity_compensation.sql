-- M1.5: enforce same-company organization assignments and isolate compensation history.
ALTER TABLE branch ADD CONSTRAINT uq_branch_tenant_company_id UNIQUE(tenant_id,company_id,id);
ALTER TABLE personnel_department ADD CONSTRAINT uq_personnel_department_company_id UNIQUE(tenant_id,company_id,id);
ALTER TABLE personnel_employee ADD CONSTRAINT fk_personnel_employee_branch_company
 FOREIGN KEY(tenant_id,company_id,branch_id) REFERENCES branch(tenant_id,company_id,id);
ALTER TABLE personnel_employee ADD CONSTRAINT fk_personnel_employee_dept_company
 FOREIGN KEY(tenant_id,company_id,department_id) REFERENCES personnel_department(tenant_id,company_id,id);
ALTER TABLE personnel_department ADD CONSTRAINT fk_personnel_department_parent_company
 FOREIGN KEY(tenant_id,company_id,parent_id) REFERENCES personnel_department(tenant_id,company_id,id);

CREATE TABLE personnel_compensation_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 effective_date date NOT NULL,
 amount numeric(15,2) NOT NULL,
 currency_code char(3) NOT NULL DEFAULT 'PHP',
 pay_basis varchar(20) NOT NULL,
 reason varchar(250) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(),
 recorded_by uuid NOT NULL,
 CONSTRAINT fk_personnel_compensation_employee FOREIGN KEY(tenant_id,employee_id)
  REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT ck_personnel_comp_amount CHECK(amount > 0),
 CONSTRAINT ck_personnel_comp_basis CHECK(pay_basis IN ('MONTHLY','DAILY','HOURLY')),
 CONSTRAINT uq_personnel_compensation_effective UNIQUE(tenant_id,employee_id,effective_date)
);
CREATE INDEX ix_personnel_comp_history ON personnel_compensation_history(tenant_id,employee_id,effective_date DESC);
ALTER TABLE personnel_compensation_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_compensation_history FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_comp_tenant ON personnel_compensation_history
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
