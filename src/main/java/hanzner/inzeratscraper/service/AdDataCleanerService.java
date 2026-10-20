package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AdDataCleanerService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d+[\\s\\d]*)\\s*(Kč|,-)", Pattern.CASE_INSENSITIVE);

    public AdDataCleanerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 1. Rychlé deterministické parsování ceny v Javě
     */
    public BigDecimal parseBasicPrice(String rawPrice, String rawDescription) {
        if (rawPrice != null) {
            String digitsOnly = rawPrice.replaceAll("[^0-9]", "");
            if (!digitsOnly.isEmpty()) {
                long val = Long.parseLong(digitsOnly);
                // Na Bazoši lidi často dávají "1 Kč" jako symbolickou cenu, když je cena v textu
                if (val > 1) {
                    return BigDecimal.valueOf(val);
                }
            }
        }
        // Fallback: Zkusíme najít cenu v textu pomocí Regexu (např. "Cena: 1 500 Kč" nebo "450,-")
        Matcher matcher = PRICE_PATTERN.matcher(rawDescription);
        if (matcher.find()) {
            String foundPrice = matcher.group(1).replaceAll("[^0-9]", "");
            if (!foundPrice.isEmpty()) {
                return new BigDecimal(foundPrice);
            }
        }
        return null; // Cena nenalezena -> vyřeší AI nebo označíme k ruční kontrole
    }

    /**
     * 2. Základní očištění textu pomocí Regexů (pokud bys nechtěl použít AI)
     */
    public String basicRegexCleanDescription(String rawText) {
        if (rawText == null) return "";
        return rawText
                // Odstranění typických bazarových frází (case-insensitive)
                .replaceAll("(?i)inzerát platí do smazání\\.?", "")
                .replaceAll("(?i)platí do smazání\\.?", "")
                .replaceAll("(?i)pouze osobní odběr.*", "")
                .replaceAll("(?i)na dobírku neposílám.*", "")
                .replaceAll("(?i)první kontakt (prosím )?(pouze )?(emailem|mailem|sms).*", "")
                // Sjednocení vícenásobných odřádkování
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 3. Pokročilé čištění a strukturování přes LLM (vrací rovnou CleanedProductDto)
     */
    public CleanedProductDto cleanWithAi(RawAdDto rawAd, List<String> myEshopCategories) throws Exception {
        String prompt = """
            Jsi datový analytik pro e-shop. Tvým úkolem je převést surový bazarový inzerát na čistý produkt pro e-shop.
            
            Pravidla:
            1. Z názvu odstraň slova jako "Prodám", "Nabízím", "TOP STAV", vykřičníky apod.
            2. Z popisu odstraň bazarový balast (informace o tom, že inzerát platí do smazání, zákazy dobírky, časy kdy volat, telefonní čísla). Zachovej všechny technické detaily, rozměry a stav.
            3. Urči číselnou cenu v CZK. Pokud je v poli 'Surová cena' text (např. 'V textu' nebo '1 Kč'), najdi skutečnou cenu v popisu. Pokud tam není, nastav price na null a priceNeedsReview na true.
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
            Nadpis: %s
            Surová cena: %s
            Popis: %s
            """.formatted(myEshopCategories, rawAd.rawTitle(), rawAd.rawPrice(), rawAd.rawDescription());

        // Zde zavolá REST API (např. Gemini API) a výsledek se namapuje přes Jackson:
        String jsonResponseFromAi = callLlmApi(prompt);
        return objectMapper.readValue(jsonResponseFromAi, CleanedProductDto.class);
    }

    private String callLlmApi(String prompt) {
        // Implementace HTTP POST dotazu na LLM API (Gemini)
        // ...
        return "{}";
    }
}
