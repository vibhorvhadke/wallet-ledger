package com.wallet.wallet_ledger.risk;

public record RiskDecisionCommand(long transferId, String decision, String reason) {
}