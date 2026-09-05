package channels;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.files.FilesCompleteUploadExternalRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.EventLogger;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Uploads outbound files to Slack (JCLAW-345) via the 3-step external flow Slack
 * mandates: {@code files.getUploadURLExternal} (reserve a slot) → POST the bytes
 * to the returned presigned URL → {@code files.completeUploadExternal} (share into
 * the channel/thread with a caption). The target is resolved by
 * {@link SlackWebApi#resolveChannel} (JCLAW-1060), so a {@code U}-user becomes a DM
 * channel — uploads reject user ids — and a {@code #name} becomes a channel id, both
 * through the same cache the text-send path uses.
 *
 * <p>The presigned POST goes through an SSRF-hardened client + a Slack-host
 * allowlist; the URL comes from Slack's own API response, so this is
 * defense-in-depth. The three Slack operations sit behind an injectable
 * {@link Uploader} (package-private static seam, like
 * {@link TelegramFileDownloader#DOWNLOAD_CLIENT}) so tests exercise the
 * orchestration without the network.
 */
public final class SlackFileUploader {

    private SlackFileUploader() {}

    private static final String CHANNEL = "channel";
    private static final String CHANNEL_NAME = "slack";
    private static final List<String> SLACK_HOST_SUFFIXES =
            List.of(".slack.com", ".slack-edge.com", ".slack-files.com");

    private static final Slack slack = Slack.getInstance();

    /** SSRF-hardened client for the presigned-URL POST (60s write window for large
     *  uploads, matching the Telegram upload timeout). */
    static OkHttpClient UPLOAD_CLIENT = HttpFactories.general().newBuilder()
            .dns(SsrfGuard.SAFE_DNS)
            .writeTimeout(Duration.ofSeconds(60))
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    /** Reserved-slot coordinates from {@code files.getUploadURLExternal}. */
    public record UploadUrl(String uploadUrl, String fileId) {}

    /** The three Slack ops, injectable so tests avoid the API + network. Public so
     *  the default-package test can supply a fake; swapped via the {@code IMPL} seam. */
    public interface Uploader {
        UploadUrl getUploadUrl(String botToken, String filename, long length);
        boolean postBytes(String uploadUrl, File file, String contentType);
        boolean completeUpload(String botToken, String fileId, String title,
                               String channelId, String initialComment, String threadTs);
    }

    static Uploader IMPL = liveUploader();

    /**
     * Upload {@code file} to {@code peerId} (a channel id, {@code #name}, or a
     * {@code U}-user resolved to a DM) on behalf of the agent's bot, sharing it with an
     * optional {@code caption} into the optional {@code threadTs}. Best-effort: every
     * failure is logged and returns false; never throws.
     */
    public static boolean upload(String botToken, String peerId, String threadTs,
                                 File file, String displayName, String caption) {
        if (botToken == null || botToken.isBlank() || file == null || !file.isFile()) {
            return false;
        }
        var res = SlackWebApi.resolveChannel(botToken, peerId);
        String channelId = res.channelId();
        if (channelId == null) {
            warn("upload: could not resolve channel for " + peerId
                    + (res.error() != null ? " (" + res.error() + ")" : ""));
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
        } catch (Exception _) {
            return false;
        }
    }

    private static String mimeOf(File file) {
        try {
            var probed = Files.probeContentType(file.toPath());
            return probed != null ? probed : HttpKeys.APPLICATION_OCTET_STREAM;
        } catch (IOException _) {
            return HttpKeys.APPLICATION_OCTET_STREAM;
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
                    var req = new Request.Builder().url(uploadUrl).post(body).build();
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
        };
    }
}
