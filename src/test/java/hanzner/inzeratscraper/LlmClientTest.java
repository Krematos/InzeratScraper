package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.LlmClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
