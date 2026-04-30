package channels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import services.AgentService;
import services.AttachmentService;
import utils.Filenames;
import utils.HttpFactories;
import utils.Strings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;

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

    /** Telegram Bot API caps bot-downloadable files at 20 MiB. */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final Duration GETFILE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(90);

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
                                  TelegramChannel.PendingAttachment pending,
                                  String agentName) {
        return download(botToken, pending, agentName,
                "https://api.telegram.org/bot" + botToken,
                "https://api.telegram.org/file/bot" + botToken);
    }

    /**
     * Overload exposing the API and file base URLs for tests (mock HTTP
     * server). Production callers use {@link #download(String, TelegramChannel.PendingAttachment, String)}.
     * Public because jclaw tests live in the default package and can't see
     * package-private channel methods.
     */
    public static Result download(String botToken,
                                  TelegramChannel.PendingAttachment pending,
                                  String agentName,
                                  String apiBaseUrl,
                                  String fileBaseUrl) {
        JsonObject getFileResp;
        try {
            var url = apiBaseUrl + "/getFile?file_id=" + pending.telegramFileId();
            var req = new okhttp3.Request.Builder().url(url).get().build();
            try (var resp = HttpFactories.general(GETFILE_TIMEOUT).newCall(req).execute()) {
                if (resp.code() != 200) {
                    return new DownloadFailed("getFile HTTP " + resp.code());
                }
                var body = resp.body() != null ? resp.body().string() : "";
                getFileResp = JsonParser.parseString(body).getAsJsonObject();
            }
        } catch (Exception e) {
            return new DownloadFailed("getFile: " + e.getMessage());
        }

        if (!getFileResp.has("ok") || !getFileResp.get("ok").getAsBoolean()) {
            return new DownloadFailed("getFile error: " + Strings.truncate(getFileResp.toString(), 200));
        }
        var result = getFileResp.getAsJsonObject("result");
        String filePath = result.has("file_path") && !result.get("file_path").isJsonNull()
                ? result.get("file_path").getAsString() : null;
        if (filePath == null || filePath.isBlank()) {
            return new DownloadFailed("getFile response missing file_path");
        }
        long reportedSize = result.has("file_size") && !result.get("file_size").isJsonNull()
                ? result.get("file_size").getAsLong() : pending.sizeBytes();
        if (reportedSize > MAX_FILE_BYTES) {
            return new SizeExceeded(reportedSize, MAX_FILE_BYTES);
        }

        var uuid = UUID.randomUUID().toString();
        var extension = Filenames.extensionOf(pending.suggestedFilename(), filePath);
        var leaf = uuid + extension;

        var stagingDir = AgentService.acquireWorkspacePath(agentName, "attachments/staging");
        try {
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            return new DownloadFailed("Failed to create staging dir: " + e.getMessage());
        }
        Path stagedPath = AgentService.acquireContained(stagingDir, leaf);

        try {
            var downloadUrl = fileBaseUrl + "/" + filePath;
            var req = new okhttp3.Request.Builder().url(downloadUrl).get().build();
            try (var resp = HttpFactories.general(DOWNLOAD_TIMEOUT).newCall(req).execute()) {
                if (resp.code() != 200) {
                    return new DownloadFailed("download HTTP " + resp.code());
                }
                if (resp.body() == null) {
                    return new DownloadFailed("download body absent");
                }
                try (InputStream in = resp.body().byteStream()) {
                    Files.copy(in, stagedPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            long actualSize = Files.size(stagedPath);
            if (actualSize > MAX_FILE_BYTES) {
                try { Files.deleteIfExists(stagedPath); } catch (IOException _) {}
                return new SizeExceeded(actualSize, MAX_FILE_BYTES);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(stagedPath); } catch (IOException _) {}
            return new DownloadFailed("download: " + e.getMessage());
        }

        var originalFilename = pending.suggestedFilename() != null
                ? pending.suggestedFilename()
                : "telegram-" + pending.telegramFileId() + extension;
        return new Ok(new AttachmentService.Input(
                uuid, originalFilename, pending.mimeType(), reportedSize, pending.kind()));
    }

}
