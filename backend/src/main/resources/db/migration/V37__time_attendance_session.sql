-- M2.2: append-friendly attendance sessions with one open session per employee.
-- Timestamps are server supplied UTC instants; capture origins must never be client-trusted.
CREATE TABLE time_attendance_session (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 clock_in_at timestamptz NOT NULL DEFAULT now(),
 clock_out_at timestamptz,
 clock_in_by uuid NOT NULL,
 clock_out_by uuid,
 source varchar(16) NOT NULL DEFAULT 'ADMIN' CHECK(source='ADMIN'),
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_session_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_session_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_session_in_actor FOREIGN KEY(tenant_id,clock_in_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_session_out_actor FOREIGN KEY(tenant_id,clock_out_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_session_close CHECK(
  (clock_out_at IS NULL AND clock_out_by IS NULL) OR
  (clock_out_at IS NOT NULL AND clock_out_by IS NOT NULL AND clock_out_at>=clock_in_at))
);
CREATE UNIQUE INDEX uq_time_open_session ON time_attendance_session(tenant_id,employee_id)
 WHERE clock_out_at IS NULL;
CREATE INDEX ix_time_employee_sessions ON time_attendance_session(tenant_id,employee_id,clock_in_at DESC);
ALTER TABLE time_attendance_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_attendance_session FORCE ROW LEVEL SECURITY;
CREATE POLICY time_session_tenant ON time_attendance_session
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
