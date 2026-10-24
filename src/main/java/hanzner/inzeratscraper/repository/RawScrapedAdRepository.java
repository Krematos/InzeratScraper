package hanzner.inzeratscraper.repository;

import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RawScrapedAdRepository extends JpaRepository<RawScrapedAd, Long> {

    boolean existsByExternalId(String externalId);

    Optional<RawScrapedAd> findByExternalId(String externalId);

    List<RawScrapedAd> findByStatus(AdProcessingStatus status);

    List<RawScrapedAd> findByStatusIn(Collection<AdProcessingStatus> statuses);

    /**
     * Vrátí agregovaný počet inzerátů seskupený podle stavu (fáze zpracování).
     */
    @Query("select a.status, count(a) from RawScrapedAd a group by a.status")
    List<Object[]> countGroupedByStatus();

    /**
     * Atomický podmíněný claim inzerátu pro bezpečný souběh bez SKIP LOCKED.
     * Vrátí 1 pokud byl záznam úspěšně získán kýmkoliv, 0 pokud ho mezitím získal jiný proces/vlákno.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RawScrapedAd a set a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.PROCESSING, a.processingStartedAt = :now " +
           "where a.id = :id and a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.SCRAPED")
    int claim(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Výběr kandidátů na zpracování: stav SCRAPED a plánovaný čas pokusu je null nebo v minulosti.
     * Stránkování probíhá na úrovni DB.
     */
    @Query("select a from RawScrapedAd a where a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.SCRAPED " +
           "and (a.nextRetryAt is null or a.nextRetryAt <= :now)")
    List<RawScrapedAd> findCandidateAdsForProcessing(@Param("now") Instant now, Pageable pageable);

    /**
     * Obnova zaseknutých záznamů po pádu aplikace (stav PROCESSING starší než cutoff).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RawScrapedAd a set a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.SCRAPED, a.processingStartedAt = null " +
           "where a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.PROCESSING and a.processingStartedAt <= :cutoff")
    int resetStuckProcessingAds(@Param("cutoff") Instant cutoff);

    /**
     * Opakování FAILED záznamů: resetuje stav na SCRAPED a vynuluje pokusy i chyby.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RawScrapedAd a set a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.SCRAPED, a.attempts = 0, a.nextRetryAt = null, a.errorMessage = null " +
           "where a.status = hanzner.inzeratscraper.entity.AdProcessingStatus.FAILED")
    int resetFailedAdsToScraped();
}
