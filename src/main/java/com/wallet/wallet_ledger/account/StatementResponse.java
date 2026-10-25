package com.wallet.wallet_ledger.account;

import java.util.List;

public record StatementResponse(long walletId, int limit, int offset,
                                List<StatementEntry> entries, String nextCursor) {
}