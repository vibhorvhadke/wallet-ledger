package com.wallet.wallet_ledger.account;
import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

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
                        rs.getObject("created_at", OffsetDateTime.class)),
                userId, request.name());
    }

    /** Returns the owner's user id, or 404 if the id is not a user wallet (system accounts are not exposed). */
    public long requireUserWalletOwner(long walletId) {
        List<Long> owners = jdbc.query(
                "SELECT user_id FROM accounts WHERE id = ? AND account_type = 'USER_WALLET'",
                (rs, rowNum) -> rs.getLong("user_id"), walletId);
        if (owners.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet " + walletId + " not found");
        }
        return owners.get(0);
    }

    public long systemAccountId(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM accounts WHERE account_type = 'SYSTEM' AND name = ?", Long.class, name);
    }
}