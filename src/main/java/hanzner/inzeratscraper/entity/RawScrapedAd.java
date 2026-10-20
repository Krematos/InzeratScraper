package hanzner.inzeratscraper.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "raw_scraped_ads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RawScrapedAd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String externalId;

    @Column(length = 1024)
    private String url;

    @Column(length = 128)
    private String subdomainCategory;

    @Column(length = 512)
    private String rawTitle;

    @Column(columnDefinition = "TEXT")
    private String rawDescription;

    @Column(length = 128)
    private String rawPrice;

    @Column(length = 256)
    private String rawLocation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "raw_scraped_ad_images", joinColumns = @JoinColumn(name = "ad_id"))
    @Column(name = "image_url", length = 1024)
    @Builder.Default
    private List<String> rawImages = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AdProcessingStatus status;

    @Column(columnDefinition = "TEXT")
    private String cleanedPayloadJson;

    private boolean priceNeedsReview;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Instant scrapedAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.scrapedAt == null) {
            this.scrapedAt = Instant.now();
        }
        this.updatedAt = now;
        if (this.status == null) {
            this.status = AdProcessingStatus.SCRAPED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
