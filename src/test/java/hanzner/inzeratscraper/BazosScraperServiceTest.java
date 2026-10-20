package hanzner.inzeratscraper;

import hanzner.inzeratscraper.service.BazosScraperService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
