package hanzner.inzeratscraper;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import hanzner.inzeratscraper.service.AdDataCleanerService;
import hanzner.inzeratscraper.service.BazosScraperService;
import hanzner.inzeratscraper.service.ImageDownloadService;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import hanzner.inzeratscraper.service.RemoteEshopSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationOrchestratorServiceTest {

    @Mock
    private BazosScraperService bazosScraperService;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Mock
    private AdDataCleanerService adDataCleanerService;

    @Mock
    private RemoteEshopSyncService remoteEshopSyncService;

    @Mock
    private RawScrapedAdRepository rawScrapedAdRepository;

    private ObjectMapper objectMapper;
    private MigrationOrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orchestratorService = new MigrationOrchestratorService(
                bazosScraperService,
                imageDownloadService,
                adDataCleanerService,
                remoteEshopSyncService,
                rawScrapedAdRepository,
                objectMapper
        );
    }

    @Test
    void testScrapeProfileToStagingDownloadsImagesByDefault() {
        String url = "https://www.bazos.cz/hodnoceni.php?idmail=123";
        List<String> imgs1 = List.of("https://www.bazos.cz/img/1/1.jpg");
        List<String> imgs2 = List.of("https://www.bazos.cz/img/1/2.jpg");
        List<RawAdDto> scraped = List.of(
                new RawAdDto("1", "u1", "stroje", "T1", "D1", "100 Kč", "Praha", imgs1),
                new RawAdDto("2", "u2", "auto", "T2", "D2", "200 Kč", "Brno", imgs2)
        );
        when(bazosScraperService.scrapeAllUserAds(url)).thenReturn(scraped);
        when(imageDownloadService.downloadImagesForAd(anyString(), anyList()))
                .thenReturn(List.of(Path.of("data/images/1/img_01.jpg")));

        int count = orchestratorService.scrapeProfileToStaging(url);

        assertEquals(2, count);
        verify(bazosScraperService).scrapeAllUserAds(url);
        verify(imageDownloadService).downloadImagesForAd("1", imgs1);
        verify(imageDownloadService).downloadImagesForAd("2", imgs2);
    }

    @Test
    void testScrapeProfileToStagingSkipsImageDownloadWhenDisabled() {
        String url = "https://www.bazos.cz/hodnoceni.php?idmail=123";
        List<RawAdDto> scraped = List.of(
                new RawAdDto("1", "u1", "stroje", "T1", "D1", "100 Kč", "Praha", List.of("https://www.bazos.cz/img/1/1.jpg"))
        );
        when(bazosScraperService.scrapeAllUserAds(url)).thenReturn(scraped);

        int count = orchestratorService.scrapeProfileToStaging(url, false);

        assertEquals(1, count);
        verify(bazosScraperService).scrapeAllUserAds(url);
        verify(imageDownloadService, never()).downloadImagesForAd(anyString(), anyList());
    }

    @Test
    void testCleanStagedAdsSetsCleanedAndNeedsReviewStatus() throws IOException, InterruptedException {
        RawScrapedAd ad1 = RawScrapedAd.builder()
                .externalId("101")
                .url("https://stroje.bazos.cz/inzerat/101/vrtacka.php")
                .subdomainCategory("stroje")
                .rawTitle("Prodám vrtačku")
                .rawDescription("Plně funkční")
                .rawPrice("1 500 Kč")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        RawScrapedAd ad2 = RawScrapedAd.builder()
                .externalId("102")
                .url("https://stroje.bazos.cz/inzerat/102/pila.php")
                .subdomainCategory("stroje")
                .rawTitle("Prodám pilu")
                .rawDescription("Cena dohodou")
                .rawPrice("Dohodou")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.SCRAPED)).thenReturn(List.of(ad1, ad2));

        CleanedProductDto cleaned1 = new CleanedProductDto(
                "101", ad1.getUrl(), "Vrtačka", "Plně funkční", new BigDecimal("1500.00"), "STANDARD", false, "Nářadí", "USED", Map.of(), List.of()
        );
        CleanedProductDto cleaned2 = new CleanedProductDto(
                "102", ad2.getUrl(), "Pila", "Cena dohodou", null, "STANDARD", true, "Nářadí", "USED", Map.of(), List.of()
        );

        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class)))
                .thenReturn(cleaned1)
                .thenReturn(cleaned2);

        int cleanedCount = orchestratorService.cleanStagedAds(10);

        assertEquals(2, cleanedCount);
        assertEquals(AdProcessingStatus.CLEANED, ad1.getStatus());
        assertNotNull(ad1.getCleanedPayloadJson());
        assertNull(ad1.getErrorMessage());

        assertEquals(AdProcessingStatus.NEEDS_REVIEW, ad2.getStatus());
        assertTrue(ad2.isPriceNeedsReview());
        verify(rawScrapedAdRepository).save(ad1);
        verify(rawScrapedAdRepository).save(ad2);
    }

    @Test
    void testCleanStagedAdsHandlesAiExceptionAndMarksAdFailed() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("103")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.SCRAPED)).thenReturn(List.of(ad));
        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class))).thenThrow(new IOException("LLM API timeout"));

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        assertEquals(0, cleanedCount);
        assertEquals(AdProcessingStatus.FAILED, ad.getStatus());
        assertEquals("LLM API timeout", ad.getErrorMessage());
        verify(rawScrapedAdRepository).save(ad);
    }

    @Test
    void testGetAdsNeedingReviewFiltersCorrectly() {
        RawScrapedAd okAd = RawScrapedAd.builder()
                .externalId("1")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .build();
        RawScrapedAd reviewStatusAd = RawScrapedAd.builder()
                .externalId("2")
                .status(AdProcessingStatus.NEEDS_REVIEW)
                .priceNeedsReview(true)
                .build();
        RawScrapedAd flagOnlyAd = RawScrapedAd.builder()
                .externalId("3")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(true)
                .build();

        when(rawScrapedAdRepository.findAll()).thenReturn(List.of(okAd, reviewStatusAd, flagOnlyAd));

        List<RawScrapedAd> result = orchestratorService.getAdsNeedingReview();

        assertEquals(2, result.size());
        assertEquals(List.of("2", "3"), result.stream().map(RawScrapedAd::getExternalId).toList());
    }

    @Test
    void testSyncCleanedAdsToEshopDryRunDoesNotCallVpsOrMutateStatus() {
        CleanedProductDto dto = new CleanedProductDto(
                "201", "url", "Vrtačka", "Popis", new BigDecimal("900.00"), "STANDARD", false, "Nářadí", "USED", Map.of(), List.of()
        );
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("201")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson(objectMapper.writeValueAsString(dto))
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));

        int count = orchestratorService.syncCleanedAdsToEshop(10, true);

        assertEquals(1, count);
        assertEquals(AdProcessingStatus.CLEANED, ad.getStatus());
        verify(remoteEshopSyncService, never()).pushProductToVps(any());
        verify(rawScrapedAdRepository, never()).save(any());
    }

    @Test
    void testSyncCleanedAdsToEshopRealRunPushesToVpsAndUpdatesStatus() {
        CleanedProductDto dto = new CleanedProductDto(
                "202", "url", "Vrtačka", "Popis", new BigDecimal("900.00"), "STANDARD", false, "Nářadí", "USED", Map.of(), List.of()
        );
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("202")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson(objectMapper.writeValueAsString(dto))
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));

        int count = orchestratorService.syncCleanedAdsToEshop(10, false);

        assertEquals(1, count);
        assertEquals(AdProcessingStatus.SYNCED, ad.getStatus());
        verify(remoteEshopSyncService).pushProductToVps(dto);
        verify(rawScrapedAdRepository).save(ad);
    }

    @Test
    void testSyncCleanedAdsToEshopMarksFailedWhenPushThrows() {
        CleanedProductDto dto = new CleanedProductDto(
                "203", "url", "Vrtačka", "Popis", new BigDecimal("900.00"), "STANDARD", false, "Nářadí", "USED", Map.of(), List.of()
        );
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("203")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson(objectMapper.writeValueAsString(dto))
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));
        doThrow(new RuntimeException("VPS 500 error")).when(remoteEshopSyncService).pushProductToVps(any());

        int count = orchestratorService.syncCleanedAdsToEshop(10, false);

        assertEquals(0, count);
        assertEquals(AdProcessingStatus.FAILED, ad.getStatus());
        assertEquals("VPS 500 error", ad.getErrorMessage());
        verify(rawScrapedAdRepository).save(ad);
    }
}
