package hanzner.inzeratscraper;

import hanzner.inzeratscraper.controller.MigrationController;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationControllerTest {

    @Mock
    private MigrationOrchestratorService orchestratorService;

    private MigrationController controller;

    @BeforeEach
    void setUp() {
        controller = new MigrationController(orchestratorService);
    }

    @Test
    void testScrapeProfileEndpoint() {
        String profileUrl = "https://www.bazos.cz/hodnoceni.php?idmail=5412977";
        when(orchestratorService.scrapeProfileToStaging(profileUrl)).thenReturn(12);

        ResponseEntity<Map<String, Object>> response = controller.scrapeProfile(profileUrl);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SCRAPE", response.getBody().get("phase"));
        assertEquals(profileUrl, response.getBody().get("profileUrl"));
        assertEquals(12, response.getBody().get("newlyScrapedCount"));
        verify(orchestratorService).scrapeProfileToStaging(profileUrl);
    }

    @Test
    void testCleanStagedAdsEndpoint() {
        when(orchestratorService.cleanStagedAds(15)).thenReturn(14);

        ResponseEntity<Map<String, Object>> response = controller.cleanStagedAds(15);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CLEAN", response.getBody().get("phase"));
        assertEquals(15, response.getBody().get("limit"));
        assertEquals(14, response.getBody().get("cleanedCount"));
        verify(orchestratorService).cleanStagedAds(15);
    }

    @Test
    void testGetAdsNeedingReviewEndpoint() {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("999")
                .status(AdProcessingStatus.NEEDS_REVIEW)
                .priceNeedsReview(true)
                .build();
        when(orchestratorService.getAdsNeedingReview()).thenReturn(List.of(ad));

        ResponseEntity<List<RawScrapedAd>> response = controller.getAdsNeedingReview();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("999", response.getBody().getFirst().getExternalId());
        verify(orchestratorService).getAdsNeedingReview();
    }

    @Test
    void testSyncCleanedAdsEndpoint() {
        when(orchestratorService.syncCleanedAdsToEshop(20, true)).thenReturn(18);

        ResponseEntity<Map<String, Object>> response = controller.syncCleanedAds(20, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SYNC", response.getBody().get("phase"));
        assertEquals(true, response.getBody().get("dryRun"));
        assertEquals(20, response.getBody().get("limit"));
        assertEquals(18, response.getBody().get("processedCount"));
        verify(orchestratorService).syncCleanedAdsToEshop(20, true);
    }
}
