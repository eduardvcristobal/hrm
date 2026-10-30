-- M1.1: Tenant-isolated personnel masterfile foundation. No API routes activated.
ALTER TABLE branch ADD CONSTRAINT uq_branch_tenant_id UNIQUE (tenant_id,id);
CREATE TABLE personnel_department (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 department_code varchar(40) NOT NULL,
 department_name varchar(160) NOT NULL,
 parent_id uuid,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_personnel_dept_tenant_id UNIQUE (tenant_id,id),
 CONSTRAINT uq_personnel_dept_company_code UNIQUE (tenant_id,company_id,department_code),
 CONSTRAINT fk_personnel_dept_company FOREIGN KEY (tenant_id,company_id)
  REFERENCES company(tenant_id,id),
 CONSTRAINT fk_personnel_dept_parent FOREIGN KEY (tenant_id,parent_id)
  REFERENCES personnel_department(tenant_id,id)
);
CREATE INDEX ix_personnel_department_company ON personnel_department(tenant_id,company_id);

CREATE TABLE personnel_employee (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 branch_id uuid,
 department_id uuid,
 employee_number varchar(50) NOT NULL,
 first_name varchar(120) NOT NULL,
 middle_name varchar(120),
 last_name varchar(120) NOT NULL,
 suffix varchar(30),
 preferred_name varchar(120),
 hire_date date NOT NULL,
 employment_status varchar(25) NOT NULL DEFAULT 'ACTIVE',
 employment_type varchar(25) NOT NULL DEFAULT 'REGULAR',
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_personnel_employee_tenant_id UNIQUE (tenant_id,id),
 CONSTRAINT uq_personnel_employee_company_number UNIQUE (tenant_id,company_id,employee_number),
 CONSTRAINT fk_personnel_employee_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_personnel_employee_branch FOREIGN KEY (tenant_id,branch_id) REFERENCES branch(tenant_id,id),
 CONSTRAINT fk_personnel_employee_department FOREIGN KEY (tenant_id,department_id) REFERENCES personnel_department(tenant_id,id),
 CONSTRAINT ck_personnel_employee_status CHECK (employment_status IN ('ACTIVE','ON_LEAVE','SUSPENDED','TERMINATED','RETIRED')),
 CONSTRAINT ck_personnel_employee_type CHECK (employment_type IN ('REGULAR','PROBATIONARY','CONTRACTUAL','PROJECT','SEASONAL','CASUAL','PART_TIME'))
);
CREATE INDEX ix_personnel_employee_tenant_company ON personnel_employee(tenant_id,company_id,last_name,first_name);

CREATE TABLE personnel_employment_history (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 effective_date date NOT NULL,
 end_date date,
 employment_type varchar(25) NOT NULL,
 employment_status varchar(25) NOT NULL,
 job_title varchar(160),
 reason varchar(250),
 recorded_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_personnel_history_employee FOREIGN KEY (tenant_id,employee_id)
  REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT ck_personnel_history_dates CHECK (end_date IS NULL OR end_date >= effective_date)
);
CREATE INDEX ix_personnel_history_employee ON personnel_employment_history(tenant_id,employee_id,effective_date DESC);

-- FORCE prevents accidental bypass by table owner; DB administrators and BYPASSRLS roles remain privileged.
ALTER TABLE personnel_department ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_department FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_department_tenant ON personnel_department
 USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
ALTER TABLE personnel_employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_employee FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_employee_tenant ON personnel_employee
 USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
ALTER TABLE personnel_employment_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_employment_history FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_history_tenant ON personnel_employment_history
 USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
