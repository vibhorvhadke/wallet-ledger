package com.wallet.wallet_ledger.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{10,14}", message = "must be 10 to 14 digits") String phone,
        @Pattern(regexp = "PENDING|VERIFIED|REJECTED") String kycStatus) {
}