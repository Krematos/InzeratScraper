package hanzner.inzeratscraper.service;

import lombok.Getter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Zajišťuje nezávislou validaci v kódu pro cílovou tabulku 'products'.
 */
@Component
public class ProductDataValidator {

    public static final String DEFAULT_CATEGORY = "Ostatní";
    public static final Set<String> ALLOWED_VAT_RATES = Set.of("STANDARD", "REDUCED", "ZERO");

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    public static final int MAX_REVIEW_REASON_LENGTH = 200;

    public static final BigDecimal MIN_PRICE = BigDecimal.ONE;
    public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");
    public static final BigDecimal PRICE_DEVIATION_THRESHOLD = new BigDecimal("0.20"); // 20 % odchylka

    private static final Pattern CONTROL_AND_EXTRA_WHITESPACE = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("[ \\t]++");
    private static final String NEWLINE_PLACEHOLDER = "___NEWLINE_TOKEN___";

    @Getter
    private final List<String> defaultCategories;
    @Getter
    private final String configuredVatRate;

    // Sledování použitých názvů pro UNIQUE constraint
    private final Set<String> registeredNames = ConcurrentHashMap.newKeySet();

    public ProductDataValidator() {
        this(List.of(DEFAULT_CATEGORY), "STANDARD");
    }

    @Autowired
    public ProductDataValidator(
            @Value("#{'${eshop.categories:Ostatní}'.split(',')}") List<String> defaultCategories,
            @Value("${eshop.vat-rate:STANDARD}") String configuredVatRate) {
        this.defaultCategories = defaultCategories != null && !defaultCategories.isEmpty()
                ? defaultCategories
                : List.of(DEFAULT_CATEGORY);

        String vatUpper = configuredVatRate != null ? configuredVatRate.trim().toUpperCase(Locale.ROOT) : "";
        if (!ALLOWED_VAT_RATES.contains(vatUpper)) {
            throw new IllegalArgumentException("Neplatná konfigurace eshop.vat-rate: '" + configuredVatRate
                    + "'. Povolené hodnoty jsou: " + ALLOWED_VAT_RATES);
        }
        this.configuredVatRate = vatUpper;
    }

