-- =========================================================
-- V6: Indexes for the fraud rules, which run inside every transfer.
-- Measured before: each rule did a full scan of transfers (~90 ms total per transfer at 650k rows).
-- =========================================================

-- VELOCITY (count in the last N minutes) and AMOUNT_SPIKE (average over the last N days):
-- find one sender's transfers in a time range
CREATE INDEX idx_transfers_from_created
    ON transfers (from_account_id, created_at);

-- NEW_PAYEE_LARGE_AMOUNT: has this sender ever paid this recipient?
-- (also useful later for statement Q&A: "how much did I send to Rahul")
CREATE INDEX idx_transfers_from_to
    ON transfers (from_account_id, to_account_id);