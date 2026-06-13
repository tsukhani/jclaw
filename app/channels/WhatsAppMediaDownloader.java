package channels;

import it.auties.whatsapp.model.info.ChatMessageInfo;
import models.WhatsAppBinding;
import services.AgentService;
import services.AttachmentService;
import services.EventLogger;
import utils.Filenames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Downloads inbound WhatsApp media into the agent's staging dir, returning
 * {@link AttachmentService.Input}s the runner finalizes — the same shape Slack /
 * Telegram inbound produce, so the downstream agent path is unchanged.
 *
 * <p>One class, branched only at the byte-fetch step: the Cloud-API path does the
 * Graph media two-step ({@code GET /{mediaId}} → CDN URL → bytes, SSRF-guarded)
 * and the WhatsApp-Web path pulls bytes from the live Cobalt session. Each
 * transport fills its own private method (disjoint hunks → conflict-free merge of
 * the two tracks); the shared {@link #downloadAll} dispatch + the
 * {@link AttachmentService.Input} contract live here so nothing transport-specific
 * escapes.
 */
public final class WhatsAppMediaDownloader {

    private WhatsAppMediaDownloader() {}

    /** Per-message inbound media cap, matching Slack/Telegram. */
    static final int MAX_INBOUND_FILES = 8;

    /**
     * Stage every media part on {@code msg} for {@code binding}'s transport.
     * Empty when the message carries no media.
     */
    public static List<AttachmentService.Input> downloadAll(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        if (msg.media() == null || msg.media().isEmpty()) {
            return List.of();
        }
        return switch (binding.transport) {
            case CLOUD_API -> downloadCloudApi(binding, msg, agentName);
            case WHATSAPP_WEB -> downloadCobalt(binding, msg, agentName);
        };
    }

    // Cloud-API Graph media download — implemented in JCLAW-446 (Track A).
    @SuppressWarnings("java:S1172") // params are the contract Track A fills in
    private static List<AttachmentService.Input> downloadCloudApi(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        return List.of();
    }

    /** WhatsApp-Web (Cobalt) per-file download cap — 20 MiB, matching Telegram. */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** Bound on a single {@code downloadMedia} call before giving up on a part. */
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 90;

    /** Minimal MIME→extension fallback for staging leaves when the media carries
     *  no filename (images/audio/video/stickers). Cosmetic only — the final MIME
     *  and kind are re-sniffed from disk by {@link AttachmentService#finalizeAttachment}. */
    private static final Map<String, String> MIME_EXT = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp",
            "video/mp4", ".mp4",
            "audio/ogg", ".ogg",
            "audio/mpeg", ".mp3",
            "application/pdf", ".pdf");

    /**
     * WhatsApp-Web (Cobalt) media download (JCLAW-450). Each {@code PendingMedia}'s
     * {@code mediaId} is the inbound message id; the live
     * {@link WhatsAppCobaltSession} (resolved by binding id from the runner) holds
     * the matching {@link ChatMessageInfo}, which Cobalt's
     * {@code Whatsapp.downloadMedia} decrypts to bytes. Bytes are staged under the
     * agent's {@code attachments/staging/<uuid>} leaf — the exact same shape the
     * Cloud-API and Telegram paths produce — so the downstream multimodal assembly
     * path is unchanged. Oversized or failed parts are skipped (logged), never
     * thrown.
     */
    private static List<AttachmentService.Input> downloadCobalt(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        var session = WhatsAppCobaltRunner.session(binding.id);
        if (session == null || session.whatsapp() == null) {
            EventLogger.warn("channel", agentName, "whatsapp",
                    "No live WhatsApp-Web session for binding %s; skipping media".formatted(binding.id));
            return List.of();
        }

        var staged = new ArrayList<AttachmentService.Input>();
        var parts = msg.media();
        int limit = Math.min(parts.size(), MAX_INBOUND_FILES);
        for (int i = 0; i < limit; i++) {
            var part = parts.get(i);
            var input = downloadOne(session, part, agentName);
            if (input != null) staged.add(input);
        }
        return staged;
    }

    /** Download + stage one media part, or null on miss / oversize / error. */
    private static AttachmentService.Input downloadOne(
            WhatsAppCobaltSession session, WhatsAppInboundMessage.PendingMedia part, String agentName) {
        var info = session.recentMessage(part.mediaId());
        if (info == null) {
            EventLogger.warn("channel", agentName, "whatsapp",
                    "Inbound media message %s no longer cached; skipping".formatted(part.mediaId()));
            return null;
        }
        byte[] bytes;
        try {
            bytes = session.whatsapp().downloadMedia(info).get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            EventLogger.warn("channel", agentName, "whatsapp",
                    "WhatsApp-Web media download failed for %s: %s".formatted(part.mediaId(), e.getMessage()));
            return null;
        }
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > MAX_FILE_BYTES) {
            EventLogger.warn("channel", agentName, "whatsapp",
                    "WhatsApp-Web media %s exceeds %d bytes; skipping".formatted(part.mediaId(), MAX_FILE_BYTES));
            return null;
        }
        return stage(bytes, part, agentName);
    }

    /** Write the decrypted bytes into the agent's staging dir under a fresh UUID
     *  leaf and return the {@link AttachmentService.Input} the runner finalizes. */
    private static AttachmentService.Input stage(
            byte[] bytes, WhatsAppInboundMessage.PendingMedia part, String agentName) {
        var uuid = UUID.randomUUID().toString();
        var extension = extensionFor(part);
        var leaf = uuid + extension;
        var stagingDir = AgentService.acquireWorkspacePath(agentName, "attachments/staging");
        try {
            Files.createDirectories(stagingDir);
            Path stagedPath = AgentService.acquireContained(stagingDir, leaf);
            Files.write(stagedPath, bytes);
        } catch (IOException e) {
            EventLogger.warn("channel", agentName, "whatsapp",
                    "Failed to stage WhatsApp-Web media %s: %s".formatted(part.mediaId(), e.getMessage()));
            return null;
        }
        var originalFilename = part.filename() != null && !part.filename().isBlank()
                ? part.filename() : "whatsapp-" + uuid + extension;
        return new AttachmentService.Input(
                uuid, originalFilename, part.mimeType(), bytes.length, kindFor(part.mimeType()));
    }

    /** Best-effort staging extension: from the declared filename, else the MIME
     *  fallback table, else empty (finalize re-sniffs regardless). */
    private static String extensionFor(WhatsAppInboundMessage.PendingMedia part) {
        var fromName = Filenames.extensionOf(part.filename());
        if (!fromName.isEmpty()) return fromName;
        var mime = part.mimeType();
        return mime != null ? MIME_EXT.getOrDefault(mime.toLowerCase(java.util.Locale.ROOT), "") : "";
    }

    /** Map MIME to the inbound modality kind, mirroring
     *  {@link models.MessageAttachment#kindForMime}. */
    private static String kindFor(String mime) {
        return models.MessageAttachment.kindForMime(mime);
    }
}
