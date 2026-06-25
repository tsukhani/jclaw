package channels;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import models.WhatsAppBinding;
import models.WhatsAppConversationWindow;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.EventLogger;
import services.Tx;
import utils.GsonHolder;
import utils.HttpFactories;
import utils.HttpKeys;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WhatsApp Cloud API (Meta) client via raw HTTP.
 */
public class WhatsAppChannel implements Channel {

    private static final Gson gson = GsonHolder.INSTANCE;
    private static final String API_BASE = "https://graph.facebook.com/v21.0/";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get(HttpKeys.APPLICATION_JSON);

    private static final String WHATSAPP = "whatsapp";
    private static final String CHANNEL = "channel";

    /** Graph messages-API envelope key, required on every outbound body. */
    private static final String MESSAGING_PRODUCT = "messaging_product";

    public record WhatsAppConfig(String phoneNumberId, String accessToken, String appSecret, String verifyToken) {
        public static WhatsAppConfig load() {
            var cc = ChannelConfig.findByType(WHATSAPP);
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new WhatsAppConfig(
                    json.get("phoneNumberId").getAsString(),
                    json.get("accessToken").getAsString(),
                    json.has("appSecret") ? json.get("appSecret").getAsString() : null,
                    json.has("verifyToken") ? json.get("verifyToken").getAsString() : null
            );
        }
    }

    // Per-binding credentials (JCLAW-446). Null on the stateless instance, which
    // falls back to the app-global config — the pre-444 path, retired once
    // JCLAW-446/447 migrate their last callers off WhatsAppConfig.load().
    private final String phoneNumberId;
    private final String accessToken;
    private final String appSecret;

    // Per-binding 24h-window context (JCLAW-447). Null on the stateless instance
    // (no window enforcement on the backward-compat path).
    private final Long bindingId;
    private final String templateName;
    private final String templateLanguage;

    /** WhatsApp's per-text-message body cap. Longer replies are chunked. Public for
     *  the default-package test seam. */
    public static final int MAX_TEXT_CHARS = 4096;

    private static final String DEFAULT_TEMPLATE_LANGUAGE = "en_US";

    /** Stateless instance using the app-global config (backward-compat path). */
    public WhatsAppChannel() {
        this(null, null, null, null, null, null);
    }

    private WhatsAppChannel(String phoneNumberId, String accessToken, String appSecret,
                            Long bindingId, String templateName, String templateLanguage) {
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
        this.appSecret = appSecret;
        this.bindingId = bindingId;
        this.templateName = templateName;
        this.templateLanguage = templateLanguage;
    }

    /** Per-binding instance carrying this binding's Cloud-API credentials and 24h-window
     *  context (JCLAW-446/447). */
    public static WhatsAppChannel forBinding(WhatsAppBinding binding) {
        return new WhatsAppChannel(binding.phoneNumberId, binding.accessToken, binding.appSecret,
                binding.id, binding.templateName, binding.templateLanguage);
    }

    /** The credentials this instance should use: its own per-binding fields when set,
     *  else the app-global config (the pre-444 path). */
    private WhatsAppConfig effectiveConfig() {
        if (phoneNumberId != null && accessToken != null) {
            return new WhatsAppConfig(phoneNumberId, accessToken, appSecret, null);
        }
        return WhatsAppConfig.load();
    }

    @Override
    public String channelName() { return WHATSAPP; }

