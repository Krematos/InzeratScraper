package hanzner.inzeratscraper;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import hanzner.inzeratscraper.service.RemoteEshopSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoteEshopSyncServiceTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Mock
    private RawScrapedAdRepository rawScrapedAdRepository;

    private ObjectMapper objectMapper;
    private RemoteEshopSyncService syncService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        syncService = new RemoteEshopSyncService(restClient, "test-api-key", rawScrapedAdRepository, objectMapper);
    }

    private void mockSuccessfulRestClientCall() {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/api/internal/products/import")).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(CleanedProductDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    @Test
    void testPushProductToVpsSuccess() {
        mockSuccessfulRestClientCall();

        CleanedProductDto dto = new CleanedProductDto(
                "209172265",
                "https://stroje.bazos.cz/inzerat/209172265/vrtacka.php",
                "Vrtačka Bosch",
                "Plně funkční vrtačka.",
                BigDecimal.valueOf(1500),
                false,
                "Nářadí",
                "USED",
                Map.of("Značka", "Bosch"),
                List.of("https://www.bazos.cz/img/1/265/209172265.jpg")
        );

        syncService.pushProductToVps(dto);

        verify(restClient).post();
        verify(requestBodyUriSpec).uri("/api/internal/products/import");
        verify(requestBodySpec).header("X-API-Key", "test-api-key");
        verify(requestBodySpec).header("X-Internal-Api-Key", "test-api-key");
        verify(requestBodySpec).body(dto);
        verify(responseSpec).toBodilessEntity();
    }

    @Test
    void testPushProductToVpsRethrowsRestClientResponseException() {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/api/internal/products/import")).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(CleanedProductDto.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "{\"error\":\"Invalid API key\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8
        ));

        CleanedProductDto dto = new CleanedProductDto(
                "209172265", "url", "Title", "Desc", BigDecimal.TEN, false, "Nářadí", "USED", Map.of(), List.of()
        );

        assertThrows(RestClientResponseException.class, () -> syncService.pushProductToVps(dto));
    }

    @Test
    void testSyncAllCleanedAdsUpdatesStatusAndSkipsReviewOrBlankPayload() {
        mockSuccessfulRestClientCall();

        CleanedProductDto validDto = new CleanedProductDto(
                "1001", "url1", " Vrtačka ", "Popis", BigDecimal.valueOf(1200), false, "Nářadí", "USED", Map.of(), List.of()
        );
        String validJson = objectMapper.writeValueAsString(validDto);

        RawScrapedAd readyAd = RawScrapedAd.builder()
                .externalId("1001")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson(validJson)
                .build();

        RawScrapedAd needsReviewAd = RawScrapedAd.builder()
                .externalId("1002")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(true)
                .cleanedPayloadJson(validJson)
                .build();

        RawScrapedAd blankPayloadAd = RawScrapedAd.builder()
                .externalId("1003")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson("   ")
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED))
                .thenReturn(List.of(readyAd, needsReviewAd, blankPayloadAd));

        int syncedCount = syncService.syncAllCleanedAds();

        assertEquals(1, syncedCount);
        assertEquals(AdProcessingStatus.SYNCED, readyAd.getStatus());
        assertNull(readyAd.getErrorMessage());
        verify(rawScrapedAdRepository, times(1)).save(readyAd);
        verify(rawScrapedAdRepository, never()).save(needsReviewAd);
        verify(rawScrapedAdRepository, never()).save(blankPayloadAd);
    }

    @Test
    void testSyncAllCleanedAdsMarksAdAsFailedOnException() {
        CleanedProductDto validDto = new CleanedProductDto(
                "1001", "url1", "Vrtačka", "Popis", BigDecimal.valueOf(1200), false, "Nářadí", "USED", Map.of(), List.of()
        );
        String validJson = objectMapper.writeValueAsString(validDto);

        RawScrapedAd failingAd = RawScrapedAd.builder()
                .externalId("1001")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson(validJson)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(failingAd));
        when(restClient.post()).thenThrow(new RuntimeException("Connection refused"));

        int syncedCount = syncService.syncAllCleanedAds();

        assertEquals(0, syncedCount);
        assertEquals(AdProcessingStatus.FAILED, failingAd.getStatus());
        assertEquals("Connection refused", failingAd.getErrorMessage());
        verify(rawScrapedAdRepository).save(failingAd);
    }
}
