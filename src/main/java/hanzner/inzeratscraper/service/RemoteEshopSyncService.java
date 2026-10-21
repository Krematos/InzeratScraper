package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Service
public class RemoteEshopSyncService {

    private static final String IMPORT_ENDPOINT = "/api/internal/products/import";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String HEADER_INTERNAL_API_KEY = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final String apiKey;
    private final RawScrapedAdRepository rawScrapedAdRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public RemoteEshopSyncService(
            @Value("${eshop.remote.url:http://localhost:8080}") String eshopUrl,
            @Value("${eshop.remote.api-key:}") String apiKey,
            RawScrapedAdRepository rawScrapedAdRepository,
            ObjectMapper objectMapper) {
        this(RestClient.builder().baseUrl(eshopUrl).build(), apiKey, rawScrapedAdRepository, objectMapper);
    }

    public RemoteEshopSyncService(
            RestClient restClient,
            String apiKey,
            RawScrapedAdRepository rawScrapedAdRepository,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.rawScrapedAdRepository = rawScrapedAdRepository;
        this.objectMapper = objectMapper;
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
                    cleanedProduct.externalId(), cleanedProduct.cleanTitle());
        } catch (RestClientResponseException e) {
            log.error("Chyba HTTP {} při odesílání produktu {} do e-shopu. Tělo odpovědi: {}",
                    e.getStatusCode().value(), cleanedProduct.externalId(), e.getResponseBodyAsString());
            throw e;
        }
    }

    /**
     * Dávkově odešle všechny záznamy ve stavu CLEANED (kde priceNeedsReview == false) do e-shopu
     * a po úspěšném přenosu změní jejich stav v lokální DB na SYNCED.
     */
    public int syncAllCleanedAds() {
        List<RawScrapedAd> cleanedAds = rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED);
        int syncedCount = 0;

        for (RawScrapedAd ad : cleanedAds) {
            if (ad.isPriceNeedsReview()) {
                log.info("Přeskakuji synchronizaci inzerátu {} – vyžaduje kontrolu ceny (priceNeedsReview=true).", ad.getExternalId());
                continue;
            }
            if (ad.getCleanedPayloadJson() == null || ad.getCleanedPayloadJson().isBlank()) {
                log.warn("Přeskakuji synchronizaci inzerátu {} – chybí cleanedPayloadJson.", ad.getExternalId());
                continue;
            }

            try {
                CleanedProductDto dto = objectMapper.readValue(ad.getCleanedPayloadJson(), CleanedProductDto.class);
                pushProductToVps(dto);
                ad.setStatus(AdProcessingStatus.SYNCED);
                ad.setErrorMessage(null);
                rawScrapedAdRepository.save(ad);
                syncedCount++;
            } catch (Exception e) {
                log.error("Selhala synchronizace inzerátu {}: {}", ad.getExternalId(), e.getMessage());
                ad.setStatus(AdProcessingStatus.FAILED);
                ad.setErrorMessage(e.getMessage());
                rawScrapedAdRepository.save(ad);
            }
        }

        log.info("Dávková synchronizace dokončena. Úspěšně přeneseno {}/{} vyčištěných inzerátů.",
                syncedCount, cleanedAds.size());
        return syncedCount;
    }
}
