package com.wallet.wallet_ledger.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Default provider: fixed JSON answers, no network, no API key.
 * Each task's system prompt starts with "TASK: <name>" so the mock knows what shape to return.
 * Statement questions are answered from mock-llm/statement-replies.json if it is on the classpath (tests).
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;
    private final Map<String, String> statementReplies = new HashMap<>();

    @SuppressWarnings("unchecked")
    public MockLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ClassPathResource file = new ClassPathResource("mock-llm/statement-replies.json");
        if (file.exists()) {
            try (InputStream in = file.getInputStream()) {
                Map<String, Object> replies = objectMapper.readValue(in, Map.class);
                replies.forEach((question, reply) ->
                        statementReplies.put(normalize(question), objectMapper.writeValueAsString(reply)));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + file.getPath(), e);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userPrompt) {
        if (systemPrompt.startsWith("TASK: RISK_EXPLANATION")) {
            String label = userPrompt.contains("\"NEW_PAYEE_LARGE_AMOUNT\"") ? "likely_ok" : "review";
            return """
                    {"explanation": "This transfer is unusual compared with the wallet's recent activity, which is why the rule fired. A reviewer should confirm it with the customer before taking further action.", "label": "%s"}
                    """.formatted(label);
        }
        if (systemPrompt.startsWith("TASK: STATEMENT_QUESTION")) {
            Map<String, Object> input = objectMapper.readValue(userPrompt, Map.class);
            return statementReplies.getOrDefault(normalize(String.valueOf(input.get("question"))), "{\"type\": 0}");
        }
        return "{}";
    }

    private static String normalize(String question) {
        return question.strip().toLowerCase(Locale.ROOT);
    }
}