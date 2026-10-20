package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AdDataCleanerService {

    private static final String KEY_CONTENT = "content";
    private static final String DEFAULT_CATEGORY = "Ostatní";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String llmApiKey;
    private final String llmApiUrl;
    private final List<String> defaultCategories;

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:[ .]\\d{3})++|\\d++)\\s*+(?:Kč|CZK|,-)",
            Pattern.CASE_INSENSITIVE
    );

    public AdDataCleanerService(ObjectMapper objectMapper) {
        this(objectMapper, "", "", List.of(DEFAULT_CATEGORY));
    }

    @Autowired
    public AdDataCleanerService(
            ObjectMapper objectMapper,
            @Value("${llm.api.key:}") String llmApiKey,
            @Value("${llm.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}") String llmApiUrl,
            @Value("#{'${eshop.categories:Ostatní}'.split(',')}") List<String> defaultCategories) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.llmApiKey = llmApiKey;
        this.llmApiUrl = llmApiUrl;
        this.defaultCategories = defaultCategories != null ? defaultCategories : List.of(DEFAULT_CATEGORY);
    }

    /**
     * Vnořený record pro surovou JSON odpověď z LLM před obohacením o externalId, sourceUrl a imageUrls.
     */
    public record LlmCleanedResponse(
            String cleanTitle,
            String cleanDescription,
            BigDecimal price,
            boolean priceNeedsReview,
            String suggestedCategory,
            String condition,
            Map<String, String> parameters
    ) {}

    /**
     * 1. Rychlé deterministické parsování ceny v Javě.
     * Ošetřuje null hodnoty, mezery/tečky v číslech (např. "1 500 Kč", "1.500,-") a ignoruje symbolické ceny <= 1 Kč.
     */
    public BigDecimal parseBasicPrice(String rawPrice, String rawDescription) {
        if (rawPrice != null && !rawPrice.isBlank()) {
            BigDecimal extractedFromRawPrice = extractValidPriceFromToken(rawPrice);
            if (extractedFromRawPrice != null) {
                return extractedFromRawPrice;
            }
        }

        // Fallback: Zkusíme najít cenu v textu pomocí Regexu (např. "Cena: 1 500 Kč", "1.500,-" nebo "450,-")
        if (rawDescription != null && !rawDescription.isBlank()) {
            Matcher matcher = PRICE_PATTERN.matcher(rawDescription);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                BigDecimal val = extractValidPriceFromToken(candidate);
                if (val != null) {
                    return val;
                }
            }
        }

        return null; // Cena nenalezena -> vyřeší AI nebo označíme k ruční kontrole
    }

    private BigDecimal extractValidPriceFromToken(String token) {
        if (token == null) {
            return null;
        }
        String digitsOnly = token.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty() || digitsOnly.length() > 9) {
            return null;
        }
        long val = Long.parseLong(digitsOnly);
        // Na Bazoši lidi často dávají "1 Kč" jako symbolickou cenu, když je cena v textu
        if (val > 1) {
            return BigDecimal.valueOf(val);
        }
        return null;
    }

    /**
     * 2. Základní očištění textu pomocí Regexů (odstranění bazarového balastu, telefonů, e-mailů).
     */
    public String basicRegexCleanDescription(String rawText) {
        if (rawText == null) return "";
        return rawText
                // Odstranění typických bazarových frází (case-insensitive, bez super-lineárního backtrackingu)
                .replaceAll("(?i)inzerát platí do smazání[^.\\n]*+\\.?", "")
                .replaceAll("(?i)platí do smazání[^.\\n]*+\\.?", "")
                .replaceAll("(?i)pouze osobní odběr[^.\\n]*+\\.?", "")
                .replaceAll("(?i)na dobírku neposílám[^.\\n]*+\\.?", "")
                .replaceAll("(?i)první kontakt\\s*+(?:prosím\\s*+)?(?:pouze\\s*+)?(?:emailem|mailem|e-mailem|sms)[^.\\n]*+\\.?", "")
                .replaceAll("(?i)podívejte se (?:i )?na (?:mé |moje )?další inzeráty[^.\\n]*+\\.?", "")
                .replaceAll("(?i)mrkněte (?:i )?na (?:mé |moje )?další inzeráty[^.\\n]*+\\.?", "")
                .replaceAll("(?i)nevolejte prosím[^.\\n]*+\\.?", "")
                // Odstranění e-mailových adres
                .replaceAll("(?i)[a-z0-9._%+\\-]++@[a-z0-9\\-]++(?:\\.[a-z0-9\\-]++)*+\\.[a-z]{2,}+", "")
                // Odstranění českých telefonních čísel (např. +420 777 123 456, 777123456, tel: 602 111 222)
                .replaceAll("(?i)(?:(?:tel\\.?|telefon|mobil)\\s*+:?\\s*+)?(?:\\+420\\s*+)?[67]\\d{2}[ \\-]?\\d{3}[ \\-]?\\d{3}\\b", "")
                // Sjednocení vícenásobných odřádkování a mezer
                .replaceAll("[ \\t]++\\n", "\n")
                .replaceAll("\\n{3,}+", "\n\n")
                .trim();
    }

    /**
     * Přetížení využívající výchozí kategorie z application.properties.
     */
    public CleanedProductDto cleanWithAi(RawAdDto rawAd) throws IOException, InterruptedException {
        return cleanWithAi(rawAd, this.defaultCategories);
    }

    /**
     * 3. Pokročilé čištění a strukturování přes LLM (vrací rovnou obohacený CleanedProductDto).
     */
    public CleanedProductDto cleanWithAi(RawAdDto rawAd, List<String> myEshopCategories) throws IOException, InterruptedException {
        List<String> categoriesToUse = (myEshopCategories == null || myEshopCategories.isEmpty())
                ? this.defaultCategories
                : myEshopCategories;

        String preCleanedDescription = basicRegexCleanDescription(rawAd.rawDescription());
        BigDecimal deterministicPrice = parseBasicPrice(rawAd.rawPrice(), rawAd.rawDescription());

        String prompt = """
            Jsi datový analytik pro e-shop. Tvým úkolem je převést surový bazarový inzerát na čistý produkt pro e-shop.
            
            Pravidla:
            1. Z názvu odstraň slova jako "Prodám", "Nabízím", "TOP STAV", vykřičníky apod.
            2. Z popisu odstraň bazarový balast (informace o tom, že inzerát platí do smazání, zákazy dobírky, časy kdy volat, telefonní čísla, odkazy na další inzeráty). Zachovej všechny technické detaily, rozměry a stav.
            3. Urči číselnou cenu v CZK. Pokud je v poli 'Surová cena' text (např. 'V textu', 'Dohodou' nebo '1 Kč'), najdi skutečnou cenu v popisu. Pokud tam není, nastav price na null a priceNeedsReview na true.
            4. Vyber nejvhodnější kategorii POUZE z tohoto seznamu: %s
            5. Extrahuj klíčové parametry (značka, rozměry, materiál apod.) do mapy 'parameters'.
            
            Vrať POUZE validní JSON odpovídající struktuře:
            {
              "cleanTitle": "string",
              "cleanDescription": "string",
              "price": number nebo null,
              "priceNeedsReview": boolean,
              "suggestedCategory": "string",
              "condition": "NEW" | "USED" | "DAMAGED",
              "parameters": {"klic": "hodnota"}
            }
            
            Surový inzerát:
            Subdoména Bazoše: %s
            Nadpis: %s
            Surová cena: %s
            Popis: %s
            """.formatted(
                categoriesToUse,
                rawAd.subdomainCategory(),
                rawAd.rawTitle(),
                rawAd.rawPrice(),
                preCleanedDescription
        );

        String jsonResponseFromAi = stripMarkdownCodeFences(callLlmApi(prompt));
        LlmCleanedResponse aiResult = objectMapper.readValue(jsonResponseFromAi, LlmCleanedResponse.class);

        // Post-validace ceny:
        BigDecimal finalPrice = aiResult.price();
        boolean finalNeedsReview = aiResult.priceNeedsReview();

        if (finalPrice == null || finalPrice.compareTo(BigDecimal.ONE) <= 0) {
            if (deterministicPrice != null && deterministicPrice.compareTo(BigDecimal.ONE) > 0) {
                finalPrice = deterministicPrice;
            } else {
                finalPrice = null;
                finalNeedsReview = true;
            }
        }

        String finalTitle = (aiResult.cleanTitle() != null && !aiResult.cleanTitle().isBlank())
                ? aiResult.cleanTitle().trim()
                : rawAd.rawTitle();
        String finalDesc = (aiResult.cleanDescription() != null && !aiResult.cleanDescription().isBlank())
                ? aiResult.cleanDescription().trim()
                : preCleanedDescription;
        String finalCategory = (aiResult.suggestedCategory() != null && !aiResult.suggestedCategory().isBlank())
                ? aiResult.suggestedCategory().trim()
                : categoriesToUse.getFirst();
        String finalCondition = (aiResult.condition() != null && !aiResult.condition().isBlank())
                ? aiResult.condition().trim()
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
                finalTitle,
                finalDesc,
                finalPrice,
                finalNeedsReview,
                finalCategory,
                finalCondition,
                finalParams,
                finalImages
        );
    }

    /**
     * Očistí případné markdown bloky (```json ... ```) z odpovědi LLM bez použití regexu s backtrackingem.
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

    /**
     * Zavolá nakonfigurované LLM API (Gemini nebo OpenAI kompatibilní endpoint) s podporou retry při HTTP 429.
     */
    protected String callLlmApi(String prompt) throws IOException, InterruptedException {
        if (llmApiKey == null || llmApiKey.isBlank() || "replace-with-api-key".equals(llmApiKey)) {
            throw new IllegalStateException("LLM API klíč (llm.api.key / LLM_API_KEY) není nastaven v konfiguraci.");
        }

        boolean isGemini = llmApiUrl.contains("googleapis.com") || llmApiUrl.contains("gemini");
        String requestUrl = llmApiUrl;
        String requestBodyJson;

        if (isGemini) {
            if (!requestUrl.contains("key=")) {
                requestUrl += (requestUrl.contains("?") ? "&" : "?") + "key=" + llmApiKey;
            }
            Map<String, Object> geminiPayload = new LinkedHashMap<>();
            geminiPayload.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));
            geminiPayload.put("generationConfig", Map.of(
                    "responseMimeType", "application/json",
                    "temperature", 0.1
            ));
            requestBodyJson = objectMapper.writeValueAsString(geminiPayload);
        } else {
            Map<String, Object> openAiPayload = new LinkedHashMap<>();
            openAiPayload.put("model", "gpt-4o-mini");
            openAiPayload.put("messages", List.of(
                    Map.of("role", "user", KEY_CONTENT, prompt)
            ));
            openAiPayload.put("response_format", Map.of("type", "json_object"));
            openAiPayload.put("temperature", 0.1);
            requestBodyJson = objectMapper.writeValueAsString(openAiPayload);
        }

        int maxRetries = 3;
        int attempt = 0;

        while (true) {
            attempt++;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson));

            if (!isGemini) {
                reqBuilder.header("Authorization", "Bearer " + llmApiKey);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return extractTextFromLlmResponse(response.body(), isGemini);
            }

            if (status == 429 && attempt <= maxRetries) {
                long backoffMs = 2000L * attempt;
                log.warn("LLM API rate-limit (HTTP 429), pokus {}/{}. Čekám {} ms před opakováním...", attempt, maxRetries, backoffMs);
                Thread.sleep(backoffMs);
                continue;
            }

            throw new IOException("Chyba při volání LLM API (HTTP " + status + "): " + response.body());
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
}
