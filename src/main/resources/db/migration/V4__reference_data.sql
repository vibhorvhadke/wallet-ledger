-- =========================================================
-- V4: Reference data every environment needs.
-- =========================================================

-- System accounts (belong to no user; may go negative)
INSERT INTO accounts (account_type, name) VALUES
                                              ('SYSTEM', 'CASH_IN'),
                                              ('SYSTEM', 'CASH_OUT'),
                                              ('SYSTEM', 'FEE_REVENUE');

-- Fraud rules (thresholds live here, not in code)
INSERT INTO risk_rules (code, description, action, threshold_count, window_minutes) VALUES
    ('VELOCITY', 'More than 5 outgoing transfers from one wallet in 10 minutes', 'HOLD', 5, 10);

INSERT INTO risk_rules (code, description, action, amount_multiplier, lookback_days) VALUES
    ('AMOUNT_SPIKE', 'Amount is over 5x the wallet''s 30-day average transfer', 'HOLD', 5.00, 30);

INSERT INTO risk_rules (code, description, action, threshold_amount) VALUES
    ('NEW_PAYEE_LARGE_AMOUNT', 'First transfer to this recipient above Rs 10,000', 'FLAG_ALLOW', 1000000);

-- One lock row per scheduled job
INSERT INTO job_locks (job_name) VALUES
    ('RECONCILIATION');