package com.wallet.wallet_ledger.reconciliation;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// The unique property gives this class its own context -> its own database (it corrupts data on purpose)
@SpringBootTest(properties = "test.isolation=reconciliation")
@Import(TestcontainersConfiguration.class)
class ReconciliationAcceptanceTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final int WALLETS = 100;
    private static final long TOPUP_AMOUNT = 1_000_000; // Rs 10,000 per wallet
    private static final long PEER_AMOUNT = 100;        // Rs 1 per peer transfer

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReconciliationService reconciliationService;

    @Test
    void reportsExactlyTheThreeCorruptions() {
        long baseWallet = seedTenThousandTransfers();

        // --- Clean data: the job must pass ---
        ReconciliationRunResponse cleanRun = reconciliationService.run();
        assertThat(cleanRun.status()).isEqualTo("PASSED");
        assertThat(cleanRun.issuesFound()).isZero();

        // --- Pick what to corrupt (chosen so their accounts don't overlap) ---
        long transferA = jdbc.queryForObject("SELECT id FROM transfers WHERE note = 'seed 1'", Long.class);   // wallets 1 -> 2
        long transferB = jdbc.queryForObject("SELECT id FROM transfers WHERE note = 'seed 50'", Long.class);  // wallets 50 -> 51
        long walletZ = baseWallet + 80;                                                                     // not touched by A or B

        // --- Corrupt directly in the database (the insert-only trigger must be switched off to do this) ---
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_no_update_delete");
        jdbc.update("UPDATE ledger_entries SET amount = 150 WHERE transfer_id = ? AND direction = 'CREDIT'", transferA);
        jdbc.update("DELETE FROM ledger_entries WHERE transfer_id = ? AND direction = 'CREDIT'", transferB);
        jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_no_update_delete");
        jdbc.update("UPDATE accounts SET balance = balance + 777 WHERE id = ?", walletZ);

        // --- Run again: exactly those 3 problems, nothing else ---
        ReconciliationRunResponse run = reconciliationService.run();

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.issuesFound()).isEqualTo(3);
        assertThat(run.issues())
                .extracting(i -> tuple(i.checkName(), i.entityType(), i.entityId(), i.expectedValue(), i.actualValue()))
                .containsExactlyInAnyOrder(
                        tuple("TRANSFER_BALANCED", "TRANSFER", transferA, "credits=100", "credits=150"),
                        tuple("TRANSFER_BALANCED", "TRANSFER", transferB, "credits=100", "credits=0"),
                        tuple("STORED_BALANCE_MATCHES", "ACCOUNT", walletZ, "1000000", "1000777"));
    }

    /** 100 wallets, 100 top-ups, 9,900 peer transfers = 10,000 transfers. Returns the first wallet id. */
    private long seedTenThousandTransfers() {
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);

        // 100 users, one wallet each (ids are consecutive because they are inserted in one statement)
        jdbc.update("""
                INSERT INTO users (name, email, phone, kyc_status)
                SELECT 'Recon ' || g, 'recon' || g || '@test.com', '6' || lpad(g::text, 9, '0'), 'VERIFIED'
                FROM generate_series(0, 99) g
                """);
        jdbc.update("""
                INSERT INTO accounts (user_id, account_type, name)
                SELECT id, 'USER_WALLET', 'Main' FROM users WHERE email LIKE 'recon%@test.com' ORDER BY id
                """);
        long baseWallet = jdbc.queryForObject("""
                SELECT MIN(a.id) FROM accounts a JOIN users u ON u.id = a.user_id
                WHERE u.email LIKE 'recon%@test.com'
                """, Long.class);
        long lastWallet = jdbc.queryForObject("""
                SELECT MAX(a.id) FROM accounts a JOIN users u ON u.id = a.user_id
                WHERE u.email LIKE 'recon%@test.com'
                """, Long.class);
        assertThat(lastWallet - baseWallet).isEqualTo(WALLETS - 1);
        // 100 top-ups: CASH_IN -> each wallet
        jdbc.update("""
                WITH t AS (
                    INSERT INTO transfers (type, status, amount, note, from_account_id, to_account_id)
                    SELECT 'TOPUP', 'COMPLETED', ?, 'seed-topup ' || g, ?, ? + g
                    FROM generate_series(0, 99) g
                    RETURNING id, amount, from_account_id, to_account_id
                )
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount)
                SELECT id, from_account_id, 'DEBIT', amount FROM t
                UNION ALL
                SELECT id, to_account_id, 'CREDIT', amount FROM t
                """, TOPUP_AMOUNT, cashIn, baseWallet);

        // 9,900 peer transfers: wallet (g % 100) -> wallet ((g + 1) % 100)
        jdbc.update("""
                WITH t AS (
                    INSERT INTO transfers (type, status, amount, note, from_account_id, to_account_id)
                    SELECT 'PEER_TRANSFER', 'COMPLETED', ?, 'seed ' || g, ? + (g % 100), ? + ((g + 1) % 100)
                    FROM generate_series(1, 9900) g
                    RETURNING id, amount, from_account_id, to_account_id
                )
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount)
                SELECT id, from_account_id, 'DEBIT', amount FROM t
                UNION ALL
                SELECT id, to_account_id, 'CREDIT', amount FROM t
                """, PEER_AMOUNT, baseWallet, baseWallet);

        // Stored balances = balances computed from the ledger
        jdbc.update("""
                UPDATE accounts a SET balance = c.computed
                FROM (
                    SELECT account_id,
                           SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS computed
                    FROM ledger_entries GROUP BY account_id
                ) c
                WHERE a.id = c.account_id
                """);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfers", Long.class)).isEqualTo(10_000);
        return baseWallet;
    }
}