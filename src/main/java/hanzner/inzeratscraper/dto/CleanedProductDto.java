package hanzner.inzeratscraper.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DTO odpovídající struktuře V1 tabulky `products` v cílovém e-shopu:
 * - name: varchar(100) UNIQUE NOT NULL
 * - description: varchar(1000) NOT NULL
 * - price: numeric(12, 2) NOT NULL
 * - vatRate: varchar(20) NOT NULL ('STANDARD', 'REDUCED', 'ZERO')
 * - category: varchar(255) NULL
 * - externalId, sourceUrl, imageUrls, condition, parameters: doplňková metadata pro integraci a fotky
 */
public record CleanedProductDto(
        String externalId,
        String sourceUrl,
        String name,                    // max 100 znaků, unikátní název produktu v e-shopu
        String description,             // max 1000 znaků, očištěný popis pro e-shop
        BigDecimal price,               // numeric(12, 2)
        String vatRate,                 // STANDARD, REDUCED, ZERO
        boolean priceNeedsReview,       // true, pokud cena nebyla jasná a vyžaduje kontrolu
        String category,                // varchar(255)
        String condition,               // NEW, USED, DAMAGED
        Map<String, String> parameters, // doplňkové parametry
        List<String> imageUrls          // URL fotek pro stažení do e-shopu
) {
    // Pomocné gettery pro zpětnou kompatibilitu, pokud by starší kód volal cleanTitle/cleanDescription
    public String cleanTitle() {
        return name();
    }

    public String cleanDescription() {
        return description();
    }

    public String suggestedCategory() {
        return category();
    }
}
