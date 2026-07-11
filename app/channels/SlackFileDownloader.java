package channels;

import models.MessageAttachment;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import services.AgentService;
import services.AttachmentService;
import utils.Filenames;
import utils.HttpFactories;
import utils.SsrfGuard;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Downloads inbound Slack files (JCLAW-344) authenticating with the agent's
 * binding bot token. Unlike Telegram's two-step Bot API flow, Slack's
 * {@code url_private_download} is the direct byte URL; we GET it with an
 * {@code Authorization: Bearer <botToken>} header. The result lands in the
 * agent's {@code attachments/staging/} dir under a fresh UUID leaf so the
 * existing {@link AttachmentService#finalizeAttachment} pipeline can move it
 * into the conversation directory and re-sniff the MIME — no Slack-specific data
 * leaks into the runner (the returned {@link AttachmentService.Input} is the same
 * shape the web upload path produces).
 *
 * <p>Security: the download URL arrives inside the (HMAC-verified) event payload,
 * so defense-in-depth gates it through {@link SsrfGuard#assertSafeScheme(URI)} +
 * the {@link SsrfGuard#SAFE_DNS} resolver AND an explicit Slack-host allowlist
 * before the bot token is attached or any byte is read. Redirects are followed
 * manually (one hop) so a 302 can't bounce past those gates; the
 * {@code Authorization} header is dropped on a cross-origin hop (Slack CDN URLs
 * are pre-signed, so the token is both unnecessary and a leak risk there).
 *
 * <p>Size cap: 20 MiB, matching {@link TelegramFileDownloader#MAX_FILE_BYTES}.
 */
public final class SlackFileDownloader {

    private SlackFileDownloader() {}

    /** Cap inbound Slack downloads at 20 MiB (matches TelegramFileDownloader). */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(90);
    private static final String AUDIO_SUBTYPE = "slack_audio";
    private static final String VIDEO_PREFIX = "video/";

    /** Slack file hosts: the URL (and any redirect target) must resolve to one of
     *  these before we attach the bot token or stream bytes. */
    private static final List<String> SLACK_HOST_SUFFIXES =
            List.of(".slack.com", ".slack-edge.com", ".slack-files.com");

    /**
     * SSRF-hardened client — same derivation as
     * {@link TelegramFileDownloader#DOWNLOAD_CLIENT}: {@link HttpFactories#general()}
     * for the shared pool/dispatcher/timeouts, layered with {@link SsrfGuard#SAFE_DNS}
     * (every hostname resolution gated) and redirect-following disabled so we hop
     * manually past the host allowlist. Package-private non-final so tests can swap a
     * socket-free interceptor client in (mirrors {@code WebFetchTool.CLIENT}).
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

    public sealed interface Result permits Ok, SizeExceeded, DownloadFailed {}

    public record Ok(AttachmentService.Input input) implements Result {}

    public record SizeExceeded(long actualBytes, long limit) implements Result {}

    public record DownloadFailed(String reason) implements Result {}

    /**
     * Download a Slack file into the agent's staging directory. Returns an
     * {@link AttachmentService.Input} on success that the caller feeds into the
     * runner's multimodal assembly path. Size-limit rejections and network/host
     * errors return distinct result variants so callers can emit an appropriate
     * user-visible reply.
     */
    public static Result download(String botToken, SlackPendingFile file, String agentName) {
        String url = file.urlPrivateDownload();
        if (url == null || url.isBlank()) {
            return new DownloadFailed("missing url_private_download");
        }
        // Early reject from Slack's reported size (the stream is also capped below).
        if (file.sizeBytes() > MAX_FILE_BYTES) {
            return new SizeExceeded(file.sizeBytes(), MAX_FILE_BYTES);
        }

        var uuid = UUID.randomUUID().toString();
        var extension = Filenames.extensionOf(file.name(), url);
        var leaf = uuid + extension;

        var stagingDir = AgentService.acquireWorkspacePath(agentName, "attachments/staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            return new DownloadFailed("Failed to create staging dir: " + e.getMessage());
        }
        Path stagedPath = WorkspacePathGuard.acquireContained(stagingDir, leaf);

        var fetched = fetchToStaging(url, botToken, stagedPath, true, 1);
        if (fetched instanceof FetchFailed(String reason)) {
            bestEffortDelete(stagedPath);
            return new DownloadFailed(reason);
        }
        if (fetched instanceof FetchSize(long bytes)) {
            bestEffortDelete(stagedPath);
            return new SizeExceeded(bytes, MAX_FILE_BYTES);
        }
        var ok = (FetchOk) fetched;

        var effectiveMime = effectiveMime(file.subtype(), file.mimeType(), ok.contentType());
        var kind = MessageAttachment.kindForMime(effectiveMime);
        var originalFilename = (file.name() != null && !file.name().isBlank())
                ? file.name()
                : "slack-" + file.id() + extension;
        return new Ok(new AttachmentService.Input(
                uuid, originalFilename, effectiveMime, ok.sizeBytes(), kind));
    }

    /** Sealed result of a single fetch leg (recursed once for a CDN redirect). */
    private sealed interface FetchResult permits FetchOk, FetchFailed, FetchSize {}
    private record FetchOk(String contentType, long sizeBytes) implements FetchResult {}
    private record FetchFailed(String reason) implements FetchResult {}
    private record FetchSize(long actualBytes) implements FetchResult {}

    /**
     * GET {@code url} (optionally with the Bearer token) and stream the body into
     * {@code stagedPath}. Validates scheme + Slack-host before opening a socket. A
     * 3xx is followed once: the redirect target is re-validated, and the token is
     * dropped on a cross-origin hop. Returns {@link FetchOk} with the response
     * content-type + on-disk size, else a failure / size variant.
     */
    private static FetchResult fetchToStaging(String url, String botToken,
                                              Path stagedPath, boolean withAuth, int redirectsLeft) {
        final URI uri;
        try {
            uri = URI.create(url);
            SsrfGuard.assertSafeScheme(uri);
        } catch (Exception e) {
            return new FetchFailed("unsafe url: " + e.getMessage());
        }
        if (!isSlackHost(uri.getHost())) {
            return new FetchFailed("refusing non-Slack host: " + uri.getHost());
        }
        var builder = new Request.Builder().url(url).get();
        if (withAuth) {
            builder.header("Authorization", "Bearer " + botToken);
        }
        try {
            var call = DOWNLOAD_CLIENT.newCall(builder.build());
            call.timeout().timeout(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try (var resp = call.execute()) {
                int code = resp.code();
                if (code >= 300 && code < 400) {
                    if (redirectsLeft <= 0) {
                        return new FetchFailed("too many redirects");
                    }
                    String loc = resp.header("Location");
                    if (loc == null || loc.isBlank()) {
                        return new FetchFailed("redirect without Location");
                    }
                    // Cross-origin hop (e.g. files.slack.com -> *.slack-edge.com CDN):
                    // drop the bot token — CDN URLs are pre-signed. Same-host keeps it.
                    boolean sameHost = uri.getHost() != null
                            && uri.getHost().equalsIgnoreCase(URI.create(loc).getHost());
                    return fetchToStaging(loc, botToken, stagedPath, sameHost && withAuth, redirectsLeft - 1);
                }
                if (code != 200) {
                    return new FetchFailed("download HTTP " + code);
                }
                // OkHttp 5's Response.body is non-null (an empty body for bodyless
                // responses), so a null-check here is dead — read the type directly.
                var ct = resp.body().contentType();
                String ctStr = ct != null ? ct.toString() : "";
                // Slack serves an HTML login page (not bytes) when the token lacks
                // files:read or url_private (not _download) was used. Reject it.
                if (ctStr.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                    return new FetchFailed("got HTML (check files:read scope / url_private_download)");
                }
                try (InputStream in = resp.body().byteStream()) {
                    Files.copy(in, stagedPath, StandardCopyOption.REPLACE_EXISTING);
                }
                long size = Files.size(stagedPath);
                if (size > MAX_FILE_BYTES) {
                    return new FetchSize(size);
                }
                return new FetchOk(ctStr.isBlank() ? null : ctStr, size);
            }
        } catch (Exception e) {
            return new FetchFailed("download: " + e.getMessage());
        }
    }

    /**
     * The MIME to record. Prefer the actual response Content-Type (more reliable
     * than Slack's declared metadata), falling back to the file object's declared
     * mimetype. Slack delivers {@code slack_audio} voice clips as {@code video/*}
     * (an mp4 container); remap the prefix to {@code audio/} so the transcription
     * path picks them up.
     */
    static String effectiveMime(String subtype, String declaredMime, String fetchedContentType) {
        String mime = (fetchedContentType != null && !fetchedContentType.isBlank())
                ? stripParams(fetchedContentType)
                : declaredMime;
        if (mime == null || mime.isBlank()) {
            mime = "application/octet-stream";
        }
        if (AUDIO_SUBTYPE.equals(subtype) && mime.toLowerCase(Locale.ROOT).startsWith(VIDEO_PREFIX)) {
            mime = "audio/" + mime.substring(VIDEO_PREFIX.length());
        }
        return mime;
    }

    private static String stripParams(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }

    private static boolean isSlackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("slack.com")) {
            return true;
        }
        for (var suffix : SLACK_HOST_SUFFIXES) {
            if (h.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Best-effort delete used during download cleanup — swallows IO errors. */
    private static void bestEffortDelete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException _) { /* best-effort */ }
    }
}
