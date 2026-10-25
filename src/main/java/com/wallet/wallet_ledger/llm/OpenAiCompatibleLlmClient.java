package com.wallet.wallet_ledger.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Any OpenAI-compatible chat API (default: Groq's free tier).
 * Enabled with llm.provider=openai-compatible; set LLM_API_KEY, optionally LLM_BASE_URL and LLM_MODEL.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(@Value("${llm.base-url}") String baseUrl,
                                     @Value("${llm.api-key}") String apiKey,
                                     @Value("${llm.model}") String model,
                                     ObjectMapper objectMapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("llm.provider=openai-compatible requires the LLM_API_KEY environment variable");
        }
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,                                 // most likely answer, as repeatable as possible
                "response_format", Map.of("type", "json_object"), // ask the provider for valid JSON
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt))));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("LLM call failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            ChatResponse parsed = objectMapper.readValue(response.body(), ChatResponse.class);
            return parsed.choices().get(0).message().content();
        } catch (IOException e) {
            throw new LlmException("LLM call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM call interrupted", e);
        }
    }

    private record ChatResponse(List<Choice> choices) {
    }

    private record Choice(Message message) {
    }

    private record Message(String role, String content) {
    }
}