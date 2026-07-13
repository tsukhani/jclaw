package channels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import services.AgentService;
import services.AttachmentService;
import utils.Filenames;
import utils.HttpFactories;
import utils.SsrfGuard;
import utils.Strings;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Downloads inbound Telegram attachments (JCLAW-136) via the Bot API.
 * Two-step flow mandated by Telegram: first {@code getFile} to resolve the
 * opaque {@code file_id} to a temporary {@code file_path}, then a plain HTTPS
 * GET against {@code https://api.telegram.org/file/bot{TOKEN}/{FILE_PATH}}
 * to stream bytes. The download lands in the agent's
 * {@code attachments/staging/} directory under a fresh UUID leaf so the
 * existing JCLAW-25 finalize pipeline ({@link AttachmentService#finalizeAttachment})
 * can move it into the conversation directory and Tika-sniff the MIME at
 * persistence time. No Telegram-specific data leaks into the runner — the
 * returned {@link AttachmentService.Input} is the same shape the web upload
 * path produces.
 *
 * <p>Size limits: Telegram's Bot API caps bot-downloadable files at 20 MiB.
 * The {@code getFile} response's {@code file_size} lets us reject early; we
 * still also stream-cap in case the reported size is absent or wrong.
 */
public final class TelegramFileDownloader {

    private TelegramFileDownloader() {}

    /** Telegram Bot API caps bot-downloadable files at 20 MiB (single-sourced by
     *  {@link StagedDownload#MAX_FILE_BYTES}). */
    public static final long MAX_FILE_BYTES = StagedDownload.MAX_FILE_BYTES;

    private static final Duration GETFILE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * SSRF-hardened client for the second-step byte download. The download URL
     * embeds Telegram's opaque, server-supplied {@code file_path}; a compromised
     * or spoofed Bot API endpoint (or a DNS answer that resolves
     * {@code api.telegram.org} to an internal address) could otherwise steer the
     * GET at loopback / RFC-1918 / link-local (cloud-metadata) ranges. The
     * SSRF-hardening recipe ({@link SsrfGuard#SAFE_DNS} + disabled redirects) is
     * single-sourced in {@link StagedDownload#newSsrfClient()};
     * {@link SsrfGuard#assertSafeScheme(URI)} in {@link #streamFileToStaging}
     * covers the literal-IP / non-http(s)-scheme cases the {@code Dns} hook can't see.
     */
    // Non-final + package-private so tests can swap a socket-free interceptor
    // client in for the loopback happy-path leg (mirrors WebFetchTool.CLIENT).
    static OkHttpClient DOWNLOAD_CLIENT = StagedDownload.newSsrfClient();

    /** Visible for testing: assert the download client carries the SSRF DNS gate. */
    public static OkHttpClient downloadClient() {
        return DOWNLOAD_CLIENT;
    }

    // Bot API getFile response JSON keys.
    private static final String FIELD_FILE_PATH = "file_path";
    private static final String FIELD_FILE_SIZE = "file_size";

    public sealed interface Result permits Ok, SizeExceeded, DownloadFailed {}

    public record Ok(AttachmentService.Input input) implements Result {}

    public record SizeExceeded(long actualBytes, long limit) implements Result {}

    public record DownloadFailed(String reason) implements Result {}

    /**
     * Download a pending attachment into the agent's staging directory.
     * Returns an {@link AttachmentService.Input} on success that the caller
     * can feed into the runner's existing multimodal assembly path.
     * Size-limit rejections and network/API errors return distinct result
     * variants so callers can emit an appropriate user-visible reply.
     *
     * <p>Used by webhook + polling handlers to bridge Telegram's file_id
     * universe into the workspace-file universe used by the rest of the
     * codebase.
     */
    public static Result download(String botToken,
                                  PendingAttachment pending,
                                  String agentName) {
        return download(botToken, pending, agentName,
                "https://api.telegram.org/bot" + botToken,
                "https://api.telegram.org/file/bot" + botToken);
    }

    /**
     * Overload exposing the API and file base URLs for tests (mock HTTP
     * server). Production callers use {@link #download(String, PendingAttachment, String)}.
     * Public because jclaw tests live in the default package and can't see
     * package-private channel methods.
     */
    // S1172: botToken is part of the public test seam — keeping the parameter
    // mirrors the production 3-arg signature so tests don't need to fork
    // unrelated logic when toggling between real and mocked HTTP base URLs.
    @SuppressWarnings("java:S1172")
    public static Result download(String botToken,
                                  PendingAttachment pending,
                                  String agentName,
                                  String apiBaseUrl,
                                  String fileBaseUrl) {
        var meta = fetchFileMetadata(apiBaseUrl, pending);
        if (meta instanceof MetaFailed(String reason)) return new DownloadFailed(reason);
        if (meta instanceof MetaSize(long bytes, long limit)) return new SizeExceeded(bytes, limit);
        var ok = (MetaOk) meta;

        var uuid = UUID.randomUUID().toString();
        var extension = Filenames.extensionOf(pending.suggestedFilename(), ok.filePath());
        var leaf = uuid + extension;

        var stagingDir = AgentService.acquireWorkspacePath(agentName, "attachments/staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            return new DownloadFailed("Failed to create staging dir: " + e.getMessage());
        }
        Path stagedPath = WorkspacePathGuard.acquireContained(stagingDir, leaf);

        var stageResult = streamFileToStaging(fileBaseUrl, ok.filePath(), stagedPath);
        if (stageResult != null) return stageResult;

        var originalFilename = pending.suggestedFilename() != null
                ? pending.suggestedFilename()
                : "telegram-" + pending.telegramFileId() + extension;
        return new Ok(new AttachmentService.Input(
                uuid, originalFilename, pending.mimeType(), ok.reportedSize(), pending.kind()));
    }

    /** Sealed result of the getFile metadata fetch. */
    private sealed interface MetaResult permits MetaOk, MetaFailed, MetaSize {}
    private record MetaOk(String filePath, long reportedSize) implements MetaResult {}
    private record MetaFailed(String reason) implements MetaResult {}
    private record MetaSize(long actualBytes, long limit) implements MetaResult {}

    /**
     * Issue the Bot API {@code getFile} call and validate the response. Returns
     * {@link MetaOk} on success, or a failure variant carrying the reason. The
     * caller is responsible for staging directory setup and the byte transfer.
     */
    private static MetaResult fetchFileMetadata(String apiBaseUrl,
                                                PendingAttachment pending) {
        JsonObject getFileResp;
        try {
            var url = apiBaseUrl + "/getFile?file_id=" + pending.telegramFileId();
            var req = new Request.Builder().url(url).get().build();
            var call = HttpFactories.general().newCall(req);
            call.timeout().timeout(GETFILE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try (var resp = call.execute()) {
                if (resp.code() != 200) {
                    return new MetaFailed("getFile HTTP " + resp.code());
                }
                var body = resp.body().string();
                getFileResp = JsonParser.parseString(body).getAsJsonObject();
            }
        } catch (Exception e) {
            return new MetaFailed("getFile: " + e.getMessage());
        }

        if (!getFileResp.has("ok") || !getFileResp.get("ok").getAsBoolean()) {
            return new MetaFailed("getFile error: " + Strings.truncate(getFileResp.toString(), 200));
        }
        var result = getFileResp.getAsJsonObject("result");
        if (result == null) {
            // ok:true with no/non-object "result" is non-contractual, but guard
            // it so download() returns a sealed MetaFailed instead of NPE-ing out.
            return new MetaFailed("getFile response missing result");
        }
        String filePath = result.has(FIELD_FILE_PATH) && !result.get(FIELD_FILE_PATH).isJsonNull()
                ? result.get(FIELD_FILE_PATH).getAsString() : null;
        if (filePath == null || filePath.isBlank()) {
            return new MetaFailed("getFile response missing file_path");
        }
        long reportedSize = result.has(FIELD_FILE_SIZE) && !result.get(FIELD_FILE_SIZE).isJsonNull()
                ? result.get(FIELD_FILE_SIZE).getAsLong() : pending.sizeBytes();
        if (reportedSize > MAX_FILE_BYTES) {
            return new MetaSize(reportedSize, MAX_FILE_BYTES);
        }
        return new MetaOk(filePath, reportedSize);
    }

    /**
     * Stream the file bytes into {@code stagedPath} via {@link StagedDownload}.
     * Returns {@code null} on success, or a failure {@link Result} on HTTP error /
     * IO error / size cap. Scheme validation happens here (the SSRF DNS gate lives
     * on {@link #DOWNLOAD_CLIENT}); the byte transfer, 20 MiB cap, and cleanup are
     * owned by {@link StagedDownload#fetch}.
     */
    private static Result streamFileToStaging(String fileBaseUrl, String filePath, Path stagedPath) {
        var downloadUrl = fileBaseUrl + "/" + filePath;
        // Reject non-http(s) schemes and literal-IP hosts in a blocked range
        // before opening a socket; SAFE_DNS on DOWNLOAD_CLIENT gates the
        // hostname-resolution path the scheme check can't see.
        try {
            SsrfGuard.assertSafeScheme(URI.create(downloadUrl));
        } catch (Exception e) {
            StagedDownload.bestEffortDelete(stagedPath);
            return new DownloadFailed("download: " + e.getMessage());
        }
        var req = new Request.Builder().url(downloadUrl).get().build();
        return switch (StagedDownload.fetch(DOWNLOAD_CLIENT, req, stagedPath)) {
            case StagedDownload.Ok _ -> null;
            case StagedDownload.TooBig(long size) -> new SizeExceeded(size, MAX_FILE_BYTES);
            case StagedDownload.Redirect(int code, _) -> new DownloadFailed("download HTTP " + code);
            case StagedDownload.HttpError(int code) -> new DownloadFailed("download HTTP " + code);
            case StagedDownload.Failed(String message) -> new DownloadFailed("download: " + message);
        };
    }

}
