package com.wallet.wallet_ledger.risk;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import com.wallet.wallet_ledger.transfer.TransferResponse;
import com.wallet.wallet_ledger.transfer.TransferService;
import com.wallet.wallet_ledger.transfer.TransferType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FraudRulesTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final AtomicInteger USER_COUNTER = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    long sender;
    long receiverA;
    long receiverB;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE risk_rules SET is_active = true");
        sender = createWallet();
        receiverA = createWallet();
        receiverB = createWallet();
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);
        transferService.transfer(TransferType.TOPUP, cashIn, sender, 10_000_000, null); // Rs 1,00,000
    }

    @Test
    void sixthTransferInTenMinutesIsHeldAndMovesNoMoney() {
        for (int i = 0; i < 5; i++) {
            assertThat(send(receiverA, 1000).status()).isEqualTo("COMPLETED");
        }

        TransferResponse sixth = send(receiverA, 1000);

        assertThat(sixth.status()).isEqualTo("HELD");
        assertThat(flags(sixth.id())).containsExactly("VELOCITY:HOLD");
        assertThat(entryCount(sixth.id())).isZero();                    // no money moved
        assertThat(balance(sender)).isEqualTo(10_000_000 - 5 * 1000);   // only the 5 completed ones
        assertThat(firstStatus(sixth.id())).isEqualTo("HELD");
    }

    @Test
    void amountOverFiveTimesTheAverageIsHeld() {
        for (int i = 0; i < 3; i++) {
            send(receiverA, 10_000); // average Rs 100
        }

        TransferResponse spike = send(receiverA, 60_000); // Rs 600 > 5 x Rs 100

        assertThat(spike.status()).isEqualTo("HELD");
        assertThat(flags(spike.id())).containsExactly("AMOUNT_SPIKE:HOLD");
        assertThat(entryCount(spike.id())).isZero();
    }

    @Test
    void firstLargeTransferToNewPayeeIsFlaggedButAllowed() {
        TransferResponse first = send(receiverB, 1_500_000); // Rs 15,000 to a new payee

        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(flags(first.id())).containsExactly("NEW_PAYEE_LARGE_AMOUNT:FLAG_ALLOW");
        assertThat(entryCount(first.id())).isEqualTo(2);   // money moved

        TransferResponse second = send(receiverB, 1_500_000); // same payee again: no longer new
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(flags(second.id())).isEmpty();
    }

    // ---------- helpers ----------

    private TransferResponse send(long to, long amount) {
        return transferService.transfer(TransferType.PEER_TRANSFER, sender, to, amount, null);
    }

    private List<String> flags(long transferId) {
        return jdbc.queryForList("""
                SELECT r.code || ':' || f.action_taken
                FROM risk_flags f JOIN risk_rules r ON r.id = f.risk_rule_id
                WHERE f.transfer_id = ? ORDER BY r.id
                """, String.class, transferId);
    }

    private long entryCount(long transferId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?", Long.class, transferId);
    }

    private long balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private String firstStatus(long transferId) {
        return jdbc.queryForObject("""
                SELECT to_status FROM transfer_status_history
                WHERE transfer_id = ? AND from_status IS NULL
                """, String.class, transferId);
    }

    private long createWallet() {
        int n = USER_COUNTER.incrementAndGet();
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, 'VERIFIED') RETURNING id",
                Long.class, "Fraud User " + n, "fraud" + n + "@test.com", String.format("4%09d", n));
        return jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, userId);
    }
}