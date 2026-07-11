package channels;

import com.google.gson.JsonParser;
import it.auties.whatsapp.model.info.ChatMessageInfo;
import models.MessageAttachment;
import models.WhatsAppBinding;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import services.AgentService;
import services.AttachmentService;
import services.EventLogger;
import utils.Filenames;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Cloud-API media size cap: 20 MiB (matches Telegram/Slack inbound). */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final String API_BASE = "https://graph.facebook.com/v21.0/";
    private static final Duration META_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(90);
    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_WHATSAPP = "whatsapp";

    /** WhatsApp media CDN hosts — the {@code /{mediaId}} lookup returns a URL that
     *  must resolve under one of these before we attach the token or stream bytes. */
    private static final List<String> CDN_HOST_SUFFIXES =
            List.of(".fbcdn.net", ".whatsapp.net", ".cdn.whatsapp.net");

    /**
     * SSRF-hardened client for the second-step CDN byte fetch — same derivation as
     * {@link TelegramFileDownloader#DOWNLOAD_CLIENT}/{@link SlackFileDownloader}:
     * {@link HttpFactories#general()} for the shared pool/dispatcher/timeouts,
     * layered with {@link SsrfGuard#SAFE_DNS} (every hostname resolution gated) and
     * redirects disabled so a 302 can't bounce past the CDN host allowlist.
     * Package-private non-final so tests can swap a socket-free interceptor client.
     */
    static OkHttpClient DOWNLOAD_CLIENT = HttpFactories.general().newBuilder()
            .dns(SsrfGuard.SAFE_DNS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    /** Visible for testing: assert the download client carries the SSRF DNS gate. */
    public static OkHttpClient downloadClient() {
        return DOWNLOAD_CLIENT;
    }

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

    /**
     * Cloud-API Graph media download (JCLAW-446). For each pending media part (up
     * to {@link #MAX_INBOUND_FILES}) it runs the Graph two-step:
     * {@code GET /v21.0/{mediaId}} (Bearer) → {@code {"url": <cdn>}} →
     * {@code GET <cdn>} (Bearer) → bytes, staging into the agent's
     * {@code attachments/staging} dir so {@link AttachmentService#finalizeAttachment}
     * can finalize + re-sniff. The CDN host is SSRF-gated and allowlisted to
     * WhatsApp's media CDNs, with a {@link #MAX_FILE_BYTES} cap. A failed part is
     * logged and skipped (the message still delivers with whatever parts succeeded).
     */
    private static List<AttachmentService.Input> downloadCloudApi(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        var token = binding.accessToken;
        if (token == null || token.isBlank()) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                    "Cloud-API media download skipped: binding has no access token");
            return List.of();
        }
        var inputs = new ArrayList<AttachmentService.Input>();
        int count = 0;
        for (var pending : msg.media()) {
            if (count++ >= MAX_INBOUND_FILES) break;
            var input = downloadOne(token, pending, agentName, API_BASE);
            if (input != null) {
                inputs.add(input);
            }
        }
        return List.copyOf(inputs);
    }

    /**
     * Download a single Cloud-API media part via the Graph two-step. Returns the
     * staged {@link AttachmentService.Input}, or {@code null} on any failure (the
     * caller skips it). Public (with the {@code apiBase} param) as the test seam —
     * jclaw tests live in the default package and can't see package-private methods.
     */
    public static AttachmentService.Input downloadOne(String token,
                                                      WhatsAppInboundMessage.PendingMedia pending,
                                                      String agentName, String apiBase) {
        var mediaId = pending.mediaId();
        if (mediaId == null || mediaId.isBlank()) return null;

        var cdnUrl = resolveCdnUrl(token, mediaId, apiBase, agentName);
        if (cdnUrl == null) return null;

        var uuid = UUID.randomUUID().toString();
        var extension = Filenames.extensionOf(pending.filename());
        var leaf = uuid + extension;

        var stagingDir = AgentService.acquireWorkspacePath(agentName, "attachments/staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                    "Cloud-API media staging dir failed: " + e.getMessage());
            return null;
        }
        Path stagedPath = WorkspacePathGuard.acquireContained(stagingDir, leaf);
        if (!streamCdnToStaging(cdnUrl, token, stagedPath, agentName)) {
            bestEffortDelete(stagedPath);
            return null;
        }

        var mime = pending.mimeType() != null && !pending.mimeType().isBlank()
                ? stripParams(pending.mimeType()) : "application/octet-stream";
        var kind = MessageAttachment.kindForMime(mime);
        var originalFilename = pending.filename() != null && !pending.filename().isBlank()
                ? pending.filename() : "whatsapp-" + mediaId + extension;
        long size;
        try {
            size = Files.size(stagedPath);
        } catch (IOException _) {
            size = 0L;
        }
        return new AttachmentService.Input(uuid, originalFilename, mime, size, kind);
    }

    /**
     * Step 1: {@code GET /v21.0/{mediaId}} (Bearer) → the temporary CDN URL. Uses
     * the general (non-SSRF) client because the Graph host is a fixed, trusted
     * operator endpoint. Returns null on any error.
     */
    private static String resolveCdnUrl(String token, String mediaId, String apiBase, String agentName) {
        try {
            var url = apiBase + mediaId;
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + token)
                    .get()
                    .build();
            var call = HttpFactories.general().newCall(req);
            call.timeout().timeout(META_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try (var resp = call.execute()) {
                var body = resp.body().string();
                if (resp.code() != 200) {
                    EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                            "Cloud-API media lookup HTTP " + resp.code());
                    return null;
                }
                var json = JsonParser.parseString(body).getAsJsonObject();
                if (!json.has("url") || json.get("url").isJsonNull()) {
                    return null;
                }
                var cdn = json.get("url").getAsString();
                return (cdn == null || cdn.isBlank()) ? null : cdn;
            }
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                    "Cloud-API media lookup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Step 2: {@code GET <cdnUrl>} (Bearer) → bytes into {@code stagedPath}.
     * Scheme + CDN-host allowlist validated before the socket opens (the SSRF DNS
     * gate covers hostname resolution); the {@link #MAX_FILE_BYTES} cap is enforced
     * on the staged size. Returns true on success.
     */
    private static boolean streamCdnToStaging(String cdnUrl, String token,
                                              Path stagedPath, String agentName) {
        try {
            var uri = URI.create(cdnUrl);
            SsrfGuard.assertSafeScheme(uri);
            if (!isCdnHost(uri.getHost())) {
                EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                        "Cloud-API media: refusing non-CDN host " + uri.getHost());
                return false;
            }
            var req = new Request.Builder()
                    .url(cdnUrl)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + token)
                    .get()
                    .build();
            var call = DOWNLOAD_CLIENT.newCall(req);
            call.timeout().timeout(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try (var resp = call.execute()) {
                if (resp.code() != 200) {
                    EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                            "Cloud-API media download HTTP " + resp.code());
                    return false;
                }
                try (InputStream in = resp.body().byteStream()) {
                    Files.copy(in, stagedPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.size(stagedPath) > MAX_FILE_BYTES) {
                EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                        "Cloud-API media exceeds 20 MiB cap; dropped");
                return false;
            }
            return true;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                    "Cloud-API media download failed: " + e.getMessage());
            return false;
        }
    }

    /** True when {@code host} resolves under one of WhatsApp's media CDN suffixes.
     *  Public for the default-package test seam. */
    public static boolean isCdnHost(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(Locale.ROOT);
        for (var suffix : CDN_HOST_SUFFIXES) {
            if (h.endsWith(suffix)) return true;
        }
        return false;
    }

    private static String stripParams(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }

    /** Best-effort delete used during download cleanup — swallows IO errors. */
    private static void bestEffortDelete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException _) { /* best-effort */ }
    }

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
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
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
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                    "Inbound media message %s no longer cached; skipping".formatted(part.mediaId()));
            return null;
        }
        byte[] bytes;
        try {
            bytes = session.whatsapp().downloadMedia(info).get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
                    "WhatsApp-Web media download failed for %s: %s".formatted(part.mediaId(), e.getMessage()));
            return null;
        }
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > MAX_FILE_BYTES) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
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
            Path stagedPath = WorkspacePathGuard.acquireContained(stagingDir, leaf);
            Files.write(stagedPath, bytes);
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, agentName, CHANNEL_WHATSAPP,
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
        return mime != null ? MIME_EXT.getOrDefault(mime.toLowerCase(Locale.ROOT), "") : "";
    }

    /** Map MIME to the inbound modality kind, mirroring
     *  {@link models.MessageAttachment#kindForMime}. */
    private static String kindFor(String mime) {
        return MessageAttachment.kindForMime(mime);
    }
}
