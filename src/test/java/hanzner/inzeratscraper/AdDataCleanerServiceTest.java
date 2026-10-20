package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.AdDataCleanerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdDataCleanerServiceTest {

    private AdDataCleanerService cleanerService;

    @BeforeEach
    void setUp() {
        cleanerService = new AdDataCleanerService(new ObjectMapper());
    }

    @Test
    void testParseBasicPriceDirectValueWithSpaces() {
        BigDecimal price = cleanerService.parseBasicPrice("1 500 Kč", "Prodám vrtačku.");
        assertEquals(BigDecimal.valueOf(1500), price);
    }

    @Test
    void testParseBasicPriceSymbolicOneCrownFallbackToDescription() {
        BigDecimal price = cleanerService.parseBasicPrice("1 Kč", "Prodám pilu, plně funkční. Cena v textu: 2500,-");
        assertEquals(BigDecimal.valueOf(2500), price);
    }

    @Test
    void testParseBasicPriceDohodouWithoutPriceInTextReturnsNull() {
        BigDecimal price = cleanerService.parseBasicPrice("Dohodou", "Prodám náhradní díly, stav viz foto.");
        assertNull(price);
    }

    @Test
    void testParseBasicPriceHandlesNullDescriptionSafely() {
        BigDecimal price = cleanerService.parseBasicPrice("Dohodou", null);
        assertNull(price);
    }

    @Test
    void testBasicRegexCleanDescriptionRemovesBazarPhrasesAndContacts() {
        String rawText = """
                Prodám aku vrtačku Bosch GSR 18V.
                Plně funkční, včetně 2 baterií a kufru.
                Inzerát platí do smazání.
                Pouze osobní odběr v Praze.
                Na dobírku neposílám!
                Podívejte se i na mé další inzeráty.
                Tel: +420 777 123 456, email: prodejce.test@seznam.cz
                """;

        String cleaned = cleanerService.basicRegexCleanDescription(rawText);

        assertTrue(cleaned.contains("Prodám aku vrtačku Bosch GSR 18V."), "Actual: " + cleaned);
        assertTrue(cleaned.contains("Plně funkční, včetně 2 baterií a kufru."), "Actual: " + cleaned);
        assertFalse(cleaned.toLowerCase().contains("platí do smazání"), "Actual: " + cleaned);
        assertFalse(cleaned.toLowerCase().contains("pouze osobní odběr"), "Actual: " + cleaned);
        assertFalse(cleaned.toLowerCase().contains("na dobírku neposílám"), "Actual: " + cleaned);
        assertFalse(cleaned.toLowerCase().contains("další inzeráty"), "Actual: " + cleaned);
        assertFalse(cleaned.contains("777 123 456"), "Actual: " + cleaned);
        assertFalse(cleaned.contains("prodejce.test@seznam.cz"), "Actual: " + cleaned);
    }

    @Test
    void testStripMarkdownCodeFences() {
        String wrapped = """
                ```json
                {"cleanTitle": "Bosch GSR 18V"}
                ```
                """;
        assertEquals("{\"cleanTitle\": \"Bosch GSR 18V\"}", cleanerService.stripMarkdownCodeFences(wrapped));
    }
}
