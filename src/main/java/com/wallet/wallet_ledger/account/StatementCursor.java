package com.wallet.wallet_ledger.account;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Position in a statement: the (created_at, id) of the last row the client saw.
 */
public record StatementCursor(OffsetDateTime createdAt, long id) {

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static StatementCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new StatementCursor(
                    OffsetDateTime.parse(raw.substring(0, separator)),
                    Long.parseLong(raw.substring(separator + 1))
            );
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "cursor is not valid");
        }
    }
}