    /**
     * Zaregistruje známý název (např. z DB nebo e-shopu) pro detekci duplicit.
     */
    public void registerExistingName(String name) {
        if (name != null && !name.isBlank()) {
            registeredNames.add(name.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Normalizace a validace názvu: strip řídicích znaků a HTML, unescape entit, max 100 znaků, unikátnost.
     */
    public String validateAndNormalizeName(String aiName, String rawTitleFallback, String externalId, List<String> reasons) {
        String base = (aiName != null && !aiName.isBlank()) ? aiName : rawTitleFallback;
        if (base == null) {
            return "";
        }

        String stripped = CONTROL_AND_EXTRA_WHITESPACE.matcher(base).replaceAll("");
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        stripped = Jsoup.clean(stripped, "", Safelist.none(), settings);
        stripped = Parser.unescapeEntities(stripped, false);
        stripped = stripped.replace('\r', ' ').replace('\n', ' ');
        stripped = MULTI_WHITESPACE.matcher(stripped).replaceAll(" ").trim();

        if (stripped.isBlank()) {
            return "";
        }

        if (codePointLength(stripped) > MAX_NAME_LENGTH) {
            stripped = truncateOnWordBoundary(stripped, MAX_NAME_LENGTH);
            reasons.add("Název zkrácen na 100 znaků");
        }

        String uniqueName = ensureNameUniqueness(stripped, externalId);
        if (!uniqueName.equals(stripped)) {
            reasons.add("Název upraven pro unikátnost");
        }
        registeredNames.add(uniqueName.toLowerCase(Locale.ROOT));
        return uniqueName;
    }

    public String ensureNameUniqueness(String baseName, String externalId) {
        String lowerKey = baseName.toLowerCase(Locale.ROOT);
        if (!registeredNames.contains(lowerKey)) {
            return baseName;
        }

        String suffix = buildSuffix(externalId);
        int maxAllowedBase = MAX_NAME_LENGTH - codePointLength(suffix);
        String truncatedBase = codePointLength(baseName) > maxAllowedBase
                ? truncateOnWordBoundary(baseName, maxAllowedBase)
                : baseName;
        return truncatedBase + suffix;
    }

    private String buildSuffix(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return " #" + (System.currentTimeMillis() % 100000);
        }
        String digitsOnly = externalId.replaceAll("[^0-9]", "");
        if (digitsOnly.length() >= 5) {
            return " #" + digitsOnly.substring(digitsOnly.length() - 5);
        }
        return " #" + externalId;
    }

    /**
     * Očištění a zkrácení popisu: zachování odřádkování, Safelist.none(), unescape entit, max 1000 znaků na hranici věty.
     */
    public String validateAndNormalizeDescription(String aiDesc, String preCleanedFallback, List<String> reasons) {
        String base = (aiDesc != null && !aiDesc.isBlank()) ? aiDesc : preCleanedFallback;
        if (base == null || base.isBlank()) {
            return "";
        }

        String cleaned = CONTROL_AND_EXTRA_WHITESPACE.matcher(base).replaceAll("");
        cleaned = cleaned.replaceAll("\r\n|\r", "\n");
        cleaned = cleaned.replaceAll("(?i)<br\\s*/?>", "\n");
        cleaned = cleaned.replaceAll("(?i)</?p[^>]*>", "\n");

        // Fáze 0.1: Zachování zalomení řádků před čištěním Jsoupem pomocí placeholderu
        cleaned = cleaned.replace("\n", NEWLINE_PLACEHOLDER);

        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        cleaned = Jsoup.clean(cleaned, "", Safelist.none(), settings);
        cleaned = Parser.unescapeEntities(cleaned, false);
        cleaned = cleaned.replace(NEWLINE_PLACEHOLDER, "\n");

        cleaned = MULTI_WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("[ \\t]+\\n", "\n");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        cleaned = cleaned.trim();

        if (codePointLength(cleaned) > MAX_DESCRIPTION_LENGTH) {
            reasons.add("Popis zkrácen na 1000 znaků");
            return truncateOnSentenceBoundary(cleaned, MAX_DESCRIPTION_LENGTH);
        }
        return cleaned;
    }

    /**
     * Validace ceny: limit 1 Kč až 9 999 999 999.99 Kč, 2 desetinná místa, porovnání s regex odhadem (max 20 % odchylka).
     */
    public BigDecimal validatePrice(BigDecimal aiPrice, BigDecimal regexPrice, List<String> reasons) {
        if (aiPrice == null || aiPrice.compareTo(MIN_PRICE) <= 0) {
            reasons.add("Cena nenalezena nebo <= 1 Kč");
            return null;
        }

        if (aiPrice.compareTo(MAX_PRICE) > 0) {
            reasons.add("Cena překračuje limit 9 999 999 999.99");
            return null;
        }

        BigDecimal scaledPrice = aiPrice.setScale(2, RoundingMode.HALF_UP);

        if (regexPrice != null && regexPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = scaledPrice.subtract(regexPrice).abs();
            BigDecimal allowedDiff = regexPrice.multiply(PRICE_DEVIATION_THRESHOLD);
            if (diff.compareTo(allowedDiff) > 0) {
                reasons.add("Cena AI se liší od inzerátu o více než 20 % (AI: " + scaledPrice + ", Inzerát: " + regexPrice + ")");
            }
        }

        return scaledPrice;
    }

    /**
     * Mapování kategorie: přesná shoda (case-insensitive) proti allowlistu, jinak fallback na 'Ostatní' + revize.
     */
    public String resolveCanonicalCategory(String aiCategory, List<String> allowedCategories, List<String> reasons) {
        if (aiCategory != null && !aiCategory.isBlank()) {
            String trimmedAi = aiCategory.trim();
            for (String allowed : allowedCategories) {
                if (allowed.equalsIgnoreCase(trimmedAi)) {
                    return allowed; // Zachování přesného zápisu velikosti písmen z allowlistu
                }
            }
        }
        reasons.add("Kategorie nenalezena v allowlistu");
        return allowedCategories.contains(DEFAULT_CATEGORY) ? DEFAULT_CATEGORY : allowedCategories.get(0);
    }

    /**
     * Sestaví souhrnný důvod pro potřebu ruční kontroly (max 200 znaků).
     */
    public String buildReviewReason(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        String combined = String.join("; ", reasons);
        if (codePointLength(combined) > MAX_REVIEW_REASON_LENGTH) {
            return truncateOnWordBoundary(combined, MAX_REVIEW_REASON_LENGTH);
        }
        return combined;
    }

    public static int codePointLength(String str) {
        return str != null ? str.codePointCount(0, str.length()) : 0;
    }

    public static String truncateOnWordBoundary(String str, int maxCodePoints) {
        if (str == null || codePointLength(str) <= maxCodePoints) {
            return str;
        }

        int targetIndex = str.offsetByCodePoints(0, maxCodePoints);
        int lastSpace = str.lastIndexOf(' ', targetIndex);
        if (lastSpace > 0) {
            return str.substring(0, lastSpace).trim();
        }
        return str.substring(0, targetIndex).trim();
    }

    public static String truncateOnSentenceBoundary(String str, int maxCodePoints) {
        if (str == null || codePointLength(str) <= maxCodePoints) {
            return str;
        }

        int targetIndex = str.offsetByCodePoints(0, maxCodePoints);
        int boundary = -1;
        for (int i = targetIndex - 1; i >= 0; i--) {
            char c = str.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                boundary = i + 1;
                break;
            }
        }

        if (boundary > 0) {
            return str.substring(0, boundary).trim();
        }
        return truncateOnWordBoundary(str, maxCodePoints);
    }
}
