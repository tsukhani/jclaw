package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * Persisted record of a file attached to a user {@link Message}. Backs the
 * vision/multimodal flow (JCLAW-25): the upload endpoint stages bytes on
 * disk under {@code workspace/{agent.name}/attachments/{conversation_id}/{uuid}.{ext}}
 * and writes one row here per file; the send path fetches rows for the
 * outgoing message, reads bytes from {@link #storagePath}, and threads
 * image attachments into the provider payload as OpenAI-style content parts.
 */
@Entity
@Table(name = "chat_message_attachment", indexes = {
        @Index(name = "idx_attachment_message", columnList = "message_id"),
        @Index(name = "idx_attachment_uuid", columnList = "uuid", unique = true)
})
// JCLAW-205 follow-up: read on attachment download (findByUuid) and
// every chat-history render that contains attachments (findByMessage).
// Rows are immutable after create (operator can't edit an attachment;
// they're recreated by re-upload), so the cache hit rate approaches
// 100% once warm. The transcript column can be sizeable for audio
// attachments but is still bounded; no @Lob/blob bytes live in the
// row itself (storagePath points at the on-disk file).
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class MessageAttachment extends Model {

    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_AUDIO = "AUDIO";
    public static final String KIND_FILE = "FILE";

    @ManyToOne(optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    public Message message;

    /** Client-facing opaque identifier; never the DB primary key. */
    @Column(nullable = false, unique = true, length = 36)
    public String uuid;

    @Column(name = "original_filename", nullable = false)
    public String originalFilename;

    /** Workspace-relative path, e.g. {@code main/attachments/42/<uuid>.png}. */
    @Column(name = "storage_path", nullable = false)
    public String storagePath;

    @Column(name = "mime_type", nullable = false)
    public String mimeType;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    /** One of {@link #KIND_IMAGE} or {@link #KIND_FILE}. */
    @Column(nullable = false, length = 16)
    public String kind;

    /** Whisper transcript for audio attachments; nullable until JCLAW-165 wires the writer. */
    @Column(columnDefinition = "TEXT")
    public String transcript;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isImage() {
        return KIND_IMAGE.equals(kind);
    }

    public boolean isAudio() {
        return KIND_AUDIO.equals(kind);
    }

    /** Classify a MIME string into one of the three persisted kinds. */
    public static String kindForMime(String mime) {
        if (mime == null) return KIND_FILE;
        if (mime.startsWith("image/")) return KIND_IMAGE;
        if (mime.startsWith("audio/")) return KIND_AUDIO;
        return KIND_FILE;
    }

    public static MessageAttachment findByUuid(String uuid) {
        if (uuid == null) return null;
        return MessageAttachment.find("uuid", uuid).first();
    }

    public static List<MessageAttachment> findByMessage(Message message) {
        return MessageAttachment.find("message = ?1 ORDER BY id ASC", message).fetch();
    }
}
