package com.wallet.wallet_ledger.risk;

import java.util.List;

public record RiskQueueResponse(List<RiskQueueItem> held, List<RiskQueueItem> flagged) {
}