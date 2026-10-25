package com.wallet.wallet_ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(@NotBlank @Size(max = 50) String name) {
}