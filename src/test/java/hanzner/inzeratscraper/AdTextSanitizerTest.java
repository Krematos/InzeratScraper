package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.AdTextSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdTextSanitizerTest {

    private AdTextSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new AdTextSanitizer();
    }

    @Test
    void testParseBasicPriceDirectValueWithSpaces() {
        BigDecimal price = sanitizer.parseBasicPrice("1 500 Kč", "Prodám vrtačku.");
        assertEquals(new BigDecimal("1500.00"), price);
    }

    @Test
    void testParseBasicPriceSymbolicOneCrownFallbackToDescription() {
        BigDecimal price = sanitizer.parseBasicPrice("1 Kč", "Prodám pilu, plně funkční. Cena v textu: 2500,-");
        assertEquals(new BigDecimal("2500.00"), price);
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

        String cleaned = sanitizer.basicRegexCleanDescription(rawText);

        assertTrue(cleaned.contains("Prodám aku vrtačku Bosch GSR 18V."));
        assertTrue(cleaned.contains("Plně funkční, včetně 2 baterií a kufru."));
        assertFalse(cleaned.toLowerCase().contains("platí do smazání"));
        assertFalse(cleaned.toLowerCase().contains("pouze osobní odběr"));
        assertFalse(cleaned.toLowerCase().contains("na dobírku neposílám"));
        assertFalse(cleaned.toLowerCase().contains("další inzeráty"));
        assertFalse(cleaned.contains("777 123 456"));
        assertFalse(cleaned.contains("prodejce.test@seznam.cz"));
    }
}
