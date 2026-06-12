package channels;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.files.FilesCompleteUploadExternalRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import services.EventLogger;
import utils.HttpFactories;
import utils.SsrfGuard;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Uploads outbound files to Slack (JCLAW-345) via the 3-step external flow Slack
 * mandates: {@code files.getUploadURLExternal} (reserve a slot) → POST the bytes
 * to the returned presigned URL → {@code files.completeUploadExternal} (share into
 * the channel/thread with a caption). A {@code U}-prefixed target is first turned
 * into a DM channel via {@code conversations.open} (uploads reject user ids),
 * cached so repeated DM sends don't re-open.
 *
 * <p>The presigned POST goes through an SSRF-hardened client + a Slack-host
 * allowlist; the URL comes from Slack's own API response, so this is
 * defense-in-depth. The four Slack operations sit behind an injectable
 * {@link Uploader} (package-private static seam, like
 * {@link TelegramFileDownloader#DOWNLOAD_CLIENT}) so tests exercise the
 * orchestration without the network.
 */
public final class SlackFileUploader {

    private SlackFileUploader() {}

    private static final String CHANNEL = "channel";
    private static final String CHANNEL_NAME = "slack";
    private static final Pattern USER_ID = Pattern.compile("^U[A-Z0-9]+$");
    private static final List<String> SLACK_HOST_SUFFIXES =
            List.of(".slack.com", ".slack-edge.com", ".slack-files.com");

    private static final Slack slack = Slack.getInstance();

    /** SSRF-hardened client for the presigned-URL POST (60s write window for large
     *  uploads, matching the Telegram upload timeout). */
    static OkHttpClient UPLOAD_CLIENT = HttpFactories.general().newBuilder()
            .dns(SsrfGuard.SAFE_DNS)
            .writeTimeout(Duration.ofSeconds(60)) // large uploads
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    /** Reserved-slot coordinates from {@code files.getUploadURLExternal}. */
    public record UploadUrl(String uploadUrl, String fileId) {}

    /** The four Slack ops, injectable so tests avoid the API + network. Public so
     *  the default-package test can supply a fake; swapped via the {@code IMPL} seam. */
    public interface Uploader {
        UploadUrl getUploadUrl(String botToken, String filename, long length);
        boolean postBytes(String uploadUrl, File file, String contentType);
        boolean completeUpload(String botToken, String fileId, String title,
                               String channelId, String initialComment, String threadTs);
        String openDm(String botToken, String userId);
    }

    static Uploader IMPL = liveUploader();

    /** DM-channel cache keyed by (token-hash, userId); bounded LRU. */
    private static final int DM_CACHE_MAX = 1024;
    private static final Map<String, String> DM_CACHE = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > DM_CACHE_MAX;
                }
            });

    /**
     * Upload {@code file} to {@code peerId} (a {@code C}-channel or a {@code U}-user,
     * resolved to a DM) on behalf of the agent's bot, sharing it with an optional
     * {@code caption} into the optional {@code threadTs}. Best-effort: every failure
     * is logged and returns false; never throws.
     */
    public static boolean upload(String botToken, String peerId, String threadTs,
                                 File file, String displayName, String caption) {
        if (botToken == null || botToken.isBlank() || file == null || !file.isFile()) {
            return false;
        }
        String channelId = resolveChannelId(botToken, peerId);
        if (channelId == null) {
            warn("upload: could not resolve channel for " + peerId);
            return false;
        }
        String title = (displayName != null && !displayName.isBlank()) ? displayName : file.getName();
        var slot = IMPL.getUploadUrl(botToken, title, file.length());
        if (slot == null) {
            return false; // getUploadUrl logged
        }
        if (!IMPL.postBytes(slot.uploadUrl(), file, mimeOf(file))) {
            return false;
        }
        return IMPL.completeUpload(botToken, slot.fileId(), title, channelId, caption, threadTs);
    }

    /** {@code C}-channels pass through; {@code U}-users open (and cache) a DM. */
    static String resolveChannelId(String botToken, String peerId) {
        if (peerId == null || peerId.isBlank()) return null;
        if (!USER_ID.matcher(peerId).matches()) return peerId;
        String key = Integer.toHexString(botToken.hashCode()) + ":" + peerId;
        var cached = DM_CACHE.get(key);
        if (cached != null) return cached;
        var dm = IMPL.openDm(botToken, peerId);
        if (dm != null) DM_CACHE.put(key, dm);
        return dm;
    }

    private static boolean isSlackHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("slack.com")) return true;
            for (var s : SLACK_HOST_SUFFIXES) {
                if (h.endsWith(s)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String mimeOf(File file) {
        try {
            var probed = java.nio.file.Files.probeContentType(file.toPath());
            return probed != null ? probed : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static void warn(String msg) {
        EventLogger.warn(CHANNEL, null, CHANNEL_NAME, "Slack upload: " + msg);
    }

    private static Uploader liveUploader() {
        return new Uploader() {
            @Override
            public UploadUrl getUploadUrl(String botToken, String filename, long length) {
                try {
                    var resp = slack.methods(botToken)
                            .filesGetUploadURLExternal(r -> r.filename(filename).length((int) length));
                    if (resp.isOk()) return new UploadUrl(resp.getUploadUrl(), resp.getFileId());
                    warn("getUploadURLExternal: " + resp.getError());
                    return null;
                } catch (IOException | SlackApiException e) {
                    warn("getUploadURLExternal: " + e.getMessage());
                    return null;
                }
            }

            @Override
            public boolean postBytes(String uploadUrl, File file, String contentType) {
                if (!isSlackHost(uploadUrl)) {
                    warn("refusing non-Slack upload host");
                    return false;
                }
                try {
                    var body = RequestBody.create(file,
                            contentType != null ? MediaType.parse(contentType) : null);
                    var req = new okhttp3.Request.Builder().url(uploadUrl).post(body).build();
                    try (var resp = UPLOAD_CLIENT.newCall(req).execute()) {
                        return resp.isSuccessful();
                    }
                } catch (Exception e) {
                    warn("upload POST: " + e.getMessage());
                    return false;
                }
            }

            @Override
            public boolean completeUpload(String botToken, String fileId, String title,
                                          String channelId, String initialComment, String threadTs) {
                try {
                    var detail = FilesCompleteUploadExternalRequest.FileDetails.builder()
                            .id(fileId).title(title).build();
                    var resp = slack.methods(botToken).filesCompleteUploadExternal(r -> r
                            .files(List.of(detail))
                            .channelId(channelId)
                            .initialComment(initialComment)
                            .threadTs(threadTs));
                    if (resp.isOk()) return true;
                    warn("completeUploadExternal: " + resp.getError());
                    return false;
                } catch (IOException | SlackApiException e) {
                    warn("completeUploadExternal: " + e.getMessage());
                    return false;
                }
            }

            @Override
            public String openDm(String botToken, String userId) {
                try {
                    var resp = slack.methods(botToken).conversationsOpen(r -> r.users(List.of(userId)));
                    return resp.isOk() && resp.getChannel() != null ? resp.getChannel().getId() : null;
                } catch (IOException | SlackApiException e) {
                    warn("conversationsOpen: " + e.getMessage());
                    return null;
                }
            }
        };
    }
}
