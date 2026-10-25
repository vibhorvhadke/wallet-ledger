package com.wallet.wallet_ledger.llm;

public class LlmException extends RuntimeException {

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmException(String message) {
        super(message);
    }
}