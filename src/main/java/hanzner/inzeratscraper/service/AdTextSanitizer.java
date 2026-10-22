package hanzner.inzeratscraper.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zajišťuje deterministické předčištění textu inzerátu a extrakci ceny pomocí regulárních výrazů.
 */
@Component
public class AdTextSanitizer {

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:[ .]\\d{3})++|\\d++)\\s*+(?:Kč|CZK|,-)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /**
     * Rychlé deterministické parsování ceny z textu v CZK.
     */
    public BigDecimal parseBasicPrice(String rawPrice, String rawDescription) {
        if (rawPrice != null && !rawPrice.isBlank()) {
            BigDecimal extractedFromRawPrice = extractValidPriceFromToken(rawPrice);
            if (extractedFromRawPrice != null) {
                return extractedFromRawPrice.setScale(2, RoundingMode.HALF_UP);
            }
        }

        if (rawDescription != null && !rawDescription.isBlank()) {
            Matcher matcher = PRICE_PATTERN.matcher(rawDescription);
            while (matcher.find()) {
                String candidate = matcher.group(1);
                BigDecimal val = extractValidPriceFromToken(candidate);
                if (val != null) {
                    return val.setScale(2, RoundingMode.HALF_UP);
                }
            }
        }

        return null;
    }

    public BigDecimal extractValidPriceFromToken(String token) {
        if (token == null) {
            return null;
        }
        String digitsOnly = token.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty() || digitsOnly.length() > 9) {
            return null;
        }
        long val = Long.parseLong(digitsOnly);
        if (val > 1) {
            return BigDecimal.valueOf(val);
        }
        return null;
    }

    /**
     * Základní odstranění bazarového balastu (telefony, maily, fráze "platí do smazání").
     */
    public String basicRegexCleanDescription(String rawText) {
        if (rawText == null) return "";
        return rawText
                .replaceAll("(?iu)inzerát platí do smazání[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)platí do smazání[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)pouze osobní odběr[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)na dobírku neposílám[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)první kontakt\\s*+(?:prosím\\s*+)?(?:pouze\\s*+)?(?:emailem|mailem|e-mailem|sms)[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)podívejte se (?:i )?na (?:mé |moje )?další inzeráty[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)mrkněte (?:i )?na (?:mé |moje )?další inzeráty[^.\\n]*+\\.?", "")
                .replaceAll("(?iu)nevolejte prosím[^.\\n]*+\\.?", "")
                .replaceAll("(?i)(?:e-?mail[ \\t]*+:?[ \\t]*+)?[a-z0-9._%+\\-]++@[a-z0-9\\-]++(?:\\.[a-z0-9\\-]++)++", "")
                .replaceAll("(?i)(?:(?:tel\\.?|telefon|mobil)[ \\t]*+:?[ \\t]*+)?(?:\\+420[ \\-]?)?[67]\\d{2}[ \\-]?\\d{3}[ \\-]?\\d{3}(?!\\d)", "")
                .replaceAll("[ \\t]++\\n", "\n")
                .replaceAll("\\n{3,}+", "\n\n")
                .trim();
    }
}
