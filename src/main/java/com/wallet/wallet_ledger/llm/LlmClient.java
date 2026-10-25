package com.wallet.wallet_ledger.llm;

/** The one small interface every LLM provider implements. */
public interface LlmClient {

    /** Sends a system prompt and a user prompt; returns the model's text reply. */
    String complete(String systemPrompt, String userPrompt);
}