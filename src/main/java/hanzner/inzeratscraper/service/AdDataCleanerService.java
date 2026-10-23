package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zajišťuje čištění surového inzerátu s využitím LLM (Gemini/OpenAI) a následnou
 * přísnou deterministickou validaci všech polí před uložením do staging DB nebo e-shopu.
 */
@Slf4j
@Service
public class AdDataCleanerService {

    private final ObjectMapper objectMapper;
    private final AdTextSanitizer textSanitizer;
    @Getter
    private final LlmClient llmClient;
    private final ProductDataValidator validator;

    public AdDataCleanerService(
            ObjectMapper objectMapper,
            String llmApiKey,
            String llmApiUrl,
            List<String> defaultCategories,
            String configuredVatRate) {
        this(
                objectMapper,
                new AdTextSanitizer(),
                new LlmClient(objectMapper, llmApiKey, llmApiUrl),
                new ProductDataValidator(defaultCategories, configuredVatRate)
        );
    }

    /**
     * Vnořený record pro strukturovanou JSON odpověď z LLM.
     */
    public record LlmCleanedResponse(
            String name,
            String description,
            BigDecimal price,
            boolean priceNeedsReview,
            String category,
            String condition,
            Map<String, String> parameters
    ) {}

    public CleanedProductDto cleanWithAi(RawAdDto rawAd) throws IOException, InterruptedException {
        return cleanWithAi(rawAd, validator.getDefaultCategories());
    }

    @Autowired
    public AdDataCleanerService(
            ObjectMapper objectMapper,
            AdTextSanitizer textSanitizer,
            LlmClient llmClient,
            ProductDataValidator validator) {
        this.objectMapper = objectMapper;
        this.textSanitizer = textSanitizer;
        this.llmClient = llmClient;
        this.validator = validator;
    }

    /**
     * Ověří, že LLM klient má nakonfigurován platný API klíč.
     */
    public void assertLlmConfigured() {
        llmClient.assertConfigured();
    }

    /**
     * Zavolá LLM pro extrakci dat z inzerátu a aplikuje validaci a sanitizaci.
     */
    public CleanedProductDto cleanWithAi(RawAdDto rawAd, List<String> categories) throws IOException, InterruptedException {
        List<String> categoriesToUse = (categories != null && !categories.isEmpty())
                ? categories
                : validator.getDefaultCategories();

        BigDecimal deterministicPrice = textSanitizer.parseBasicPrice(rawAd.rawPrice(), rawAd.rawDescription());
        String preCleanedDescription = textSanitizer.basicRegexCleanDescription(rawAd.rawDescription());

        String prompt = buildPrompt(rawAd, preCleanedDescription, categoriesToUse);
        String rawLlmResponse = llmClient.callLlmApi(prompt);
        String jsonPayload = llmClient.stripMarkdownCodeFences(rawLlmResponse);

        LlmCleanedResponse aiResult = objectMapper.readValue(jsonPayload, LlmCleanedResponse.class);

        List<String> reviewReasons = new ArrayList<>();
        boolean needsReview = aiResult.priceNeedsReview();

        // 1. name: max 100 znaků, odstranění HTML, garance atomické unikátnosti
        String validatedName = validator.validateAndNormalizeName(aiResult.name(), rawAd.rawTitle(), rawAd.externalId(), reviewReasons);
        if (validatedName.isBlank()) {
            needsReview = true;
            reviewReasons.add("Název je prázdný");
        }

        try {
            // 2. description: strip HTML, max 1000 znaků na hranici věty
            // Fáze 0.3: Porovnání probíhá po stejné normalizaci, aby samotná normalizace nevyvolala falešné NEEDS_REVIEW
            String validatedDesc = validator.validateAndNormalizeDescription(aiResult.description(), preCleanedDescription, reviewReasons);
            String normalizedAiDesc = aiResult.description() != null
                    ? validator.validateAndNormalizeDescription(aiResult.description(), null, new ArrayList<>())
                    : "";
            if (!validatedDesc.equals(normalizedAiDesc)) {
                needsReview = true;
                if (reviewReasons.stream().noneMatch(r -> r.toLowerCase(Locale.ROOT).contains("popis"))) {
                    reviewReasons.add("Popis upraven oproti LLM");
                }
            }

            // 3. price: > 1, max 9_999_999_999.99, porovnání s parseBasicPrice (odchylka > 20 %)
            BigDecimal validatedPrice = validator.validatePrice(aiResult.price(), deterministicPrice, reviewReasons);
            if (validatedPrice == null) {
                needsReview = true;
            }

            // 4. category: přesná shoda s allowlistem
            String validatedCategory = validator.resolveCanonicalCategory(aiResult.category(), categoriesToUse, reviewReasons);

            // 5. vat_rate: bere se z konfigurace
            String validatedVatRate = validator.getConfiguredVatRate();

            // 6. reviewReason: zkráceno max na 200 znaků
            String finalReviewReason = null;
            if (needsReview || !reviewReasons.isEmpty()) {
                needsReview = true;
                if (reviewReasons.isEmpty()) {
                    reviewReasons.add("Vyžaduje kontrolu");
                }
                finalReviewReason = validator.buildReviewReason(reviewReasons);
            }

            String finalCondition = (aiResult.condition() != null && !aiResult.condition().isBlank())
                    ? aiResult.condition().trim().toUpperCase(Locale.ROOT)
                    : "USED";
            Map<String, String> finalParams = aiResult.parameters() != null
                    ? aiResult.parameters()
                    : Collections.emptyMap();
            List<String> finalImages = rawAd.rawImages() != null
                    ? new ArrayList<>(rawAd.rawImages())
                    : Collections.emptyList();

            return new CleanedProductDto(
                    rawAd.externalId(),
                    rawAd.url(),
                    validatedName,
                    validatedDesc,
                    validatedPrice,
                    validatedVatRate,
                    needsReview,
                    finalReviewReason,
                    validatedCategory,
                    finalCondition,
                    finalParams,
                    finalImages
            );
        } catch (Exception e) {
            // Fáze 2.3: Při selhání uvolníme rezervovaný název
            validator.release(validatedName);
            throw e;
        }
    }

