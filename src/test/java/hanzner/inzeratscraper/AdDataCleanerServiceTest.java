package hanzner.inzeratscraper;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.service.AdDataCleanerService;
import hanzner.inzeratscraper.service.AdTextSanitizer;
import hanzner.inzeratscraper.service.LlmClient;
import hanzner.inzeratscraper.service.ProductDataValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdDataCleanerServiceTest {

    private AdDataCleanerService cleanerService;

    @BeforeEach
    void setUp() {
        cleanerService = new AdDataCleanerService(
                new ObjectMapper(),
                "",
                "",
                List.of("Elektronika", "Nářadí", "Ostatní"),
                "STANDARD"
        );
    }

    @Test
    void testVatRateInvalidConfigThrowsExceptionAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AdDataCleanerService(
                new ObjectMapper(), "", "", List.of("Ostatní"), "INVALID_VAT"
        ));
    }

    @Test
    void testValidateAndNormalizeNameRemovesHtmlAndControlChars() {
        List<String> reasons = new ArrayList<>();
        String dirty = "<b>Aku vrtačka</b> Bosch \u0007\t   18V   ";
        String normalized = cleanerService.validateAndNormalizeName(dirty, "Fallback", "BAZOS_12345", reasons);

        assertEquals("Aku vrtačka Bosch 18V", normalized);
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testValidateAndNormalizeNameTruncatesOnWordBoundaryUnder100Chars() {
        List<String> reasons = new ArrayList<>();
        String longTitle = "Velmi dlouhý název produktu který přesahuje sto znaků a je potřeba ho bezpečně zkrátit na hranici slova aby se nerozbil string v databázi";
        String normalized = cleanerService.validateAndNormalizeName(longTitle, "Fallback", "BAZOS_12345", reasons);

        assertTrue(AdDataCleanerService.codePointLength(normalized) <= 100);
        assertFalse(normalized.endsWith(" "));
        assertTrue(reasons.contains("Název zkrácen na 100 znaků"));
    }

    @Test
    void testValidateAndNormalizeNameEnsuresUniquenessWithSuffix() {
        cleanerService.registerExistingName("Aku vrtačka Bosch");

        List<String> reasons = new ArrayList<>();
        String unique = cleanerService.validateAndNormalizeName("Aku vrtačka Bosch", "Fallback", "BAZOS_194827164", reasons);

        assertEquals("Aku vrtačka Bosch #27164", unique);
        assertTrue(reasons.contains("Název upraven pro unikátnost"));
    }

    @Test
    void testValidateAndNormalizeDescriptionStripsHtmlAndLimitsTo1000Chars() {
        List<String> reasons = new ArrayList<>();
        String htmlDesc = "<p>Popis vrtačky.<script>alert('xss')</script> Plně funkční.</p>";
        String cleaned = cleanerService.validateAndNormalizeDescription(htmlDesc, "Fallback", reasons);

        assertEquals("Popis vrtačky. Plně funkční.", cleaned);
        assertFalse(cleaned.contains("<p>"));
    }

    @Test
    void testValidateAndNormalizeDescriptionPreservesNewlinesAndUnescapesEntities() {
        List<String> reasons = new ArrayList<>();
        String rawDesc = "Bosch & Makita nářadí.\nParametry 5 < 6 mm.\n\nStav výborný.";
        String cleaned = cleanerService.validateAndNormalizeDescription(rawDesc, "Fallback", reasons);

        assertEquals("Bosch & Makita nářadí.\nParametry 5 < 6 mm.\n\nStav výborný.", cleaned);
        assertFalse(cleaned.contains("&amp;"));
        assertFalse(cleaned.contains("&lt;"));
        assertTrue(reasons.isEmpty());
    }

    @Test
    void testCleanWithAiDoesNotTriggerNeedsReviewForDescriptionWithNewlinesAndAmpersand() throws IOException, InterruptedException {
        ObjectMapper objectMapper = new ObjectMapper();
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.stripMarkdownCodeFences(anyString())).thenAnswer(inv -> inv.getArgument(0));

        String llmJsonResponse = """
                {
                  "name": "Aku vrtačka Bosch & Makita",
                  "description": "Profesionální vrtačka Bosch & Makita.\\nKapacita 5 < 6 Ah.\\n\\nPlně funkční stav.",
                  "price": 2500.0,
                  "priceNeedsReview": false,
                  "category": "Nářadí",
                  "condition": "USED",
                  "parameters": {"Značka": "Bosch"}
                }
                """;
        when(mockLlm.callLlmApi(anyString())).thenReturn(llmJsonResponse);

        AdDataCleanerService customService = new AdDataCleanerService(
                objectMapper,
                new AdTextSanitizer(),
                mockLlm,
                new ProductDataValidator(List.of("Elektronika", "Nářadí", "Ostatní"), "STANDARD")
        );

        RawAdDto rawAd = new RawAdDto(
                "BAZOS_99999",
                "https://naradi.bazos.cz/inzerat/99999/vrtacka.php",
                "naradi",
                "Prodám Aku vrtačka Bosch & Makita 18V TOP STAV!",
                "Profesionální vrtačka Bosch & Makita.\nKapacita 5 < 6 Ah.\nPlně funkční stav.\nCena 2500 Kč.\nTel: 777111222",
                "2 500 Kč",
                "Praha",
                Collections.emptyList()
        );

        CleanedProductDto cleaned = customService.cleanWithAi(rawAd);

        assertEquals("Aku vrtačka Bosch & Makita", cleaned.name());
        assertEquals("Profesionální vrtačka Bosch & Makita.\nKapacita 5 < 6 Ah.\n\nPlně funkční stav.", cleaned.description());
        assertFalse(cleaned.description().contains("&amp;"));
        assertFalse(cleaned.description().contains("&lt;"));
        assertFalse(cleaned.needsReview());
        assertNull(cleaned.reviewReason());
    }

    @Test
    void testValidatePriceRejectsNullOrBelowOneCrown() {
        List<String> reasons = new ArrayList<>();
        BigDecimal price = cleanerService.validatePrice(new BigDecimal("0.50"), null, reasons);
        assertNull(price);
        assertTrue(reasons.contains("Cena nenalezena nebo <= 1 Kč"));
    }

    @Test
    void testValidatePriceRejectsAboveMaxLimit() {
        List<String> reasons = new ArrayList<>();
        BigDecimal price = cleanerService.validatePrice(new BigDecimal("10000000000"), null, reasons);
        assertNull(price);
        assertTrue(reasons.contains("Cena překračuje limit 9 999 999 999.99"));
    }

    @Test
    void testValidatePriceFlagsDeviationOverTwentyPercent() {
        List<String> reasons = new ArrayList<>();
        BigDecimal regexPrice = new BigDecimal("1000.00");
        BigDecimal aiPrice = new BigDecimal("1300.00"); // 30% rozdíl
        BigDecimal validated = cleanerService.validatePrice(aiPrice, regexPrice, reasons);

        assertNotNull(validated);
        assertEquals(new BigDecimal("1300.00"), validated);
        assertTrue(reasons.stream().anyMatch(r -> r.contains("více než 20 %")));
    }

    @Test
    void testResolveCanonicalCategoryReturnsExactAllowlistString() {
        List<String> reasons = new ArrayList<>();
        List<String> allowlist = List.of("Elektronika", "Nářadí", "Ostatní");

        assertEquals("Nářadí", cleanerService.resolveCanonicalCategory("nářadí", allowlist, reasons));
        assertTrue(reasons.isEmpty());

        assertEquals("Ostatní", cleanerService.resolveCanonicalCategory("Neznámá kategorie", allowlist, reasons));
        assertTrue(reasons.contains("Kategorie nenalezena v allowlistu"));
    }

    @Test
    void testParseBasicPriceDirectValueWithSpaces() {
        BigDecimal price = cleanerService.parseBasicPrice("1 500 Kč", "Prodám vrtačku.");
        assertEquals(new BigDecimal("1500.00"), price);
    }

    @Test
    void testParseBasicPriceSymbolicOneCrownFallbackToDescription() {
        BigDecimal price = cleanerService.parseBasicPrice("1 Kč", "Prodám pilu, plně funkční. Cena v textu: 2500,-");
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

        String cleaned = cleanerService.basicRegexCleanDescription(rawText);

        assertTrue(cleaned.contains("Prodám aku vrtačku Bosch GSR 18V."));
        assertTrue(cleaned.contains("Plně funkční, včetně 2 baterií a kufru."));
        assertFalse(cleaned.toLowerCase().contains("platí do smazání"));
        assertFalse(cleaned.toLowerCase().contains("pouze osobní odběr"));
        assertFalse(cleaned.toLowerCase().contains("na dobírku neposílám"));
        assertFalse(cleaned.toLowerCase().contains("další inzeráty"));
        assertFalse(cleaned.contains("777 123 456"));
        assertFalse(cleaned.contains("prodejce.test@seznam.cz"));
    }

    @Test
    void testStripMarkdownCodeFences() {
        String wrapped = """
                ```json
                {"name": "Bosch GSR 18V"}
                ```
                """;
        assertEquals("{\"name\": \"Bosch GSR 18V\"}", cleanerService.stripMarkdownCodeFences(wrapped));
    }
}
