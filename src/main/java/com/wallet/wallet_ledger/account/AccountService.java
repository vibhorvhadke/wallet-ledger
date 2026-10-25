package com.wallet.wallet_ledger.account;

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
}