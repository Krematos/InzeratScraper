package hanzner.inzeratscraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Klient pro komunikaci s LLM API (Google Gemini nebo OpenAI kompatibilní).
 */
@Slf4j
@Component
public class LlmClient {

    private static final String KEY_CONTENT = "content";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String llmApiKey;
    private final String llmApiUrl;

    public LlmClient(ObjectMapper objectMapper) {
        this(objectMapper, "", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
    }

    @Autowired
    public LlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api.key:}") String llmApiKey,
            @Value("${llm.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}") String llmApiUrl) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.llmApiKey = llmApiKey;
        this.llmApiUrl = llmApiUrl;
    }

    /**
     * Zavolá LLM API s daným promptem a vrátí neočištěný JSON text.
     */
    public String callLlmApi(String prompt) throws IOException, InterruptedException {
        if (llmApiKey == null || llmApiKey.isBlank() || "replace-with-api-key".equals(llmApiKey)) {
            throw new IllegalStateException("LLM API klíč (llm.api.key / LLM_API_KEY) není nastaven v konfiguraci.");
        }

        boolean isGemini = llmApiUrl.contains("googleapis.com") || llmApiUrl.contains("gemini");
        String requestBodyJson = serializeRequestBody(prompt, isGemini);

        int maxRetries = 3;
        int attempt = 0;

        while (true) {
            attempt++;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(llmApiUrl))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson));

            if (isGemini) {
                reqBuilder.header("x-goog-api-key", llmApiKey);
            } else {
                reqBuilder.header("Authorization", "Bearer " + llmApiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return extractTextFromLlmResponse(response.body(), isGemini);
            }

            if (status == 429 && attempt <= maxRetries) {
                long backoffMs = 2000L * attempt;
                log.warn("LLM API rate-limit (HTTP 429), pokus {}/{}. Čekám {} ms před opakováním...", attempt, maxRetries, backoffMs);
                Thread.sleep(backoffMs);
                continue;
            }

            throw new IOException("Chyba při volání LLM API (HTTP " + status + "): " + response.body());
        }
    }

    private String serializeRequestBody(String prompt, boolean isGemini) {
        if (isGemini) {
            Map<String, Object> geminiPayload = new LinkedHashMap<>();
            geminiPayload.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));
            geminiPayload.put("generationConfig", Map.of(
                    "responseMimeType", "application/json",
                    "temperature", 0.1
            ));
            return objectMapper.writeValueAsString(geminiPayload);
        } else {
            Map<String, Object> openAiPayload = new LinkedHashMap<>();
            openAiPayload.put("model", "gpt-4o-mini");
            openAiPayload.put("messages", List.of(
                    Map.of("role", "user", KEY_CONTENT, prompt)
            ));
            openAiPayload.put("response_format", Map.of("type", "json_object"));
            openAiPayload.put("temperature", 0.1);
            return objectMapper.writeValueAsString(openAiPayload);
        }
    }

    private String extractTextFromLlmResponse(String responseBody, boolean isGemini) {
        JsonNode root = objectMapper.readTree(responseBody);
        if (isGemini) {
            JsonNode textNode = root.path("candidates").path(0).path(KEY_CONTENT).path("parts").path(0).path("text");
            if (!textNode.isMissingNode() && !textNode.isNull()) {
                return textNode.asString();
            }
        } else {
            JsonNode contentNode = root.path("choices").path(0).path("message").path(KEY_CONTENT);
            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                return contentNode.asString();
            }
        }
        return responseBody;
    }

    /**
     * Odstraní markdown bloky ```json ... ``` pokud je model vrátil.
     */
    public String stripMarkdownCodeFences(String rawResponse) {
        if (rawResponse == null) return "{}";
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```")) {
            String withoutOpeningFence = trimmed.substring(3).trim();
            if (withoutOpeningFence.toLowerCase(Locale.ROOT).startsWith("json")) {
                withoutOpeningFence = withoutOpeningFence.substring(4).trim();
            }
            trimmed = withoutOpeningFence;
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }
}
