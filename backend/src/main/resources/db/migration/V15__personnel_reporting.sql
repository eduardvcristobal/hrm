-- M1.10.1: same-company reporting relationships.
ALTER TABLE personnel_employee ADD CONSTRAINT uq_personnel_employee_company_id UNIQUE(tenant_id,company_id,id);
ALTER TABLE personnel_employee ADD COLUMN supervisor_id uuid;
ALTER TABLE personnel_employee ADD CONSTRAINT fk_personnel_supervisor_company
 FOREIGN KEY(tenant_id,company_id,supervisor_id)
 REFERENCES personnel_employee(tenant_id,company_id,id);
ALTER TABLE personnel_employee ADD CONSTRAINT ck_personnel_not_own_supervisor
 CHECK(supervisor_id IS NULL OR supervisor_id<>id);
