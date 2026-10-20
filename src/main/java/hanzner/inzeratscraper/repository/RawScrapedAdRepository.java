package hanzner.inzeratscraper.repository;

import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RawScrapedAdRepository extends JpaRepository<RawScrapedAd, Long> {

    boolean existsByExternalId(String externalId);

    Optional<RawScrapedAd> findByExternalId(String externalId);

    List<RawScrapedAd> findByStatus(AdProcessingStatus status);
}