    private String buildPrompt(RawAdDto rawAd, String preCleanedDesc, List<String> categories) {
        return """
            Jsi datový analytik pro e-shop. Tvým úkolem je převést surový bazarový inzerát na čistý produkt pro e-shop do tabulky 'products'.
            
            Pravidla pro cílová pole v databázi e-shopu:
            1. 'name' (varchar 100, unikátní název produktu):
               - Z názvu odstraň slova jako "Prodám", "Nabízím", "TOP STAV", vykřičníky apod.
               - Název MUSÍ být stručný, výstižný a MÍT MAXIMÁLNĚ 100 znaků!
            2. 'description' (varchar 1000):
               - Odstraň bazarový balast (informace o platnosti inzerátu, zákazy dobírky, časy kdy volat, telefony, e-maily, odkazy na další inzeráty).
               - Ponechej všechny podstatné technické detaily, rozměry a stav.
               - Popis MUSÍ mít MAXIMÁLNĚ 1000 znaků!
            3. 'price' (numeric 12, 2 v CZK):
               - Urči číselnou cenu v CZK. Pokud je v poli 'Surová cena' text (např. 'V textu', 'Dohodou' nebo '1 Kč'), najdi skutečnou cenu v popisu.
               - Pokud cena v textu není, nastav price na null a priceNeedsReview na true.
            4. 'category' (varchar 255):
               - Vyber nejvhodnější kategorii POUZE z tohoto povoleného seznamu: %s
            5. 'condition' ('NEW' | 'USED' | 'DAMAGED').
            6. 'parameters': mapa klíč-hodnota pro parametry (značka, model apod.).
            
            Vrať POUZE validní JSON odpovídající struktuře:
            {
              "name": "string (max 100 znaků)",
              "description": "string (max 1000 znaků)",
              "price": number nebo null,
              "priceNeedsReview": boolean,
              "category": "string",
              "condition": "NEW" | "USED" | "DAMAGED",
              "parameters": {"klic": "hodnota"}
            }
            
            Surový inzerát:
            Subdoména Bazoše: %s
            Nadpis: %s
            Surová cena: %s
            Popis: %s
            """.formatted(
                categories,
                rawAd.subdomainCategory(),
                rawAd.rawTitle(),
                rawAd.rawPrice(),
                preCleanedDesc
        );
    }

    public static int codePointLength(String str) {
        return ProductDataValidator.codePointLength(str);
    }

    // Delegace pro zjednodušení volání v testech i externích místech
    public void registerExistingName(String name) {
        validator.registerExistingName(name);
    }

    public void registerExistingNames(Collection<String> names) {
        validator.registerExistingNames(names);
    }

    public void releaseName(String name) {
        validator.release(name);
    }

    public BigDecimal parseBasicPrice(String rawPrice, String rawDescription) {
        return textSanitizer.parseBasicPrice(rawPrice, rawDescription);
    }

    public String basicRegexCleanDescription(String rawText) {
        return textSanitizer.basicRegexCleanDescription(rawText);
    }

    public String validateAndNormalizeName(String aiName, String rawTitleFallback, String externalId, List<String> reasons) {
        return validator.validateAndNormalizeName(aiName, rawTitleFallback, externalId, reasons);
    }

    public String validateAndNormalizeDescription(String aiDesc, String preCleanedFallback, List<String> reasons) {
        return validator.validateAndNormalizeDescription(aiDesc, preCleanedFallback, reasons);
    }

    public BigDecimal validatePrice(BigDecimal aiPrice, BigDecimal regexPrice, List<String> reasons) {
        return validator.validatePrice(aiPrice, regexPrice, reasons);
    }

    public String resolveCanonicalCategory(String aiCategory, List<String> allowedCategories, List<String> reasons) {
        return validator.resolveCanonicalCategory(aiCategory, allowedCategories, reasons);
    }

    public String buildReviewReason(List<String> reasons) {
        return validator.buildReviewReason(reasons);
    }

    public String getConfiguredVatRate() {
        return validator.getConfiguredVatRate();
    }

    public String stripMarkdownCodeFences(String rawResponse) {
        return llmClient.stripMarkdownCodeFences(rawResponse);
    }
}
