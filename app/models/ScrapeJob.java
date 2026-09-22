package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;

/**
 * A crawl that runs as a background job rather than inside an agent's turn (JCLAW-1272). Its pages
 * are {@link ScrapeJobPage} rows, and their content is in the agent's workspace.
 */
@Entity
@Table(name = "scrape_job", indexes = {
        @Index(name = "idx_sj_state", columnList = "state"),
        @Index(name = "idx_sj_agent", columnList = "agent_id")
})
public class ScrapeJob extends Model {

    public enum State {
        PENDING, RUNNING,
        /** Stopped on request, keeping its place; resuming continues from the pages it has. */
        PAUSED,
        /** Left running by a stopped app too many times to be continued on its own; waits to be resumed. */
        INTERRUPTED,
        SUCCEEDED, FAILED, CANCELLED;

        /** Ended for good: nothing resumes it. */
        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }

        /** Queued or running, so a scheduler owns it. */
        public boolean active() {
            return this == PENDING || this == RUNNING;
        }
    }

    /** The job writes into this agent's workspace, so it goes with the agent. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    /** Told when the job ends. Deleting the chat keeps the job: the results are the agent's, not the chat's. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    public Conversation conversation;

    @Column(nullable = false, length = 2048)
    public String url;

    /** The {@code ScrapeJobRequest} it was accepted with, as JSON. */
    @Column(columnDefinition = "TEXT", nullable = false)
    public String options;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public State state = State.PENDING;

    /** Pages attempted, whatever their outcome. */
    @Column(name = "pages_read", nullable = false)
    public int pagesRead;

    /** Pages whose content was retrieved. */
    @Column(name = "pages_fetched", nullable = false)
    public int pagesFetched;

    @Column(name = "pages_discovered", nullable = false)
    public int pagesDiscovered;

    /** Whether the turn that started it was proven to come from the binding owner; the completion turn keeps that answer. */
    @Column(name = "owner_initiated", nullable = false)
    public boolean ownerInitiated;

    /** Time spent running, across every run of it; its time limit counts this, not time paused or down. */
    @Column(name = "runtime_millis", nullable = false)
    @ColumnDefault("0")
    public long runtimeMillis;

    /** Times a stopped app left it running; see {@code ScrapeJobService.MAX_INTERRUPTIONS}. */
    @Column(nullable = false)
    @ColumnDefault("0")
    public int interruptions;

    @Column(name = "stop_reason", length = 255)
    public String stopReason;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    /** What the crawl read, refused and left behind, in the words a crawl inside a turn uses. */
    @Column(columnDefinition = "TEXT")
    public String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = AppClock.now();
    }
}
