package com.wallet.wallet_ledger.idempotency;

import org.springframework.http.HttpStatus;

/** What the real work produced: status, response body, and the transfer it created (if any). */
public record ActionResult(HttpStatus status, Object body, Long transferId) {
}