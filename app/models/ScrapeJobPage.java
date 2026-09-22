package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;

import java.time.Instant;

/** One page a {@link ScrapeJob} attempted, in the order it was read (JCLAW-1272). */
@Entity
@Table(name = "scrape_job_page", indexes = @Index(name = "idx_sjp_job_index", columnList = "job_id, page_index"))
public class ScrapeJobPage extends Model {

    public enum Outcome {
        FETCHED,
        /** The site or its protection refused us. */
        BLOCKED,
        /** Nothing refused us, but there was nothing to read: a timeout, a 404, an error of ours. */
        FAILED;

        public static Outcome of(ScrapeReason reason) {
            return switch (reason) {
                case OK -> FETCHED;
                case TIMEOUT, NOT_FOUND, ERROR, THIN_CONTENT -> FAILED;
                default -> BLOCKED;
            };
        }
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public ScrapeJob job;

    /** 1-based position in the order the crawl read the pages; the polling cursor. */
    @Column(name = "page_index", nullable = false)
    public int pageIndex;

    /** Where the page ended up after redirects. */
    @Column(nullable = false, length = 2048)
    public String url;

    /** The URL the crawl queued, which is how a resumed crawl recognises the page. */
    @Column(name = "requested_url", length = 2048)
    public String requestedUrl;

    /** The page's {@code PageHarvest} as JSON, kept while the job can still resume and cleared when it ends. */
    @Column(columnDefinition = "TEXT")
    public String harvest;

    @Column(name = "crawl_depth", nullable = false)
    public int crawlDepth;

    @Enumerated(EnumType.STRING)
    @Column(name = "served_by", nullable = false, length = 16)
    public ScrapeRung servedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Outcome outcome;

    /** The classifier's reason, a fixed vocabulary; null when the page was fetched. */
    @Column(length = 32)
    public String reason;

    @Column(nullable = false)
    public int chars;

    /** Workspace-relative path of the page's content; null when nothing was retrieved. */
    @Column(length = 255)
    public String file;

    @Column(name = "fetched_at", nullable = false)
    public Instant fetchedAt;
}
