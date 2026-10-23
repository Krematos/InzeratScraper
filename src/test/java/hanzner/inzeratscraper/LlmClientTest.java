package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.LlmClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void testCandidateUrlsGeneration() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent");

        List<String> urls = client.getCandidateUrls();
        assertEquals(4, urls.size());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", urls.get(0));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite:generateContent", urls.get(1));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", urls.get(2));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", urls.get(3));
    }

    @Test
    void testCandidateUrlsDeduplicationWhenConfiguredWithFallbackModel() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite:generateContent");

        List<String> urls = client.getCandidateUrls();
        assertEquals(3, urls.size());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash-lite:generateContent", urls.get(0));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent", urls.get(1));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", urls.get(2));
    }

    @Test
    void testCandidateUrlsNonGemini() {
        LlmClient client = new LlmClient(new ObjectMapper(), "key", "https://api.openai.com/v1/chat/completions");
        List<String> urls = client.getCandidateUrls();
        assertEquals(List.of("https://api.openai.com/v1/chat/completions"), urls);
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
}
