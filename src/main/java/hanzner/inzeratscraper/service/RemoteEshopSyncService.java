package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Service
public class RemoteEshopSyncService {

    private static final String IMPORT_ENDPOINT = "/api/internal/products/import";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_INTERNAL_API_KEY = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public RemoteEshopSyncService(
            @Value("${eshop.remote.url:http://localhost:8080}") String eshopUrl,
            @Value("${eshop.remote.api-key:}") String apiKey) {
        this(RestClient.builder().baseUrl(eshopUrl).build(), apiKey);
    }

    public RemoteEshopSyncService(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    /**
     * Odešle vyčištěný produkt přes HTTPS na interní endpoint e-shopu chráněný API klíčem.
     */
    public void pushProductToVps(CleanedProductDto cleanedProduct) {
        try {
            restClient.post()
                    .uri(IMPORT_ENDPOINT)
                    .header(HEADER_API_KEY, apiKey)
                    .header(HEADER_INTERNAL_API_KEY, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cleanedProduct)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Produkt {} ('{}') byl úspěšně odeslán do e-shopu.",
                    cleanedProduct.externalId(), cleanedProduct.name());
        } catch (RestClientResponseException e) {
            log.error("Chyba HTTP {} při odesílání produktu {} do e-shopu. Tělo odpovědi: {}",
                    e.getStatusCode().value(), cleanedProduct.externalId(), e.getResponseBodyAsString());
            throw e;
        }
    }
}
