package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.CleanJobDto;
import hanzner.inzeratscraper.dto.CleanJobStatus;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Služba pro správu asynchronních úloh čištění inzerátů.
 * Uchovává stav úloh v paměti a garantuje, že v daný okamžik běží maximálně jeden clean job.
 */
@Slf4j
@Service
public class CleanJobService {

    private final MigrationOrchestratorService orchestratorService;
    private final ExecutorService llmExecutor;

    private final Map<String, CleanJobState> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeJobId = new AtomicReference<>(null);

    public CleanJobService(
            MigrationOrchestratorService orchestratorService,
            @Qualifier("llmExecutor") ExecutorService llmExecutor) {
        this.orchestratorService = orchestratorService;
        this.llmExecutor = llmExecutor;
    }

    /**
     * Spustí nový asynchronní clean job. Pokud již jiný job běží, vyhodí JobConflictException (HTTP 409).
     */
    public CleanJobDto startCleanJob(int limit, boolean retryFailed) {
        String newJobId = UUID.randomUUID().toString();
        if (!activeJobId.compareAndSet(null, newJobId)) {
            String currentRunning = activeJobId.get();
            log.warn("Pokus o spuštění paralelního clean jobu odmítnut (409 Conflict). Již běží job {}", currentRunning);
            throw new JobConflictException("Již běží jiný asynchronní clean job s ID: " + currentRunning);
        }

        CleanJobState jobState = new CleanJobState(newJobId, Instant.now());
        jobs.put(newJobId, jobState);

        llmExecutor.submit(() -> {
            try {
                orchestratorService.cleanStagedAds(limit, retryFailed, new CleanJobProgress() {
                    @Override
                    public void onTotalKnown(int total) {
                        jobState.total.set(total);
                    }

                    @Override
                    public void onItemCompleted(AdProcessingStatus status) {
                        jobState.processed.incrementAndGet();
                        if (status == AdProcessingStatus.CLEANED) {
                            jobState.succeeded.incrementAndGet();
                        } else if (status == AdProcessingStatus.NEEDS_REVIEW) {
                            jobState.needsReview.incrementAndGet();
                        } else {
                            jobState.failed.incrementAndGet();
                        }
                    }
                });
                jobState.status.set(CleanJobStatus.DONE);
            } catch (Exception e) {
                log.error("Chyba při běhu asynchronního clean jobu {}: {}", newJobId, e.getMessage());
                jobState.status.set(CleanJobStatus.FAILED);
            } finally {
                jobState.finishedAt.set(Instant.now());
                activeJobId.compareAndSet(newJobId, null);
            }
        });

        return jobState.toDto();
    }

    /**
     * Vrátí stav jobu podle ID nebo Optional.empty(), pokud job neexistuje (HTTP 404).
     */
    public Optional<CleanJobDto> getJob(String jobId) {
        CleanJobState state = jobs.get(jobId);
        return state != null ? Optional.of(state.toDto()) : Optional.empty();
    }

    public Optional<String> getActiveJobId() {
        return Optional.ofNullable(activeJobId.get());
    }

    /**
     * Vnitřní reprezentace stavu běžícího nebo dokončeného jobu.
     */
    private static class CleanJobState {
        final String jobId;
        final Instant startedAt;
        final AtomicReference<CleanJobStatus> status = new AtomicReference<>(CleanJobStatus.RUNNING);
        final AtomicReference<Instant> finishedAt = new AtomicReference<>(null);
        final AtomicInteger total = new AtomicInteger(0);
        final AtomicInteger processed = new AtomicInteger(0);
        final AtomicInteger succeeded = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicInteger needsReview = new AtomicInteger(0);

        CleanJobState(String jobId, Instant startedAt) {
            this.jobId = jobId;
            this.startedAt = startedAt;
        }

        CleanJobDto toDto() {
            return new CleanJobDto(
                    jobId,
                    status.get().name(),
                    total.get(),
                    processed.get(),
                    succeeded.get(),
                    failed.get(),
                    needsReview.get(),
                    startedAt,
                    finishedAt.get()
            );
        }
    }
}
