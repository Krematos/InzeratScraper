package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationOrchestratorService {

    private final BazosScraperService bazosScraperService;
    private final AdDataCleanerService adDataCleanerService;
    private final RemoteEshopSyncService remoteEshopSyncService;
    private final RawScrapedAdRepository rawScrapedAdRepository;
    private final ObjectMapper objectMapper;

    /**
     * Fáze 1: Stáhne inzeráty z uživatelského profilu na Bazoši a uloží je do lokální staging DB.
     */
    public int scrapeProfileToStaging(String profileUrl) {
        log.info("Spouštím Fázi 1 (Scrape do Staging DB) pro profil: {}", profileUrl);
        List<RawAdDto> newlyScraped = bazosScraperService.scrapeAllUserAds(profileUrl);
        log.info("Fáze 1 dokončena. Nově staženo a uloženo {} inzerátů.", newlyScraped.size());
        return newlyScraped.size();
    }

    /**
     * Fáze 2: Vezme max 'limit' záznamů ve stavu SCRAPED, vyčistí je přes AdDataCleanerService
     * a uloží výsledek jako CLEANED nebo NEEDS_REVIEW.
     */
    public int cleanStagedAds(int limit) {
        int effectiveLimit = limit > 0 ? limit : 15;
        List<RawScrapedAd> scrapedAds = rawScrapedAdRepository.findByStatus(AdProcessingStatus.SCRAPED);
        int toProcessCount = Math.min(scrapedAds.size(), effectiveLimit);

        log.info("Spouštím Fázi 2 (Čištění přes AI). Nalezeno {} SCRAPED záznamů, zpracuji max {}.",
                scrapedAds.size(), toProcessCount);

        int cleanedCount = 0;
        for (int i = 0; i < toProcessCount; i++) {
            RawScrapedAd ad = scrapedAds.get(i);
            if (cleanSingleStagedAd(ad)) {
                cleanedCount++;
            }
        }

        log.info("Fáze 2 dokončena. Úspěšně vyčištěno {}/{} záznamů.", cleanedCount, toProcessCount);
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
            ad.setPriceNeedsReview(cleanedDto.priceNeedsReview());
            ad.setStatus(cleanedDto.priceNeedsReview()
                    ? AdProcessingStatus.NEEDS_REVIEW
                    : AdProcessingStatus.CLEANED);
            ad.setErrorMessage(null);
            rawScrapedAdRepository.save(ad);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ad.setStatus(AdProcessingStatus.FAILED);
            ad.setErrorMessage("Přerušeno: " + e.getMessage());
            rawScrapedAdRepository.save(ad);
            return false;
        } catch (Exception e) {
            log.error("Chyba při AI čištění inzerátu {}: {}", ad.getExternalId(), e.getMessage());
            ad.setStatus(AdProcessingStatus.FAILED);
            ad.setErrorMessage(e.getMessage());
            rawScrapedAdRepository.save(ad);
            return false;
        }
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

        log.info("Fáze 3 dokončena (dryRun={}). Zpracováno {}/{} záznamů.", dryRun, syncedCount, cleanedAds.size());
        return syncedCount;
    }

    private boolean syncSingleCleanedAd(RawScrapedAd ad, boolean dryRun) {
        if (ad.getCleanedPayloadJson() == null || ad.getCleanedPayloadJson().isBlank()) {
            log.warn("Inzerát {} nemá vyplněný cleanedPayloadJson, přeskakuji.", ad.getExternalId());
            return false;
        }

        try {
            CleanedProductDto dto = objectMapper.readValue(ad.getCleanedPayloadJson(), CleanedProductDto.class);
            if (dryRun) {
                log.info("[DRY-RUN] Produkt připravený k odeslání ({}): {}", ad.getExternalId(), ad.getCleanedPayloadJson());
                return true;
            }

            remoteEshopSyncService.pushProductToVps(dto);
            ad.setStatus(AdProcessingStatus.SYNCED);
            ad.setErrorMessage(null);
            rawScrapedAdRepository.save(ad);
            return true;
        } catch (Exception e) {
            log.error("Selhala synchronizace inzerátu {} do e-shopu: {}", ad.getExternalId(), e.getMessage());
            ad.setStatus(AdProcessingStatus.FAILED);
            ad.setErrorMessage(e.getMessage());
            rawScrapedAdRepository.save(ad);
            return false;
        }
    }
}
