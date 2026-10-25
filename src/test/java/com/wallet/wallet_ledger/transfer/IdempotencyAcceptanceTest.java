package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IdempotencyAcceptanceTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final AtomicInteger USER_COUNTER = new AtomicInteger();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    long fromWallet;
    long toWallet;

    @BeforeEach
    void setUp() {
        fromWallet = createWallet();
        toWallet = createWallet();
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'CASH_IN'", Long.class);
        transferService.transfer(TransferType.TOPUP, cashIn, fromWallet, 50000, null); // Rs 500
    }

    @Test
    void sameRequestFiveTimesCreatesOneTransfer() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = transferBody(1000);

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(postTransfer(key, body));
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(responses.stream().map(HttpResponse::body).distinct().toList()).hasSize(1);
        assertThat(outgoingTransferCount()).isEqualTo(1);
        assertThat(balance(fromWallet)).isEqualTo(49000);
    }

    @Test
    void sameKeyTenTimesInParallelCreatesOneTransfer() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = transferBody(1000);

        int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return postTransfer(key, body);
            }));
        }
        startGate.countDown();

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(responses.stream().map(HttpResponse::body).distinct().toList()).hasSize(1);
        assertThat(outgoingTransferCount()).isEqualTo(1);
        assertThat(balance(fromWallet)).isEqualTo(49000);
    }

    @Test
    void sameKeyWithDifferentAmountIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = postTransfer(key, transferBody(1000));
        HttpResponse<String> second = postTransfer(key, transferBody(2000));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(422);
        assertThat(second.body()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(outgoingTransferCount()).isEqualTo(1);
        assertThat(balance(fromWallet)).isEqualTo(49000);
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        HttpResponse<String> response = postTransfer(null, transferBody(1000));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("MISSING_HEADER");
        assertThat(outgoingTransferCount()).isZero();
        assertThat(balance(fromWallet)).isEqualTo(50000);
    }

    // ---------- helpers ----------

    private HttpResponse<String> postTransfer(String idempotencyKey, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transfers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String transferBody(long amount) {
        return String.format("{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":%d}",
                fromWallet, toWallet, amount);
    }

    /** Number of transfers that took money OUT of the sender (the top-up is a credit, so it's not counted). */
    private long outgoingTransferCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ? AND direction = 'DEBIT'",
                Long.class, fromWallet);
    }

    private long balance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private long createWallet() {
        int n = USER_COUNTER.incrementAndGet();
        long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email, phone, kyc_status) VALUES (?, ?, ?, 'VERIFIED') RETURNING id",
                Long.class, "Idem User " + n, "idem" + n + "@test.com", String.format("7%09d", n));
        return jdbc.queryForObject(
                "INSERT INTO accounts (user_id, account_type, name) VALUES (?, 'USER_WALLET', 'Main') RETURNING id",
                Long.class, userId);
    }
}