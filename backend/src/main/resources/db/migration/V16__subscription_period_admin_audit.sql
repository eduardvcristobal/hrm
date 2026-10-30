ALTER TABLE platform_subscription_event
  ADD COLUMN from_trial_ends_at timestamptz,
  ADD COLUMN to_trial_ends_at timestamptz,
  ADD COLUMN from_period_starts_at timestamptz,
  ADD COLUMN to_period_starts_at timestamptz,
  ADD COLUMN from_period_ends_at timestamptz,
  ADD COLUMN to_period_ends_at timestamptz;
