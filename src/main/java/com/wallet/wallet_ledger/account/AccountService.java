package com.wallet.wallet_ledger.account;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;

@Service
public class AccountService {

    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WalletResponse createWallet(long userId, CreateWalletRequest request) {
        return jdbc.queryForObject("""
                        INSERT INTO accounts (user_id, account_type, name)
                        VALUES (?, 'USER_WALLET', ?)
                        RETURNING id, user_id, name, currency, balance, created_at
                        """,
                (rs, rowNum) -> new WalletResponse(
                        rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                        rs.getString("currency"), rs.getLong("balance"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                userId, request.name()
        );
    }

    /**
     * Returns the owner's user id, or 404 if the id is not a user wallet (system accounts are not exposed).
     */
    public long requireUserWalletOwner(long walletId) {
        List<Long> owners = jdbc.query(
                "SELECT user_id FROM accounts WHERE id = ? AND account_type = 'USER_WALLET'",
                (rs, rowNum) -> rs.getLong("user_id"), walletId
        );
        if (owners.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet " + walletId + " not found");
        }
        return owners.get(0);
    }

    public long systemAccountId(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM accounts WHERE account_type = 'SYSTEM' AND name = ?", Long.class, name);
    }

    public BalanceResponse getBalance(long walletId) {
        requireUserWalletOwner(walletId);
        return jdbc.queryForObject(
                "SELECT id, balance, currency FROM accounts WHERE id = ?",
                (rs, rowNum) -> new BalanceResponse(rs.getLong("id"), rs.getLong("balance"), rs.getString("currency")),
                walletId
        );
    }

    /**
     * Newest first. Uses index idx_ledger_entries_account_created (account_id, created_at DESC, id DESC).
     * Two modes: OFFSET (original contract) or CURSOR (constant cost at any depth). Not both.
     */
    public StatementResponse getStatement(long walletId, int limit, int offset, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "offset must be 0 or more");
        }
        StatementCursor position = (cursor == null) ? null : StatementCursor.decode(cursor);
        if (position != null && offset > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "use either offset or cursor, not both");
        }
        requireUserWalletOwner(walletId);

        StringBuilder sql = new StringBuilder("""
                SELECT le.id, le.transfer_id, t.type, le.direction, le.amount, t.note, le.created_at
                FROM ledger_entries le
                JOIN transfers t ON t.id = le.transfer_id
                WHERE le.account_id = ?
                """);
        List<Object> params = new ArrayList<>(List.of(walletId));

        if (position != null) {
            // Row comparison matches the index order, so PostgreSQL seeks straight to the cursor
            sql.append(" AND (le.created_at, le.id) < (?, ?)");
            params.add(position.createdAt());
            params.add(position.id());
        }
        sql.append(" ORDER BY le.created_at DESC, le.id DESC LIMIT ?");
        params.add(limit + 1); // one extra row tells us whether there is a next page
        if (position == null) {
            sql.append(" OFFSET ?");
            params.add(offset);
        }

        List<StatementEntry> rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> new StatementEntry(
                        rs.getLong("id"), rs.getLong("transfer_id"), rs.getString("type"),
                        rs.getString("direction"), rs.getLong("amount"), rs.getString("note"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                params.toArray()
        );

        boolean hasMore = rows.size() > limit;
        List<StatementEntry> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore) {
            StatementEntry last = page.get(page.size() - 1);
            nextCursor = new StatementCursor(last.createdAt(), last.entryId()).encode();
        }
        return new StatementResponse(walletId, limit, offset, page, nextCursor);
    }
}