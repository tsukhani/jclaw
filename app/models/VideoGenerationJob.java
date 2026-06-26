package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * One asynchronous video-generation request (JCLAW-230), tracked through its lifecycle by
 * {@code jobs.VideoGenerationJobRunner}. Unlike image generation (synchronous, bytes in hand), a video
 * job is submitted to a provider that returns a handle immediately and is polled to completion over
 * minutes.
 *
 * <p>Lifecycle: created {@code PENDING} by {@code services.videogen.VideoGenerationJobService#submit};
 * transitioned to {@code RUNNING} once the provider returns a {@link #providerJobId}; then
 * {@code RUNNING → SUCCEEDED} or {@code FAILED} by the runner's poll loop (or {@code → FAILED} by the
 * timeout path once it has run longer than {@code videogen.maxJobMinutes}). {@link #resultAttachmentId}
 * is filled later by the storage story (JCLAW-234) when a succeeded job's video is fetched into a
 * {@code MessageAttachment}.
 *
 * <p>Schema is managed by Hibernate auto-DDL ({@code jpa.ddl=update}). The original columns shipped with
 * the brand-new table, so none needed a {@code @ColumnDefault} (no populated-table {@code ALTER}). The
 * later {@link #percent} column (JCLAW-232) IS an {@code ALTER} on a populated table, but it is nullable,
 * so it still needs no default. No migration file either way.
 */
@Entity
@Table(name = "video_generation_job", indexes = {
        @Index(name = "idx_vgj_state", columnList = "state"),
        @Index(name = "idx_vgj_conversation", columnList = "conversation_id")
})
public class VideoGenerationJob extends Model {

    public enum State { PENDING, RUNNING, SUCCEEDED, FAILED }

    @Column(name = "agent_id")
    public Long agentId;

    @Column(name = "conversation_id")
    public Long conversationId;

    @Column(columnDefinition = "TEXT")
    public String prompt;

    /** Provider-specific generation params as JSON (duration, aspect ratio, …); nullable. */
    @Column(columnDefinition = "TEXT")
    public String params;

    /** Provider key the job was submitted with (e.g. {@code replicate}); polled via this, not the live setting. */
    @Column(nullable = false)
    public String provider;

    /** The provider's own job handle — null until {@code submit} returns it. */
    @Column(name = "provider_job_id")
    public String providerJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public State state = State.PENDING;

    /** Best-effort progress 0..100 while RUNNING — a real per-step number for the local sidecar
     *  (JCLAW-232, via the diffusion step callback), {@code null} for cloud providers which report none
     *  (SV-1). Nullable, so adding it as an {@code ALTER} on the now-populated table needs no
     *  {@code @ColumnDefault}. */
    @Column(name = "percent")
    public Integer percent;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    /** Set by JCLAW-234 once the produced video is stored as a MessageAttachment. */
    @Column(name = "result_attachment_id")
    public Long resultAttachmentId;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static List<VideoGenerationJob> findRunning() {
        return VideoGenerationJob.find("state = ?1", State.RUNNING).fetch();
    }
}
