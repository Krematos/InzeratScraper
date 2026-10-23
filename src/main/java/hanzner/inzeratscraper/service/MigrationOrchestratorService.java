package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationOrchestratorService {

    private final BazosScraperService bazosScraperService;
    private final ImageDownloadService imageDownloadService;
    private final AdDataCleanerService adDataCleanerService;
    private final RemoteEshopSyncService remoteEshopSyncService;
    private final RawScrapedAdRepository rawScrapedAdRepository;
    private final ObjectMapper objectMapper;

    @Getter
    @Setter
    @Value("${scraper.processing-timeout-minutes:15}")
    private int processingTimeoutMinutes = 15;

    @Getter
    @Setter
    @Value("${llm.max-attempts:5}")
    private int maxAttempts = 5;

    /**
     * Fáze 1.4: Obnova po pádu aplikace.
     * Převede zaseknuté záznamy ve stavu PROCESSING starší než timeout zpět na SCRAPED.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(processingTimeoutMinutes));
        int recovered = recoverStuckProcessingAds(cutoff);
        if (recovered > 0) {
            log.info("Obnova po pádu: Obnoveno {} zaseknutých záznamů ve stavu PROCESSING starších než {} minut zpět na SCRAPED.",
                    recovered, processingTimeoutMinutes);
        }
    }

    @Transactional
    public int recoverStuckProcessingAds(Instant cutoff) {
        return rawScrapedAdRepository.resetStuckProcessingAds(cutoff);
    }

    /**
     * Fáze 1: Stáhne inzeráty z uživatelského profilu na Bazoši, uloží je do lokální staging DB
     * a zálohuje obrázky na lokální disk.
     */
    public int scrapeProfileToStaging(String profileUrl) {
        return scrapeProfileToStaging(profileUrl, true);
    }

    /**
     * Fáze 1: Stáhne inzeráty z uživatelského profilu na Bazoši do lokální staging DB
     * a volitelně stáhne obrázky přes ImageDownloadService.
     */
    public int scrapeProfileToStaging(String profileUrl, boolean downloadImages) {
        log.info("Spouštím Fázi 1 (Scrape do Staging DB, downloadImages={}) pro profil: {}", downloadImages, profileUrl);
        List<RawAdDto> newlyScraped = bazosScraperService.scrapeAllUserAds(profileUrl);

        if (downloadImages && !newlyScraped.isEmpty()) {
            int downloadedFilesCount = 0;
            for (RawAdDto ad : newlyScraped) {
                List<Path> savedImages = imageDownloadService.downloadImagesForAd(ad.externalId(), ad.rawImages());
                downloadedFilesCount += savedImages.size();
            }
            log.info("Pro {} nově stažených inzerátů uloženo celkem {} obrázků na disk.",
                    newlyScraped.size(), downloadedFilesCount);
        }

        log.info("Fáze 1 dokončena. Nově staženo a uloženo {} inzerátů.", newlyScraped.size());
        return newlyScraped.size();
    }

    /**
     * Fáze 2: Vyčistí nezpracované záznamy přes AI s atomickým claimem a model fallbackem.
     */
    public int cleanStagedAds(int limit) {
        return cleanStagedAds(limit, false);
    }

    /**
     * Fáze 2: Vyčistí nezpracované záznamy přes AI s volitelným opakováním FAILED záznamů.
     */
    public int cleanStagedAds(int limit, boolean retryFailed) {
        // 1.5 Fail-fast: ověření konfigurace LLM klíče před jakýmkoliv zásahem do DB
        adDataCleanerService.assertLlmConfigured();

        // 1.7 Opakování FAILED záznamů
        if (retryFailed) {
            int resetCount = rawScrapedAdRepository.resetFailedAdsToScraped();
            if (resetCount > 0) {
                log.info("Parametr retryFailed=true: resetováno {} záznamů ze stavu FAILED zpět na SCRAPED.", resetCount);
            }
        }

        int effectiveLimit = limit > 0 ? limit : 15;
        Instant now = Instant.now();
        List<RawScrapedAd> candidates = rawScrapedAdRepository.findCandidateAdsForProcessing(
                now, PageRequest.of(0, effectiveLimit)
        );

        log.info("Spouštím Fázi 2 (Čištění přes AI). Nalezeno {} kandidátních SCRAPED záznamů, limit {}.",
                candidates.size(), effectiveLimit);

        int cleanedCount = 0;
        for (RawScrapedAd candidate : candidates) {
            // 1.3 Atomický podmíněný claim
            int claimed = (candidate.getId() != null)
                    ? rawScrapedAdRepository.claim(candidate.getId(), Instant.now())
                    : 1;

            if (claimed == 0) {
                log.debug("Inzerát ID {} již zpracovává jiné vlákno/proces, přeskakuji.", candidate.getId());
                continue;
            }

            candidate.setStatus(AdProcessingStatus.PROCESSING);
            candidate.setProcessingStartedAt(Instant.now());

            if (cleanSingleStagedAd(candidate)) {
                cleanedCount++;
            }
        }

        log.info("Fáze 2 dokončena. Úspěšně vyčištěno {} záznamů.", cleanedCount);
        return cleanedCount;
    }

    private boolean cleanSingleStagedAd(RawScrapedAd ad) {
        RawAdDto rawDto = new RawAdDto(
                ad.getExternalId(),
                ad.getUrl(),
                ad.getSubdomainCategory(),
                ad.getRawTitle(),
                ad.getRawDescription(),
                ad.getRawPrice(),
                ad.getRawLocation(),
                ad.getRawImages()
        );

        try {
            CleanedProductDto cleanedDto = adDataCleanerService.cleanWithAi(rawDto);
            String jsonPayload = objectMapper.writeValueAsString(cleanedDto);

            ad.setCleanedPayloadJson(jsonPayload);
            ad.setPriceNeedsReview(cleanedDto.needsReview());
            ad.setReviewReason(cleanedDto.reviewReason());
            ad.setStatus(cleanedDto.needsReview()
                    ? AdProcessingStatus.NEEDS_REVIEW
                    : AdProcessingStatus.CLEANED);
            ad.setErrorMessage(null);
            ad.setProcessingStartedAt(null);
            rawScrapedAdRepository.save(ad);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Zpracování inzerátu {} bylo přerušeno (InterruptedException). Vracím do SCRAPED bez zvýšení attempts.",
                    ad.getExternalId());
            ad.setStatus(AdProcessingStatus.SCRAPED);
            ad.setProcessingStartedAt(null);
            ad.setErrorMessage("Přerušeno: " + e.getMessage());
            rawScrapedAdRepository.save(ad);
            return false;
        } catch (Exception e) {
            handleProcessingError(ad, e);
            return false;
        }
    }

    private void handleProcessingError(RawScrapedAd ad, Exception e) {
        log.error("Chyba při AI čištění inzerátu {}: {}", ad.getExternalId(), e.getMessage());
        ad.setProcessingStartedAt(null);

        boolean transientError = isTransientError(e);
        int currentAttempts = ad.getAttemptsCount();

        if (transientError) {
            int newAttempts = currentAttempts + 1;
            ad.setAttempts(newAttempts);
            if (newAttempts >= maxAttempts) {
                ad.setStatus(AdProcessingStatus.FAILED);
                ad.setErrorMessage("Vyčerpán maximální počet pokusů (" + maxAttempts + "): " + e.getMessage());
            } else {
                ad.setStatus(AdProcessingStatus.SCRAPED);
                ad.setNextRetryAt(calculateBackoff(newAttempts, Instant.now()));
                ad.setErrorMessage("Přechodná chyba (pokus " + newAttempts + "/" + maxAttempts + "): " + e.getMessage());
            }
        } else {
            // Trvalá chyba (nevalidní JSON, ostatní 4xx apod.)
            ad.setStatus(AdProcessingStatus.FAILED);
            ad.setErrorMessage(e.getMessage());
        }
        rawScrapedAdRepository.save(ad);
    }

    public boolean isTransientError(Exception e) {
        if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
            return true;
        }
        if (e instanceof IOException) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (e instanceof tools.jackson.core.JacksonException || e instanceof tools.jackson.databind.DatabindException) {
                return false;
            }
            if (msg.contains("HTTP 400") || msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 404")) {
                return false;
            }
            return true;
        }
        return false;
    }

    public Instant calculateBackoff(int attempts, Instant now) {
        long baseSeconds = 5L * (1L << Math.min(attempts, 7)); // 10s, 20s, 40s...
        long jitter = ThreadLocalRandom.current().nextLong(-2, 3);
        long delaySeconds = Math.min(600L, Math.max(2L, baseSeconds + jitter));
        return now.plusSeconds(delaySeconds);
    }

    /**
     * Vrátí všechny záznamy vyžadující ruční kontrolu (stav NEEDS_REVIEW nebo priceNeedsReview = true).
     */
    public List<RawScrapedAd> getAdsNeedingReview() {
        return rawScrapedAdRepository.findAll().stream()
                .filter(ad -> ad.getStatus() == AdProcessingStatus.NEEDS_REVIEW || ad.isPriceNeedsReview())
                .toList();
    }

    /**
     * Fáze 3: Odešle vyčištěné záznamy do e-shopu. Při dryRun = true pouze zaloguje payload bez HTTP volání.
     */
    public int syncCleanedAdsToEshop(int limit, boolean dryRun) {
        int effectiveLimit = limit > 0 ? limit : 50;
        List<RawScrapedAd> cleanedAds = rawScrapedAdRepository.findByStatus(AdProcessingStatus.CLEANED).stream()
                .filter(ad -> !ad.isPriceNeedsReview())
                .limit(effectiveLimit)
                .toList();

        log.info("Spouštím Fázi 3 (Synchronizace do e-shopu, dryRun={}). K odeslání {} záznamů.",
                dryRun, cleanedAds.size());

        int syncedCount = 0;
        for (RawScrapedAd ad : cleanedAds) {
            if (syncSingleCleanedAd(ad, dryRun)) {
                syncedCount++;
            }
        }

        log.info("Fáze 3 dokončena (dryRun={}). Zpracováno {}/{} záznamů.",
                dryRun, syncedCount, cleanedAds.size());
        return syncedCount;
    }

    private boolean syncSingleCleanedAd(RawScrapedAd ad, boolean dryRun) {
        try {
            CleanedProductDto productDto = objectMapper.readValue(ad.getCleanedPayloadJson(), CleanedProductDto.class);
            if (dryRun) {
                log.info("[DRY-RUN] Produkt připravený k odeslání ({}): {}", ad.getExternalId(), ad.getCleanedPayloadJson());
                return true;
            }

            remoteEshopSyncService.pushProductToVps(productDto);
            ad.setStatus(AdProcessingStatus.SYNCED);
            rawScrapedAdRepository.save(ad);
            return true;
        } catch (Exception e) {
            log.error("Selhala synchronizace inzerátu {} do e-shopu: {}", ad.getExternalId(), e.getMessage());
            ad.setErrorMessage("Chyba synchronizace: " + e.getMessage());
            rawScrapedAdRepository.save(ad);
            return false;
        }
    }
}
