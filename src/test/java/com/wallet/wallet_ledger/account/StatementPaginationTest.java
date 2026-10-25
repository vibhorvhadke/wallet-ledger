package com.wallet.wallet_ledger.account;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import com.wallet.wallet_ledger.transfer.TransferService;
import com.wallet.wallet_ledger.transfer.TransferType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatementPaginationTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    @Autowired
    AccountService accountService;

    @Test
    void cursorPagesMatchOffsetPagesWithNoGapsOrDuplicates() {
        // A wallet with 45 entries: 1 top-up + 44 outgoing transfers
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Page User', 'page@test.com', '5000000001', 'VERIFIED') RETURNING id",
                Long.class
        );
        long otherUserId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES ('Page Other', 'page-other@test.com', '5000000002', 'VERIFIED') RETURNING id",
                Long.class
        );
        long wallet = jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, userId
        );
        long other = jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, otherUserId
        );
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);

        transferService.transfer(TransferType.TOPUP, cashIn, wallet, 100000, null);
        for (int i = 0; i < 44; i++) {
            transferService.transfer(TransferType.PEER_TRANSFER, wallet, other, 100, "t" + i);
        }

        // Walk all pages with the cursor
        List<Long> viaCursor = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            StatementResponse page = accountService.getStatement(wallet, 20, 0, cursor);
            page.entries().forEach(e -> viaCursor.add(e.entryId()));
            pageSizes.add(page.entries().size());
            cursor = page.nextCursor();
        } while (cursor != null);

        // Walk all pages with OFFSET
        List<Long> viaOffset = new ArrayList<>();
        for (int offset = 0; offset < 45; offset += 20) {
            accountService.getStatement(wallet, 20, offset, null).entries()
                    .forEach(e -> viaOffset.add(e.entryId()));
        }

        assertThat(pageSizes).containsExactly(20, 20, 5);   // last page has no next_cursor
        assertThat(viaCursor).hasSize(45).doesNotHaveDuplicates();
        assertThat(viaCursor).isEqualTo(viaOffset);         // same rows, same order
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