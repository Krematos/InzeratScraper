package hanzner.inzeratscraper.service;

import lombok.Getter;
import org.jsoup.Jsoup;
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
     * Normalizace a validace názvu: strip řídicích znaků a HTML, max 100 znaků, unikátnost.
     */
    public String validateAndNormalizeName(String aiName, String rawTitleFallback, String externalId, List<String> reasons) {
        String base = (aiName != null && !aiName.isBlank()) ? aiName : rawTitleFallback;
        if (base == null) {
            return "";
        }

        String stripped = CONTROL_AND_EXTRA_WHITESPACE.matcher(base).replaceAll("");
        stripped = Jsoup.clean(stripped, Safelist.none());
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
     * Očištění a zkrácení popisu: Safelist.none(), max 1000 znaků na hranici věty.
     */
    public String validateAndNormalizeDescription(String aiDesc, String preCleanedFallback, List<String> reasons) {
        String base = (aiDesc != null && !aiDesc.isBlank()) ? aiDesc : preCleanedFallback;
        if (base == null || base.isBlank()) {
            return "";
        }

        String cleaned = CONTROL_AND_EXTRA_WHITESPACE.matcher(base).replaceAll("");
        cleaned = Jsoup.clean(cleaned, Safelist.none());
        cleaned = MULTI_WHITESPACE.matcher(cleaned).replaceAll(" ").trim();

        if (codePointLength(cleaned) > MAX_DESCRIPTION_LENGTH) {
            reasons.add("Popis zkrácen na 1000 znaků");
            return truncateOnSentenceBoundary(cleaned, MAX_DESCRIPTION_LENGTH);
        }
        return cleaned;
    }

    /**
     * Validace ceny: > 1, <= 9_999_999_999.99, porovnání s parseBasicPrice (odchylka > 20 %).
     */
    public BigDecimal validatePrice(BigDecimal aiPrice, BigDecimal deterministicPrice, List<String> reasons) {
        BigDecimal candidate = aiPrice;

        if (candidate == null || candidate.compareTo(MIN_PRICE) <= 0) {
            if (deterministicPrice != null && deterministicPrice.compareTo(MIN_PRICE) > 0) {
                candidate = deterministicPrice;
            } else {
                reasons.add("Cena nenalezena nebo <= 1 Kč");
                return null;
            }
        }

        if (candidate.compareTo(MAX_PRICE) > 0) {
            reasons.add("Cena překračuje limit 9 999 999 999.99");
            return null;
        }

        candidate = candidate.setScale(2, RoundingMode.HALF_UP);

        if (deterministicPrice != null && deterministicPrice.compareTo(MIN_PRICE) > 0) {
            BigDecimal diff = candidate.subtract(deterministicPrice).abs();
            BigDecimal threshold = deterministicPrice.multiply(PRICE_DEVIATION_THRESHOLD);
            if (diff.compareTo(threshold) > 0) {
                reasons.add(String.format(Locale.ROOT, "Cena AI (%.2f) se liší od regexu (%.2f) o více než 20 %%",
                        candidate, deterministicPrice));
            }
        }

        return candidate;
    }

    /**
     * Vyhledání kategorie v allowlistu s návratem kanonického znění.
     */
    public String resolveCanonicalCategory(String aiCategory, List<String> allowlist, List<String> reasons) {
        if (aiCategory != null && !aiCategory.isBlank()) {
            String trimmed = aiCategory.trim();
            for (String allowed : allowlist) {
                if (allowed.equalsIgnoreCase(trimmed)) {
                    return allowed;
                }
            }
        }
        reasons.add("Kategorie nenalezena v allowlistu");
        return allowlist.contains(DEFAULT_CATEGORY) ? DEFAULT_CATEGORY : allowlist.getFirst();
    }

    /**
     * Složení důvodů revize s limitem 200 znaků.
     */
    public String buildReviewReason(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        String joined = String.join("; ", reasons);
        if (codePointLength(joined) > MAX_REVIEW_REASON_LENGTH) {
            return truncateSafely(joined, MAX_REVIEW_REASON_LENGTH);
        }
        return joined;
    }

    public static int codePointLength(String str) {
        return str != null ? str.codePointCount(0, str.length()) : 0;
    }

    public static String truncateOnWordBoundary(String text, int maxCodePoints) {
        if (codePointLength(text) <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        String sub = text.substring(0, endIndex);
        int lastSpace = sub.lastIndexOf(' ');
        if (lastSpace > maxCodePoints / 2) {
            return sub.substring(0, lastSpace).trim();
        }
        return sub.trim();
    }

    public static String truncateOnSentenceBoundary(String text, int maxCodePoints) {
        if (codePointLength(text) <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        String sub = text.substring(0, endIndex);

        int lastPeriod = Math.max(sub.lastIndexOf(". "), Math.max(sub.lastIndexOf(".\n"), sub.lastIndexOf("! ")));
        if (lastPeriod > maxCodePoints / 2) {
            return sub.substring(0, lastPeriod + 1).trim();
        }
        return truncateOnWordBoundary(text, maxCodePoints);
    }

    private String truncateSafely(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (codePointLength(trimmed) <= maxCodePoints) {
            return trimmed;
        }
        int endIndex = trimmed.offsetByCodePoints(0, maxCodePoints);
        return trimmed.substring(0, endIndex).trim();
    }
}
