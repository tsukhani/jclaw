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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import utils.AppClock;

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

    /** The agent that requested the video. Cascades: the job describes work done for this agent. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    /** The conversation the request came from. Cascades for the same reason as {@link #agent}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Conversation conversation;

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

    /**
     * Set by JCLAW-234 once the produced video is stored as a MessageAttachment.
     *
     * <p>A plain id, not a foreign key (JCLAW-984), unlike {@link #agent} and
     * {@link #conversation} beside it. This is the job's <em>result</em>, not its owner, and
     * {@code Message.attachments} is mapped {@code cascade = ALL, orphanRemoval = true} — so
     * an attachment is removed through Hibernate, not only by the database. A managed
     * association here fails the flush with {@code TransientPropertyValueException} whenever
     * a job is dirty in the same session as the attachment being removed, which no DDL rule
     * can prevent. The authoritative link is the reverse one,
     * {@link MessageAttachment#findByGenerationJobId}, and it cascades correctly.
     *
     * <p>A dangling id therefore behaves exactly as null does at the only reader
     * ({@code ApiVideogenController}, which null-guards the lookup) — the same trade
     * {@code Notification.sourceTaskId} makes.
     */
    @Column(name = "result_attachment_id")
    public Long resultAttachmentId;

    /**
     * Workspace-relative path the finished clip should also be written to, or null
     * (JCLAW-1057). Carried on the job because generation is asynchronous: the bytes
     * arrive minutes after the tool call returns, long after the agent's turn ended, so
     * the request has to outlive the turn that made it.
     *
     * <p>Validated for containment when the job is submitted, not when it completes — a
     * traversal attempt has to fail in front of the agent that made it, rather than
     * silently inside a background poller nobody is watching.
     */
    @Column(name = "save_to_path")
    public String saveToPath;

    // Deliberately NOT on TimestampedModel: that base stamps createdAt unconditionally,
    // which would stomp a caller-supplied value. The null guard below is the difference,
    // and it is load-bearing — a job can be constructed with its submission time already
    // set. Inheriting would mean overriding onCreate and calling super, which does not
    // help when the point is to NOT do what super does.
    @PrePersist
    void onCreate() {
        var now = AppClock.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = AppClock.now();
    }

    public static List<VideoGenerationJob> findRunning() {
        return VideoGenerationJob.find("state = ?1", State.RUNNING).fetch();
    }
}
