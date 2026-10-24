package hanzner.inzeratscraper.dto;

import java.math.BigDecimal;

/**
 * DTO pro položky vyžadující ruční revizi.
 */
public record ReviewItemDto(
        String externalId,
        String name,
        BigDecimal price,
        String reviewReason,
        String status
) {}
