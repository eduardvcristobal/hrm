-- M2.4: second-actor approval before correcting a closed attendance record.
CREATE TABLE time_attendance_correction (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 session_id uuid NOT NULL,
 old_clock_in timestamptz NOT NULL,
 old_clock_out timestamptz NOT NULL,
 new_clock_in timestamptz NOT NULL,
 new_clock_out timestamptz NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(trim(reason))>=8),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 requested_by uuid NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(),
 reviewed_by uuid,
 reviewed_at timestamptz,
 CONSTRAINT uq_time_correction_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_correction_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_correction_session FOREIGN KEY(tenant_id,session_id) REFERENCES time_attendance_session(tenant_id,id),
 CONSTRAINT fk_time_correction_requester FOREIGN KEY(tenant_id,requested_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_correction_reviewer FOREIGN KEY(tenant_id,reviewed_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_time_correction_inout CHECK(new_clock_out>=new_clock_in),
 CONSTRAINT ck_time_correction_actors CHECK(reviewed_by IS NULL OR reviewed_by<>requested_by),
 CONSTRAINT ck_time_correction_review CHECK(
  (status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
  OR (status IN ('APPROVED','REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_time_pending_correction ON time_attendance_correction(tenant_id,session_id)
 WHERE status='PENDING';
ALTER TABLE time_attendance_correction ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_attendance_correction FORCE ROW LEVEL SECURITY;
CREATE POLICY time_correction_tenant ON time_attendance_correction
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
