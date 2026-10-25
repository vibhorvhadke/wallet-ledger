-- =========================================================
-- V5: Store the two accounts of every transfer on the transfer itself.
-- Needed to trace a transfer's accounts even if its ledger entries are damaged,
-- and for fraud rules / statement Q&A in Milestone 5.
-- =========================================================

ALTER TABLE transfers
    ADD COLUMN from_account_id BIGINT REFERENCES accounts (id),
    ADD COLUMN to_account_id   BIGINT REFERENCES accounts (id);

-- Fill existing rows from their ledger entries
UPDATE transfers t SET from_account_id = le.account_id
    FROM ledger_entries le
WHERE le.transfer_id = t.id AND le.direction = 'DEBIT';

UPDATE transfers t SET to_account_id = le.account_id
    FROM ledger_entries le
WHERE le.transfer_id = t.id AND le.direction = 'CREDIT';

ALTER TABLE transfers
    ALTER COLUMN from_account_id SET NOT NULL,
ALTER COLUMN to_account_id SET NOT NULL,
    ADD CONSTRAINT chk_transfers_different_accounts CHECK (from_account_id <> to_account_id);