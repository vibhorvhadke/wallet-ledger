package com.wallet.wallet_ledger.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The exact status code and JSON body to send back - identical for the first call and every replay. */
public record IdempotentResponse(int status, String body) {

    public ResponseEntity<String> toResponseEntity() {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}