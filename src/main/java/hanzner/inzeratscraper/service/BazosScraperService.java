package hanzner.inzeratscraper.service;

import hanzner.inzeratscraper.dto.RawAdDto;
import hanzner.inzeratscraper.entity.AdProcessingStatus;
import hanzner.inzeratscraper.entity.RawScrapedAd;
import hanzner.inzeratscraper.repository.RawScrapedAdRepository;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BazosScraperService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int DEFAULT_MAX_PAGES = 50;
    private static final int DEFAULT_MAX_ADS = 500;
    private static final int DEFAULT_MAX_BODY_SIZE_BYTES = 2 * 1024 * 1024; // 2 MB

    private static final Pattern AD_ID_PATTERN = Pattern.compile("/inzerat/(\\d++)/");
    private static final Pattern AD_ID_FALLBACK_PATTERN = Pattern.compile("(?<!\\d)(\\d++)\\.htm");
    private static final Pattern CRZ_OFFSET_PATTERN = Pattern.compile("[?&]crz=(\\d++)");

    private final RawScrapedAdRepository rawScrapedAdRepository;
    private final int maxPages;
    private final int maxAds;
    private final int maxBodySizeBytes;

    public BazosScraperService(RawScrapedAdRepository rawScrapedAdRepository) {
        this(rawScrapedAdRepository, DEFAULT_MAX_PAGES, DEFAULT_MAX_ADS, DEFAULT_MAX_BODY_SIZE_BYTES);
    }

    @Autowired
    public BazosScraperService(
            RawScrapedAdRepository rawScrapedAdRepository,
            @Value("${scraper.max-pages:50}") int maxPages,
            @Value("${scraper.max-ads:500}") int maxAds,
            @Value("${scraper.max-body-size-bytes:2097152}") int maxBodySizeBytes) {
        this.rawScrapedAdRepository = rawScrapedAdRepository;
        this.maxPages = maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES;
        this.maxAds = maxAds > 0 ? maxAds : DEFAULT_MAX_ADS;
        this.maxBodySizeBytes = maxBodySizeBytes > 0 ? maxBodySizeBytes : DEFAULT_MAX_BODY_SIZE_BYTES;
    }

    /**
     * Stáhne všechny inzeráty konkrétního uživatele podle URL jeho profilu/hodnocení.
     * Příklad vstupní URL: "https://www.bazos.cz/hodnoceni.php?idmail=123456&idphone=789012&jmeno=Jan"
     */
    public List<RawAdDto> scrapeAllUserAds(String baseUserProfileUrl) {
        log.info("Začínám hledat odkazy na inzeráty z profilu: {} (maxPages={}, maxAds={}, maxBodySize={} B)",
                baseUserProfileUrl, maxPages, maxAds, maxBodySizeBytes);

        Set<String> allAdUrls = collectAllAdUrls(baseUserProfileUrl);
        log.info("Celkem nalezeno {} unikátních inzerátů. Začínám stahovat detaily...", allAdUrls.size());

        return scrapeDetailsForUrls(allAdUrls);
    }

    protected Set<String> collectAllAdUrls(String baseUserProfileUrl) {
        Set<String> allAdUrls = new LinkedHashSet<>();
        int offset = 0;
        int pagesFetched = 0;

        while (pagesFetched < maxPages && allAdUrls.size() < maxAds) {
            pagesFetched++;
            int nextOffset = fetchNextListingPage(baseUserProfileUrl, offset, allAdUrls);
            if (nextOffset <= offset || allAdUrls.size() >= maxAds) {
                break;
            }
            if (pagesFetched >= maxPages) {
                log.warn("Dosažen bezpečnostní limit počtu stránek výpisu (maxPages={}). Ukončuji procházení dalších stránek.", maxPages);
                break;
            }
            offset = nextOffset;
            sleepWithJitter(1000, 2000);
        }
        return allAdUrls;
    }

    private int fetchNextListingPage(String baseUserProfileUrl, int offset, Set<String> allAdUrls) {
        String pageUrl = buildPaginatedUrl(baseUserProfileUrl, offset);
        log.info("Načítám stránku výpisu (offset {}): {}", offset, pageUrl);

        try {
            Document doc = fetchDocument(pageUrl);
            Elements adLinks = doc.select("div.inzeraty h2.nadpis a[href*='/inzerat/'], span.nadpis a[href*='/inzerat/']");
            if (adLinks.isEmpty()) {
                log.info("Na offsetu {} už nejsou žádné další inzeráty. Končím hledání.", offset);
                return -1;
            }
            int newlyAdded = addNewLinksFromPage(adLinks, allAdUrls);
            if (newlyAdded == 0) {
                return -1;
            }
            return resolveNextOffset(doc, offset, adLinks.size());
        } catch (HttpStatusException e) {
            logListingHttpError(e, pageUrl);
            return -1;
        } catch (Exception e) {
            log.error("Chyba při načítání stránky seznamu {}: {}", pageUrl, e.getMessage());
            return -1;
        }
    }

    /**
     * Zjistí offset další stránky přímo ze stránkovacích odkazů v DOMu Bazoše (div.strankovani a[href*='crz=']).
     * Pokud stránkování v DOMu není přítomné, použije skutečný počet nalezených inzerátů na aktuální stránce.
     */
    public int resolveNextOffset(Document doc, int currentOffset, int linksFoundOnPage) {
        Elements paginationLinks = doc.select("div.strankovani a[href*='crz='], a[href*='crz=']");
        int smallestGreaterOffset = Integer.MAX_VALUE;

        for (Element pageLink : paginationLinks) {
            Matcher matcher = CRZ_OFFSET_PATTERN.matcher(pageLink.attr("href"));
            if (matcher.find()) {
                int candidate = Integer.parseInt(matcher.group(1));
                if (candidate > currentOffset && candidate < smallestGreaterOffset) {
                    smallestGreaterOffset = candidate;
                }
            }
        }

        if (smallestGreaterOffset != Integer.MAX_VALUE) {
            return smallestGreaterOffset;
        }
        return linksFoundOnPage > 0 ? currentOffset + linksFoundOnPage : -1;
    }

    private int addNewLinksFromPage(Elements adLinks, Set<String> allAdUrls) {
        int newLinksOnPage = 0;
        for (Element link : adLinks) {
            if (allAdUrls.size() >= maxAds) {
                log.warn("Dosažen bezpečnostní limit počtu inzerátů (maxAds={}). Ignoruji další odkazy.", maxAds);
                break;
            }
            String absUrl = link.attr("abs:href");
            if (!absUrl.isBlank() && allAdUrls.add(absUrl)) {
                newLinksOnPage++;
            }
        }
        return newLinksOnPage;
    }

    private void logListingHttpError(HttpStatusException e, String pageUrl) {
        int status = e.getStatusCode();
        if (status == 403 || status == 429) {
            log.warn("Rate-limit nebo blokace (HTTP {}) při načítání výpisu {}. Bezpečně ukončuji hledání dalších stránek.", status, pageUrl);
        } else {
            log.error("HTTP chyba {} při načítání stránky seznamu {}: {}", status, pageUrl, e.getMessage());
        }
    }

    private List<RawAdDto> scrapeDetailsForUrls(Set<String> allAdUrls) {
        List<RawAdDto> results = new ArrayList<>();
        int counter = 0;
        int total = allAdUrls.size();

        for (String adUrl : allAdUrls) {
            counter++;
            String externalId = "BAZOS_" + extractAdId(adUrl);

            if (isAlreadyScraped(externalId)) {
                log.info("[{}/{}] Inzerát {} již existuje ve staging DB, přeskakuji stahování: {}", counter, total, externalId, adUrl);
                continue;
            }

            if (!scrapeAndSaveSingleAd(adUrl, externalId, counter, total, results)) {
                break;
            }
        }
        return results;
    }

    private boolean isAlreadyScraped(String externalId) {
        return rawScrapedAdRepository != null && rawScrapedAdRepository.existsByExternalId(externalId);
    }

    private boolean scrapeAndSaveSingleAd(String adUrl, String externalId, int counter, int total, List<RawAdDto> results) {
        try {
            log.info("[{}/{}] Stahuji detail inzerátu ({}): {}", counter, total, externalId, adUrl);
            RawAdDto dto = scrapeSingleAdDetail(adUrl);
            saveToStagingDb(dto);
            results.add(dto);
            sleepWithJitter(1500, 3500);
            return true;
        } catch (HttpStatusException e) {
            return !shouldAbortOnDetailHttpError(e, adUrl, externalId, counter, total, results.size());
        } catch (Exception e) {
            log.error("Selhalo parsování inzerátu {}: {}", adUrl, e.getMessage());
            return true;
        }
    }

    private boolean shouldAbortOnDetailHttpError(HttpStatusException e, String adUrl, String externalId, int counter, int total, int savedCount) {
        int status = e.getStatusCode();
        return switch (status) {
            case 404 -> {
                log.warn("[{}/{}] Inzerát {} již byl smazán (HTTP 404), přeskakuji: {}", counter, total, externalId, adUrl);
                yield false;
            }
            case 403, 429 -> {
                log.warn("[{}/{}] Rate-limit nebo IP ban (HTTP {}) u inzerátu {}. Bezpečně ukončuji stahování, dosud stažené inzeráty ({}) zůstávají uložené.",
                        counter, total, status, adUrl, savedCount);
                yield true;
            }
            default -> {
                log.error("[{}/{}] HTTP chyba {} u inzerátu {}: {}", counter, total, status, adUrl, e.getMessage());
                yield false;
            }
        };
    }

    /**
     * Vytáhne kompletní data z jednoho konkrétního inzerátu.
     */
    public RawAdDto scrapeSingleAdDetail(String adUrl) throws IOException {
        Document doc = fetchDocument(adUrl);

        String externalId = "BAZOS_" + extractAdId(adUrl);
        String subdomain = extractSubdomain(adUrl);

        // 1. Nadpis: <h1 class="nadpisdetail">
        String title = doc.select("h1.nadpisdetail").text().trim();

        // 2. Popis: <div class="popisdetail"> (musíme zachovat odřádkování <br>!)
        Element descElement = doc.selectFirst("div.popisdetail");
        String description = extractTextWithLineBreaks(descElement);

        // 3. Cena a Lokalita: jsou v tabulce <td class="listadvlevo"> pod popisem
        String price = extractPrice(doc);
        String location = extractLocation(doc);

        // 4. Obrázky v plném rozlišení
        List<String> images = extractFullSizeImages(doc);

        return new RawAdDto(
                externalId,
                adUrl,
                subdomain,
                title,
                description,
                price,
                location,
                images
        );
    }

    /**
     * Uloží nově stažený inzerát do lokální staging databáze se stavem SCRAPED.
     */
    public RawScrapedAd saveToStagingDb(RawAdDto dto) {
        if (rawScrapedAdRepository == null) {
            return null;
        }
        return rawScrapedAdRepository.findByExternalId(dto.externalId())
                .orElseGet(() -> {
                    RawScrapedAd entity = RawScrapedAd.builder()
                            .externalId(dto.externalId())
                            .url(dto.url())
                            .subdomainCategory(dto.subdomainCategory())
                            .rawTitle(dto.rawTitle())
                            .rawDescription(dto.rawDescription())
                            .rawPrice(dto.rawPrice())
                            .rawLocation(dto.rawLocation())
                            .rawImages(dto.rawImages() != null ? new ArrayList<>(dto.rawImages()) : new ArrayList<>())
                            .status(AdProcessingStatus.SCRAPED)
                            .priceNeedsReview(false)
                            .scrapedAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    return rawScrapedAdRepository.save(entity);
                });
    }

    // =========================================================================
    // POMOCNÉ PARSOVACÍ METODY PRO DOM BAZOŠE
    // =========================================================================

    protected Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "cs-CZ,cs;q=0.9")
                .referrer("https://www.bazos.cz/")
                .timeout(15_000)
                .maxBodySize(maxBodySizeBytes)
                .get();
    }

    /**
     * Jsoup metoda .text() standardně smaže všechny <br> a slije odstavce do jedné věty.
     * Tady převedeme <br> na \n a očistíme HTML tagy.
     */
    public String extractTextWithLineBreaks(Element element) {
        if (element == null) return "";
        Element clone = element.clone();
        clone.select("br").after("\\n");
        clone.select("p").after("\\n\\n");
        return clone.text()
                .replace("\\n ", "\n")
                .replace(" \\n", "\n")
                .replace("\\n", "\n")
                .replace("&nbsp;", " ")
                .trim();
    }

    /**
     * Najde řádek v tabulce pod inzerátem, který obsahuje "Cena:", a vytáhne vedlejší buňku.
     * Obsahuje i fallbacky na span.cena nebo tučný text ceny.
     */
    public String extractPrice(Document doc) {
        // V novějším layoutu Bazoše: <tr><td>Cena:</td><td><b>1 500 Kč</b></td></tr>
        Element labelTd = doc.selectFirst("td.listadvlevo td:matchesOwn((?iu)^\\s*Cena:\\s*$), table td:matchesOwn((?iu)^\\s*Cena:\\s*$)");
        if (labelTd != null) {
            Element valueTd = labelTd.nextElementSibling();
            if (valueTd != null && !valueTd.text().isBlank()) {
                return valueTd.text().trim();
            }
        }
        // Fallback na span.cena
        Element spanCena = doc.selectFirst("span.cena, div.inzeratycena b");
        if (spanCena != null && !spanCena.text().isBlank()) {
            return spanCena.text().trim();
        }
        return "Neuvedeno";
    }

    /**
     * Najde PSČ a město v tabulce pod inzerátem (řádek "Lokalita:").
     */
    public String extractLocation(Document doc) {
        Element labelTd = doc.selectFirst("td.listadvlevo td:matchesOwn((?iu)^\\s*Lokalita:\\s*$), table td:matchesOwn((?iu)^\\s*Lokalita:\\s*$)");
        if (labelTd != null) {
            Element parentRow = labelTd.parent();
            if (parentRow != null) {
                String linksText = parentRow.select("a").text().trim();
                if (!linksText.isEmpty()) {
                    return linksText;
                }
            }
            Element valueTd = labelTd.nextElementSibling();
            if (valueTd != null && !valueTd.text().isBlank()) {
                return valueTd.text().trim();
            }
        }
        return "";
    }

    /**
     * Vytáhne všechny fotky z detailu inzerátu v plném rozlišení.
     */
    public List<String> extractFullSizeImages(Document doc) {
        Set<String> imageUrls = new LinkedHashSet<>();

        // Varianta 1: Odkazy <a> obalující náhledy v galerii (Flickity karusel nebo seznam fotek)
        Elements galleryLinks = doc.select("div.carousel a[href*='/img/'], a.GFlink[href*='/img/'], div.fli-bal a[href*='/img/']");
        for (Element link : galleryLinks) {
            String href = link.attr("abs:href");
            if (!href.isBlank()) {
                imageUrls.add(convertThumbnailUrlToFullSize(href));
            }
        }

        // Varianta 2: Obrázky přímo v <img> uvnitř karuselu (často mají plnou URL v data-flickity-lazyload nebo src)
        Elements carouselImgs = doc.select("img.carousel-cell-image, div.inzeratydetail img[src*='/img/']");
        for (Element img : carouselImgs) {
            String src = img.hasAttr("data-flickity-lazyload")
                    ? img.attr("abs:data-flickity-lazyload")
                    : img.attr("abs:src");
            if (!src.isBlank() && src.contains("/img/")) {
                imageUrls.add(convertThumbnailUrlToFullSize(src));
            }
        }

        return new ArrayList<>(imageUrls);
    }

    /**
     * Bazoš má miniatury na URL typu: https://www.bazos.cz/img/1t/164/194827164.jpg
     * Plná velikost je na:            https://www.bazos.cz/img/1/164/194827164.jpg
     * Tato metoda odstraní písmeno 't' za číslem serveru.
     */
    public String convertThumbnailUrlToFullSize(String url) {
        if (url == null) return "";
        // Odstraní 't' v cestě /img/1t/ -> /img/1/
        return url.replaceAll("/img/(\\d++)t/", "/img/$1/");
    }

    /**
     * Extrahuje číselné ID inzerátu z URL Bazoše.
     * Jako fallback (pro neočekávaný tvar URL) vytvoří deterministický 16znakový SHA-256 otisk z kanonické cesty URL
     * (bez query parametrů a fragmentu), čímž zabrání kolizím 32bitového hashCode() i přetečení Math.abs(Integer.MIN_VALUE).
     */
    public String extractAdId(String url) {
        if (url == null || url.isBlank()) return "";
        Matcher matcher = AD_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher fallbackMatcher = AD_ID_FALLBACK_PATTERN.matcher(url);
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1);
        }
        return computeStableUrlHash(url);
    }

    private String computeStableUrlHash(String url) {
        String canonicalUrl = url.trim();
        try {
            URI uri = URI.create(canonicalUrl);
            if (uri.getHost() != null && uri.getPath() != null) {
                canonicalUrl = uri.getHost().toLowerCase() + uri.getPath();
            }
        } catch (IllegalArgumentException ignored) {
            int queryIdx = canonicalUrl.indexOf('?');
            if (queryIdx > 0) {
                canonicalUrl = canonicalUrl.substring(0, queryIdx);
            }
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalUrl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Long.toUnsignedString(canonicalUrl.hashCode());
        }
    }

    public String extractSubdomain(String url) {
        try {
            String host = URI.create(url).getHost(); // např. "pc.bazos.cz"
            if (host != null && host.endsWith(".bazos.cz")) {
                String sub = host.replace(".bazos.cz", "");
                return sub.equals("www") ? "ostatni" : sub;
            }
        } catch (Exception ignored) {}
        return "ostatni";
    }

    public String buildPaginatedUrl(String baseUrl, int offset) {
        // Odstraníme případný existující parametr crz (včetně navazujícího & na začátku query stringu)
        String cleanUrl = baseUrl
                .replaceAll("\\?crz=\\d++&", "?")
                .replaceAll("[&?]crz=\\d++", "");
        if (offset <= 0) {
            return cleanUrl;
        }
        String separator = cleanUrl.contains("?") ? "&" : "?";
        return cleanUrl + separator + "crz=" + offset;
    }

    private void sleepWithJitter(int minMs, int maxMs) {
        try {
            int sleepTime = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
