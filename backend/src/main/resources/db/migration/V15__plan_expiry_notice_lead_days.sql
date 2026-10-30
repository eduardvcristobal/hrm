ALTER TABLE platform_subscription_plan
  ADD COLUMN expiry_notice_days integer NOT NULL DEFAULT 14
  CHECK (expiry_notice_days BETWEEN 0 AND 365);
