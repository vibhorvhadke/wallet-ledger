package com.wallet.wallet_ledger.account;

import java.time.OffsetDateTime;

public record StatementEntry(long entryId, long transferId, String transferType, String direction,
                             long amount, String note, OffsetDateTime createdAt) {
}