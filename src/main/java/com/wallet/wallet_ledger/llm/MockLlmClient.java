package com.wallet.wallet_ledger.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default provider: fixed JSON answers, no network, no API key.
 * Each task's system prompt starts with "TASK: <name>" so the mock knows what shape to return.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (systemPrompt.startsWith("TASK: RISK_EXPLANATION")) {
            String label = userPrompt.contains("\"NEW_PAYEE_LARGE_AMOUNT\"") ? "likely_ok" : "review";
            return """
                    {"explanation": "This transfer is unusual compared with the wallet's recent activity, which is why the rule fired. A reviewer should confirm it with the customer before taking further action.", "label": "%s"}
                    """.formatted(label);
        }
        return "{}";
    }
}