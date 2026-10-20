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
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    private static final Pattern AD_ID_PATTERN = Pattern.compile("/inzerat/(\\d+)/");
    private static final Pattern AD_ID_FALLBACK_PATTERN = Pattern.compile("(\\d+)\\.htm");

    private final RawScrapedAdRepository rawScrapedAdRepository;

    public BazosScraperService(RawScrapedAdRepository rawScrapedAdRepository) {
        this.rawScrapedAdRepository = rawScrapedAdRepository;
    }

    /**
     * Stáhne všechny inzeráty konkrétního uživatele podle URL jeho profilu/hodnocení.
     * Příklad vstupní URL: "https://www.bazos.cz/hodnoceni.php?idmail=123456&idphone=789012&jmeno=Jan"
     */
    public List<RawAdDto> scrapeAllUserAds(String baseUserProfileUrl) {
        Set<String> allAdUrls = new LinkedHashSet<>();
        int offset = 0;
        int pageSize = 20;

        log.info("Začínám hledat odkazy na inzeráty z profilu: {}", baseUserProfileUrl);

        // 1. FÁZE: Posbírání všech URL adres jednotlivých inzerátů ze stránkování
        while (true) {
            String pageUrl = buildPaginatedUrl(baseUserProfileUrl, offset);
            log.info("Načítám stránku výpisu (offset {}): {}", offset, pageUrl);

            try {
                Document doc = fetchDocument(pageUrl);
                // Na Bazoši jsou nadpisy v seznamu uvnitř <div class="inzeraty inzeratyflex"> -> <h2 class="nadpis"><a href="...">
                Elements adLinks = doc.select("div.inzeraty h2.nadpis a[href*='/inzerat/'], span.nadpis a[href*='/inzerat/']");

                if (adLinks.isEmpty()) {
                    log.info("Na offsetu {} už nejsou žádné další inzeráty. Končím hledání.", offset);
                    break;
                }

                int newLinksOnPage = 0;
                for (Element link : adLinks) {
                    // abs:href automaticky doplní správnou subdoménu (např. https://pc.bazos.cz/inzerat/...)
                    String absUrl = link.attr("abs:href");
                    if (!absUrl.isBlank() && allAdUrls.add(absUrl)) {
                        newLinksOnPage++;
                    }
                }

                // Pojistka: Pokud Bazoš při přestřelení offsetu vrátí pořád tu samou poslední stránku, vyskočíme
                if (newLinksOnPage == 0) {
                    break;
                }

                offset += pageSize;
                sleepWithJitter(1000, 2000); // Krátká pauza mezi stránkami seznamu

            } catch (HttpStatusException e) {
                int status = e.getStatusCode();
                if (status == 403 || status == 429) {
                    log.warn("Rate-limit nebo blokace (HTTP {}) při načítání výpisu {}. Bezpečně ukončuji hledání dalších stránek.", status, pageUrl);
                } else {
                    log.error("HTTP chyba {} při načítání stránky seznamu {}: {}", status, pageUrl, e.getMessage());
                }
                break;
            } catch (Exception e) {
                log.error("Chyba při načítání stránky seznamu {}: {}", pageUrl, e.getMessage());
                break;
            }
        }

        log.info("Celkem nalezeno {} unikátních inzerátů. Začínám stahovat detaily...", allAdUrls.size());

        // 2. FÁZE: Stažení detailu každého inzerátu (s inkrementální kontrolou a okamžitým ukládáním do DB)
        List<RawAdDto> results = new ArrayList<>();
        int counter = 0;

        for (String adUrl : allAdUrls) {
            counter++;
            String externalId = "BAZOS_" + extractAdId(adUrl);

            if (rawScrapedAdRepository != null && rawScrapedAdRepository.existsByExternalId(externalId)) {
                log.info("[{}/{}] Inzerát {} již existuje ve staging DB, přeskakuji stahování: {}", counter, allAdUrls.size(), externalId, adUrl);
                continue;
            }

            try {
                log.info("[{}/{}] Stahuji detail inzerátu ({}): {}", counter, allAdUrls.size(), externalId, adUrl);
                RawAdDto dto = scrapeSingleAdDetail(adUrl);
                saveToStagingDb(dto);
                results.add(dto);

                // Pauza 1.5 až 3.5 vteřiny mezi detaily proti IP banu
                sleepWithJitter(1500, 3500);
            } catch (HttpStatusException e) {
                int status = e.getStatusCode();
                if (status == 404) {
                    log.warn("[{}/{}] Inzerát {} již byl smazán (HTTP 404), přeskakuji: {}", counter, allAdUrls.size(), externalId, adUrl);
                } else if (status == 403 || status == 429) {
                    log.warn("[{}/{}] Rate-limit nebo IP ban (HTTP {}) u inzerátu {}. Bezpečně ukončuji stahování, dosud stažené inzeráty ({}) zůstávají uložené.",
                            counter, allAdUrls.size(), status, adUrl, results.size());
                    break;
                } else {
                    log.error("[{}/{}] HTTP chyba {} u inzerátu {}: {}", counter, allAdUrls.size(), status, adUrl, e.getMessage());
                }
            } catch (Exception e) {
                log.error("Selhalo parsování inzerátu {}: {}", adUrl, e.getMessage());
            }
        }

        return results;
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
                            .scrapedAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
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
                .get();
    }

    /**
     * Jsoup metoda .text() standardně smaže všechny <br> a slije odstavce do jedné věty.
     * Tady převedeme <br> na \n a očistíme HTML tagy přes Safelist.none().
     */
    public String extractTextWithLineBreaks(Element element) {
        if (element == null) return "";
        String html = element.html();
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        String withNewLines = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n\n");
        return Jsoup.clean(withNewLines, "", Safelist.none(), outputSettings)
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .trim();
    }

    /**
     * Najde řádek v tabulce pod inzerátem, který obsahuje "Cena:", a vytáhne vedlejší buňku.
     * Obsahuje i fallbacky na span.cena nebo tučný text ceny.
     */
    public String extractPrice(Document doc) {
        // V novějším layoutu Bazoše: <tr><td>Cena:</td><td><b>1 500 Kč</b></td></tr>
        Element priceRow = doc.selectFirst("td.listadvlevo tr:contains(Cena:), table tr:contains(Cena:)");
        if (priceRow != null) {
            Elements tds = priceRow.select("td");
            if (tds.size() >= 2) {
                String text = tds.get(1).text().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
            Element boldPrice = priceRow.selectFirst("b, strong");
            if (boldPrice != null && !boldPrice.text().isBlank()) {
                return boldPrice.text().trim();
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
        Element locRow = doc.selectFirst("td.listadvlevo tr:contains(Lokalita:), table tr:contains(Lokalita:)");
        if (locRow != null) {
            // V prvním odkazu bývá PSČ, ve druhém město
            String linksText = locRow.select("a").text().trim();
            if (!linksText.isEmpty()) {
                return linksText;
            }
            Elements tds = locRow.select("td");
            if (tds.size() >= 2) {
                return tds.get(1).text().trim();
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
        return url.replaceAll("/img/(\\d+)t/", "/img/$1/");
    }

    public String extractAdId(String url) {
        if (url == null) return "";
        Matcher matcher = AD_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher fallbackMatcher = AD_ID_FALLBACK_PATTERN.matcher(url);
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1);
        }
        return String.valueOf(Math.abs(url.hashCode()));
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
        // Odstraníme případný existující parametr crz, ať ho nedublujeme
        String cleanUrl = baseUrl.replaceAll("[&?]crz=\\d+", "");
        if (offset == 0) {
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
