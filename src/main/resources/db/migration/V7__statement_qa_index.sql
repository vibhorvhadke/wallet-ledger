-- =========================================================
-- V7: Find a wallet's incoming transfers in a time range
-- (statement Q&A: "total received", "largest", "number of transfers").
-- =========================================================
CREATE INDEX idx_transfers_to_created
    ON transfers (to_account_id, created_at);