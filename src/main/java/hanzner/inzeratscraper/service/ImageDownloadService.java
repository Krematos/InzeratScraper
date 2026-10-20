package hanzner.inzeratscraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ImageDownloadService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bazos.cz/";

    private final HttpClient httpClient;
    private final Path baseImagesDir;

    public ImageDownloadService(
            @Value("${scraper.images.dir:./data/images}") String imagesDirPath) {
        this.baseImagesDir = Paths.get(imagesDirPath);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Stáhne obrázky pro daný externalId do lokální složky ./data/images/{externalId}/
     * s hlavičkami User-Agent a Referer. Chybějící obrázky (HTTP 404 apod.) přeskočí
     * a vrátí seznam úspěšně uložených cest.
     */
    public List<Path> downloadImagesForAd(String externalId, List<String> imageUrls) {
        if (externalId == null || externalId.isBlank() || imageUrls == null || imageUrls.isEmpty()) {
            return Collections.emptyList();
        }

        String safeFolderName = externalId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        Path adImageDir = baseImagesDir.resolve(safeFolderName);

        try {
            Files.createDirectories(adImageDir);
        } catch (IOException e) {
            log.error("Nelze vytvořit složku pro obrázky {}: {}", adImageDir, e.getMessage());
            return Collections.emptyList();
        }

        List<Path> savedPaths = new ArrayList<>();
        int index = 0;

        for (String imageUrl : imageUrls) {
            if (imageUrl == null || imageUrl.isBlank()) {
                continue;
            }
            index++;
            String fileName = String.format("img_%02d.jpg", index);
            Path targetFile = adImageDir.resolve(fileName);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status == 404) {
                    log.warn("Obrázek nenalezen (HTTP 404) pro inzerát {}, přeskakuji: {}", externalId, imageUrl);
                    continue;
                }

                if (status >= 200 && status < 300 && response.body() != null && response.body().length > 0) {
                    Files.write(targetFile, response.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    savedPaths.add(targetFile);
                    log.debug("Uložen obrázek {} ({} B)", targetFile, response.body().length);
                } else {
                    log.warn("Neúspěšné stažení obrázku {} pro inzerát {} (HTTP {})", imageUrl, externalId, status);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Stahování obrázků pro inzerát {} bylo přerušeno.", externalId);
                break;
            } catch (Exception e) {
                log.warn("Chyba při stahování obrázku {} pro inzerát {}: {}", imageUrl, externalId, e.getMessage());
            }
        }

        log.info("Pro inzerát {} staženo {}/{} obrázků do {}", externalId, savedPaths.size(), imageUrls.size(), adImageDir);
        return savedPaths;
    }
}
