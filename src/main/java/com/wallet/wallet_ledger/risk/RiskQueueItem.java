package com.wallet.wallet_ledger.risk;

import java.time.OffsetDateTime;
import java.util.List;

public record RiskQueueItem(long transferId, String type, String status, long amount, String note,
                            long fromAccountId, long toAccountId, OffsetDateTime createdAt,
                            List<String> rules) {
}