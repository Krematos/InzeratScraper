package hanzner.inzeratscraper;

import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.service.BazosScraperService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
public class BazosScraperServiceTest {
    @Autowired
    private BazosScraperService scraperService;

    @Test
    void testSingleAdScrape() throws Exception {
        String testUrl = "https://pc.bazos.cz/inzerat/194827164/tvuj-konkretni-inzerat.php";
        RawAdDto result = scraperService.scrapeSingleAdDetail(testUrl);

        log.info("ID: {}", result.externalId());
        log.info("Kategorie (subdoména): {}", result.subdomainCategory());
        log.info("Nadpis: {}", result.rawTitle());
        log.info("Cena: {}", result.rawPrice());
        log.info("Fotky ({}): {}", result.rawImages().size(), result.rawImages());
        log.info("Popis:\n{}", result.rawDescription());
    }
}
