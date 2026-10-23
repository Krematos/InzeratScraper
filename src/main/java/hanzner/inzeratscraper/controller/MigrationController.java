package hanzner.inzeratscraper.controller;

import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationOrchestratorService orchestratorService;

    /**
     * Fáze 1: Stažení inzerátů z profilu Bazoše do lokální H2 databáze a volitelná záloha fotek.
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
     * Fáze 2: Vyčištění surových inzerátů ve stavu SCRAPED přes AI.
     * Příklad: POST /api/migration/clean?limit=15&retryFailed=false
     */
    @PostMapping("/clean")
    public ResponseEntity<Map<String, Object>> cleanStagedAds(
            @RequestParam(defaultValue = "15") int limit,
            @RequestParam(defaultValue = "false") boolean retryFailed) {
        int cleanedCount = orchestratorService.cleanStagedAds(limit, retryFailed);
        return ResponseEntity.ok(Map.of(
                "phase", "CLEAN",
                "limit", limit,
                "retryFailed", retryFailed,
                "cleanedCount", cleanedCount
        ));
    }

    /**
     * Kontrola inzerátů vyžadujících ruční revizi (např. chybějící cena / "Dohodou").
     * Příklad: GET /api/migration/review
     */
    @GetMapping("/review")
    public ResponseEntity<List<RawScrapedAd>> getAdsNeedingReview() {
        return ResponseEntity.ok(orchestratorService.getAdsNeedingReview());
    }

    /**
     * Fáze 3: Synchronizace vyčištěných produktů do e-shopu (podporuje dryRun=true).
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
}
