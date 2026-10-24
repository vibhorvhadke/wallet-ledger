package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UnsafeTransferLabTest {

    static {
        // Same reason as in main(): avoid the Asia/Calcutta time zone error
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UnsafeTransferService unsafeTransferService;

    @Test
    void parallelTransfersBreakTheBalance() throws Exception {
        // --- Setup: two users, one wallet each ---
        long ashaId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Asha', 'asha@lab.test', '9000000001', 'VERIFIED') RETURNING id",
                Long.class);
        long rahulId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Rahul', 'rahul@lab.test', '9000000002', 'VERIFIED') RETURNING id",
                Long.class);
        long fromWallet = jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, ashaId);
        long toWallet = jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, rahulId);

        // --- Top up Rs 500 (50000 paise) properly: transfer + 2 entries + balances ---
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);
        long topupId = jdbc.queryForObject(
                "INSERT INTO transfers (type, status, amount) VALUES ('TOPUP', 'COMPLETED', 50000) RETURNING id", Long.class);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'DEBIT', 50000)", topupId, cashIn);
        jdbc.update("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'CREDIT', 50000)", topupId, fromWallet);
        jdbc.update("UPDATE accounts SET balance = balance - 50000 WHERE id = ?", cashIn);
        jdbc.update("UPDATE accounts SET balance = balance + 50000 WHERE id = ?", fromWallet);

        // --- Fire 100 parallel Rs 10 (1000 paise) transfers ---
        int requests = 100;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < requests; i++) {
            pool.submit(() -> {
                startGate.await(); // all threads start at the same moment
                try {
                    unsafeTransferService.transfer(fromWallet, toWallet, 1000);
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
                return null;
            });
        }
        startGate.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        // --- Record what happened ---
        long storedFrom = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, fromWallet);
        long storedTo = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, toWallet);
        String ledgerBalanceSql = """
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE account_id = ?
                """;
        long ledgerFrom = jdbc.queryForObject(ledgerBalanceSql, Long.class, fromWallet);
        long ledgerTo = jdbc.queryForObject(ledgerBalanceSql, Long.class, toWallet);

        System.out.println("=========== UNSAFE TRANSFER RESULT ===========");
        System.out.println("Succeeded:                 " + succeeded.get() + "  (correct answer: 50)");
        System.out.println("Rejected (no funds):       " + rejected.get() + "  (correct answer: 50)");
        System.out.println("Other errors:              " + failed.get());
        System.out.println("Sender stored balance:     " + storedFrom + "  (correct answer: 0)");
        System.out.println("Sender ledger balance:     " + ledgerFrom + "  (correct answer: 0)");
        System.out.println("Receiver stored balance:   " + storedTo + "  (correct answer: 50000)");
        System.out.println("Receiver ledger balance:   " + ledgerTo + "  (correct answer: 50000)");
        System.out.println("==============================================");
    }
}