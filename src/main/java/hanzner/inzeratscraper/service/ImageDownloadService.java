package hanzner.inzeratscraper.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private static final long DEFAULT_MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final int DEFAULT_MAX_IMAGES_PER_AD = 20;

    private final HttpClient httpClient;
    private final Path baseImagesDir;
    private final long maxImageBytes;
    private final int maxImagesPerAd;

    public ImageDownloadService(String imagesDirPath) {
        this(imagesDirPath, DEFAULT_MAX_IMAGE_BYTES, DEFAULT_MAX_IMAGES_PER_AD);
    }

    @Autowired
    public ImageDownloadService(
            @Value("${scraper.images.dir:./data/images}") String imagesDirPath,
            @Value("${scraper.images.max-bytes:10485760}") long maxImageBytes,
            @Value("${scraper.images.max-per-ad:20}") int maxImagesPerAd) {
        this.baseImagesDir = Paths.get(imagesDirPath);
        this.maxImageBytes = maxImageBytes > 0 ? maxImageBytes : DEFAULT_MAX_IMAGE_BYTES;
        this.maxImagesPerAd = maxImagesPerAd > 0 ? maxImagesPerAd : DEFAULT_MAX_IMAGES_PER_AD;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Stáhne obrázky pro daný externalId do lokální složky ./data/images/{externalId}/
     * s hlavičkami User-Agent a Referer a s limitem velikosti souboru (ochrana proti OOM / honeypotu).
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
            if (index >= maxImagesPerAd) {
                log.warn("Dosažen limit počtu obrázků ({}) pro inzerát {}, další přeskakuji.", maxImagesPerAd, externalId);
                break;
            }
            index++;
            Path targetFile = adImageDir.resolve(String.format("img_%02d.jpg", index));

            if (!downloadSingleImageBounded(externalId, imageUrl, targetFile, savedPaths)) {
                break;
            }
        }

        log.info("Pro inzerát {} staženo {}/{} obrázků do {}", externalId, savedPaths.size(), imageUrls.size(), adImageDir);
        return savedPaths;
    }

    private boolean downloadSingleImageBounded(String externalId, String imageUrl, Path targetFile, List<Path> savedPaths) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            try (InputStream bodyStream = response.body()) {
                if (status == 404) {
                    log.warn("Obrázek nenalezen (HTTP 404) pro inzerát {}, přeskakuji: {}", externalId, imageUrl);
                    return true;
                }
                if (status < 200 || status >= 300 || bodyStream == null) {
                    log.warn("Neúspěšné stažení obrázku {} pro inzerát {} (HTTP {})", imageUrl, externalId, status);
                    return true;
                }

                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (contentLength > maxImageBytes) {
                    log.warn("Obrázek {} pro inzerát {} překračuje povolenou velikost ({} B > {} B), přeskakuji.",
                            imageUrl, externalId, contentLength, maxImageBytes);
                    return true;
                }

                if (writeStreamWithLimit(bodyStream, targetFile, maxImageBytes)) {
                    savedPaths.add(targetFile);
                } else {
                    Files.deleteIfExists(targetFile);
                    log.warn("Stahování obrázku {} pro inzerát {} přerušeno: překročen limit {} B.",
                            imageUrl, externalId, maxImageBytes);
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Stahování obrázků pro inzerát {} bylo přerušeno.", externalId);
            return false;
        } catch (Exception e) {
            log.warn("Chyba při stahování obrázku {} pro inzerát {}: {}", imageUrl, externalId, e.getMessage());
            return true;
        }
    }

    private boolean writeStreamWithLimit(InputStream in, Path targetFile, long maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long totalRead = 0;

        try (OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > maxBytes) {
                    return false;
                }
                out.write(buffer, 0, read);
            }
        }
        return totalRead > 0;
    }
}
