package com.wallet.wallet_ledger.account;

import java.time.OffsetDateTime;

public record WalletResponse(long id, long userId, String name, String currency,
                             long balance, OffsetDateTime createdAt) {
}