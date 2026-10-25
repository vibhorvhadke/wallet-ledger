package com.wallet.wallet_ledger.user;

import java.time.OffsetDateTime;

public record UserResponse(long id, String name, String email, String phone,
                           String kycStatus, OffsetDateTime createdAt) {
}