package com.wallet.wallet_ledger.account;

public record BalanceResponse(long walletId, long balance, String currency) {
}