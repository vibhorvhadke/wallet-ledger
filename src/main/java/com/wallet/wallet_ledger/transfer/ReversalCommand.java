package com.wallet.wallet_ledger.transfer;

/**
 * What gets hashed for idempotency. Includes the transfer id from the URL, so reusing a key
 * for a DIFFERENT transfer is rejected with 422 instead of replaying the wrong response.
 */
public record ReversalCommand(long transferId, String reason) {
}