-- =========================================================
-- V3: Indexes for real queries only.
-- Every index here slows down inserts, so each one has a reason.
-- =========================================================

-- Wallet statement (newest first) and computing a balance
CREATE INDEX idx_ledger_entries_account_created
    ON ledger_entries (account_id, created_at DESC, id DESC);

-- A transfer's entries; per-transfer reconciliation check
CREATE INDEX idx_ledger_entries_transfer
    ON ledger_entries (transfer_id);

-- A user's wallets
CREATE INDEX idx_accounts_user
    ON accounts (user_id);

-- Status history in GET /transfers/{id}
CREATE INDEX idx_transfer_status_history_transfer
    ON transfer_status_history (transfer_id);

-- Issues of one reconciliation run
CREATE INDEX idx_reconciliation_issues_run
    ON reconciliation_issues (reconciliation_run_id);

-- Admin risk queue: only HELD transfers, oldest first
CREATE INDEX idx_transfers_held
    ON transfers (created_at)
    WHERE status = 'HELD';