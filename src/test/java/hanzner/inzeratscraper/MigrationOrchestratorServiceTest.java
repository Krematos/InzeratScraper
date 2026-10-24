package hanzner.inzeratscraper;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.dto.ReviewItemDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import hanzner.inzeratscraper.service.AdDataCleanerService;
import hanzner.inzeratscraper.service.BazosScraperService;
import hanzner.inzeratscraper.service.ImageDownloadService;
import hanzner.inzeratscraper.service.LlmRateLimitException;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import hanzner.inzeratscraper.service.RemoteEshopSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                .id(1L)
                .externalId("101")
                .url("https://stroje.bazos.cz/inzerat/101/vrtacka.php")
                .subdomainCategory("stroje")
                .rawTitle("Prodám vrtačku")
                .rawDescription("Plně funkční")
                .rawPrice("1 500 Kč")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        RawScrapedAd ad2 = RawScrapedAd.builder()
                .id(2L)
                .externalId("102")
                .url("https://stroje.bazos.cz/inzerat/102/pila.php")
                .subdomainCategory("stroje")
                .rawTitle("Prodám pilu")
                .rawDescription("Cena dohodou")
                .rawPrice("Dohodou")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad1, ad2));
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);

        CleanedProductDto cleaned1 = new CleanedProductDto(
                "101", ad1.getUrl(), "Vrtačka", "Plně funkční", new BigDecimal("1500.00"), "STANDARD", false, null, "Nářadí", "USED", Map.of(), List.of()
        );
        CleanedProductDto cleaned2 = new CleanedProductDto(
                "102", ad2.getUrl(), "Pila", "Cena dohodou", null, "STANDARD", true, "Cena nenalezena", "Nářadí", "USED", Map.of(), List.of()
        );

        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class)))
                .thenAnswer(inv -> {
                    RawAdDto dto = inv.getArgument(0);
                    return "101".equals(dto.externalId()) ? cleaned1 : cleaned2;
                });

        int cleanedCount = orchestratorService.cleanStagedAds(10);

        assertEquals(2, cleanedCount);
        assertEquals(AdProcessingStatus.CLEANED, ad1.getStatus());
        assertNotNull(ad1.getCleanedPayloadJson());
        assertNull(ad1.getErrorMessage());

        assertEquals(AdProcessingStatus.NEEDS_REVIEW, ad2.getStatus());
        assertTrue(ad2.isPriceNeedsReview());
        assertEquals("Cena nenalezena", ad2.getReviewReason());
        verify(rawScrapedAdRepository).save(ad1);
        verify(rawScrapedAdRepository).save(ad2);
    }

    @Test
    void testCleanStagedAdsFailFastWhenLlmNotConfiguredDoesNotTouchDb() {
        doThrow(new IllegalStateException("LLM API klíč není nastaven"))
                .when(adDataCleanerService).assertLlmConfigured();

        assertThrows(IllegalStateException.class, () -> orchestratorService.cleanStagedAds(10));

        verify(rawScrapedAdRepository, never()).findCandidateAdsForProcessing(any(), any());
        verify(rawScrapedAdRepository, never()).claim(any(), any());
        verify(rawScrapedAdRepository, never()).save(any());
    }

    @Test
    void testCleanStagedAdsTransientErrorIncrementsAttemptsAndSetsNextRetry() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .id(103L)
                .externalId("103")
                .attempts(0)
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad));
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);
        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class)))
                .thenThrow(new IOException("LLM API timeout"));

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        assertEquals(0, cleanedCount);
        assertEquals(AdProcessingStatus.SCRAPED, ad.getStatus());
        assertEquals(1, ad.getAttemptsCount());
        assertNotNull(ad.getNextRetryAt());
        assertTrue(ad.getErrorMessage().contains("Přechodná chyba (pokus 1/5)"));
        verify(rawScrapedAdRepository).save(ad);
    }

    @Test
    void testCleanStagedAdsTransientErrorExceedingMaxAttemptsMarksFailed() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .id(104L)
                .externalId("104")
                .attempts(4)
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad));
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);
        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class)))
                .thenThrow(new IOException("LLM API timeout"));

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        assertEquals(0, cleanedCount);
        assertEquals(AdProcessingStatus.FAILED, ad.getStatus());
        assertEquals(5, ad.getAttemptsCount());
        assertTrue(ad.getErrorMessage().contains("Vyčerpán maximální počet pokusů (5)"));
        verify(rawScrapedAdRepository).save(ad);
    }

    @Test
    void testCleanStagedAdsPermanentErrorMarksFailedImmediately() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .id(105L)
                .externalId("105")
                .attempts(0)
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad));
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);
        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class)))
                .thenThrow(new IOException("Chyba při volání LLM API (HTTP 400): Bad Request"));

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        assertEquals(0, cleanedCount);
        assertEquals(AdProcessingStatus.FAILED, ad.getStatus());
        assertTrue(ad.getErrorMessage().contains("HTTP 400"));
        verify(rawScrapedAdRepository).save(ad);
    }

    @Test
    void testCleanStagedAdsInterruptedExceptionRestoresInterruptAndLeavesAttemptsUnchanged() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .id(106L)
                .externalId("106")
                .attempts(2)
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad));
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);
        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class)))
                .thenThrow(new InterruptedException("Task cancelled"));

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        assertEquals(0, cleanedCount);
        assertEquals(AdProcessingStatus.SCRAPED, ad.getStatus());
        assertEquals(2, ad.getAttemptsCount()); // Nezvýšeno
        assertNull(ad.getProcessingStartedAt());
        assertTrue(Thread.currentThread().isInterrupted());
        // Vyčistit interrupt flag pro další testy
        Thread.interrupted();
    }

    @Test
    void testCleanStagedAdsSkipsUnclaimedAd() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .id(107L)
                .externalId("107")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad));
        when(rawScrapedAdRepository.claim(eq(107L), any())).thenReturn(0); // Někdo jiný získal claim

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        assertEquals(0, cleanedCount);
        verify(adDataCleanerService, never()).cleanWithAi(any());
        verify(rawScrapedAdRepository, never()).save(ad);
    }

    @Test
    void testCleanStagedAdsRetryFailedResetsFailedAds() {
        when(rawScrapedAdRepository.resetFailedAdsToScraped()).thenReturn(3);
        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any())).thenReturn(List.of());

        int cleaned = orchestratorService.cleanStagedAds(10, true);

        assertEquals(0, cleaned);
        verify(rawScrapedAdRepository).resetFailedAdsToScraped();
    }

    @Test
    void testRecoverStuckProcessingAdsCallsRepository() {
        Instant cutoff = Instant.now().minusSeconds(900);
        when(rawScrapedAdRepository.resetStuckProcessingAds(cutoff)).thenReturn(4);

        int count = orchestratorService.recoverStuckProcessingAds(cutoff);

        assertEquals(4, count);
        verify(rawScrapedAdRepository).resetStuckProcessingAds(cutoff);
    }

    @Test
    void testPrefillRegisteredNamesPopulatesExistingNames() {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("301")
                .status(AdProcessingStatus.CLEANED)
                .cleanedPayloadJson("{\"name\":\"Aku šroubovák DeWalt\"}")
                .build();

        when(rawScrapedAdRepository.findByStatusIn(any())).thenReturn(List.of(ad));

        orchestratorService.prefillRegisteredNames();

        verify(adDataCleanerService).registerExistingName("Aku šroubovák DeWalt");
    }

    @Test
    void testCleanSingleStagedAdReleasesNameOnDatabaseSaveException() throws IOException, InterruptedException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .id(401L)
                .externalId("401")
                .status(AdProcessingStatus.SCRAPED)
                .build();

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(List.of(ad));
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);

        CleanedProductDto cleaned = new CleanedProductDto(
                "401", "url", "Aku vrtačka Narex", "Popis", new BigDecimal("1000.00"), "STANDARD", false, null, "Nářadí", "USED", Map.of(), List.of()
        );
        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class))).thenReturn(cleaned);
        // Simulace chyby při ukládání do DB
        when(rawScrapedAdRepository.save(any(RawScrapedAd.class)))
                .thenThrow(new RuntimeException("DB disk full"))
                .thenReturn(ad);

        orchestratorService.cleanStagedAds(5);

        // Ověříme, že při chybě uložení byl název uvolněn
        verify(adDataCleanerService).releaseName("Aku vrtačka Narex");
    }

    @Test
    void testIsTransientErrorClassificationTable() {
        assertTrue(orchestratorService.isTransientError(new LlmRateLimitException("LLM rate limit vyčerpán (HTTP 429)")));
        assertTrue(orchestratorService.isTransientError(new HttpTimeoutException("timeout")));
        assertTrue(orchestratorService.isTransientError(new IOException("Chyba při volání LLM API (HTTP 503): Overloaded")));
        assertTrue(orchestratorService.isTransientError(new IOException("LLM API rate-limit (HTTP 429)")));
        assertTrue(orchestratorService.isTransientError(new IOException("Connection reset")));

        assertFalse(orchestratorService.isTransientError(new IOException("Chyba při volání LLM API (HTTP 400): Bad Request")));
        assertFalse(orchestratorService.isTransientError(new IOException("Chyba při volání LLM API (HTTP 401): Unauthorized")));
        assertFalse(orchestratorService.isTransientError(new IOException("Chyba při volání LLM API (HTTP 403): Forbidden")));
        assertFalse(orchestratorService.isTransientError(new IOException("Chyba při volání LLM API (HTTP 404): Not Found")));
        assertFalse(orchestratorService.isTransientError(new IllegalArgumentException("Invalid state")));
    }

    @Test
    void testGetAdsNeedingReviewFiltersCorrectly() {
        RawScrapedAd okAd = RawScrapedAd.builder()
                .externalId("1")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .build();
        RawScrapedAd needsReviewAd1 = RawScrapedAd.builder()
                .externalId("2")
                .status(AdProcessingStatus.NEEDS_REVIEW)
                .priceNeedsReview(false)
                .build();
        RawScrapedAd needsReviewAd2 = RawScrapedAd.builder()
                .externalId("3")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(true)
                .build();

        when(rawScrapedAdRepository.findAll()).thenReturn(List.of(okAd, needsReviewAd1, needsReviewAd2));

        List<ReviewItemDto> result = orchestratorService.getAdsNeedingReview();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(dto -> "2".equals(dto.externalId())));
        assertTrue(result.stream().anyMatch(dto -> "3".equals(dto.externalId())));
        assertFalse(result.stream().anyMatch(dto -> "1".equals(dto.externalId())));
    }

    @Test
    void testSyncCleanedAdsToEshopDryRunDoesNotCallHttp() {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("201")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson("""
                        {
                          "externalId": "201",
                          "sourceUrl": "url",
                          "name": "Vrtačka",
                          "description": "Popis",
                          "price": 900.00,
                          "vatRate": "STANDARD",
                          "needsReview": false,
                          "reviewReason": null,
                          "category": "Nářadí",
                          "condition": "USED",
                          "parameters": {},
                          "imageUrls": []
                        }
                        """)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));

        int synced = orchestratorService.syncCleanedAdsToEshop(10, true);

        assertEquals(1, synced);
        assertEquals(AdProcessingStatus.CLEANED, ad.getStatus());
        verify(remoteEshopSyncService, never()).pushProductToVps(any());
        verify(rawScrapedAdRepository, never()).save(ad);
    }

    @Test
    void testSyncCleanedAdsToEshopRealSyncSavesSyncedStatus() {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("202")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson("""
                        {
                          "externalId": "202",
                          "sourceUrl": "url",
                          "name": "Pila",
                          "description": "Popis pily",
                          "price": 1200.00,
                          "vatRate": "STANDARD",
                          "needsReview": false,
                          "reviewReason": null,
                          "category": "Nářadí",
                          "condition": "USED",
                          "parameters": {},
                          "imageUrls": []
                        }
                        """)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));

        int synced = orchestratorService.syncCleanedAdsToEshop(10, false);

        assertEquals(1, synced);
        assertEquals(AdProcessingStatus.SYNCED, ad.getStatus());
        verify(remoteEshopSyncService).pushProductToVps(any());
        verify(rawScrapedAdRepository).save(ad);
    }

    @Test
    void testSyncCleanedAdsToEshopHandlesErrorAndSetsErrorMessage() {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("203")
                .status(AdProcessingStatus.CLEANED)
                .priceNeedsReview(false)
                .cleanedPayloadJson("""
                        {
                          "externalId": "203",
                          "sourceUrl": "url",
                          "name": "Bruska",
                          "description": "Popis brusky",
                          "price": 500.00,
                          "vatRate": "STANDARD",
                          "needsReview": false,
                          "reviewReason": null,
                          "category": "Nářadí",
                          "condition": "USED",
                          "parameters": {},
                          "imageUrls": []
                        }
                        """)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));
        doThrow(new RuntimeException("VPS 500 error")).when(remoteEshopSyncService).pushProductToVps(any());

        int synced = orchestratorService.syncCleanedAdsToEshop(10, false);

        assertEquals(0, synced);
        assertEquals(AdProcessingStatus.CLEANED, ad.getStatus());
        assertTrue(ad.getErrorMessage().contains("VPS 500 error"));
        verify(rawScrapedAdRepository).save(ad);
    }

    // =========================================================================
    // Testy Fáze 4: Paralelní zpracování na virtuálních vláknech
    // =========================================================================

    @Test
    @DisplayName("Fáze 4: 12 záznamů při concurrency 3 trvá výrazně méně než sekvenčně a všechny jsou zpracovány právě jednou")
    void testCleanStagedAdsParallelExecutionSignificantlyFasterThanSequential() throws IOException, InterruptedException {
        int adCount = 12;
        List<RawScrapedAd> candidates = new ArrayList<>();
        for (int i = 1; i <= adCount; i++) {
            candidates.add(RawScrapedAd.builder()
                    .id((long) i)
                    .externalId("ext-" + i)
                    .url("https://bazos.cz/inzerat/" + i)
                    .status(AdProcessingStatus.SCRAPED)
                    .build());
        }

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any())).thenReturn(candidates);
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);

        Semaphore concurrencyLimit = new Semaphore(3);
        AtomicInteger activeConcurrent = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrent = new AtomicInteger(0);
        Set<String> processedIds = ConcurrentHashMap.newKeySet();

        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class))).thenAnswer(inv -> {
            RawAdDto dto = inv.getArgument(0);
            concurrencyLimit.acquire();
            int current = activeConcurrent.incrementAndGet();
            maxObservedConcurrent.accumulateAndGet(current, Math::max);
            try {
                // Simulace latence 200 ms
                Thread.sleep(200);
                processedIds.add(dto.externalId());
                return new CleanedProductDto(
                        dto.externalId(), dto.url(), "Produkt " + dto.externalId(), "Popis",
                        BigDecimal.TEN, "STANDARD", false, null, "Kategorie", "USED",
                        Map.of(), List.of()
                );
            } finally {
                activeConcurrent.decrementAndGet();
                concurrencyLimit.release();
            }
        });

        long start = System.currentTimeMillis();
        int cleanedCount = orchestratorService.cleanStagedAds(adCount);
        long duration = System.currentTimeMillis() - start;

        // Všech 12 bylo zpracováno
        assertEquals(adCount, cleanedCount, "Všech 12 inzerátů mělo být vyčištěno");
        assertEquals(adCount, processedIds.size(), "Všech 12 unikátních inzerátů bylo zpracováno");

        // Maximální pozorovaný souběh nepřekročil 3
        assertTrue(maxObservedConcurrent.get() <= 3,
                "Souběh neměl překročit 3, byl: " + maxObservedConcurrent.get());

        // Sekvenčně by 12 * 200 ms trvalo >= 2400 ms. Při souběhu 3 má trvat ~ 4 dávky * 200 ms = ~800-1000 ms.
        // Ověříme, že trvalo výrazně méně než 2000 ms.
        assertTrue(duration < 2000,
                "Dávka trvala " + duration + " ms, což mělo být výrazně méně než sekvenčních 2400 ms");
    }

    @Test
    @DisplayName("Fáze 4: Výjimka v jedné paralelní úloze nezhroutí ostatní úlohy")
    void testCleanStagedAdsExceptionInOneTaskDoesNotCollapseOthers() throws IOException, InterruptedException {
        List<RawScrapedAd> candidates = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            candidates.add(RawScrapedAd.builder()
                    .id((long) i)
                    .externalId("fail-test-" + i)
                    .url("https://bazos.cz/inzerat/" + i)
                    .status(AdProcessingStatus.SCRAPED)
                    .build());
        }

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any())).thenReturn(candidates);
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);

        when(adDataCleanerService.cleanWithAi(any(RawAdDto.class))).thenAnswer(inv -> {
            RawAdDto dto = inv.getArgument(0);
            if ("fail-test-3".equals(dto.externalId())) {
                throw new IOException("Simulovaný výpadek LLM pro inzerát 3");
            }
            return new CleanedProductDto(
                    dto.externalId(), dto.url(), "Produkt " + dto.externalId(), "Popis",
                    BigDecimal.TEN, "STANDARD", false, null, "Kategorie", "USED",
                    Map.of(), List.of()
            );
        });

        int cleanedCount = orchestratorService.cleanStagedAds(5);

        // 4 úlohy úspěšně prošly i přes selhání úlohy 3
        assertEquals(4, cleanedCount, "Ostatní 4 inzeráty měly úspěšně projít");
    }

    @Test
    @DisplayName("Fáze 4.4: @PreDestroy obnova rozpracovaných PROCESSING inzerátů při ukončení")
    void testOnShutdownRecoversProcessingAds() {
        when(rawScrapedAdRepository.resetStuckProcessingAds(any())).thenReturn(3);

        orchestratorService.onShutdown();

        verify(rawScrapedAdRepository).resetStuckProcessingAds(any());
    }

    // =========================================================================
    // Testy pro getStatusStats() a exportCleanedProducts()
    // =========================================================================

    @Test
    @DisplayName("getStatusStats() vrátí všechny stavy s příslušnými počty z DB")
    void testGetStatusStats() {
        List<Object[]> queryResult = List.of(
                new Object[]{AdProcessingStatus.SCRAPED, 25L},
                new Object[]{AdProcessingStatus.CLEANED, 10L},
                new Object[]{AdProcessingStatus.FAILED, 2L}
        );
        when(rawScrapedAdRepository.countGroupedByStatus()).thenReturn(queryResult);

        Map<String, Long> stats = orchestratorService.getStatusStats();

        assertNotNull(stats);
        assertEquals(AdProcessingStatus.values().length, stats.size());
        assertEquals(25L, stats.get("SCRAPED"));
        assertEquals(10L, stats.get("CLEANED"));
        assertEquals(2L, stats.get("FAILED"));
        assertEquals(0L, stats.get("PROCESSING"));
        assertEquals(0L, stats.get("NEEDS_REVIEW"));
        assertEquals(0L, stats.get("SYNCED"));
    }

    @Test
    @DisplayName("exportCleanedProducts() vrátí vyčištěné DTO a uloží do souboru, pokud saveToFile=true")
    void testExportCleanedProductsWithSaveToFile() throws IOException {
        RawScrapedAd ad = RawScrapedAd.builder()
                .externalId("exp-1")
                .status(AdProcessingStatus.CLEANED)
                .cleanedPayloadJson("""
                        {
                          "externalId": "exp-1",
                          "sourceUrl": "https://bazos.cz/exp-1",
                          "name": "Kladivo",
                          "description": "Zánovní",
                          "price": 200.00,
                          "vatRate": "STANDARD",
                          "needsReview": false,
                          "reviewReason": null,
                          "category": "Nářadí",
                          "condition": "USED",
                          "parameters": {},
                          "imageUrls": []
                        }
                        """)
                .build();

        when(rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED)).thenReturn(List.of(ad));

        Path exportFile = Path.of("data", "products-export.json");
        Files.deleteIfExists(exportFile);

        List<CleanedProductDto> result = orchestratorService.exportCleanedProducts(AdProcessingStatus.CLEANED, true);

        assertEquals(1, result.size());
        assertEquals("exp-1", result.getFirst().externalId());
        assertEquals("Kladivo", result.getFirst().name());
        assertTrue(Files.exists(exportFile), "Soubor s exportem měl být vytvořen");

        // Úklid po testu
        Files.deleteIfExists(exportFile);
    }
}
