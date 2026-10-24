package hanzner.inzeratscraper.controller;

import hanzner.inzeratscraper.dto.CleanJobDto;
import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.ReviewItemDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.service.CleanJobService;
import hanzner.inzeratscraper.service.JobConflictException;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/migration")
public class MigrationController {

    private final MigrationOrchestratorService orchestratorService;
    private final CleanJobService cleanJobService;

    @Autowired
    public MigrationController(
            MigrationOrchestratorService orchestratorService,
            CleanJobService cleanJobService) {
        this.orchestratorService = orchestratorService;
        this.cleanJobService = cleanJobService;
    }

    public MigrationController(MigrationOrchestratorService orchestratorService) {
        this(orchestratorService, null);
    }

    /**
     * Stažení inzerátů z profilu Bazoše do lokální H2 databáze a volitelná záloha fotek.
     * Příklad: POST /api/migration/scrape?profileUrl=https://www.bazos.cz/hodnoceni.php?idmail=...&downloadImages=true
     */
    @PostMapping("/scrape")
    public ResponseEntity<Map<String, Object>> scrapeProfile(
            @RequestParam String profileUrl,
            @RequestParam(defaultValue = "true") boolean downloadImages) {
        int newlyScraped = orchestratorService.scrapeProfileToStaging(profileUrl, downloadImages);
        return ResponseEntity.ok(Map.of(
                "phase", "SCRAPE",
                "profileUrl", profileUrl,
                "downloadImages", downloadImages,
                "newlyScrapedCount", newlyScraped
        ));
    }

    /**
     * Vyčištění surových inzerátů ve stavu SCRAPED přes AI.
     * Výchozí chování je asynchronní (async=true), vrací 202 Accepted s {jobId, status}.
     * Parametr async=false zachovává původní synchronní chování (vrací 200 OK s cleanedCount).
     */
    @PostMapping("/clean")
    public ResponseEntity<Map<String, Object>> cleanStagedAds(
            @RequestParam(defaultValue = "15") int limit,
            @RequestParam(defaultValue = "false") boolean retryFailed,
            @RequestParam(defaultValue = "true") boolean async) {
        if (!async) {
            int cleanedCount = orchestratorService.cleanStagedAds(limit, retryFailed);
            return ResponseEntity.ok(Map.of(
                    "phase", "CLEAN",
                    "limit", limit,
                    "retryFailed", retryFailed,
                    "cleanedCount", cleanedCount
            ));
        }

        if (cleanJobService == null) {
            throw new IllegalStateException("CleanJobService není k dispozici.");
        }
        CleanJobDto job = cleanJobService.startCleanJob(limit, retryFailed);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "jobId", job.jobId(),
                "status", job.status()
        ));
    }

    /**
     * Zpětná kompatibilita pro synchronní volání se dvěma parametry (limit, retryFailed).
     */
    public ResponseEntity<Map<String, Object>> cleanStagedAds(int limit, boolean retryFailed) {
        return cleanStagedAds(limit, retryFailed, false);
    }

    /**
     * Zjištění stavu asynchronní úlohy čištění podle ID.
     * Vrací {jobId, status, total, processed, succeeded, failed, needsReview, startedAt, finishedAt}.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<CleanJobDto> getJobStatus(@PathVariable String jobId) {
        if (cleanJobService == null) {
            return ResponseEntity.notFound().build();
        }
        return cleanJobService.getJob(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Kontrola inzerátů vyžadujících ruční revizi v podobě DTO (Fáze 5.4).
     * Příklad: GET /api/migration/review
     */
    @GetMapping("/review")
    public ResponseEntity<List<ReviewItemDto>> getAdsNeedingReview() {
        return ResponseEntity.ok(orchestratorService.getAdsNeedingReview());
    }

    /**
     * Přehled počtu inzerátů v jednotlivých fázích / stavech.
     * Příklad: GET /api/migration/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStatusStats() {
        return ResponseEntity.ok(orchestratorService.getStatusStats());
    }

    /**
     * Závěrečný export vyčištěných produktů ve formátu JSON.
     * Volitelně lze vyfiltrovat specifický stav (např. status=CLEANED) a uložit do souboru ./data/products-export.json.
     * Příklad: GET /api/migration/export?saveToFile=true&status=CLEANED
     */
    @GetMapping("/export")
    public ResponseEntity<List<CleanedProductDto>> exportCleanedProducts(
            @RequestParam(required = false) AdProcessingStatus status,
            @RequestParam(defaultValue = "false") boolean saveToFile) {
        return ResponseEntity.ok(orchestratorService.exportCleanedProducts(status, saveToFile));
    }

    /**
     * Synchronizace vyčištěných produktů do e-shopu (podporuje dryRun=true).
     * Příklad: POST /api/migration/sync?limit=20&dryRun=true
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncCleanedAds(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        int syncedCount = orchestratorService.syncCleanedAdsToEshop(limit, dryRun);
        return ResponseEntity.ok(Map.of(
                "phase", "SYNC",
                "dryRun", dryRun,
                "limit", limit,
                "processedCount", syncedCount
        ));
    }

    @ExceptionHandler(JobConflictException.class)
    public ResponseEntity<Map<String, String>> handleJobConflict(JobConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
