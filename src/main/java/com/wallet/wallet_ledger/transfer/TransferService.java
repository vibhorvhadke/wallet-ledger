package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

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
    public TransferResponse transfer(TransferType type, long fromAccountId, long toAccountId,
                                     long amount, String note) {
        if (type == TransferType.REVERSAL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TYPE", "Use reverse() to create a reversal");
        }
        return moveMoney(type, fromAccountId, toAccountId, amount, note, null);
    }

    /**
     * Fixes a mistake with a NEW transfer that moves the money back and points to the original.
     * Ledger entries of the original are never changed.
     */
    @Transactional
    public TransferResponse reverse(long originalTransferId, String reason, String changedBy) {
        // 1. Lock the ORIGINAL transfer row, so two reversals of it cannot run at the same time
        List<OriginalTransfer> found = jdbc.query(
                "SELECT id, type, status, amount FROM transfers WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new OriginalTransfer(
                        rs.getLong("id"), rs.getString("type"), rs.getString("status"), rs.getLong("amount")),
                originalTransferId);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                    "Transfer " + originalTransferId + " not found");
        }
        OriginalTransfer original = found.get(0);

        // 2. Check it can be reversed
        if ("REVERSAL".equals(original.type())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_REVERSE_REVERSAL",
                    "A reversal cannot itself be reversed");
        }
        if (!"COMPLETED".equals(original.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_NOT_REVERSIBLE",
                    "Only COMPLETED transfers can be reversed; this one is " + original.status());
        }

        // 3. Find who was debited and credited, then move the money back the other way
        long originalDebitAccount = jdbc.queryForObject(
                "SELECT account_id FROM ledger_entries WHERE transfer_id = ? AND direction = 'DEBIT'",
                Long.class, originalTransferId);
        long originalCreditAccount = jdbc.queryForObject(
                "SELECT account_id FROM ledger_entries WHERE transfer_id = ? AND direction = 'CREDIT'",
                Long.class, originalTransferId);

        String note = (reason != null) ? reason : "Reversal of transfer " + originalTransferId;
        TransferResponse reversal = moveMoney(TransferType.REVERSAL, originalCreditAccount, originalDebitAccount,
                original.amount(), note, originalTransferId);

        // 4. Mark the original REVERSED and record it in the audit log
        jdbc.update("UPDATE transfers SET status = 'REVERSED' WHERE id = ?", originalTransferId);
        jdbc.update("INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by) VALUES (?, 'COMPLETED', 'REVERSED', ?)",
                originalTransferId, changedBy);

        return reversal;
    }

    /** The one money path: lock both accounts, check funds, write transfer + entries + balances + audit row. */
    private TransferResponse moveMoney(TransferType type, long fromAccountId, long toAccountId,
                                       long amount, String note, Long reversalOfTransferId) {
        if (amount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be a positive number of paise");
        }
        if (fromAccountId == toAccountId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT", "Cannot transfer to the same account");
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
        TransferResponse transfer = jdbc.queryForObject("""
                INSERT INTO transfers (type, status, amount, note, reversal_of_transfer_id)
                VALUES (?, 'COMPLETED', ?, ?, ?)
                RETURNING id, created_at
                """,
                (rs, rowNum) -> new TransferResponse(
                        rs.getLong("id"), type.name(), "COMPLETED", amount, note,
                        fromAccountId, toAccountId, rs.getObject("created_at", OffsetDateTime.class)),
                type.name(), amount, note, reversalOfTransferId);

        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'DEBIT', ?)",
                transfer.id(), fromAccountId, amount);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'CREDIT', ?)",
                transfer.id(), toAccountId, amount);

        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, fromAccountId);
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, toAccountId);

        jdbc.update("INSERT INTO transfer_status_history (transfer_id, from_status, to_status, changed_by) VALUES (?, NULL, 'COMPLETED', 'SYSTEM')",
                transfer.id());

        return transfer;
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

    private record OriginalTransfer(long id, String type, String status, long amount) {
    }
}