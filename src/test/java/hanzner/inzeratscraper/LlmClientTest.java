package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.LlmClient;
import hanzner.inzeratscraper.service.LlmRateLimitException;
import hanzner.inzeratscraper.service.RateLimiter;
import hanzner.inzeratscraper.service.Sleeper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmClientTest {

    @Test
    void testStripMarkdownCodeFences() {
        LlmClient client = new LlmClient(new ObjectMapper(), "test-key", "https://api.example.com");
        String wrapped = """
                ```json
                {"name": "Bosch GSR 18V"}
                ```
                """;
        assertEquals("{\"name\": \"Bosch GSR 18V\"}", client.stripMarkdownCodeFences(wrapped));
    }

    @Test
    void testMissingApiKeyThrowsIllegalStateException() {
        LlmClient client = new LlmClient(new ObjectMapper(), "", "https://api.example.com");
        assertThrows(IllegalStateException.class, () -> client.callLlmApi("test prompt"));
    }

    @Test
    void testAssertConfiguredThrowsWhenMissingOrPlaceholder() {
        LlmClient clientEmpty = new LlmClient(new ObjectMapper(), "", "https://api.example.com");
        assertThrows(IllegalStateException.class, clientEmpty::assertConfigured);

        LlmClient clientNull = new LlmClient(new ObjectMapper(), null, "https://api.example.com");
        assertThrows(IllegalStateException.class, clientNull::assertConfigured);

        LlmClient clientPlaceholder = new LlmClient(new ObjectMapper(), "replace-with-api-key", "https://api.example.com");
        assertThrows(IllegalStateException.class, clientPlaceholder::assertConfigured);
    }

    @Test
    void testAssertConfiguredPassesWhenKeySet() {
        LlmClient clientValid = new LlmClient(new ObjectMapper(), "valid-key", "https://api.example.com");
        assertDoesNotThrow(clientValid::assertConfigured);
    }

    @Test
    void testCandidateUrlsGenerationWith38FlashPrimary() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent");

        List<String> urls = client.getCandidateUrls();
        assertEquals(4, urls.size());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", urls.get(0));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent", urls.get(1));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", urls.get(2));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", urls.get(3));
    }

    @Test
    void testCandidateUrlsGenerationWith31FlashLitePrimary() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent");

        List<String> urls = client.getCandidateUrls();
        assertEquals(4, urls.size());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent", urls.get(0));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", urls.get(1));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", urls.get(2));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", urls.get(3));
    }

    @Test
    void testCandidateUrlsDeduplicationWhenConfiguredWithFallbackModel() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");

        List<String> urls = client.getCandidateUrls();
        assertEquals(4, urls.size());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", urls.get(0));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent", urls.get(1));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", urls.get(2));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", urls.get(3));
    }

    @Test
    void testCandidateUrlsNonGemini() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key", "https://api.openai.com/v1/chat/completions");
        List<String> urls = client.getCandidateUrls();
        assertEquals(List.of("https://api.openai.com/v1/chat/completions"), urls);
    }

    @Test
    void testReadTimeoutConfiguration() {
        LlmClient defaultClient = new LlmClient(new ObjectMapper(), "key", "https://api.example.com");
        assertEquals(Duration.ofSeconds(15), defaultClient.getReadTimeout());

        LlmClient customClient = new LlmClient(
                new ObjectMapper(), "key", "https://api.example.com",
                HttpClient.newHttpClient(), 3, new RateLimiter(0),
                Duration.ofSeconds(20), System::nanoTime, Thread::sleep
        );
        assertEquals(Duration.ofSeconds(20), customClient.getReadTimeout());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFallbackOn503SwitchesToNextModel() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response503 = mock(HttpResponse.class);
        when(response503.statusCode()).thenReturn(503);
        when(response503.body()).thenReturn("{\"error\": \"Model is overloaded\"}");

        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"name\\\":\\\"Test\\\"}\"}]}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response503)
                .thenReturn(response200);

        LlmClient client = new LlmClient(new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient);

        String result = client.callLlmApi("test prompt");
        assertEquals("{\"name\":\"Test\"}", result);
        verify(mockHttpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFallbackOn404SwitchesToNextModel() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response404 = mock(HttpResponse.class);
        when(response404.statusCode()).thenReturn(404);
        when(response404.body()).thenReturn("{\"error\": \"Model not found\"}");

        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"name\\\":\\\"FallbackAfter404\\\"}\"}]}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response404)
                .thenReturn(response200);

        LlmClient client = new LlmClient(new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient);

        String result = client.callLlmApi("test prompt");
        assertEquals("{\"name\":\"FallbackAfter404\"}", result);
        verify(mockHttpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFallbackOnTimeoutSwitchesToNextModel() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);

        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"name\\\":\\\"FallbackOK\\\"}\"}]}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("Request timed out"))
                .thenReturn(response200);

        LlmClient client = new LlmClient(new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient);

        String result = client.callLlmApi("test prompt");
        assertEquals("{\"name\":\"FallbackOK\"}", result);
        verify(mockHttpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAllModelsFailThrowsIOException() throws Exception {
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response503 = mock(HttpResponse.class);
        when(response503.statusCode()).thenReturn(503);
        when(response503.body()).thenReturn("Overloaded");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response503);

        LlmClient client = new LlmClient(new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient);

        IOException ex = assertThrows(IOException.class, () -> client.callLlmApi("test prompt"));
        assertTrue(ex.getMessage().contains("503"));
        verify(mockHttpClient, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // ==========================================
    // FÁZE 3 TESTY (Souběh, Rate Limiter, Cooldown, Bezpečnost)
    // ==========================================

    @Test
    @SuppressWarnings("unchecked")
    void testBulkheadMaxConcurrencyLimitsActiveRequests() throws Exception {
        // (a) při max-concurrency=2 a 10 úlohách nikdy neběží víc než 2 zároveň (čítač v mocku HTTP klienta)
        HttpClient mockHttpClient = mock(HttpClient.class);
        AtomicInteger activeConcurrent = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrent = new AtomicInteger(0);

        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    int current = activeConcurrent.incrementAndGet();
                    maxObservedConcurrent.accumulateAndGet(current, Math::max);
                    Thread.sleep(50);
                    activeConcurrent.decrementAndGet();
                    return response200;
                });

        LlmClient client = new LlmClient(
                new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient,
                2, // maxConcurrency = 2
                new RateLimiter(0) // bez rate limiteru pro tento test
        );

        int totalTasks = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalTasks);

        try (ExecutorService executor = Executors.newFixedThreadPool(totalTasks)) {
            for (int i = 0; i < totalTasks; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        client.callLlmApi("prompt");
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
        }

        assertTrue(maxObservedConcurrent.get() <= 2,
                "Maximální souběh v HTTP klientu nesmí přesáhnout maxConcurrency (2), ale bylo: " + maxObservedConcurrent.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRateLimiterSpacingRespected() throws Exception {
        // (b) rozestup volání respektuje requests-per-minute (falešné hodiny)
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response200);

        AtomicLong fakeNanos = new AtomicLong(0L);
        List<Long> sleepDurations = Collections.synchronizedList(new ArrayList<>());
        Sleeper sleeper = sleepDurations::add;

        // 12 RPM -> interval 5 sekund = 5000 ms
        LlmClient client = new LlmClient(
                new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient,
                5, 12, fakeNanos::get, sleeper
        );

        // 1. volání v čase 0s -> 0 ms čekání
        client.callLlmApi("p1");
        assertTrue(sleepDurations.isEmpty());

        // Čas se posunul o 1s (1_000_000_000 ns) -> další volání musí čekat zbývající 4s (4000 ms)
        fakeNanos.set(1_000_000_000L);
        client.callLlmApi("p2");
        assertEquals(1, sleepDurations.size());
        assertEquals(4000L, sleepDurations.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHttp429WithRetryAfterHeaderDelaysAllThreads() throws Exception {
        // (c) odpověď 429 s Retry-After: 3 zpozdí další volání všech vláken
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response429 = mock(HttpResponse.class);
        when(response429.statusCode()).thenReturn(429);
        when(response429.body()).thenReturn("Too Many Requests");

        HttpHeaders headersWithRetryAfter = HttpHeaders.of(
                Map.of("Retry-After", List.of("3")),
                (k, v) -> true
        );
        when(response429.headers()).thenReturn(headersWithRetryAfter);

        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response429)
                .thenReturn(response200);

        AtomicLong fakeNanos = new AtomicLong(10_000_000_000L); // 10s
        List<Long> sleepDurations = Collections.synchronizedList(new ArrayList<>());
        Sleeper sleeper = sleepDurations::add;

        LlmClient client = new LlmClient(
                new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient,
                3, 0, fakeNanos::get, sleeper
        );

        // Volání 1 dostane 429 s Retry-After: 3 -> nastaví cooldown na now + 3s (13s) a na retry pokusu usne na 3000 ms
        String res = client.callLlmApi("prompt");
        assertNotNull(res);
        assertTrue(sleepDurations.contains(3000L));

        // Nyní druhé volání v čase 11s (zatímco cooldown trvá do 13s)
        fakeNanos.set(11_000_000_000L);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response200);

        client.callLlmApi("prompt2");
        // Druhé vlákno muselo čekat zbývající 2s (2000 ms) kvůli sdílenému cooldownu!
        assertTrue(sleepDurations.contains(2000L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHttp429WithoutRetryAfterUsesExponentialBackoff() throws Exception {
        // (d) chybějící Retry-After použije backoff
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response429 = mock(HttpResponse.class);
        when(response429.statusCode()).thenReturn(429);
        when(response429.body()).thenReturn("Quota Exceeded");
        when(response429.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

        HttpResponse<String> response200 = mock(HttpResponse.class);
        when(response200.statusCode()).thenReturn(200);
        when(response200.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response429)
                .thenReturn(response200);

        AtomicLong fakeNanos = new AtomicLong(0L);
        List<Long> sleepDurations = Collections.synchronizedList(new ArrayList<>());
        Sleeper sleeper = sleepDurations::add;

        LlmClient client = new LlmClient(
                new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient,
                3, 0, fakeNanos::get, sleeper
        );

        client.callLlmApi("prompt");

        // 1. pokus o backoff má základ 2s + jitter (0-1s) -> očekává se 2000 nebo 3000 ms
        assertEquals(1, sleepDurations.size());
        assertTrue(sleepDurations.get(0) >= 2000L && sleepDurations.get(0) <= 3000L,
                "Exponenciální backoff pro 1. pokus bez hlavičky musí být v rozmezí 2-3s, ale bylo: " + sleepDurations.get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHttp429ExhaustionThrowsLlmRateLimitException() throws Exception {
        // Fáze 3.4: Po vyčerpání pokusů vyhodit typovanou výjimku LlmRateLimitException
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response429 = mock(HttpResponse.class);
        when(response429.statusCode()).thenReturn(429);
        when(response429.body()).thenReturn("Rate limit exceeded permanently");
        when(response429.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response429);

        LlmClient client = new LlmClient(
                new ObjectMapper(), "test-key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                mockHttpClient,
                3, 0, () -> 0L, (ms) -> {}
        );

        LlmRateLimitException ex = assertThrows(LlmRateLimitException.class, () -> client.callLlmApi("prompt"));
        assertTrue(ex.getMessage().contains("429"));
        assertTrue(ex.getMessage().contains("vyčerpán"));
        // Celkem: 1 počáteční pokus + 3 retries = 4 volání na 1. modelu
        verify(mockHttpClient, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testResponseBodyTruncationAndApiKeySanitization() throws Exception {
        // Fáze 3.5: Nikdy nelogovat API klíč ani celé tělo odpovědi delší než 500 znaků
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> response500 = mock(HttpResponse.class);
        when(response500.statusCode()).thenReturn(500);

        String hugeBody = "X".repeat(2000);
        when(response500.body()).thenReturn(hugeBody);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response500);

        LlmClient client = new LlmClient(
                new ObjectMapper(), "super-secret-key-12345",
                "https://api.example.com/v1/chat?key=super-secret-key-12345",
                mockHttpClient
        );

        IOException ex = assertThrows(IOException.class, () -> client.callLlmApi("prompt"));
        String msg = ex.getMessage();

        // Nesmí obsahovat celé 2000 znaků tělo
        assertTrue(msg.contains("[zkráceno]"));
        assertTrue(msg.length() < 700, "Zpráva výjimky nesmí přesáhnout zkrácené tělo (500 znaků + text chyby)");

        // Ověření sanitizace URL s klíčem
        String sanitized = LlmClient.sanitizeUrl("https://api.example.com?key=super-secret-key-12345&foo=bar");
        assertEquals("https://api.example.com?key=***&foo=bar", sanitized);
        assertTrue(!sanitized.contains("super-secret-key-12345"));
    }
}
