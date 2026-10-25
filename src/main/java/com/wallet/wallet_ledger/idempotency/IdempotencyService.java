package com.wallet.wallet_ledger.idempotency;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the action at most once per (userId, key).
     * The key row, the action's writes, and the saved response commit together in ONE transaction.
     * userId is null for requests with no user yet (e.g. POST /users, admin endpoints).
     */
    @Transactional
    public IdempotentResponse execute(Long userId, UUID key, Object request, Supplier<ActionResult> action) {
        String requestHash = sha256(objectMapper.writeValueAsString(request));

        // 1. Claim the key FIRST. If another transaction holds the same key uncommitted, this waits.
        List<Long> inserted = jdbc.query("""
                INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT ON CONSTRAINT uq_idempotency_keys_user_key DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getLong("id"), userId, key, requestHash);

        if (inserted.isEmpty()) {
            return replayOrReject(userId, key, requestHash);
        }
        long keyRowId = inserted.get(0);

        // 2. Do the real work in the SAME transaction (e.g. the transfer)
        ActionResult result = action.get();

        // 3. Save the response on the key row; read it back so the first response
        //    is byte-for-byte identical to every replay
        String savedBody = jdbc.queryForObject("""
                UPDATE idempotency_keys
                SET status = 'COMPLETED', response_code = ?, response_body = ?::jsonb, transfer_id = ?
                WHERE id = ?
                RETURNING response_body::text
                """, String.class,
                result.status().value(), objectMapper.writeValueAsString(result.body()),
                result.transferId(), keyRowId);

        return new IdempotentResponse(result.status().value(), savedBody);
    }

    private IdempotentResponse replayOrReject(Long userId, UUID key, String requestHash) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT request_hash, status, response_code, response_body::text AS response_body
                FROM idempotency_keys
                WHERE user_id IS NOT DISTINCT FROM ? AND idempotency_key = ?
                """, userId, key);

        if (!requestHash.equals(row.get("request_hash"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request body");
        }
        if ("IN_PROGRESS".equals(row.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
        return new IdempotentResponse((Integer) row.get("response_code"), (String) row.get("response_body"));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}