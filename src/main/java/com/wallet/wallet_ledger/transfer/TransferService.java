package com.wallet.wallet_ledger.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Moves money between two accounts in ONE transaction (READ COMMITTED + row locks).
     * Transaction starts when this method is entered and commits when it returns.
     * Any exception before that rolls everything back.
     */
    @Transactional
    public long transfer(TransferType type, long fromAccountId, long toAccountId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        // 1. Lock BOTH account rows, always in ascending id order (prevents deadlocks)
        LockedAccount first = lockAccount(Math.min(fromAccountId, toAccountId));
        LockedAccount second = lockAccount(Math.max(fromAccountId, toAccountId));
        LockedAccount from = (first.id() == fromAccountId) ? first : second;

        // 2. Re-check the balance WHILE HOLDING the lock (system accounts may go negative)
        if (from.isUserWallet() && from.balance() < amount) {
            throw new InsufficientFundsException(fromAccountId);
        }

        // 3. Insert the transfer, its balanced ledger entries, and update stored balances
        Long transferId = jdbc.queryForObject(
                "INSERT INTO transfers (type, status, amount) VALUES (?, 'COMPLETED', ?) RETURNING id",
                Long.class, type.name(), amount);

        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'DEBIT', ?)",
                transferId, fromAccountId, amount);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'CREDIT', ?)",
                transferId, toAccountId, amount);

        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, fromAccountId);
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, toAccountId);

        jdbc.update("INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by) VALUES (?, NULL, 'COMPLETED', 'SYSTEM')",
                transferId);

        return transferId;
        // 4. Method returns -> COMMIT -> locks released
    }

    private LockedAccount lockAccount(long accountId) {
        return jdbc.queryForObject(
                "SELECT id, account_type, balance FROM accounts WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new LockedAccount(
                        rs.getLong("id"), rs.getString("account_type"), rs.getLong("balance")),
                accountId);
    }

    private record LockedAccount(long id, String accountType, long balance) {
        boolean isUserWallet() {
            return "USER_WALLET".equals(accountType);
        }
    }
}