package hanzner.inzeratscraper;

import hanzner.inzeratscraper.dto.CleanJobDto;
import hanzner.inzeratscraper.dto.CleanJobStatus;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.service.CleanJobProgress;
import hanzner.inzeratscraper.service.CleanJobService;
import hanzner.inzeratscraper.service.JobConflictException;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanJobServiceTest {

    @Mock
    private MigrationOrchestratorService orchestratorService;

    private ExecutorService executor;
    private CleanJobService cleanJobService;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        cleanJobService = new CleanJobService(orchestratorService, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    @DisplayName("startCleanJob vrátí jobId ve stavu RUNNING a po dokončení přejde do DONE")
    void testStartCleanJobLifecycle() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(inv -> {
            CleanJobProgress progress = inv.getArgument(2);
            if (progress != null) {
                progress.onTotalKnown(3);
                progress.onItemCompleted(AdProcessingStatus.CLEANED);
                progress.onItemCompleted(AdProcessingStatus.NEEDS_REVIEW);
                progress.onItemCompleted(AdProcessingStatus.FAILED);
            }
            latch.countDown();
            return 2;
        }).when(orchestratorService).cleanStagedAds(anyInt(), anyBoolean(), any());

        CleanJobDto initial = cleanJobService.startCleanJob(10, false);

        assertNotNull(initial.jobId());
        assertEquals("RUNNING", initial.status());
        assertNotNull(initial.startedAt());
        assertNull(initial.finishedAt());

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Úloha měla doběhnout");

        // Počkáme krátce na propsání DONE ve finally bloku
        Thread.sleep(50);

        Optional<CleanJobDto> finishedOpt = cleanJobService.getJob(initial.jobId());
        assertTrue(finishedOpt.isPresent());
        CleanJobDto finished = finishedOpt.get();

        assertEquals(CleanJobStatus.DONE.name(), finished.status());
        assertEquals(3, finished.total());
        assertEquals(3, finished.processed());
        assertEquals(1, finished.succeeded());
        assertEquals(1, finished.needsReview());
        assertEquals(1, finished.failed());
        assertNotNull(finished.finishedAt());
    }

    @Test
    @DisplayName("Druhý souběžný job vyhodí JobConflictException (409)")
    void testSecondConcurrentJobThrowsConflict() throws InterruptedException {
        CountDownLatch jobStartedLatch = new CountDownLatch(1);
        CountDownLatch jobHoldLatch = new CountDownLatch(1);

        doAnswer(inv -> {
            jobStartedLatch.countDown();
            jobHoldLatch.await(3, TimeUnit.SECONDS);
            return 1;
        }).when(orchestratorService).cleanStagedAds(anyInt(), anyBoolean(), any());

        CleanJobDto job1 = cleanJobService.startCleanJob(10, false);
        assertNotNull(job1.jobId());

        assertTrue(jobStartedLatch.await(2, TimeUnit.SECONDS));

        // Pokus o spuštění druhého jobu, zatímco první běží
        JobConflictException conflict = assertThrows(JobConflictException.class, () ->
                cleanJobService.startCleanJob(10, false)
        );
        assertTrue(conflict.getMessage().contains(job1.jobId()));

        // Uvolnění prvního jobu
        jobHoldLatch.countDown();
        Thread.sleep(50);

        // Po skončení prvního jobu již lze spustit další job
        CleanJobDto job2 = cleanJobService.startCleanJob(5, false);
        assertNotNull(job2.jobId());
    }

    @Test
    @DisplayName("Neznámé jobId vrací Optional.empty()")
    void testGetUnknownJobReturnsEmpty() {
        Optional<CleanJobDto> job = cleanJobService.getJob("non-existent");
        assertFalse(job.isPresent());
    }

    @Test
    @DisplayName("Při výjimce v orchestrátoru přejde job do stavu FAILED a uvolní zámek pro další job")
    void testJobFailureTransitionsToFailedAndReleasesLock() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(inv -> {
            latch.countDown();
            throw new RuntimeException("Chyba DB během čištění");
        }).when(orchestratorService).cleanStagedAds(anyInt(), anyBoolean(), any());

        CleanJobDto job = cleanJobService.startCleanJob(5, false);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);

        Optional<CleanJobDto> failedOpt = cleanJobService.getJob(job.jobId());
        assertTrue(failedOpt.isPresent());
        assertEquals(CleanJobStatus.FAILED.name(), failedOpt.get().status());
        assertNotNull(failedOpt.get().finishedAt());

        // Zámek byl uvolněn
        assertFalse(cleanJobService.getActiveJobId().isPresent());
    }
}
