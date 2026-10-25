package com.wallet.wallet_ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PeerTransferRequest(
        @NotNull @Positive Long fromWalletId,
        @NotNull @Positive Long toWalletId,
        @NotNull @Positive Long amount,
        @Size(max = 255) String note) {
}