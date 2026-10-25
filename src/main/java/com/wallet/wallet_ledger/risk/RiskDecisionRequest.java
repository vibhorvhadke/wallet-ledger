package com.wallet.wallet_ledger.risk;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RiskDecisionRequest(
        @NotNull @Pattern(regexp = "RELEASE|REJECT", message = "must be RELEASE or REJECT") String decision,
        @Size(max = 255) String reason) {
}