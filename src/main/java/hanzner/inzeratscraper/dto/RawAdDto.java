package hanzner.inzeratscraper.dto;

import java.util.List;

public record RawAdDto(String externalId,
                       String url,
                       String subdomainCategory,
                       String rawTitle,
                       String rawDescription,
                       String rawPrice,
                       String rawLocation,
                       List<String> rawImages) {
}
