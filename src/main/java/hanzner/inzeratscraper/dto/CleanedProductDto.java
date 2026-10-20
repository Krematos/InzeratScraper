package hanzner.inzeratscraper.dto;

import java.math.BigDecimal;
import java.util.Map;

public record CleanedProductDto(
        String cleanTitle,
        String cleanDescription,
        BigDecimal price,
        boolean priceNeedsReview, // true, pokud bylo "Dohodou" a cena se nenašla ani v textu
        String suggestedCategory,
        String condition, // NEW, USED, DAMAGED
        Map<String, String> parameters // např. {"Značka": "Bosch", "Stav": "Plně funkční"}
) {
}
