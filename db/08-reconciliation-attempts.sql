ALTER TABLE payments ADD COLUMN IF NOT EXISTS reconcile_attempts integer;
UPDATE payments SET reconcile_attempts = 0 WHERE reconcile_attempts IS NULL;
ALTER TABLE payments ALTER COLUMN reconcile_attempts SET DEFAULT 0;
ALTER TABLE payments ALTER COLUMN reconcile_attempts SET NOT NULL;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS last_reconciled_at timestamp;

ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS reconcile_attempts integer;
UPDATE payment_refunds SET reconcile_attempts = 0 WHERE reconcile_attempts IS NULL;
ALTER TABLE payment_refunds ALTER COLUMN reconcile_attempts SET DEFAULT 0;
ALTER TABLE payment_refunds ALTER COLUMN reconcile_attempts SET NOT NULL;
ALTER TABLE payment_refunds ADD COLUMN IF NOT EXISTS last_reconciled_at timestamp;

ALTER TABLE subscription_payments ADD COLUMN IF NOT EXISTS reconcile_attempts integer;
UPDATE subscription_payments SET reconcile_attempts = 0 WHERE reconcile_attempts IS NULL;
ALTER TABLE subscription_payments ALTER COLUMN reconcile_attempts SET DEFAULT 0;
ALTER TABLE subscription_payments ALTER COLUMN reconcile_attempts SET NOT NULL;
ALTER TABLE subscription_payments ADD COLUMN IF NOT EXISTS last_reconciled_at timestamp;
