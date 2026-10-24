-- =========================================================
-- V2: Ledger entries can be inserted but never updated or deleted.
-- Mistakes are fixed with a new REVERSAL transfer.
-- =========================================================

CREATE FUNCTION prevent_ledger_entry_change()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is insert-only: % is not allowed', TG_OP;
END;
$$;

-- Blocks UPDATE and DELETE on any row
CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION prevent_ledger_entry_change();

-- Blocks TRUNCATE (which skips row-level triggers)
CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_ledger_entry_change();