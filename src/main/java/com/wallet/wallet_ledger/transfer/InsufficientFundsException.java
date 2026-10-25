package com.wallet.wallet_ledger.transfer;

import com.wallet.wallet_ledger.common.ApiException;
import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(long accountId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Insufficient funds in account " + accountId);
    }
}