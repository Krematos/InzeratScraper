package hanzner.inzeratscraper;

import hanzner.inzeratscraper.controller.MigrationController;
import hanzner.inzeratscraper.dto.CleanJobDto;
import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.ReviewItemDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.service.CleanJobService;
import hanzner.inzeratscraper.service.JobConflictException;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MigrationControllerTest {

    @Mock
    private MigrationOrchestratorService orchestratorService;

    @Mock
    private CleanJobService cleanJobService;

    private MigrationController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new MigrationController(orchestratorService, cleanJobService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testScrapeProfileEndpoint() {
        String profileUrl = "https://www.bazos.cz/hodnoceni.php?idmail=5412977";
        when(orchestratorService.scrapeProfileToStaging(profileUrl, true)).thenReturn(12);

        ResponseEntity<Map<String, Object>> response = controller.scrapeProfile(profileUrl, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SCRAPE", response.getBody().get("phase"));
        assertEquals(profileUrl, response.getBody().get("profileUrl"));
        assertEquals(true, response.getBody().get("downloadImages"));
        assertEquals(12, response.getBody().get("newlyScrapedCount"));
        verify(orchestratorService).scrapeProfileToStaging(profileUrl, true);
    }

    @Test
    void testCleanStagedAdsSyncEndpoint() {
        when(orchestratorService.cleanStagedAds(15, false)).thenReturn(14);

        ResponseEntity<Map<String, Object>> response = controller.cleanStagedAds(15, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CLEAN", response.getBody().get("phase"));
        assertEquals(15, response.getBody().get("limit"));
        assertEquals(false, response.getBody().get("retryFailed"));
        assertEquals(14, response.getBody().get("cleanedCount"));
        verify(orchestratorService).cleanStagedAds(15, false);
    }

    @Test
    void testCleanStagedAdsSyncEndpointWithRetryFailed() {
        when(orchestratorService.cleanStagedAds(25, true)).thenReturn(20);

        ResponseEntity<Map<String, Object>> response = controller.cleanStagedAds(25, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CLEAN", response.getBody().get("phase"));
        assertEquals(25, response.getBody().get("limit"));
        assertEquals(true, response.getBody().get("retryFailed"));
        assertEquals(20, response.getBody().get("cleanedCount"));
        verify(orchestratorService).cleanStagedAds(25, true);
    }

    @Test
    void testGetAdsNeedingReviewEndpoint() {
        ReviewItemDto item = new ReviewItemDto("999", "Aku vrtačka", null, "Cena nenalezena", "NEEDS_REVIEW");
        when(orchestratorService.getAdsNeedingReview()).thenReturn(List.of(item));

        ResponseEntity<List<ReviewItemDto>> response = controller.getAdsNeedingReview();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("999", response.getBody().getFirst().externalId());
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

    // =========================================================================
    // Testy Fáze 5: Asynchronní job API (MockMvc)
    // =========================================================================

    @Test
    @DisplayName("Fáze 5 MockMvc: POST /api/migration/clean vrací 202 Accepted a stav jobu lze dotázat přes GET")
    void testCleanAsyncReturns202AndStatusCanBeQueried() throws Exception {
        String jobId = "job-12345";
        CleanJobDto initialJob = new CleanJobDto(
                jobId, "RUNNING", 10, 0, 0, 0, 0, Instant.now(), null
        );
        CleanJobDto completedJob = new CleanJobDto(
                jobId, "DONE", 10, 10, 8, 1, 1, Instant.now(), Instant.now()
        );

        when(cleanJobService.startCleanJob(anyInt(), anyBoolean())).thenReturn(initialJob);
        when(cleanJobService.getJob(jobId)).thenReturn(Optional.of(completedJob));

        // 1. Spuštění asynchronního jobu -> 202 Accepted
        mockMvc.perform(post("/api/migration/clean")
                        .param("limit", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // 2. Dotaz na stav jobu -> 200 OK
        mockMvc.perform(get("/api/migration/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.processed").value(10))
                .andExpect(jsonPath("$.succeeded").value(8))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.needsReview").value(1));
    }

    @Test
    @DisplayName("Fáze 5 MockMvc: Druhý souběžný start asynchronního jobu vrací 409 Conflict")
    void testSecondConcurrentCleanJobReturns409Conflict() throws Exception {
        when(cleanJobService.startCleanJob(anyInt(), anyBoolean()))
                .thenThrow(new JobConflictException("Již běží jiný job s ID: job-active"));

        mockMvc.perform(post("/api/migration/clean"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Již běží jiný job s ID: job-active"));
    }

    @Test
    @DisplayName("Fáze 5 MockMvc: Dotaz na neznámé jobId vrací 404 Not Found")
    void testGetUnknownJobReturns404NotFound() throws Exception {
        when(cleanJobService.getJob("non-existent-id")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/migration/jobs/non-existent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Fáze 5 MockMvc: async=false zachová původní synchronní chování a vrací 200 OK")
    void testCleanWithAsyncFalseReturns200Ok() throws Exception {
        when(orchestratorService.cleanStagedAds(15, false)).thenReturn(10);

        mockMvc.perform(post("/api/migration/clean")
                        .param("async", "false")
                        .param("limit", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("CLEAN"))
                .andExpect(jsonPath("$.limit").value(15))
                .andExpect(jsonPath("$.cleanedCount").value(10));
    }

    @Test
    @DisplayName("Fáze 5.4 MockMvc: GET /api/migration/review vrací seznam ReviewItemDto")
    void testGetReviewEndpointMockMvc() throws Exception {
        ReviewItemDto item = new ReviewItemDto("ext-42", "Aku vrtačka", new BigDecimal("1200.00"), "Nízká cena", "NEEDS_REVIEW");
        when(orchestratorService.getAdsNeedingReview()).thenReturn(List.of(item));

        mockMvc.perform(get("/api/migration/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-42"))
                .andExpect(jsonPath("$[0].name").value("Aku vrtačka"))
                .andExpect(jsonPath("$[0].price").value(1200.00))
                .andExpect(jsonPath("$[0].reviewReason").value("Nízká cena"))
                .andExpect(jsonPath("$[0].status").value("NEEDS_REVIEW"));
    }

    // =========================================================================
    // Testy pro /stats a /export endpointy
    // =========================================================================

    @Test
    @DisplayName("GET /api/migration/stats vrací statistiky všech stavů")
    void testGetStatusStatsEndpoint() throws Exception {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("SCRAPED", 20L);
        stats.put("PROCESSING", 0L);
        stats.put("CLEANED", 15L);
        stats.put("NEEDS_REVIEW", 2L);
        stats.put("FAILED", 1L);
        stats.put("SYNCED", 0L);

        when(orchestratorService.getStatusStats()).thenReturn(stats);

        mockMvc.perform(get("/api/migration/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.SCRAPED").value(20))
                .andExpect(jsonPath("$.CLEANED").value(15))
                .andExpect(jsonPath("$.NEEDS_REVIEW").value(2))
                .andExpect(jsonPath("$.FAILED").value(1))
                .andExpect(jsonPath("$.SYNCED").value(0));

        verify(orchestratorService).getStatusStats();
    }

    @Test
    @DisplayName("GET /api/migration/export vrací exportované produkty a předává parametry")
    void testExportCleanedProductsEndpoint() throws Exception {
        CleanedProductDto product = new CleanedProductDto(
                "ext-100", "https://bazos.cz/inzerat/100", "Pila Makita", "Pěkný stav",
                new BigDecimal("2500.00"), "STANDARD", false, null, "Nářadí", "USED",
                Map.of(), List.of()
        );

        when(orchestratorService.exportCleanedProducts(eq(AdProcessingStatus.CLEANED), eq(true)))
                .thenReturn(List.of(product));

        mockMvc.perform(get("/api/migration/export")
                        .param("status", "CLEANED")
                        .param("saveToFile", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("ext-100"))
                .andExpect(jsonPath("$[0].name").value("Pila Makita"))
                .andExpect(jsonPath("$[0].price").value(2500.00));

        verify(orchestratorService).exportCleanedProducts(AdProcessingStatus.CLEANED, true);
    }
}
