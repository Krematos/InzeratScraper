package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanedProductDto;
import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.dto.ReviewItemDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MigrationOrchestratorService {

    private final BazosScraperService bazosScraperService;
    private final ImageDownloadService imageDownloadService;
    private final AdDataCleanerService adDataCleanerService;
    private final RemoteEshopSyncService remoteEshopSyncService;
    private final RawScrapedAdRepository rawScrapedAdRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService llmExecutor;

    @Getter
    @Setter
    @Value("${scraper.processing-timeout-minutes:15}")
    private int processingTimeoutMinutes = 15;

    @Getter
    @Setter
    @Value("${llm.max-attempts:5}")
    private int maxAttempts = 5;

    @Autowired
    public MigrationOrchestratorService(
            BazosScraperService bazosScraperService,
            ImageDownloadService imageDownloadService,
            AdDataCleanerService adDataCleanerService,
            RemoteEshopSyncService remoteEshopSyncService,
            RawScrapedAdRepository rawScrapedAdRepository,
            ObjectMapper objectMapper,
            @Qualifier("llmExecutor") ExecutorService llmExecutor) {
        this.bazosScraperService = bazosScraperService;
        this.imageDownloadService = imageDownloadService;
        this.adDataCleanerService = adDataCleanerService;
        this.remoteEshopSyncService = remoteEshopSyncService;
        this.rawScrapedAdRepository = rawScrapedAdRepository;
        this.objectMapper = objectMapper;
        this.llmExecutor = llmExecutor != null ? llmExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    public MigrationOrchestratorService(
            BazosScraperService bazosScraperService,
            ImageDownloadService imageDownloadService,
            AdDataCleanerService adDataCleanerService,
            RemoteEshopSyncService remoteEshopSyncService,
            RawScrapedAdRepository rawScrapedAdRepository,
            ObjectMapper objectMapper) {
        this(bazosScraperService, imageDownloadService, adDataCleanerService,
                remoteEshopSyncService, rawScrapedAdRepository, objectMapper,
                Executors.newVirtualThreadPerTaskExecutor());
    }

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

    /**
     * Fáze 4.4: Řádné ukončení aplikace.
     * Vrátí případné rozpracované PROCESSING záznamy zpět do stavu SCRAPED.
     */
    @PreDestroy
    public void onShutdown() {
        try {
            int recovered = recoverStuckProcessingAds(Instant.now());
            if (recovered > 0) {
                log.info("Řádné ukončení aplikace (@PreDestroy): Vráceno {} rozpracovaných PROCESSING záznamů zpět na SCRAPED.", recovered);
            }
        } catch (Exception e) {
            log.warn("Chyba při obnově PROCESSING záznamů během shutdownu: {}", e.getMessage());
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
     * Fáze 2 & 4: Vyčistí nezpracované záznamy paralelně přes AI na virtuálních vláknech
     * s atomickým claimem a volitelným opakováním FAILED záznamů.
     */
    public int cleanStagedAds(int limit, boolean retryFailed) {
        return cleanStagedAds(limit, retryFailed, null);
    }

    /**
     * Fáze 2, 4 & 5: Vyčistí nezpracované záznamy paralelně přes AI na virtuálních vláknech
     * s atomickým claimem, volitelným opakováním FAILED záznamů a sledováním průběhu (CleanJobProgress).
     */
    public int cleanStagedAds(int limit, boolean retryFailed, CleanJobProgress progress) {
        // 1.5 Fail-fast: ověření konfigurace LLM klíče před jakýmkoliv zásahem do DB
        adDataCleanerService.assertLlmConfigured();

        // 2.2 Předvyplnění unikátních názvů z již zpracovaných záznamů (CLEANED, NEEDS_REVIEW, SYNCED)
        prefillRegisteredNames();

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

        if (progress != null) {
            progress.onTotalKnown(candidates.size());
        }

        log.info("Spouštím Fázi 2 (Čištění přes AI). Nalezeno {} kandidátních SCRAPED záznamů, limit {}.",
                candidates.size(), effectiveLimit);

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        AtomicBoolean interrupted = new AtomicBoolean(false);

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

            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                    () -> cleanSingleStagedAd(candidate, interrupted, progress),
                    llmExecutor
            );
            futures.add(future);
        }

        if (futures.isEmpty()) {
            log.info("Fáze 2 dokončena. Žádné záznamy nebyly získány k čištění.");
            return 0;
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Paralelní čištění inzerátů bylo přerušeno: {}", e.getMessage());
            for (CompletableFuture<Boolean> f : futures) {
                f.cancel(true);
            }
        } catch (ExecutionException e) {
            log.error("Neočekávaná chyba při paralelním běhu úloh čištění: {}", e.getMessage());
        }

        if (interrupted.get()) {
            Thread.currentThread().interrupt();
        }

        int cleanedCount = 0;
        for (CompletableFuture<Boolean> f : futures) {
            if (!f.isCancelled() && !f.isCompletedExceptionally() && Boolean.TRUE.equals(f.getNow(false))) {
                cleanedCount++;
            }
        }

        log.info("Fáze 2 dokončena. Úspěšně vyčištěno {} záznamů.", cleanedCount);
        return cleanedCount;
    }

    /**
     * Fáze 2.2: Předvyplní zaregistrované názvy z již zpracovaných záznamů v DB (CLEANED, NEEDS_REVIEW, SYNCED).
     */
    public void prefillRegisteredNames() {
        List<RawScrapedAd> alreadyProcessed = rawScrapedAdRepository.findByStatusIn(
                List.of(AdProcessingStatus.CLEANED, AdProcessingStatus.NEEDS_REVIEW, AdProcessingStatus.SYNCED)
        );
        for (RawScrapedAd ad : alreadyProcessed) {
            String json = ad.getCleanedPayloadJson();
            if (json != null && !json.isBlank()) {
                try {
                    JsonNode root = objectMapper.readTree(json);
                    String name = root.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        adDataCleanerService.registerExistingName(name);
                    }
                } catch (Exception e) {
                    log.warn("Nepodařilo se přečíst název z existujícího payloadu pro inzerát {}: {}", ad.getExternalId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Zpracuje jeden inzerát. Nesmí být @Transactional, aby se nedrželo DB spojení během volání LLM (Fáze 4.3).
     */
    private boolean cleanSingleStagedAd(RawScrapedAd ad, AtomicBoolean interruptedFlag, CleanJobProgress progress) {
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

        CleanedProductDto cleanedDto = null;
        try {
            cleanedDto = adDataCleanerService.cleanWithAi(rawDto);
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
            log.info("Inzerát {} úspěšně vyčištěn (stav: {}, název: '{}').",
                    ad.getExternalId(), ad.getStatus(), cleanedDto.name());
            if (progress != null) {
                progress.onItemCompleted(ad.getStatus());
            }
            return true;
        } catch (InterruptedException e) {
            if (interruptedFlag != null) {
                interruptedFlag.set(true);
            }
            if (cleanedDto != null) {
                adDataCleanerService.releaseName(cleanedDto.name());
            }
            Thread.currentThread().interrupt();
            log.warn("Zpracování inzerátu {} bylo přerušeno (InterruptedException). Vracím do SCRAPED bez zvýšení attempts.",
                    ad.getExternalId());
            ad.setStatus(AdProcessingStatus.SCRAPED);
            ad.setProcessingStartedAt(null);
            ad.setErrorMessage("Přerušeno: " + e.getMessage());
            rawScrapedAdRepository.save(ad);
            if (progress != null) {
                progress.onItemCompleted(ad.getStatus());
            }
            return false;
        } catch (Exception e) {
            if (cleanedDto != null) {
                adDataCleanerService.releaseName(cleanedDto.name());
            }
            handleProcessingError(ad, e);
            if (progress != null) {
                progress.onItemCompleted(ad.getStatus());
            }
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
        if (e instanceof LlmRateLimitException) {
            return true;
        }
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
     * Vrátí všechny záznamy vyžadující ruční kontrolu v podobě DTO (Fáze 5.4).
     */
    public List<ReviewItemDto> getAdsNeedingReview() {
        return rawScrapedAdRepository.findAll().stream()
                .filter(ad -> ad.getStatus() == AdProcessingStatus.NEEDS_REVIEW || ad.isPriceNeedsReview())
                .map(this::toReviewItemDto)
                .toList();
    }

    private ReviewItemDto toReviewItemDto(RawScrapedAd ad) {
        String name = ad.getRawTitle();
        BigDecimal price = null;
        if (ad.getCleanedPayloadJson() != null && !ad.getCleanedPayloadJson().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(ad.getCleanedPayloadJson());
                if (root.hasNonNull("name")) {
                    name = root.path("name").asText();
                }
                if (root.hasNonNull("price")) {
                    price = new BigDecimal(root.path("price").asText());
                }
            } catch (Exception ignored) {
            }
        }
        return new ReviewItemDto(
                ad.getExternalId(),
                name,
                price,
                ad.getReviewReason(),
                ad.getStatus() != null ? ad.getStatus().name() : null
        );
    }

    /**
     * Vrátí přehled počtu inzerátů v jednotlivých fázích / stavech.
     */
    public Map<String, Long> getStatusStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        for (AdProcessingStatus status : AdProcessingStatus.values()) {
            stats.put(status.name(), 0L);
        }
        List<Object[]> grouped = rawScrapedAdRepository.countGroupedByStatus();
        for (Object[] row : grouped) {
            if (row != null && row.length >= 2 && row[0] instanceof AdProcessingStatus status) {
                stats.put(status.name(), ((Number) row[1]).longValue());
            }
        }
        return stats;
    }

    /**
     * Vrátí vyčištěné produkty připravené pro e-shop ve formě DTO a volitelně je uloží do JSON souboru na disk.
     */
    public List<CleanedProductDto> exportCleanedProducts(AdProcessingStatus status, boolean saveToFile) {
        List<RawScrapedAd> ads = (status != null)
                ? rawScrapedAdRepository.findByStatus(status)
                : rawScrapedAdRepository.findByStatusIn(List.of(AdProcessingStatus.CLEANED, AdProcessingStatus.SYNCED));

        List<CleanedProductDto> products = new ArrayList<>();
        for (RawScrapedAd ad : ads) {
            String json = ad.getCleanedPayloadJson();
            if (json != null && !json.isBlank()) {
                try {
                    CleanedProductDto dto = objectMapper.readValue(json, CleanedProductDto.class);
                    products.add(dto);
                } catch (Exception e) {
                    log.warn("Chyba při deserializaci vyčištěného produktu pro inzerát {}: {}", ad.getExternalId(), e.getMessage());
                }
            }
        }

        if (saveToFile) {
            try {
                Path dataDir = Path.of("./data");
                if (!Files.exists(dataDir)) {
                    Files.createDirectories(dataDir);
                }
                Path exportFile = dataDir.resolve("products-export.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(exportFile.toFile(), products);
                log.info("Závěrečný export {} produktů byl úspěšně uložen do souboru: {}", products.size(), exportFile.toAbsolutePath());
            } catch (IOException e) {
                log.error("Nepodařilo se uložit export produktů do souboru: {}", e.getMessage(), e);
            }
        }

        return products;
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
