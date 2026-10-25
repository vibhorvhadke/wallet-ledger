package com.wallet.wallet_ledger.transfer;

import java.time.OffsetDateTime;
import java.util.List;

public record TransferDetailsResponse(long id, String type, String status, long amount, String note,
                                      Long reversalOfTransferId, OffsetDateTime createdAt,
                                      List<Entry> entries, List<StatusChange> statusHistory) {

    public record Entry(long id, long accountId, String direction, long amount, OffsetDateTime createdAt) {
    }

    public record StatusChange(String fromStatus, String toStatus, String changedBy, OffsetDateTime changedAt) {
    }
}