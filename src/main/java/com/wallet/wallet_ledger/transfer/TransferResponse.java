package com.wallet.wallet_ledger.transfer;

import java.time.OffsetDateTime;

public record TransferResponse(long id, String type, String status, long amount, String note,
                               long fromAccountId, long toAccountId, OffsetDateTime createdAt) {
}