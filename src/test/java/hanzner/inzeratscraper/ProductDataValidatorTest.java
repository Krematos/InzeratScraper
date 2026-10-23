package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.ProductDataValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductDataValidatorTest {

    private ProductDataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ProductDataValidator(
                List.of("Elektronika", "Nářadí", "Ostatní"),
                "STANDARD"
        );
    }

    @Test
    void testVatRateInvalidConfigThrowsExceptionAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ProductDataValidator(
                List.of("Ostatní"), "INVALID_VAT"
        ));
    }

    @Test
    void testValidateAndNormalizeNameRemovesHtmlAndControlChars() {
        List<String> reasons = new ArrayList<>();
        String dirty = "<b>Aku vrtačka</b> Bosch \u0007\t   18V   ";
        String normalized = validator.validateAndNormalizeName(dirty, "Fallback", "BAZOS_12345", reasons);

        assertEquals("Aku vrtačka Bosch 18V", normalized);
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testValidateAndNormalizeNamePreservesAmpersandAndLessThanWithoutHtmlEntities() {
        List<String> reasons = new ArrayList<>();
        String raw = "Bosch & Makita 5 < 6 mm";
        String normalized = validator.validateAndNormalizeName(raw, "Fallback", "BAZOS_12345", reasons);

        assertEquals("Bosch & Makita 5 < 6 mm", normalized);
        assertFalse(normalized.contains("&amp;"));
        assertFalse(normalized.contains("&lt;"));
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testValidateAndNormalizeNameTruncatesOnWordBoundaryUnder100Chars() {
        List<String> reasons = new ArrayList<>();
        String longTitle = "Velmi dlouhý název produktu který přesahuje sto znaků a je potřeba ho bezpečně zkrátit na hranici slova aby se nerozbil string v databázi";
        String normalized = validator.validateAndNormalizeName(longTitle, "Fallback", "BAZOS_12345", reasons);

        assertTrue(ProductDataValidator.codePointLength(normalized) <= 100);
        assertFalse(normalized.endsWith(" "));
        assertTrue(reasons.contains("Název zkrácen na 100 znaků"));
    }

    @Test
    void testValidateAndNormalizeNameEnsuresUniquenessWithSuffix() {
        validator.registerExistingName("Aku vrtačka Bosch");

        List<String> reasons = new ArrayList<>();
        String unique = validator.validateAndNormalizeName("Aku vrtačka Bosch", "Fallback", "BAZOS_194827164", reasons);

        assertEquals("Aku vrtačka Bosch #27164", unique);
        assertTrue(reasons.contains("Název upraven pro unikátnost"));
    }

    @Test
    void testValidateAndNormalizeDescriptionStripsHtmlAndLimitsTo1000Chars() {
        List<String> reasons = new ArrayList<>();
        String htmlDesc = "<p>Popis vrtačky.<script>alert('xss')</script> Plně funkční.</p>";
        String cleaned = validator.validateAndNormalizeDescription(htmlDesc, "Fallback", reasons);

        assertEquals("Popis vrtačky. Plně funkční.", cleaned);
        assertFalse(cleaned.contains("<p>"));
    }

    @Test
    void testValidateAndNormalizeDescriptionPreservesExactNewlinesAndAmpersand() {
        List<String> reasons = new ArrayList<>();
        String raw = "Bosch & Makita\n\nDruhý odstavec";
        String cleaned = validator.validateAndNormalizeDescription(raw, "Fallback", reasons);

        assertEquals("Bosch & Makita\n\nDruhý odstavec", cleaned);
        assertFalse(cleaned.contains("&amp;"));
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testValidateAndNormalizeDescriptionPreservesNewlinesAndUnescapesEntities() {
        List<String> reasons = new ArrayList<>();
        String descWithNewlinesAndEntities = """
                Aku vrtačka Bosch & Makita.
                Kapacita 5 < 6 Ah.

                Plně funkční, včetně nabíječky.
                """;

        String cleaned = validator.validateAndNormalizeDescription(descWithNewlinesAndEntities, "Fallback", reasons);

        assertEquals("""
                Aku vrtačka Bosch & Makita.
                Kapacita 5 < 6 Ah.

                Plně funkční, včetně nabíječky.""", cleaned);
        assertFalse(cleaned.contains("&amp;"));
        assertFalse(cleaned.contains("&lt;"));
        assertTrue(cleaned.contains("\n"));
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testValidateAndNormalizeDescriptionConvertsHtmlBreaksToNewlines() {
        List<String> reasons = new ArrayList<>();
        String htmlWithBreaks = "První řádek.<br>Druhý řádek.<br/>Třetí řádek.<p>Čtvrtý odstavec.</p>";
        String cleaned = validator.validateAndNormalizeDescription(htmlWithBreaks, "Fallback", reasons);

        assertEquals("""
                První řádek.
                Druhý řádek.
                Třetí řádek.
                Čtvrtý odstavec.""", cleaned);
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testValidatePriceRejectsNullOrBelowOneCrown() {
        List<String> reasons = new ArrayList<>();
        BigDecimal price = validator.validatePrice(new BigDecimal("0.50"), null, reasons);
        assertNull(price);
        assertTrue(reasons.contains("Cena nenalezena nebo <= 1 Kč"));
    }

    @Test
    void testValidatePriceRejectsAboveMaxLimit() {
        List<String> reasons = new ArrayList<>();
        BigDecimal price = validator.validatePrice(new BigDecimal("10000000000"), null, reasons);
        assertNull(price);
        assertTrue(reasons.contains("Cena překračuje limit 9 999 999 999.99"));
    }

    @Test
    void testValidatePriceFlagsDeviationOverTwentyPercent() {
        List<String> reasons = new ArrayList<>();
        BigDecimal regexPrice = new BigDecimal("1000.00");
        BigDecimal aiPrice = new BigDecimal("1300.00"); // 30% rozdíl
        BigDecimal validated = validator.validatePrice(aiPrice, regexPrice, reasons);

        assertNotNull(validated);
        assertEquals(new BigDecimal("1300.00"), validated);
        assertTrue(reasons.stream().anyMatch(r -> r.contains("více než 20 %")));
    }

    @Test
    void testResolveCanonicalCategoryReturnsExactAllowlistString() {
        List<String> reasons = new ArrayList<>();
        List<String> allowlist = List.of("Elektronika", "Nářadí", "Ostatní");

        assertEquals("Nářadí", validator.resolveCanonicalCategory("nářadí", allowlist, reasons));
        assertTrue(reasons.isEmpty());

        assertEquals("Ostatní", validator.resolveCanonicalCategory("Neznámá kategorie", allowlist, reasons));
        assertTrue(reasons.contains("Kategorie nenalezena v allowlistu"));
    }

    @Test
    void testBuildReviewReasonTruncatesTo200Chars() {
        List<String> longReasons = List.of(
                "Velmi dlouhý první důvod pro ruční kontrolu inzerátu",
                "Druhý důvod který obsahuje spoustu detailů o nesouladu",
                "Třetí důvod který definitivně překročí maximální povolenou délku dvě stě znaků v cílovém sloupci review_reason"
        );
        String combined = validator.buildReviewReason(longReasons);

        assertNotNull(combined);
        assertTrue(ProductDataValidator.codePointLength(combined) <= 200);
    }
}
