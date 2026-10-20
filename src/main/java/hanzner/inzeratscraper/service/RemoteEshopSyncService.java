package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class RemoteEshopSyncService {

    private final RestClient restClient;
    private final String apiKey;

    public RemoteEshopSyncService(
            @Value("${eshop.remote.url}") String eshopUrl, // např. https://tvuj-eshop.cz
            @Value("${eshop.remote.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(eshopUrl)
                .build();
    }

    public void pushProductToVps(CleanedProductDto cleanedProduct) {
        restClient.post()
                .uri("/api/internal/import/product")
                .header("X-Internal-Api-Key", apiKey) // Tajný klíč pro autentizaci
                .contentType(MediaType.APPLICATION_JSON)
                .body(cleanedProduct)
                .retrieve()
                .toBodilessEntity();
    }
}
