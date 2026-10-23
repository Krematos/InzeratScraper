package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.RateLimiter;
import hanzner.inzeratscraper.service.Sleeper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void testRequestSpacingWithFakeClock() throws Exception {
        AtomicLong fakeNanos = new AtomicLong(0L);
        List<Long> sleepDurations = Collections.synchronizedList(new ArrayList<>());
        Sleeper sleeper = sleepDurations::add;

        // 10 RPM -> 60s / 10 = 6s rozestup = 6_000_000_000 ns (6000 ms)
        RateLimiter rateLimiter = new RateLimiter(10, fakeNanos::get, sleeper);

        // 1. volání v čase 0 ns -> žádné čekání
        rateLimiter.acquire();
        assertTrue(sleepDurations.isEmpty());

        // 2. volání o 1 sekundu později (1_000_000_000 ns) -> musí čekat 5000 ms
        fakeNanos.set(1_000_000_000L);
        rateLimiter.acquire();
        assertEquals(1, sleepDurations.size());
        assertEquals(5000L, sleepDurations.get(0));

        // 3. volání okamžitě v čase 1 sekunda -> další slot je v čase 12s -> musí čekat 11000 ms
        rateLimiter.acquire();
        assertEquals(2, sleepDurations.size());
        assertEquals(11000L, sleepDurations.get(1));
    }

    @Test
    void testRateLimiterDisabledWhenZeroOrNegativeRpm() throws Exception {
        List<Long> sleepDurations = new ArrayList<>();
        Sleeper sleeper = sleepDurations::add;

        RateLimiter zeroRpm = new RateLimiter(0, () -> 0L, sleeper);
        zeroRpm.acquire();
        zeroRpm.acquire();

        RateLimiter negRpm = new RateLimiter(-5, () -> 0L, sleeper);
        negRpm.acquire();

        assertTrue(sleepDurations.isEmpty(), "Při RPM <= 0 nesmí rate limiter nikdy uspat vlákno");
    }

    @Test
    void testRateLimiterWithClockConstructor() throws Exception {
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(100_000L), ZoneId.of("UTC"));
        List<Long> sleepDurations = new ArrayList<>();
        Sleeper sleeper = sleepDurations::add;

        RateLimiter limiter = new RateLimiter(30, fixedClock, sleeper);
        limiter.acquire(); // slot 100_000 -> 0 ms
        limiter.acquire(); // slot 102_000 (30 RPM = 2s rozestup) -> 2000 ms

        assertEquals(1, sleepDurations.size());
        assertEquals(2000L, sleepDurations.get(0));
    }

    @Test
    void testConcurrentAcquireReservesDistinctTimeSlots() throws Exception {
        AtomicLong fakeNanos = new AtomicLong(0L);
        List<Long> sleepDurations = Collections.synchronizedList(new ArrayList<>());
        Sleeper sleeper = sleepDurations::add;

        // 60 RPM = 1 požadavek za sekundu (1000 ms rozestup)
        RateLimiter limiter = new RateLimiter(60, fakeNanos::get, sleeper);

        int threads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        limiter.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
        }

        // 1. vlákno mělo 0 ms (nepřidáno do sleepDurations), zbylých 9 vláken čekalo 1000, 2000, ... 9000 ms
        assertEquals(9, sleepDurations.size());
        Collections.sort(sleepDurations);
        for (int i = 0; i < 9; i++) {
            long expected = (i + 1) * 1000L;
            assertEquals(expected, sleepDurations.get(i));
        }
    }
}
