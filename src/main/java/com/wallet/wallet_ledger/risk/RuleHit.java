package com.wallet.wallet_ledger.risk;

/** A fraud rule that fired for a transfer. action is HOLD or FLAG_ALLOW. */
public record RuleHit(long ruleId, String code, String action) {
}