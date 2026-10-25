package com.wallet.wallet_ledger.reconciliation;

import java.time.OffsetDateTime;
import java.util.List;

public record ReconciliationRunResponse(long id, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                        String status, int checksRun, int issuesFound,
                                        List<Issue> issues) {

    public record Issue(long id, String checkName, String entityType, Long entityId,
                        String expectedValue, String actualValue) {
    }
}