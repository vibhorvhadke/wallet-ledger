package com.wallet.wallet_ledger.transfer;

import jakarta.validation.constraints.Size;

public record ReverseRequest(@Size(max = 255) String reason) {
}