    /**
     * Generic cross-channel text send. WhatsApp's Cloud API caps a text body at
     * {@value #MAX_TEXT_CHARS} chars, so a longer reply is split into ordered
     * chunks (JCLAW-447) and sent sequentially, each through the shared single-
     * retry policy. WhatsApp text carries no markup, so no formatter runs.
     *
     * <p>24h customer-service window: on a per-binding instance, when the peer is
     * OUTSIDE the window free-form text is NOT permitted — Meta requires a
     * pre-approved template to (re-)open the conversation, and the template carries
     * its own approved body, not the agent's reply. So out-of-window we send the
     * configured template and stop (the free-form reply will land once the user
     * replies and the window re-opens). With no template configured we log a
     * warning and best-effort send the reply free-form (Meta will reject it, but
     * that's an operator-config gap to surface, not swallow). The backward-compat
     * stateless instance has no window context and always sends free-form.
     */
    @Override
    public SendResult sendText(String peerId, String text) {
        if (text == null || text.isEmpty()) return SendResult.OK;

        if (!isWithinWindow(peerId)) {
            // Out of window: a template (its own body) re-opens the conversation;
            // the agent's reply text can't go free-form until the user replies.
            return sendOutOfWindowOpener(peerId, text) ? SendResult.OK : SendResult.FAILED;
        }

        // In window: free-form, chunked at the 4096 cap.
        boolean allOk = true;
        for (var chunk : chunkText(text, MAX_TEXT_CHARS)) {
            allOk = sendWithRetry(peerId, chunk) && allOk;
        }
        return allOk ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * Send a photo via the Graph media two-step (JCLAW-447): upload the bytes to
     * get a media id, then send an {@code image} message carrying that id +
     * {@code caption}. Overrides the no-op default.
     */
    @Override
    public SendResult sendPhoto(String peerId, File file, String caption) {
        return sendMedia(peerId, file, null, caption);
    }

    /** Send a document via the Graph media two-step (JCLAW-447). */
    @Override
    public SendResult sendDocument(String peerId, File file, String caption) {
        return sendMedia(peerId, file, null, caption);
    }

    /**
     * Send a media file to {@code peerId} via the Graph two-step: POST the bytes to
     * {@code /{phoneNumberId}/media} (multipart) to obtain a media id, then POST a
     * message of the matching type ({@code image}/{@code audio}/{@code video}/
     * {@code document}) referencing that id. The message type is inferred from
     * {@code mimeType} (falling back to the file's probed content type, then
     * {@code document}). {@code caption} rides with image/video/document (audio
     * carries none). Returns {@link SendResult#OK} on a 200 from the message send.
     */
    public SendResult sendMedia(String peerId, File file, String mimeType, String caption) {
        if (file == null || !file.isFile()) {
            EventLogger.warn(CHANNEL, null, WHATSAPP, "sendMedia: file missing or unreadable");
            return SendResult.FAILED;
        }
        var config = effectiveConfig();
        if (config == null) return SendResult.FAILED;

        var mime = resolveMime(file, mimeType);
        var mediaId = uploadMedia(config, file, mime);
        if (mediaId == null) return SendResult.FAILED;

        var type = mediaMessageType(mime);
        var mediaObj = new LinkedHashMap<String, Object>();
        mediaObj.put("id", mediaId);
        // Audio messages reject a caption; image/video/document accept one.
        if (!"audio".equals(type) && caption != null && !caption.isBlank()) {
            mediaObj.put("caption", caption);
        }
        var body = gson.toJson(Map.of(
                MESSAGING_PRODUCT, WHATSAPP,
                "to", peerId,
                "type", type,
                type, mediaObj
        ));
        return postMessage(config, body, "media (%s) sent to %s".formatted(type, peerId));
    }

    /**
     * React to an earlier message (JCLAW-447): send a {@code reaction} message
     * referencing {@code targetMessageId} with {@code emoji} (pass an empty string
     * to remove a prior reaction). A reaction is never window-gated — it's a status
     * signal, not a free-form message.
     */
    public SendResult sendReaction(String peerId, String targetMessageId, String emoji) {
        var config = effectiveConfig();
        if (config == null) return SendResult.FAILED;
        if (targetMessageId == null || targetMessageId.isBlank()) return SendResult.FAILED;
        var body = gson.toJson(Map.of(
                MESSAGING_PRODUCT, WHATSAPP,
                "to", peerId,
                "type", "reaction",
                "reaction", Map.of(
                        "message_id", targetMessageId,
                        "emoji", emoji != null ? emoji : "")
        ));
        return postMessage(config, body, "reaction sent to %s".formatted(peerId));
    }

    public static boolean sendMessage(String to, String text) {
        var config = WhatsAppConfig.load();
        if (config == null) {
            EventLogger.error(CHANNEL, null, WHATSAPP, "WhatsApp not configured");
            return false;
        }
        return new WhatsAppChannel().sendWithRetry(to, text);
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        var config = effectiveConfig();
        if (config == null) return SendResult.FAILED;
        var body = gson.toJson(Map.of(
                MESSAGING_PRODUCT, WHATSAPP,
                "to", peerId,
                "type", "text",
                "text", Map.of("body", text)
        ));
        return postMessage(config, body, "Message sent to %s".formatted(peerId));
    }

    /**
     * POST a {@code /{phoneNumberId}/messages} body and map the response to a
     * {@link SendResult}. The single place every outbound message type (text,
     * media, reaction, template) shares the HTTP call, auth header, and error
     * logging. {@code successLog} is the info line on a 200. Must not throw.
     */
    private SendResult postMessage(WhatsAppConfig config, String jsonBody, String successLog) {
        var url = API_BASE + config.phoneNumberId() + "/messages";
        var request = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + config.accessToken())
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();
        try (var response = HttpFactories.general().newCall(request).execute()) {
            if (response.code() == 200) {
                EventLogger.info(CHANNEL, null, WHATSAPP, successLog);
                return SendResult.OK;
            }
            var responseBody = response.body().string();
            EventLogger.warn(CHANNEL, null, WHATSAPP,
                    "WhatsApp API error (HTTP %d): %s".formatted(response.code(), responseBody));
            return SendResult.FAILED;
        } catch (Exception e) {
            EventLogger.warn(CHANNEL, null, WHATSAPP,
                    "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    // ── JCLAW-447 outbound helpers ──

    /**
     * Split {@code text} into ordered chunks no longer than {@code limit} chars.
     * Prefers to break at the last newline (then the last space) within the window
     * so a chunk doesn't sever a word/line mid-token; falls back to a hard cut when
     * a single run exceeds the limit with no breakpoint. Public for the
     * default-package test seam.
     */
    public static List<String> chunkText(String text, int limit) {
        var chunks = new ArrayList<String>();
        if (text == null || text.isEmpty()) return chunks;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int end = Math.min(i + limit, n);
            if (end < n) {
                int nl = text.lastIndexOf('\n', end);
                int sp = text.lastIndexOf(' ', end);
                int brk = Math.max(nl, sp);
                if (brk > i) {
                    end = brk + 1; // include the breakpoint char in this chunk
                }
            }
            chunks.add(text.substring(i, end));
            i = end;
        }
        return chunks;
    }

    /**
     * Whether free-form outbound is allowed for {@code peerId} right now. True for
     * the stateless backward-compat instance (no binding context → no window to
     * enforce); otherwise consults the {@link models.WhatsAppConversationWindow}.
     * Wrapped in {@link services.Tx#run} so it works whether or not the caller (the
     * streaming sink, the delivery dispatcher, the message tool) already holds a
     * JPA transaction — {@code Tx.run} reuses an active one and opens its own
     * otherwise. A read failure fails open (free-form) rather than blocking the reply.
     */
    private boolean isWithinWindow(String peerId) {
        if (bindingId == null) return true;
        try {
            return Tx.run(() -> WhatsAppConversationWindow.isWithinWindow(
                    bindingId, peerId, Instant.now()));
        } catch (Exception e) {
            EventLogger.warn(CHANNEL, null, WHATSAPP,
                    "24h-window check failed (%s); defaulting to free-form".formatted(e.getMessage()));
            return true;
        }
    }

    /**
     * Out-of-window opener: send the configured pre-approved template to re-open
     * the conversation. With no template configured, warn and best-effort send the
     * reply free-form (chunked; Meta will reject it outside the window, but the
     * failure is an operator-config gap we surface rather than swallow).
     */
    private boolean sendOutOfWindowOpener(String peerId, String text) {
        if (templateName == null || templateName.isBlank()) {
            EventLogger.warn(CHANNEL, null, WHATSAPP,
                    ("Outbound to %s is outside the 24h window and no template is configured "
                            + "— best-effort free-form send (likely to be rejected by Meta)")
                            .formatted(peerId));
            boolean allOk = true;
            for (var chunk : chunkText(text, MAX_TEXT_CHARS)) {
                allOk = sendWithRetry(peerId, chunk) && allOk;
            }
            return allOk;
        }
        var config = effectiveConfig();
        if (config == null) return false;
        var lang = templateLanguage != null && !templateLanguage.isBlank()
                ? templateLanguage : DEFAULT_TEMPLATE_LANGUAGE;
        var body = gson.toJson(Map.of(
                MESSAGING_PRODUCT, WHATSAPP,
                "to", peerId,
                "type", "template",
                "template", Map.of(
                        "name", templateName,
                        "language", Map.of("code", lang))
        ));
        return postMessage(config, body, "template '%s' sent to %s".formatted(templateName, peerId)).ok();
    }

    /**
     * Step 1 of the media two-step: upload {@code file} to
     * {@code /{phoneNumberId}/media} as multipart and return the resulting media
     * id, or null on failure.
     */
    private String uploadMedia(WhatsAppConfig config, File file, String mime) {
        var url = API_BASE + config.phoneNumberId() + "/media";
        var fileBody = RequestBody.create(file, MediaType.parse(mime));
        var multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(MESSAGING_PRODUCT, WHATSAPP)
                .addFormDataPart("type", mime)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();
        var request = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + config.accessToken())
                .post(multipart)
                .build();
        try (var response = HttpFactories.general().newCall(request).execute()) {
            var responseBody = response.body().string();
            if (response.code() != 200) {
                EventLogger.warn(CHANNEL, null, WHATSAPP,
                        "Media upload error (HTTP %d): %s".formatted(response.code(), responseBody));
                return null;
            }
            var json = JsonParser.parseString(responseBody).getAsJsonObject();
            return json.has("id") && !json.get("id").isJsonNull() ? json.get("id").getAsString() : null;
        } catch (Exception e) {
            EventLogger.warn(CHANNEL, null, WHATSAPP, "Media upload failed: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /** Resolve the upload MIME: caller-supplied value wins, else probe the file,
     *  else a generic binary type. */
    private static String resolveMime(File file, String mimeType) {
        if (mimeType != null && !mimeType.isBlank()) return mimeType;
        try {
            var probed = Files.probeContentType(file.toPath());
            if (probed != null && !probed.isBlank()) return probed;
        } catch (Exception _) {
            // fall through to the default
        }
        return "application/octet-stream";
    }

    /** Map a MIME type to the WhatsApp media message type
     *  ({@code image}/{@code audio}/{@code video}/{@code document}). Public for the
     *  default-package test seam. */
    public static String mediaMessageType(String mime) {
        if (mime == null) return "document";
        var m = mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) return "image";
        if (m.startsWith("audio/")) return "audio";
        if (m.startsWith("video/")) return "video";
        return "document";
    }

    public static void markAsRead(WhatsAppConfig config, String messageId) {
        var url = API_BASE + config.phoneNumberId() + "/messages";
        var body = gson.toJson(Map.of(
                MESSAGING_PRODUCT, WHATSAPP,
                "status", "read",
                "message_id", messageId
        ));

        var request = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + config.accessToken())
                .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                .build();
        try (var resp = HttpFactories.general().newCall(request).execute()) {
            // Drain the body so the connection returns to the pool; result discarded.
            resp.body().bytes();
        } catch (Exception _) {
            // Read receipt failure is non-critical
        }
    }

    // --- HMAC-SHA256 signature verification ---

    public static boolean verifySignature(String appSecret, String rawBody, String signatureHeader) {
        if (appSecret == null || signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            var received = HexFormat.of().parseHex(signatureHeader.substring(7));
            return MessageDigest.isEqual(expected, received);
        } catch (Exception _) {
            return false;
        }
    }

    // --- Parse inbound webhook ---

    public record InboundMessage(String from, String text, String messageId, String phoneNumberId) {}

    public static InboundMessage parseWebhook(JsonObject payload) {
        if (!payload.has("entry")) return null;
        var entries = payload.getAsJsonArray("entry");
        if (entries.isEmpty()) return null;

        var entry = entries.get(0).getAsJsonObject();
        if (!entry.has("changes")) return null;
        var changes = entry.getAsJsonArray("changes");
        if (changes.isEmpty()) return null;

        var change = changes.get(0).getAsJsonObject();
        var value = change.getAsJsonObject("value");
        if (value == null || !value.has("messages")) return null;

        var messages = value.getAsJsonArray("messages");
        if (messages.isEmpty()) return null;

        var msg = messages.get(0).getAsJsonObject();
        if (!"text".equals(msg.get("type").getAsString())) return null;

        var from = msg.get("from").getAsString();
        var text = msg.getAsJsonObject("text").get("body").getAsString();
        var messageId = msg.get("id").getAsString();

        var phoneNumberId = value.has("metadata")
                ? value.getAsJsonObject("metadata").get("phone_number_id").getAsString()
                : null;

        return new InboundMessage(from, text, messageId, phoneNumberId);
    }
}
