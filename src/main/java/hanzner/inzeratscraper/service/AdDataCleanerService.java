package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fasáda orchestrující předčištění, LLM analýzu a následnou kódovou validaci inzerátů pro tabulku 'products'.
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AdDataCleanerService {

    private final ObjectMapper objectMapper;
    private final AdTextSanitizer textSanitizer;
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

    /**
     * Vyčistí inzerát pomocí LLM a provede striktní nezávislou validaci v kódu.
     */
    public CleanedProductDto cleanWithAi(RawAdDto rawAd, List<String> myEshopCategories) throws IOException, InterruptedException {
        List<String> categoriesToUse = (myEshopCategories == null || myEshopCategories.isEmpty())
                ? validator.getDefaultCategories()
                : myEshopCategories;

        String preCleanedDescription = textSanitizer.basicRegexCleanDescription(rawAd.rawDescription());
        BigDecimal deterministicPrice = textSanitizer.parseBasicPrice(rawAd.rawPrice(), rawAd.rawDescription());

        String prompt = buildPrompt(rawAd, preCleanedDescription, categoriesToUse);
        String jsonResponse = llmClient.stripMarkdownCodeFences(llmClient.callLlmApi(prompt));
        LlmCleanedResponse aiResult = objectMapper.readValue(jsonResponse, LlmCleanedResponse.class);

        // =========================================================================
        // VALIDACE V KÓDU (NEZÁVISLÁ NA LLM)
        // =========================================================================
        List<String> reviewReasons = new ArrayList<>();
        boolean needsReview = aiResult.priceNeedsReview();
        if (needsReview) {
            reviewReasons.add("Cena označená k revizi");
        }

        // 1. name: normalizace, HTML strip, max 100 znaků, unikátnost
        String validatedName = validator.validateAndNormalizeName(aiResult.name(), rawAd.rawTitle(), rawAd.externalId(), reviewReasons);
        if (validatedName.isBlank()) {
            needsReview = true;
            reviewReasons.add("Název je prázdný");
        }

        // 2. description: strip HTML, max 1000 znaků na hranici věty
        String validatedDesc = validator.validateAndNormalizeDescription(aiResult.description(), preCleanedDescription, reviewReasons);
        String rawAiDesc = aiResult.description() != null
                ? aiResult.description().replaceAll("\r\n|\r", "\n").trim()
                : null;
        if (!validatedDesc.equals(rawAiDesc)) {
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
               - Urči číselnou cenu v CZK. Pokud je v poli 'Surovová cena' text (např. 'V textu', 'Dohodou' nebo '1 Kč'), najdi skutečnou cenu v popisu.
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

    public BigDecimal validatePrice(BigDecimal aiPrice, BigDecimal deterministicPrice, List<String> reasons) {
        return validator.validatePrice(aiPrice, deterministicPrice, reasons);
    }

    public String resolveCanonicalCategory(String aiCategory, List<String> allowlist, List<String> reasons) {
        return validator.resolveCanonicalCategory(aiCategory, allowlist, reasons);
    }

    public String stripMarkdownCodeFences(String rawResponse) {
        return llmClient.stripMarkdownCodeFences(rawResponse);
    }
}
