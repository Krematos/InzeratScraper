package hanzner.inzeratscraper.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Validátor konfiguračních vlastností souběhu a scraperu při startu aplikace (Fáze 6.2).
 * Zajišťuje, že aplikace nenastartuje s neplatnými hodnotami (<= 0 nebo překračujícími rozumné horní meze).
 */
@Getter
@Configuration
public class ConcurrencyConfigValidator {

    public static final int MAX_CONCURRENCY_LOWER_BOUND = 1;
    public static final int MAX_CONCURRENCY_UPPER_BOUND = 50;

    public static final int RPM_LOWER_BOUND = 1;
    public static final int RPM_UPPER_BOUND = 10_000;

    public static final int MAX_ATTEMPTS_LOWER_BOUND = 1;
    public static final int MAX_ATTEMPTS_UPPER_BOUND = 20;

    public static final int PROCESSING_TIMEOUT_LOWER_BOUND = 1;
    public static final int PROCESSING_TIMEOUT_UPPER_BOUND = 1440; // 24 hodin

    private final int maxConcurrency;
    private final int requestsPerMinute;
    private final int maxAttempts;
    private final int processingTimeoutMinutes;

    public ConcurrencyConfigValidator(
            @Value("${llm.max-concurrency:3}") int maxConcurrency,
            @Value("${llm.requests-per-minute:10}") int requestsPerMinute,
            @Value("${llm.max-attempts:5}") int maxAttempts,
            @Value("${scraper.processing-timeout-minutes:15}") int processingTimeoutMinutes) {
        this.maxConcurrency = maxConcurrency;
        this.requestsPerMinute = requestsPerMinute;
        this.maxAttempts = maxAttempts;
        this.processingTimeoutMinutes = processingTimeoutMinutes;
    }

    @PostConstruct
    public void validate() {
        if (maxConcurrency < MAX_CONCURRENCY_LOWER_BOUND || maxConcurrency > MAX_CONCURRENCY_UPPER_BOUND) {
            throw new IllegalStateException(String.format(
                    "Chyba konfigurace: 'llm.max-concurrency' má neplatnou hodnotu %d. Povolený rozsah je %d až %d.",
                    maxConcurrency, MAX_CONCURRENCY_LOWER_BOUND, MAX_CONCURRENCY_UPPER_BOUND
            ));
        }

        if (requestsPerMinute < RPM_LOWER_BOUND || requestsPerMinute > RPM_UPPER_BOUND) {
            throw new IllegalStateException(String.format(
                    "Chyba konfigurace: 'llm.requests-per-minute' má neplatnou hodnotu %d. Povolený rozsah je %d až %d.",
                    requestsPerMinute, RPM_LOWER_BOUND, RPM_UPPER_BOUND
            ));
        }

        if (maxAttempts < MAX_ATTEMPTS_LOWER_BOUND || maxAttempts > MAX_ATTEMPTS_UPPER_BOUND) {
            throw new IllegalStateException(String.format(
                    "Chyba konfigurace: 'llm.max-attempts' má neplatnou hodnotu %d. Povolený rozsah je %d až %d.",
                    maxAttempts, MAX_ATTEMPTS_LOWER_BOUND, MAX_ATTEMPTS_UPPER_BOUND
            ));
        }

        if (processingTimeoutMinutes < PROCESSING_TIMEOUT_LOWER_BOUND || processingTimeoutMinutes > PROCESSING_TIMEOUT_UPPER_BOUND) {
            throw new IllegalStateException(String.format(
                    "Chyba konfigurace: 'scraper.processing-timeout-minutes' má neplatnou hodnotu %d. Povolený rozsah je %d až %d minut.",
                    processingTimeoutMinutes, PROCESSING_TIMEOUT_LOWER_BOUND, PROCESSING_TIMEOUT_UPPER_BOUND
            ));
        }
    }
}
