package hanzner.inzeratscraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Rate limiter pro řízení frekvence požadavků na LLM API.
 * Implementuje minimální rozestup požadavků (leaky bucket) odvozený z povoleného počtu požadavků za minutu (RPM).
 * Výpočet a rezervace časového slotu probíhá v krátkém synchronizovaném bloku, samotný spánek probíhá mimo zámek,
 * což je optimální pro virtuální vlákna (nezpůsobuje thread pinning).
 */
@Slf4j
@Component
public class RateLimiter {

    private final int requestsPerMinute;
    private final long intervalNanos;
    private final LongSupplier nanoTimeSupplier;
    private final Sleeper sleeper;

    private long nextAvailableTimeNanos = 0L;

    public RateLimiter() {
        this(10);
    }

    @Autowired
    public RateLimiter(@Value("${llm.requests-per-minute:10}") int requestsPerMinute) {
        this(requestsPerMinute, System::nanoTime, Thread::sleep);
    }

    public RateLimiter(int requestsPerMinute, Clock clock, Sleeper sleeper) {
        this(requestsPerMinute, () -> clock.instant().toEpochMilli() * 1_000_000L, sleeper);
    }

    public RateLimiter(int requestsPerMinute, LongSupplier nanoTimeSupplier, Sleeper sleeper) {
        this.requestsPerMinute = requestsPerMinute;
        this.intervalNanos = requestsPerMinute > 0 ? (60_000_000_000L / requestsPerMinute) : 0L;
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "nanoTimeSupplier nesmí být null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper nesmí být null");
    }

    /**
     * Zablokuje volající vlákno na dobu nezbytnou pro dodržení povolené frekvence RPM.
     * Pokud je RPM <= 0, limitování je vypnuto a metoda se vrátí okamžitě.
     *
     * @throws InterruptedException pokud bylo vlákno přerušeno během čekání
     */
    public void acquire() throws InterruptedException {
        if (intervalNanos <= 0) {
            return;
        }

        long waitMillis = reserveWaitMillis();
        if (waitMillis > 0) {
            log.debug("RateLimiter: pozastavuji vlákno na {} ms pro dodržení limitu {} RPM.", waitMillis, requestsPerMinute);
            sleeper.sleep(waitMillis);
        }
    }

    private synchronized long reserveWaitMillis() {
        long now = nanoTimeSupplier.getAsLong();
        long targetTime;
        if (nextAvailableTimeNanos > now) {
            targetTime = nextAvailableTimeNanos;
            nextAvailableTimeNanos += intervalNanos;
        } else {
            targetTime = now;
            nextAvailableTimeNanos = now + intervalNanos;
        }
        long waitNanos = targetTime - now;
        return waitNanos > 0 ? (waitNanos + 999_999L) / 1_000_000L : 0L;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public long getIntervalNanos() {
        return intervalNanos;
    }
}
