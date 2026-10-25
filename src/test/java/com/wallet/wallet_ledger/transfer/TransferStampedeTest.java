package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferStampedeTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final AtomicInteger USER_COUNTER = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    @RepeatedTest(20)
    void hundredConcurrentTransfersFromFiveHundredRupees() throws Exception {
        // --- Setup: sender with Rs 500, receiver with Rs 0 ---
        long fromWallet = createWallet();
        long toWallet = createWallet();
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);
        transferService.transfer(TransferType.TOPUP, cashIn, fromWallet, 50000, null);

        // --- 100 concurrent Rs 10 transfers ---
        int requests = 100;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                startGate.await();
                try {
                    transferService.transfer(TransferType.PEER_TRANSFER, fromWallet, toWallet, 1000, null);
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    firstError.compareAndSet(null, e);
                }
                return null;
            });
        }
        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // --- Acceptance checks ---
        assertThat(failed.get()).as("Unexpected error: %s", firstError.get()).isZero();
        assertThat(succeeded.get()).isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(50);

        long storedFrom = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, fromWallet);
        long storedTo = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, toWallet);
        assertThat(storedFrom).isZero();
        assertThat(storedTo).isEqualTo(50000);

        // Ledger agrees with stored balances (never negative: ledger balance ends at exactly 0)
        assertThat(ledgerBalance(fromWallet)).isZero();
        assertThat(ledgerBalance(toWallet)).isEqualTo(50000);

        // Total debits equal total credits across the whole ledger
        long totalDebits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'DEBIT'", Long.class);
        long totalCredits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'CREDIT'", Long.class);
        assertThat(totalDebits).isEqualTo(totalCredits);
    }

    private long createWallet() {
        int n = USER_COUNTER.incrementAndGet();
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, 'VERIFIED') RETURNING id",
                Long.class, "User " + n, "stampede" + n + "@test.com", String.format("8%09d", n));
        return jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, userId);
    }

    private long ledgerBalance(long accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE account_id = ?
                """, Long.class, accountId);
    }
    @BeforeEach
    void disableRiskRules() {
        // This test sends many transfers from one wallet on purpose; fraud rules are tested in FraudRulesTest
        jdbc.update("UPDATE risk_rules SET is_active = false");
    }

    @AfterEach
    void enableRiskRules() {
        jdbc.update("UPDATE risk_rules SET is_active = true");
    }
}