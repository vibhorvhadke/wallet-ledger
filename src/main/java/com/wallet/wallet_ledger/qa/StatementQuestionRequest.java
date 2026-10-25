package com.wallet.wallet_ledger.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StatementQuestionRequest(@NotBlank @Size(max = 300) String question) {
}