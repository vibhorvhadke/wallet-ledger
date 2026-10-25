package com.wallet.wallet_ledger.risk;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import com.wallet.wallet_ledger.transfer.TransferResponse;
import com.wallet.wallet_ledger.transfer.TransferService;
import com.wallet.wallet_ledger.transfer.TransferType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RiskExplanationTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    @Autowired
    RiskExplanationService riskExplanationService;

    @Test
    void heldTransferGetsAnExplanationAndLabelFromTheMock() {
        jdbc.update("UPDATE risk_rules SET is_active = true");
        long sender = createWallet("explain-sender@test.com", "3000000001");
        long receiver = createWallet("explain-receiver@test.com", "3000000002");
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);
        transferService.transfer(TransferType.TOPUP, cashIn, sender, 1_000_000, null);
        for (int i = 0; i < 5; i++) {
            transferService.transfer(TransferType.PEER_TRANSFER, sender, receiver, 1000, null);
        }
        TransferResponse held = transferService.transfer(TransferType.PEER_TRANSFER, sender, receiver, 1000, null);
        assertThat(held.status()).isEqualTo("HELD");

        riskExplanationService.explainPendingFlags();

        Map<String, Object> flag = jdbc.queryForMap(
                "SELECT llm_label, llm_explanation, explained_at FROM risk_flags WHERE transfer_id = ?", held.id());
        assertThat(flag.get("llm_label")).isEqualTo("review");
        assertThat((String) flag.get("llm_explanation")).isNotBlank();
        assertThat(flag.get("explained_at")).isNotNull();

        // A second run doesn't touch flags that are already explained
        Object firstExplainedAt = flag.get("explained_at");
        riskExplanationService.explainPendingFlags();
        assertThat(jdbc.queryForObject("SELECT explained_at FROM risk_flags WHERE transfer_id = ?",
                Object.class, held.id())).isEqualTo(firstExplainedAt);
    }

    @Test
    void onlyValidJsonWithAKnownLabelIsAccepted() {
        assertThat(riskExplanationService.parse(
                "```json\n{\"explanation\": \"Two sentences. Here.\", \"label\": \"likely_fraud\"}\n```"))
                .isPresent();
        assertThat(riskExplanationService.parse(
                "{\"explanation\": \"Looks fine.\", \"label\": \"approve\"}")).isEmpty();      // unknown label
        assertThat(riskExplanationService.parse(
                "{\"explanation\": \"\", \"label\": \"review\"}")).isEmpty();                  // empty explanation
        assertThat(riskExplanationService.parse("Sure! I think this is fraud.")).isEmpty(); // not JSON
    }

    private long createWallet(String email, String phone) {
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Explain User', ?, ?, 'VERIFIED') RETURNING id",
                Long.class, email, phone);
        return jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, userId);
    }
}