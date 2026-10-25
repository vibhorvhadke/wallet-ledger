package com.wallet.wallet_ledger.reconciliation;

public record ReconciliationIssue(String checkName, String entityType, Long entityId,
                                  String expected, String actual) {
}