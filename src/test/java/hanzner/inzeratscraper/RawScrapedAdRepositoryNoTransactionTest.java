package hanzner.inzeratscraper;

import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Záměrně BEZ @Transactional na třídě: ověřuje, že modifikující dotazy v repository
 * (claim, reset…) si transakci otevřou samy, tak jako v reálném běhu z HTTP vlákna.
 * Test s transakcí na třídě tento problém maskuje (transakci dodá test).
 */
@SpringBootTest
class RawScrapedAdRepositoryNoTransactionTest {

    @Autowired
    private RawScrapedAdRepository repository;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void claimWorksWithoutOuterTransaction() {
        RawScrapedAd ad = repository.save(RawScrapedAd.builder()
                .externalId("BAZOS_NOTX_CLAIM")
                .status(AdProcessingStatus.SCRAPED)
                .build());

        assertEquals(1, repository.claim(ad.getId(), Instant.now()));
        assertEquals(0, repository.claim(ad.getId(), Instant.now()));
        assertEquals(AdProcessingStatus.PROCESSING, repository.findById(ad.getId()).orElseThrow().getStatus());
    }

    @Test
    void resetStuckProcessingAdsWorksWithoutOuterTransaction() {
        Instant started = Instant.now().minus(1, ChronoUnit.HOURS);
        RawScrapedAd ad = repository.save(RawScrapedAd.builder()
                .externalId("BAZOS_NOTX_STUCK")
                .status(AdProcessingStatus.PROCESSING)
                .processingStartedAt(started)
                .build());

        assertEquals(1, repository.resetStuckProcessingAds(Instant.now().minus(15, ChronoUnit.MINUTES)));
        assertEquals(AdProcessingStatus.SCRAPED, repository.findById(ad.getId()).orElseThrow().getStatus());
    }

    @Test
    void resetFailedAdsWorksWithoutOuterTransaction() {
        RawScrapedAd ad = repository.save(RawScrapedAd.builder()
                .externalId("BAZOS_NOTX_FAILED")
                .status(AdProcessingStatus.FAILED)
                .errorMessage("chyba")
                .build());

        assertEquals(1, repository.resetFailedAdsToScraped());
        assertEquals(AdProcessingStatus.SCRAPED, repository.findById(ad.getId()).orElseThrow().getStatus());
    }
}
