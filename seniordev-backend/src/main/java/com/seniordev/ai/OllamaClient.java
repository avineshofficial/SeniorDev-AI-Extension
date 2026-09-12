package com.seniordev.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client for the local Ollama API.
 * Uses /api/chat with role-based messages and structured JSON output
 * via the `format` parameter for fix generation.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Value("${ollama.model:qwen2.5-coder:3b}")
    private String model;

    @Value("${ollama.num-ctx:4096}")
    private int numCtx;

    public OllamaClient(@Value("${ollama.url:http://localhost:11434}") String ollamaUrl) {
        this.baseUrl = ollamaUrl;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMinutes(15));
        this.restClient = RestClient.builder().baseUrl(ollamaUrl).requestFactory(requestFactory).build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generates a structured FixResult for a code issue.
     * Uses the `format` parameter to enforce JSON schema on the response.
     */
    public FixResult generateFix(String systemPrompt, String userPrompt) {
        Map<String, Object> formatSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "explanation", Map.of("type", "string"),
                "fixCode", Map.of("type", "string"),
                "affectedFiles", Map.of("type", "array", "items", Map.of("type", "string")),
                "confidence", Map.of("type", "string")
            ),
            "required", List.of("explanation", "fixCode", "affectedFiles", "confidence")
        );

        Map<String, Object> request = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "stream", false,
            "format", formatSchema,
            "options", Map.of(
                "temperature", 0.0,
                "top_p", 0.1,
                "num_ctx", 2048,
                "num_predict", 300
            )
        );

        OllamaChatResponse response = restClient.post()
            .uri("/api/chat")
            .body(request)
            .retrieve()
            .body(OllamaChatResponse.class);

        if (response != null && response.message() != null && response.message().content() != null) {
            try {
                return objectMapper.readValue(response.message().content(), FixResult.class);
            } catch (Exception e) {
                log.error("Failed to parse FixResult from Ollama response: {}", e.getMessage());
            }
        }
        return null;
    }

    public FixResult generateWholeFileFix(String systemPrompt, String userPrompt) {
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("fixCode", java.util.Map.of("type", "string"));
        properties.put("explanation", java.util.Map.of("type", "string"));

        Map<String, Object> formatSchema = Map.of(
            "type", "object",
            "properties", properties,
            "required", List.of("fixCode", "explanation")
        );

        Map<String, Object> request = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "stream", false,
            "format", formatSchema,
            "options", Map.of(
                "temperature", 0.0,
                "top_p", 0.1,
                "num_ctx", 4096,
                "num_predict", 2048
            )
        );

        try {
            OllamaChatResponse response = restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(OllamaChatResponse.class);

            if (response != null && response.message() != null && response.message().content() != null) {
                String content = response.message().content().trim();
                try {
                    return objectMapper.readValue(content, FixResult.class);
                } catch (Exception parseErr) {
                    log.warn("JSON parsing failed, attempting fallback extraction: {}", parseErr.getMessage());
                    if (content.contains("\"fixCode\"")) {
                        int start = content.indexOf("\"fixCode\"");
                        int colon = content.indexOf(":", start);
                        int quoteStart = content.indexOf("\"", colon + 1);
                        if (quoteStart != -1) {
                            StringBuilder sb = new StringBuilder();
                            boolean escape = false;
                            for (int idx = quoteStart + 1; idx < content.length(); idx++) {
                                char c = content.charAt(idx);
                                if (escape) {
                                    if (c == 'n') sb.append('\n');
                                    else if (c == 't') sb.append('\t');
                                    else if (c == '\"') sb.append('\"');
                                    else if (c == '\\') sb.append('\\');
                                    else sb.append(c);
                                    escape = false;
                                } else if (c == '\\') {
                                    escape = true;
                                } else if (c == '\"') {
                                    break;
                                } else {
                                    sb.append(c);
                                }
                            }
                            String extractedCode = sb.toString();
                            if (!extractedCode.isBlank()) {
                                return new FixResult("AI fix applied cleanly", extractedCode, List.of(), "HIGH", List.of());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to generate whole file fix: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Generates a free-form chat response (plain text, no structured output).
     * Used for developer Q&A where structured JSON is not needed.
     */
    public String generateChatResponse(String systemPrompt, String userPrompt) {
        Map<String, Object> request = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "stream", false,
            "options", Map.of(
                "temperature", 0.2,
                "num_ctx", 2048,
                "num_predict", 350
            )
        );

        OllamaChatResponse response = restClient.post()
            .uri("/api/chat")
            .body(request)
            .retrieve()
            .body(OllamaChatResponse.class);

        if (response != null && response.message() != null && response.message().content() != null) {
            return response.message().content();
        }
        return "No response from AI model.";
    }

    /**
     * Checks whether Ollama is reachable and the configured model is present.
     *
     * @return true if Ollama is running and the model is available
     */
    public boolean isAvailable() {
        try {
            String response = restClient.get()
                .uri("/api/tags")
                .retrieve()
                .body(String.class);

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode models = root.get("models");
                if (models != null && models.isArray()) {
                    for (JsonNode modelNode : models) {
                        String name = modelNode.has("name") ? modelNode.get("name").asText() : "";
                        if (name.startsWith(model.split(":")[0])) {
                            return true;
                        }
                    }
                }
                log.warn("Ollama is running but model '{}' not found in available models", model);
                return false;
            }
        } catch (Exception e) {
            log.debug("Ollama availability check failed: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Returns the configured model name.
     */
    public String getModelName() {
        return model;
    }

    record OllamaChatResponse(String model, String created_at, Message message, boolean done) {}
    record Message(String role, String content) {}
}
