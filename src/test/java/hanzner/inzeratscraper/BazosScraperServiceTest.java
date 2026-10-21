package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.BazosScraperService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BazosScraperServiceTest {

    private BazosScraperService scraperService;

    @BeforeEach
    void setUp() {
        scraperService = new BazosScraperService(null);
    }

    @Test
    void testExtractAdIdFromStandardAndFallbackUrls() {
        String standardUrl = "https://pc.bazos.cz/inzerat/194827164/herni-notebook-lenovo.php";
        assertEquals("194827164", scraperService.extractAdId(standardUrl));

        String fallbackUrl = "https://www.bazos.cz/ostatni/194827164.htm";
        assertEquals("194827164", scraperService.extractAdId(fallbackUrl));

        // Fallback SHA-256 otisk ignoruje query parametry a nikdy nevrátí zápornou hodnotu
        String unusualUrl1 = "https://stroje.bazos.cz/detail/vrtacka-bosch?utm_source=test";
        String unusualUrl2 = "https://stroje.bazos.cz/detail/vrtacka-bosch?utm_source=other";
        String hash1 = scraperService.extractAdId(unusualUrl1);
        String hash2 = scraperService.extractAdId(unusualUrl2);
        assertEquals(hash1, hash2);
        assertFalse(hash1.startsWith("-"));
        assertEquals(16, hash1.length());
        assertNotEquals(hash1, scraperService.extractAdId("https://stroje.bazos.cz/detail/jiny-inzerat"));
    }

    @Test
    void testResolveNextOffsetFromDomAndFallback() {
        String htmlWithPagination = """
                <html>
                  <body>
                    <div class="strankovani">
                      <a href="hodnoceni.php?idmail=123&crz=15">2</a>
                      <a href="hodnoceni.php?idmail=123&crz=30">3</a>
                    </div>
                  </body>
                </html>
                """;
        Document docWithPagination = Jsoup.parse(htmlWithPagination, "https://www.bazos.cz/");
        assertEquals(15, scraperService.resolveNextOffset(docWithPagination, 0, 15));
        assertEquals(30, scraperService.resolveNextOffset(docWithPagination, 15, 15));

        Document docWithoutPagination = Jsoup.parse("<html><body></body></html>", "https://www.bazos.cz/");
        assertEquals(18, scraperService.resolveNextOffset(docWithoutPagination, 0, 18));
    }

    @Test
    void testHoneypotPageAndAdLimitsAreEnforced() {
        AtomicInteger fetchedPages = new AtomicInteger(0);

        // Nastavíme přísný limit: max 2 stránky, max 3 inzeráty
        BazosScraperService boundedScraper = new BazosScraperService(null, 2, 3, 1024 * 1024) {
            @Override
            protected Document fetchDocument(String url) {
                int page = fetchedPages.incrementAndGet();
                int baseId = page * 100;
                String html = """
                        <html>
                          <body>
                            <div class="inzeraty">
                              <h2 class="nadpis"><a href="https://stroje.bazos.cz/inzerat/%d/a.php">A</a></h2>
                              <h2 class="nadpis"><a href="https://stroje.bazos.cz/inzerat/%d/b.php">B</a></h2>
                            </div>
                            <div class="strankovani">
                              <a href="hodnoceni.php?idmail=1&crz=%d">Další</a>
                            </div>
                          </body>
                        </html>
                        """.formatted(baseId + 1, baseId + 2, page * 20);
                return Jsoup.parse(html, "https://www.bazos.cz/");
            }

            @Override
            public Set<String> collectAllAdUrls(String baseUserProfileUrl) {
                return super.collectAllAdUrls(baseUserProfileUrl);
            }
        };

        Set<String> urls = ((BazosScraperService) boundedScraper).scrapeAllUserAds("https://www.bazos.cz/hodnoceni.php?idmail=1")
                .stream()
                .map( hanzner.inzeratscraper.dto.RawAdDto::url)
                .collect(java.util.stream.Collectors.toSet());

        // I když stránkování generuje nekonečně mnoho dalších odkazů, scraper zastaví na maxAds = 3
        assertEquals(3, urls.size());
    }

    @Test
    void testBuildPaginatedUrlCleansExistingCrzParamSafely() {
        String urlWithLeadingCrz = "https://www.bazos.cz/hodnoceni.php?crz=20&idmail=5412977";
        assertEquals("https://www.bazos.cz/hodnoceni.php?idmail=5412977&crz=40",
                scraperService.buildPaginatedUrl(urlWithLeadingCrz, 40));
        assertEquals("https://www.bazos.cz/hodnoceni.php?idmail=5412977",
                scraperService.buildPaginatedUrl(urlWithLeadingCrz, 0));
    }

    @Test
    void testExtractSubdomain() {
        assertEquals("pc", scraperService.extractSubdomain("https://pc.bazos.cz/inzerat/194827164/notebook.php"));
        assertEquals("stroje", scraperService.extractSubdomain("https://stroje.bazos.cz/inzerat/123456/soustruh.php"));
        assertEquals("ostatni", scraperService.extractSubdomain("https://www.bazos.cz/inzerat/123456/neco.php"));
    }

    @Test
    void testConvertThumbnailUrlToFullSize() {
        String thumbnailUrl = "https://www.bazos.cz/img/1t/164/194827164.jpg";
        String expectedFullUrl = "https://www.bazos.cz/img/1/164/194827164.jpg";
        assertEquals(expectedFullUrl, scraperService.convertThumbnailUrlToFullSize(thumbnailUrl));

        String thumbnailWithQuery = "https://www.bazos.cz/img/2t/999/194827999.jpg?t=1710000000";
        assertEquals("https://www.bazos.cz/img/2/999/194827999.jpg?t=1710000000",
                scraperService.convertThumbnailUrlToFullSize(thumbnailWithQuery));
    }

    @Test
    void testDomExtractionForPriceLocationAndImages() {
        String html = """
                <html>
                  <body>
                    <h1 class="nadpisdetail">Prodám stolní vrtačku</h1>
                    <div class="popisdetail">První řádek<br>Druhý řádek</div>
                    <table>
                      <tr>
                        <td class="listadvlevo">
                          <table>
                            <tr><td>Cena:</td><td><b>3 200 Kč</b></td></tr>
                            <tr><td>Lokalita:</td><td><a href="#">110 00</a> <a href="#">Praha 1</a></td></tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                    <div class="carousel">
                      <a href="https://www.bazos.cz/img/1t/164/194827164.jpg">Foto 1</a>
                      <img class="carousel-cell-image" data-flickity-lazyload="https://www.bazos.cz/img/2t/164/194827164_2.jpg" />
                    </div>
                  </body>
                </html>
                """;

        Document doc = Jsoup.parse(html, "https://stroje.bazos.cz/");

        assertEquals("3 200 Kč", scraperService.extractPrice(doc));
        assertEquals("110 00 Praha 1", scraperService.extractLocation(doc));
        assertEquals("První řádek\nDruhý řádek", scraperService.extractTextWithLineBreaks(doc.selectFirst("div.popisdetail")));

        List<String> images = scraperService.extractFullSizeImages(doc);
        assertEquals(List.of(
                "https://www.bazos.cz/img/1/164/194827164.jpg",
                "https://www.bazos.cz/img/2/164/194827164_2.jpg"
        ), images);
    }
}
