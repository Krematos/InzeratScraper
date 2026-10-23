package hanzner.inzeratscraper;

import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RawScrapedAdRepositoryTest {

    @Autowired
    private RawScrapedAdRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void testClaimAtomicExclusivity() {
        RawScrapedAd ad = repository.save(RawScrapedAd.builder()
                .externalId("BAZOS_CLAIM_1")
                .status(AdProcessingStatus.SCRAPED)
                .build());

        Instant now = Instant.now();
        int firstClaim = repository.claim(ad.getId(), now);
        int secondClaim = repository.claim(ad.getId(), now);

        assertEquals(1, firstClaim);
        assertEquals(0, secondClaim);

        RawScrapedAd updated = repository.findById(ad.getId()).orElseThrow();
        assertEquals(AdProcessingStatus.PROCESSING, updated.getStatus());
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), updated.getProcessingStartedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void testFindCandidateAdsForProcessingFiltersRetryTimeAndStatus() {
        Instant now = Instant.now();

        // 1. Ready: SCRAPED, nextRetryAt is null
        RawScrapedAd ad1 = repository.save(RawScrapedAd.builder()
                .externalId("AD_READY_NULL")
                .status(AdProcessingStatus.SCRAPED)
                .nextRetryAt(null)
                .build());

        // 2. Ready: SCRAPED, nextRetryAt in past
        RawScrapedAd ad2 = repository.save(RawScrapedAd.builder()
                .externalId("AD_READY_PAST")
                .status(AdProcessingStatus.SCRAPED)
                .nextRetryAt(now.minusSeconds(60))
                .build());

        // 3. Not ready: SCRAPED, nextRetryAt in future
        repository.save(RawScrapedAd.builder()
                .externalId("AD_FUTURE")
                .status(AdProcessingStatus.SCRAPED)
                .nextRetryAt(now.plusSeconds(300))
                .build());

        // 4. Not ready: PROCESSING
        repository.save(RawScrapedAd.builder()
                .externalId("AD_PROCESSING")
                .status(AdProcessingStatus.PROCESSING)
                .build());

        List<RawScrapedAd> candidates = repository.findCandidateAdsForProcessing(now, PageRequest.of(0, 10));

        assertEquals(2, candidates.size());
        List<String> externalIds = candidates.stream().map(RawScrapedAd::getExternalId).toList();
        assertTrue(externalIds.contains("AD_READY_NULL"));
        assertTrue(externalIds.contains("AD_READY_PAST"));
    }

    @Test
    void testResetStuckProcessingAds() {
        Instant cutoff = Instant.now().minusSeconds(900); // 15 min ago

        // Stuck ad: PROCESSING, started 20 min ago
        RawScrapedAd stuck = repository.save(RawScrapedAd.builder()
                .externalId("AD_STUCK")
                .status(AdProcessingStatus.PROCESSING)
                .processingStartedAt(cutoff.minusSeconds(300))
                .build());

        // Active ad: PROCESSING, started 5 min ago
        RawScrapedAd active = repository.save(RawScrapedAd.builder()
                .externalId("AD_ACTIVE")
                .status(AdProcessingStatus.PROCESSING)
                .processingStartedAt(cutoff.plusSeconds(600))
                .build());

        int resetCount = repository.resetStuckProcessingAds(cutoff);
        assertEquals(1, resetCount);

        RawScrapedAd stuckReloaded = repository.findById(stuck.getId()).orElseThrow();
        assertEquals(AdProcessingStatus.SCRAPED, stuckReloaded.getStatus());
        assertNull(stuckReloaded.getProcessingStartedAt());

        RawScrapedAd activeReloaded = repository.findById(active.getId()).orElseThrow();
        assertEquals(AdProcessingStatus.PROCESSING, activeReloaded.getStatus());
    }

    @Test
    void testResetFailedAdsToScraped() {
        RawScrapedAd failed = repository.save(RawScrapedAd.builder()
                .externalId("AD_FAILED")
                .status(AdProcessingStatus.FAILED)
                .attempts(5)
                .nextRetryAt(Instant.now().plusSeconds(600))
                .errorMessage("API timeout")
                .build());

        int count = repository.resetFailedAdsToScraped();
        assertEquals(1, count);

        RawScrapedAd reloaded = repository.findById(failed.getId()).orElseThrow();
        assertEquals(AdProcessingStatus.SCRAPED, reloaded.getStatus());
        assertEquals(0, reloaded.getAttemptsCount());
        assertNull(reloaded.getNextRetryAt());
        assertNull(reloaded.getErrorMessage());
    }
}
