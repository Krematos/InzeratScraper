package hanzner.inzeratscraper;

import com.sun.net.httpserver.HttpServer;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import hanzner.inzeratscraper.service.AdDataCleanerService;
import hanzner.inzeratscraper.service.AdTextSanitizer;
import hanzner.inzeratscraper.service.BazosScraperService;
import hanzner.inzeratscraper.service.ImageDownloadService;
import hanzner.inzeratscraper.service.LlmClient;
import hanzner.inzeratscraper.service.MigrationOrchestratorService;
import hanzner.inzeratscraper.service.ProductDataValidator;
import hanzner.inzeratscraper.service.RemoteEshopSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Smoke integrační test pro Fázi 6.4:
 * Zpracování 10 inzerátů s vestavěným HTTP serverem simulujícím LLM API
 * včetně odpovědi HTTP 429 s hlavičkou Retry-After.
 */
@ExtendWith(MockitoExtension.class)
class ConcurrencySmokeTest {

    @Mock
    private BazosScraperService bazosScraperService;

    @Mock
    private ImageDownloadService imageDownloadService;

    @Mock
    private RemoteEshopSyncService remoteEshopSyncService;

    @Mock
    private RawScrapedAdRepository rawScrapedAdRepository;

    private HttpServer server;
    private ObjectMapper objectMapper;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger rateLimit429Count = new AtomicInteger(0);
    private final ConcurrentHashMap<String, RawScrapedAd> savedAds = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        requestCount.set(0);
        rateLimit429Count.set(0);
        savedAds.clear();

        // Jednoduchý vestavěný HTTP server simulující Gemini API
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/models/gemini-3.8-flash:generateContent", exchange -> {
            int currentReq = requestCount.incrementAndGet();

            // Druhý příchozí požadavek vrátí HTTP 429 s hlavičkou Retry-After: 1
            if (currentReq == 2) {
                rateLimit429Count.incrementAndGet();
                exchange.getResponseHeaders().set("Retry-After", "1");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] errorBody = "{\"error\":{\"code\":429,\"message\":\"Resource exhausted\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, errorBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorBody);
                }
                return;
            }

            // Ostatní požadavky vrátí úspěšnou Gemini JSON odpověď
            String geminiJson = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "{\\"name\\":\\"Elektricka vrtacka Narex %d\\",\\"description\\":\\"Plne funkcni profi vrtacka Narex.\\",\\"price\\":1500.00,\\"priceNeedsReview\\":false,\\"category\\":\\"Nářadí\\",\\"condition\\":\\"USED\\",\\"parameters\\":{}}"
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """.formatted(currentReq);

            byte[] successBody = geminiJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, successBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(successBody);
            }
        });

        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Fáze 6.4: Smoke test 10 inzerátů s reálným LlmClientem a HTTP serverem včetně 429 s Retry-After")
    void testSmokeTenAdsWithSimulatedLlmAndRateLimiting() {
        int adCount = 10;
        int port = server.getAddress().getPort();
        String fakeLlmUrl = "http://127.0.0.1:" + port + "/models/gemini-3.8-flash:generateContent";

        LlmClient llmClient = new LlmClient(
                objectMapper,
                "test-api-key",
                fakeLlmUrl,
                HttpClient.newHttpClient(),
                3,       // max-concurrency
                600,     // RPM (dostatečně vysoké pro rychlý test)
                System::nanoTime,
                Thread::sleep
        );

        ProductDataValidator validator = new ProductDataValidator(List.of("Nářadí"), "STANDARD");
        AdDataCleanerService cleanerService = new AdDataCleanerService(
                objectMapper,
                new AdTextSanitizer(),
                llmClient,
                validator
        );

        List<RawScrapedAd> candidateList = Collections.synchronizedList(new ArrayList<>());
        for (int i = 1; i <= adCount; i++) {
            candidateList.add(RawScrapedAd.builder()
                    .id((long) i)
                    .externalId("smoke-ad-" + i)
                    .url("https://bazos.cz/inzerat/" + i)
                    .rawTitle("Vrtačka Narex " + i)
                    .rawDescription("Plně funkční vrtačka, dobrý stav.")
                    .rawPrice("1 500 Kč")
                    .status(AdProcessingStatus.SCRAPED)
                    .build());
        }

        when(rawScrapedAdRepository.findCandidateAdsForProcessing(any(), any()))
                .thenReturn(candidateList);
        when(rawScrapedAdRepository.claim(any(), any())).thenReturn(1);
        when(rawScrapedAdRepository.save(any(RawScrapedAd.class))).thenAnswer(inv -> {
            RawScrapedAd saved = inv.getArgument(0);
            savedAds.put(saved.getExternalId(), saved);
            return saved;
        });

        MigrationOrchestratorService orchestratorService = new MigrationOrchestratorService(
                bazosScraperService,
                imageDownloadService,
                cleanerService,
                remoteEshopSyncService,
                rawScrapedAdRepository,
                objectMapper,
                Executors.newVirtualThreadPerTaskExecutor()
        );

        int cleanedCount = orchestratorService.cleanStagedAds(adCount);

        // Ověření výsledků
        assertEquals(adCount, cleanedCount, "Všech 10 inzerátů mělo být úspěšně vyčištěno");
        assertEquals(adCount, savedAds.size(), "Všech 10 inzerátů mělo být uloženo do DB");

        // Ověření, že byla vrácena a ošetřena HTTP 429 odpověď s Retry-After
        assertTrue(rateLimit429Count.get() >= 1, "Musela proběhnout alespoň jedna 429 odpověď");
        assertTrue(requestCount.get() >= adCount + 1, "Celkový počet HTTP dotazů musí být >= 11 (10 úspěchů + 1 retry)");

        // Ověření stavu uložených inzerátů
        for (int i = 1; i <= adCount; i++) {
            RawScrapedAd ad = savedAds.get("smoke-ad-" + i);
            assertNotNull(ad, "Inzerát " + i + " musí být uložen");
            assertEquals(AdProcessingStatus.CLEANED, ad.getStatus());
            assertNotNull(ad.getCleanedPayloadJson());
            assertTrue(ad.getCleanedPayloadJson().contains("Narex"));
        }
    }
}
