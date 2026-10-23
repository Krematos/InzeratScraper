package hanzner.inzeratscraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Klient pro komunikaci s LLM API (Google Gemini nebo OpenAI kompatibilní).
 * Podporuje automatický fallback na záložní modely při HTTP 503 a timeoutu.
 */
@Slf4j
@Component
public class LlmClient {

    private static final String KEY_CONTENT = "content";
    private static final Pattern MODEL_URL_PATTERN = Pattern.compile("/models/([^:/?]+)");

    static final List<String> FALLBACK_MODELS = List.of(
            "gemini-3.8-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash"
    );

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String llmApiKey;
    private final String llmApiUrl;

    public LlmClient(ObjectMapper objectMapper) {
        this(objectMapper, "", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent");
    }

    @Autowired
    public LlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api.key:}") String llmApiKey,
            @Value("${llm.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent}") String llmApiUrl) {
        this(objectMapper, llmApiKey, llmApiUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build());
    }

    public LlmClient(
            ObjectMapper objectMapper,
            String llmApiKey,
            String llmApiUrl,
            HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.llmApiKey = llmApiKey;
        this.llmApiUrl = llmApiUrl;
    }

    /**
     * Fail-fast kontrola konfigurace LLM klíče před spuštěním operací.
     */
    public void assertConfigured() {
        if (llmApiKey == null || llmApiKey.isBlank() || "replace-with-api-key".equals(llmApiKey)) {
            throw new IllegalStateException("LLM API klíč (llm.api.key / LLM_API_KEY) není nastaven v konfiguraci.");
        }
    }

    /**
     * Vrátí seznam URL pro pokusy o volání LLM: primární URL následované fallback modely.
     */
    public List<String> getCandidateUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(llmApiUrl);

        Matcher matcher = MODEL_URL_PATTERN.matcher(llmApiUrl);
        if (matcher.find()) {
            String prefix = llmApiUrl.substring(0, matcher.start(1));
            String suffix = llmApiUrl.substring(matcher.end(1));
            for (String fallbackModel : FALLBACK_MODELS) {
                String fallbackUrl = prefix + fallbackModel + suffix;
                if (!urls.contains(fallbackUrl)) {
                    urls.add(fallbackUrl);
                }
            }
        }
        return urls;
    }

    /**
     * Zavolá LLM API s daným promptem a vrátí neočištěný JSON text.
     * Při HTTP 503 nebo timeoutu přepíná na záložní modely (3.8 flash lite, 3.5 flash lite, 3.6 flash).
     */
    public String callLlmApi(String prompt) throws IOException, InterruptedException {
        assertConfigured();

        List<String> candidateUrls = getCandidateUrls();
        int maxRetries429 = 3;
        IOException lastException = null;

        for (int i = 0; i < candidateUrls.size(); i++) {
            String currentUrl = candidateUrls.get(i);
            boolean isGemini = currentUrl.contains("googleapis.com") || currentUrl.contains("gemini");
            String requestBodyJson = serializeRequestBody(prompt, isGemini);
            boolean hasNextFallback = (i + 1 < candidateUrls.size());
            int attempt429 = 0;

            while (true) {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(currentUrl))
                        .timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson));

                if (isGemini) {
                    reqBuilder.header("x-goog-api-key", llmApiKey);
                } else {
                    reqBuilder.header("Authorization", "Bearer " + llmApiKey);
                }

                HttpResponse<String> response;
                try {
                    response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                } catch (HttpTimeoutException | SocketTimeoutException e) {
                    if (hasNextFallback) {
                        log.warn("LLM API timeout pro model na URL: {}. Přepínám na další model v pořadí fallbacku: {}",
                                currentUrl, candidateUrls.get(i + 1));
                        lastException = new IOException("LLM API timeout pro URL " + currentUrl + ": " + e.getMessage(), e);
                        break;
                    } else {
                        throw new IOException("Chyba při volání LLM API (timeout): " + e.getMessage(), e);
                    }
                }

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return extractTextFromLlmResponse(response.body(), isGemini);
                }

                if (status == 429 && attempt429 < maxRetries429) {
                    attempt429++;
                    long backoffMs = 2000L * attempt429;
                    log.warn("LLM API rate-limit (HTTP 429) pro {}, pokus {}/{}. Čekám {} ms před opakováním...",
                            currentUrl, attempt429, maxRetries429, backoffMs);
                    Thread.sleep(backoffMs);
                    continue;
                }

                if ((status == 503 || status == 504) && hasNextFallback) {
                    log.warn("LLM API vrátilo HTTP {} pro model na URL: {}. Přepínám na další model v pořadí fallbacku: {}",
                            status, currentUrl, candidateUrls.get(i + 1));
                    lastException = new IOException("Chyba při volání LLM API (HTTP " + status + "): " + response.body());
                    break;
                }

                throw new IOException("Chyba při volání LLM API (HTTP " + status + "): " + response.body());
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("LLM API volání selhalo pro všechny modely.");
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
