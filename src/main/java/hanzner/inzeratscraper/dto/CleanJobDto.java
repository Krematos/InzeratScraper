package hanzner.inzeratscraper.dto;

import java.time.Instant;

/**
 * DTO reprezentující stav asynchronní úlohy čištění inzerátů.
 */
public record CleanJobDto(
        String jobId,
        String status,
        int total,
        int processed,
        int succeeded,
        int failed,
        int needsReview,
        Instant startedAt,
        Instant finishedAt
) {}
