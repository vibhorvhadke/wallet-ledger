package com.wallet.wallet_ledger.transfer;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(long accountId) {
        super("Insufficient funds in account " + accountId);
    }
}