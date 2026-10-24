package hanzner.inzeratscraper.service;

import lombok.Getter;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Klient pro komunikaci s LLM API (Google Gemini nebo OpenAI kompatibilní).
 * Podporuje:
 * - bulkhead (Semaphore) omezující maximální souběžná volání (llm.max-concurrency),
 * - rate limiting (RateLimiter) řídící frekvenci požadavků za minutu (llm.requests-per-minute),
 * - sdílenou pauzu při HTTP 429 (cooldown) respektovanou všemi vlákna na základě Retry-After / exponenciálního backoffu,
 * - typovanou výjimku LlmRateLimitException po vyčerpání pokusů,
 * - bezpečnostní zkracování logů a ochranu API klíčů,
 * - automatický fallback na záložní modely při HTTP 503, 504, 404 a timeoutu (výchozí read timeout 15 s).
 */
@Slf4j
@Component
@Getter
public class LlmClient {

    private static final String KEY_CONTENT = "content";
    private static final Pattern MODEL_URL_PATTERN = Pattern.compile("/models/([^:/?]+)");
    private static final int MAX_LOG_BODY_LENGTH = 500;
    private static final int MAX_RETRIES_429 = 3;
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);

    // Pořadí fallback modelů: gemini-3.5-flash-lite je rychlý, levný a odolný vůči špičkám
    static final List<String> FALLBACK_MODELS = List.of(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.6-flash"
    );

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String llmApiKey;
    private final String llmApiUrl;
    private final Semaphore bulkhead;
    private final RateLimiter rateLimiter;
    private final Duration readTimeout;
    private final LongSupplier nanoTimeSupplier;
    private final Sleeper sleeper;

    // Sdílená pauza při 429 pro všechna vlákna
    private volatile long cooldownUntilNanos = 0L;

    public LlmClient(ObjectMapper objectMapper) {
        this(objectMapper, "", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");
    }

    public LlmClient(ObjectMapper objectMapper, String llmApiKey, String llmApiUrl) {
        this(objectMapper, llmApiKey, llmApiUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build(), 3, new RateLimiter(0));
    }

    public LlmClient(ObjectMapper objectMapper, String llmApiKey, String llmApiUrl, HttpClient httpClient) {
        this(objectMapper, llmApiKey, llmApiUrl, httpClient, 3, new RateLimiter(0));
    }

    public LlmClient(
            ObjectMapper objectMapper,
            String llmApiKey,
            String llmApiUrl,
            HttpClient httpClient,
            int maxConcurrency,
            RateLimiter rateLimiter) {
        this(objectMapper, llmApiKey, llmApiUrl, httpClient, maxConcurrency, rateLimiter,
                DEFAULT_READ_TIMEOUT, System::nanoTime, Thread::sleep);
    }

    public LlmClient(
            ObjectMapper objectMapper,
            String llmApiKey,
            String llmApiUrl,
            HttpClient httpClient,
            int maxConcurrency,
            int requestsPerMinute,
            LongSupplier nanoTimeSupplier,
            Sleeper sleeper) {
        this(objectMapper, llmApiKey, llmApiUrl, httpClient, maxConcurrency,
                new RateLimiter(requestsPerMinute, nanoTimeSupplier, sleeper),
                DEFAULT_READ_TIMEOUT,
                nanoTimeSupplier, sleeper);
    }

    @Autowired
    public LlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.api.key:}") String llmApiKey,
            @Value("${llm.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent}") String llmApiUrl,
            @Value("${llm.max-concurrency:3}") int maxConcurrency,
            @Value("${llm.read-timeout-seconds:15}") int readTimeoutSeconds,
            RateLimiter rateLimiter) {
        this(objectMapper, llmApiKey, llmApiUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build(), maxConcurrency, rateLimiter,
                Duration.ofSeconds(readTimeoutSeconds > 0 ? readTimeoutSeconds : 15),
                System::nanoTime, Thread::sleep);
    }

    public LlmClient(
            ObjectMapper objectMapper,
            String llmApiKey,
            String llmApiUrl,
            HttpClient httpClient,
            int maxConcurrency,
            RateLimiter rateLimiter,
            LongSupplier nanoTimeSupplier,
            Sleeper sleeper) {
        this(objectMapper, llmApiKey, llmApiUrl, httpClient, maxConcurrency, rateLimiter,
                DEFAULT_READ_TIMEOUT, nanoTimeSupplier, sleeper);
    }

    public LlmClient(
            ObjectMapper objectMapper,
            String llmApiKey,
            String llmApiUrl,
            HttpClient httpClient,
            int maxConcurrency,
            RateLimiter rateLimiter,
            Duration readTimeout,
            LongSupplier nanoTimeSupplier,
            Sleeper sleeper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper nesmí být null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient nesmí být null");
        this.llmApiKey = llmApiKey != null ? llmApiKey : "";
        this.llmApiUrl = llmApiUrl != null ? llmApiUrl : "";
        this.bulkhead = new Semaphore(Math.max(1, maxConcurrency), true);
        this.readTimeout = (readTimeout != null && !readTimeout.isNegative() && !readTimeout.isZero())
                ? readTimeout : DEFAULT_READ_TIMEOUT;
        this.nanoTimeSupplier = nanoTimeSupplier != null ? nanoTimeSupplier : System::nanoTime;
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
        this.rateLimiter = rateLimiter != null ? rateLimiter : new RateLimiter(0, this.nanoTimeSupplier, this.sleeper);
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
     * Řízeno přes bulkhead (Semaphore), rate limiter a sdílený cooldown při HTTP 429.
     * Při HTTP 503, 504, 404 nebo timeoutu přepíná na záložní modely.
     */
    public String callLlmApi(String prompt) throws IOException, InterruptedException {
        assertConfigured();

        // Bulkhead omezující maximální souběh volání
        bulkhead.acquire();
        try {
            return doCallLlmApi(prompt);
        } finally {
            bulkhead.release();
        }
    }

    private String doCallLlmApi(String prompt) throws IOException, InterruptedException {
        List<String> candidateUrls = getCandidateUrls();
        IOException lastException = null;

        for (int i = 0; i < candidateUrls.size(); i++) {
            String currentUrl = candidateUrls.get(i);
            boolean isGemini = currentUrl.contains("googleapis.com") || currentUrl.contains("gemini");
            String requestBodyJson = serializeRequestBody(prompt, isGemini);
            boolean hasNextFallback = (i + 1 < candidateUrls.size());
            int attempt429 = 0;

            while (true) {
                // Dodržení sdílené pauzy po HTTP 429
                awaitCooldownIfActive();

                // Dodržení limitu frekvence požadavků
                rateLimiter.acquire();

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(currentUrl))
                        .timeout(readTimeout)
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
                        log.warn("LLM API timeout ({} s) pro model na URL: {}. Přepínám na další model v pořadí fallbacku: {}",
                                readTimeout.toSeconds(), sanitizeUrl(currentUrl), sanitizeUrl(candidateUrls.get(i + 1)));
                        lastException = new IOException("LLM API timeout pro URL " + sanitizeUrl(currentUrl) + ": " + e.getMessage(), e);
                        break;
                    } else {
                        throw new IOException("Chyba při volání LLM API (timeout): " + e.getMessage(), e);
                    }
                }

                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    if (i > 0) {
                        log.info("Úspěšná odpověď z fallback modelu na URL: {}", sanitizeUrl(currentUrl));
                    }
                    return extractTextFromLlmResponse(response.body(), isGemini);
                }

                //  Ošetření HTTP 429 rate limitu
                if (status == 429) {
                    if (attempt429 < MAX_RETRIES_429) {
                        attempt429++;
                        long delaySeconds = parseRetryAfterHeader(response);
                        if (delaySeconds < 0) {
                            delaySeconds = calculate429BackoffSeconds(attempt429);
                        }
                        updateCooldown(Duration.ofSeconds(delaySeconds).toNanos());
                        log.warn("LLM API rate-limit (HTTP 429) pro {}, pokus {}/{}. Nastaven sdílený cooldown na {} s.",
                                sanitizeUrl(currentUrl), attempt429, MAX_RETRIES_429, delaySeconds);
                        continue;
                    } else {
                        log.error("LLM API rate-limit (HTTP 429) vyčerpán po {} pokusech pro URL {}.",
                                MAX_RETRIES_429, sanitizeUrl(currentUrl));
                        throw new LlmRateLimitException("LLM API rate-limit (HTTP 429) vyčerpán po " +
                                MAX_RETRIES_429 + " pokusech pro " + sanitizeUrl(currentUrl));
                    }
                }

                // Fallback při HTTP 503, 504 a 404 (neexistující / nedostupný model)
                if ((status == 503 || status == 504 || status == 404) && hasNextFallback) {
                    log.warn("LLM API vrátilo HTTP {} pro model na URL: {}. Přepínám na další model v pořadí fallbacku: {}",
                            status, sanitizeUrl(currentUrl), sanitizeUrl(candidateUrls.get(i + 1)));
                    lastException = new IOException("Chyba při volání LLM API (HTTP " + status + "): " +
                            truncateForLog(response.body(), MAX_LOG_BODY_LENGTH));
                    break;
                }

                throw new IOException("Chyba při volání LLM API (HTTP " + status + "): " +
                        truncateForLog(response.body(), MAX_LOG_BODY_LENGTH));
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("LLM API volání selhalo pro všechny modely.");
    }

    private void awaitCooldownIfActive() throws InterruptedException {
        long now = nanoTimeSupplier.getAsLong();
        long remainingNanos = cooldownUntilNanos - now;
        if (remainingNanos > 0) {
            long waitMillis = (remainingNanos + 999_999L) / 1_000_000L;
            log.warn("Aktivní sdílená pauza (cooldown) po HTTP 429 pro všechna vlákna. Čekám {} ms...", waitMillis);
            sleeper.sleep(waitMillis);
        }
    }

    public synchronized void updateCooldown(long cooldownDurationNanos) {
        long now = nanoTimeSupplier.getAsLong();
        long target = now + cooldownDurationNanos;
        if (target > cooldownUntilNanos) {
            cooldownUntilNanos = target;
        }
    }

    public long parseRetryAfterHeader(HttpResponse<?> response) {
        if (response == null || response.headers() == null) {
            return -1L;
        }
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            String val = header.get().trim();
            try {
                long seconds = Long.parseLong(val);
                if (seconds > 0) {
                    return Math.min(120L, seconds);
                }
            } catch (NumberFormatException ignored) {
                try {
                    ZonedDateTime date = ZonedDateTime.parse(val, DateTimeFormatter.RFC_1123_DATE_TIME);
                    long seconds = Duration.between(ZonedDateTime.now(), date).toSeconds();
                    if (seconds > 0) {
                        return Math.min(120L, seconds);
                    }
                } catch (Exception ignoredDate) {
                    // ignorovat, použije se fallback
                }
            }
        }
        return -1L;
    }

    public long calculate429BackoffSeconds(int attempt429) {
        long base = 2L * (1L << Math.min(attempt429 - 1, 5)); // 2s, 4s, 8s...
        long jitter = ThreadLocalRandom.current().nextLong(0, 2); // 0 až 1 s jitter
        return Math.clamp(base + jitter, 1L, 120L);
    }

    /**
     * Zkrátí text pro bezpečné logování na maximálně stanovený počet znaků (Fáze 3.5).
     */
    public static String truncateForLog(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [zkráceno]";
    }

    /**
     * Odstraní případné API klíče v query parametrech URL před zalogováním.
     */
    public static String sanitizeUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("([?&]key=)[^&]+", "$1***");
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
