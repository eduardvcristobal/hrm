-- M2.5.2: auditable manual consumption and reversal for approved leave.
-- This is not a statutory entitlement or automatic payroll deduction engine.
CREATE TABLE time_leave_balance_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 leave_type_id uuid NOT NULL,
 leave_request_id uuid NOT NULL,
 event_type varchar(16) NOT NULL CHECK (event_type IN ('DEBIT','REVERSAL')),
 days numeric(8,3) NOT NULL CHECK(days>0 AND days<=366),
 reverses_event_id uuid,
 note varchar(500) NOT NULL CHECK(length(trim(note))>=8),
 posted_by uuid NOT NULL,
 posted_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_time_leave_balance_event_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_time_leave_balance_event_employee FOREIGN KEY(tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_time_leave_balance_event_type FOREIGN KEY(tenant_id,leave_type_id)
   REFERENCES time_leave_type(tenant_id,id),
 CONSTRAINT fk_time_leave_balance_event_request FOREIGN KEY(tenant_id,leave_request_id)
   REFERENCES time_leave_request(tenant_id,id),
 CONSTRAINT fk_time_leave_balance_event_actor FOREIGN KEY(tenant_id,posted_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_time_leave_balance_event_reversed FOREIGN KEY(tenant_id,reverses_event_id)
   REFERENCES time_leave_balance_event(tenant_id,id),
 CONSTRAINT ck_time_leave_balance_event_reversal CHECK (
  (event_type='DEBIT' AND reverses_event_id IS NULL)
  OR (event_type='REVERSAL' AND reverses_event_id IS NOT NULL)
 )
);
-- One debit and at most one reversal per request: a previously reversed
-- leave request cannot accidentally consume credits again.
CREATE UNIQUE INDEX uq_time_leave_one_debit
 ON time_leave_balance_event(tenant_id,leave_request_id) WHERE event_type='DEBIT';
CREATE UNIQUE INDEX uq_time_leave_one_reversal
 ON time_leave_balance_event(tenant_id,reverses_event_id) WHERE event_type='REVERSAL';
CREATE INDEX ix_time_leave_balance_event_employee
 ON time_leave_balance_event(tenant_id,employee_id,leave_type_id,posted_at DESC);
ALTER TABLE time_leave_balance_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_leave_balance_event FORCE ROW LEVEL SECURITY;
CREATE POLICY time_leave_balance_event_tenant ON time_leave_balance_event
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
