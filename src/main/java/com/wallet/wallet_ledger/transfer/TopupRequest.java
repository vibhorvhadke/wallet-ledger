package com.wallet.wallet_ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TopupRequest(
        @NotNull @Positive Long walletId,
        @NotNull @Positive Long amount,
        @Size(max = 255) String note) {
}