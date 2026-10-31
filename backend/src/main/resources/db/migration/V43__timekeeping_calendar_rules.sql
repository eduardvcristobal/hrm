-- M2.6 company calendar and explicitly reviewed employee work/rest overrides.
-- No automatic statutory holiday catalogue, pay multipliers, or leave debits.
CREATE TABLE time_company_calendar_day (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 calendar_date date NOT NULL,
 day_kind varchar(32) NOT NULL CHECK(day_kind IN ('HOLIDAY','SPECIAL_NON_WORKING','COMPANY_CLOSURE')),
 title varchar(160) NOT NULL CHECK(length(trim(title))>=3),
 note varchar(500),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_company_calendar_day_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_company_calendar_date UNIQUE(tenant_id,company_id,calendar_date),
 CONSTRAINT fk_time_calendar_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_time_calendar_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE TABLE time_employee_day_override (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 calendar_date date NOT NULL,
 day_kind varchar(16) NOT NULL CHECK(day_kind IN ('REST_DAY','WORK_DAY')),
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>=8),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_employee_day_override_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_employee_day_override_date UNIQUE(tenant_id,employee_id,calendar_date),
 CONSTRAINT fk_time_day_override_employee FOREIGN KEY(tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_day_override_actor FOREIGN KEY(tenant_id,created_by)
   REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_time_company_calendar_range ON time_company_calendar_day(tenant_id,company_id,calendar_date);
CREATE INDEX ix_time_day_override_employee ON time_employee_day_override(tenant_id,employee_id,calendar_date);
ALTER TABLE time_company_calendar_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_company_calendar_day FORCE ROW LEVEL SECURITY;
CREATE POLICY time_calendar_tenant ON time_company_calendar_day
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE time_employee_day_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_employee_day_override FORCE ROW LEVEL SECURITY;
CREATE POLICY time_day_override_tenant ON time_employee_day_override
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
