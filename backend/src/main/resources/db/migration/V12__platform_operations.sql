CREATE TABLE platform_subscription_plan (
  code varchar(32) PRIMARY KEY,
  name varchar(80) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  amount numeric(12,2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
  currency char(3) NOT NULL DEFAULT 'PHP',
  billing_interval varchar(12) NOT NULL DEFAULT 'MONTHLY' CHECK (billing_interval IN ('MONTHLY','ANNUAL')),
  trial_days integer NOT NULL DEFAULT 0 CHECK (trial_days BETWEEN 0 AND 365),
  active boolean NOT NULL DEFAULT true,
  price_configured boolean NOT NULL DEFAULT false,
  feature_codes text[] NOT NULL DEFAULT '{}',
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid,
  updated_by_email varchar(254)
);
INSERT INTO platform_subscription_plan(code,name,description,amount,currency,billing_interval,trial_days,active,price_configured,feature_codes)
VALUES ('TRIAL','Trial','Starter access for a new workspace.',0,'PHP','MONTHLY',14,true,true,ARRAY['people']),
       ('STARTER','Starter','Core people operations for small teams.',0,'PHP','MONTHLY',0,true,false,ARRAY['people','time-attendance']),
       ('GROWTH','Growth','Expanded people operations for growing teams.',0,'PHP','MONTHLY',0,true,false,ARRAY['people','time-attendance','payroll','reports']);

ALTER TABLE tenant ADD COLUMN plan_code varchar(32) NOT NULL DEFAULT 'TRIAL' REFERENCES platform_subscription_plan(code);
ALTER TABLE tenant ADD COLUMN subscription_status varchar(25) NOT NULL DEFAULT 'TRIALING'
  CHECK (subscription_status IN ('TRIALING','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED'));
ALTER TABLE tenant ADD COLUMN trial_ends_at timestamptz DEFAULT now() + interval '14 days';
ALTER TABLE tenant ADD COLUMN period_starts_at timestamptz;
ALTER TABLE tenant ADD COLUMN period_ends_at timestamptz;
ALTER TABLE tenant ADD COLUMN subscription_version bigint NOT NULL DEFAULT 0;

UPDATE tenant SET subscription_status=CASE status WHEN 'ACTIVE' THEN 'TRIALING' WHEN 'SUSPENDED' THEN 'SUSPENDED' ELSE 'CANCELLED' END;

CREATE TABLE platform_subscription_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  from_status varchar(25) NOT NULL,
  to_status varchar(25) NOT NULL,
  from_plan_code varchar(32),
  to_plan_code varchar(32) NOT NULL REFERENCES platform_subscription_plan(code),
  reason varchar(500) NOT NULL,
  admin_id uuid NOT NULL,
  admin_email varchar(254) NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_platform_subscription_event_tenant ON platform_subscription_event(tenant_id,recorded_at DESC);
CREATE TABLE platform_plan_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plan_code varchar(32) NOT NULL REFERENCES platform_subscription_plan(code),
  action varchar(12) NOT NULL CHECK (action IN ('CREATED','UPDATED')),
  reason varchar(500) NOT NULL,
  admin_id uuid NOT NULL,
  admin_email varchar(254) NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform_payment_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  submitted_by uuid NOT NULL REFERENCES auth_identity(id),
  plan_code varchar(32) NOT NULL REFERENCES platform_subscription_plan(code),
  amount numeric(12,2) NOT NULL CHECK (amount > 0),
  currency char(3) NOT NULL,
  billing_interval varchar(12) NOT NULL CHECK (billing_interval IN ('MONTHLY','ANNUAL')),
  payment_reference varchar(100) NOT NULL,
  note varchar(300) NOT NULL DEFAULT '',
  status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  decided_at timestamptz,
  decided_by uuid,
  decided_by_email varchar(254),
  decision_reason varchar(500)
);
CREATE UNIQUE INDEX uq_platform_payment_reference ON platform_payment_request(lower(payment_reference));
CREATE INDEX ix_platform_payment_request_review ON platform_payment_request(status,submitted_at DESC);
CREATE UNIQUE INDEX uq_platform_payment_pending_tenant ON platform_payment_request(tenant_id) WHERE status='PENDING';

CREATE TABLE platform_subscription_payment_qr (
  id smallint PRIMARY KEY DEFAULT 1 CHECK (id=1),
  content_type varchar(40) NOT NULL,
  file_name varchar(160) NOT NULL,
  image_data bytea NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  uploaded_by uuid NOT NULL,
  uploaded_by_email varchar(254) NOT NULL
);

CREATE TABLE platform_module (
  code varchar(60) PRIMARY KEY,
  name varchar(100) NOT NULL,
  description varchar(300) NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid,
  updated_by_email varchar(254),
  update_reason varchar(500) NOT NULL DEFAULT ''
);
INSERT INTO platform_module(code,name,description) VALUES
 ('people','People','Employee records and organization directory'),
 ('time-attendance','Time & attendance','Attendance, schedules, and time records'),
 ('payroll','Payroll','Payroll runs and employee pay'),
 ('reports','Reports','People and payroll reporting'),
 ('workspace-settings','Workspace settings','Workspace configuration and access controls');

CREATE TABLE platform_mail_settings (
  id smallint PRIMARY KEY DEFAULT 1 CHECK (id=1),
  enabled boolean NOT NULL DEFAULT false,
  mode varchar(16) NOT NULL DEFAULT 'DEVELOPMENT' CHECK (mode IN ('DEVELOPMENT','PRODUCTION')),
  from_email varchar(254) NOT NULL DEFAULT 'onboarding@resend.dev',
  from_name varchar(120) NOT NULL DEFAULT 'Apex People',
  reply_to varchar(254),
  dev_recipient varchar(254),
  api_key_ciphertext text,
  updated_by uuid,
  updated_by_email varchar(254),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform_backup_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  generated_by uuid NOT NULL,
  generated_by_email varchar(254) NOT NULL,
  file_name varchar(240) NOT NULL,
  sha256 varchar(64) NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
  storage_path text NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'CREATED',
  created_at timestamptz NOT NULL DEFAULT now(),
  verified_at timestamptz,
  verify_message text,
  drill_status varchar(20) NOT NULL DEFAULT 'NOT_RUN',
  drill_started_at timestamptz,
  drill_completed_at timestamptz,
  drill_message text
);
CREATE INDEX ix_platform_backup_event_created ON platform_backup_event(created_at DESC);
