-- M2.1: tenant-isolated reusable shifts and effective-dated employee schedules.
CREATE TABLE time_shift_template (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 shift_code varchar(40) NOT NULL,
 shift_name varchar(160) NOT NULL,
 starts_at time NOT NULL,
 ends_at time NOT NULL,
 timezone varchar(80) NOT NULL DEFAULT 'Asia/Manila',
 unpaid_break_minutes integer NOT NULL DEFAULT 60 CHECK(unpaid_break_minutes BETWEEN 0 AND 240),
 grace_minutes integer NOT NULL DEFAULT 0 CHECK(grace_minutes BETWEEN 0 AND 120),
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_shift_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_time_shift_company_code UNIQUE(tenant_id,company_id,shift_code),
 CONSTRAINT fk_time_shift_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT ck_time_shift_nonzero CHECK(starts_at<>ends_at)
);
CREATE TABLE time_schedule_assignment (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 shift_id uuid NOT NULL,
 effective_from date NOT NULL,
 effective_until date,
 -- bit0=Monday through bit6=Sunday; 127 every day.
 weekdays_mask integer NOT NULL DEFAULT 31 CHECK(weekdays_mask BETWEEN 1 AND 127),
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_assignment_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_schedule_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_schedule_shift FOREIGN KEY(tenant_id,shift_id) REFERENCES time_shift_template(tenant_id,id),
 CONSTRAINT fk_time_schedule_actor FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_schedule_dates CHECK(effective_until IS NULL OR effective_until>=effective_from)
);
CREATE INDEX ix_time_schedule_employee ON time_schedule_assignment(tenant_id,employee_id,effective_from DESC);
ALTER TABLE time_shift_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_shift_template FORCE ROW LEVEL SECURITY;
CREATE POLICY time_shift_tenant ON time_shift_template USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE time_schedule_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_schedule_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY time_schedule_tenant ON time_schedule_assignment USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
