package com.wallet.wallet_ledger.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DELIBERATELY BROKEN - used only to demonstrate the race condition.
 * Reads balances without locks, checks in Java, then writes values computed in Java.
 */
@Service
public class UnsafeTransferService {

    private final JdbcTemplate jdbc;

    public UnsafeTransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional // default isolation: READ COMMITTED
    public long transfer(long fromAccountId, long toAccountId, long amount) {
        // 1. Read balances - no lock taken
        long fromBalance = jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, fromAccountId);
        long toBalance = jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", Long.class, toAccountId);

        // 2. Check in Java - another transaction may change the balance right after this
        if (fromBalance < amount) {
            throw new InsufficientFundsException(fromAccountId);
        }

        // 3. Write values computed in Java - may overwrite another transaction's update
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", fromBalance - amount, fromAccountId);
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", toBalance + amount, toAccountId);

        // 4. Record the transfer and its two ledger entries
        Long transferId = jdbc.queryForObject(
                "INSERT INTO transfers (type, status, amount, from_account_id, to_account_id) VALUES ('PEER_TRANSFER', 'COMPLETED', ?, ?, ?) RETURNING id",
                Long.class, amount, fromAccountId, toAccountId);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'DEBIT', ?)",
                transferId, fromAccountId, amount);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'CREDIT', ?)",
                transferId, toAccountId, amount);

        return transferId;
    }
}