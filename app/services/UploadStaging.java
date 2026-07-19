package services;

import models.Agent;
import models.MessageAttachment;
import play.data.Upload;
import utils.ApiResponses;
import utils.WorkspacePathGuard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static utils.TikaHolder.TIKA;

/**
 * The single, shared multipart-upload staging path (JCLAW-765). Extracted from
 * {@code ApiChatController} so the chat upload endpoint and the App→Agent invoke
 * endpoint enforce the <em>same</em> size/type limits and containment — one code
 * path, no second staging surface that could drift into a bypass.
 *
 * <p>{@link #stage} validates the batch, sniffs each file's MIME with Tika
 * (authoritative over the browser Content-Type), picks the per-kind size cap from
 * {@link UploadLimits}, and copies the bytes into the agent's
 * {@code attachments/staging/} dir under {@link WorkspacePathGuard} containment. It
 * returns {@link AttachmentService.Input} records — the same shape the agent-run
 * pipeline finalizes into {@code MessageAttachment} rows during the run.
 */
public final class UploadStaging {

    private UploadStaging() {}

    /**
     * Candidate extensions probed against {@link play.libs.MimeTypes} (via
     * {@link MimeExtensions}) when an upload's original filename carried no
     * extension — data lives in Play's bundled {@code mime-types.properties} plus
     * the {@code mimetype.*} overrides in {@code conf/application.conf}.
     */
    private static final String[] EXTENSION_CANDIDATES = {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg",
            "avif", "heic", "heif",
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "weba",
            "pdf", "txt", "md", "csv", "json", "html", "xml",
            "zip", "tar", "gz",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    };

    /**
     * Validate + stage every uploaded file, returning the staged inputs. Fails
     * closed (4xx via {@link ApiResponses#error}) on an empty batch, too many
     * files, an invalid file, a bad filename, an oversized file, or a containment
     * escape — reusing {@link UploadLimits} so caps stay identical to chat uploads.
     */
    public static List<AttachmentService.Input> stage(Agent agent, Upload[] files) {
        validate(files);
        var stagingDir = acquireStagingDir(agent);
        var inputs = new ArrayList<AttachmentService.Input>();
        try {
            Files.createDirectories(stagingDir);
            for (var upload : files) {
                inputs.add(stageOne(stagingDir, upload));
            }
        } catch (IOException e) {
            ApiResponses.error(500, ApiResponses.INTERNAL_ERROR, "Upload failed: " + e.getMessage());
        }
        return inputs;
    }

    private static void validate(Upload[] files) {
        if (files == null || files.length == 0) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "No files uploaded");
        }
        int maxFiles = UploadLimits.maxFiles();
        if (files.length > maxFiles) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Too many files (max " + maxFiles + ")");
        }
        for (var u : files) {
            if (u == null || u.asFile() == null || !u.asFile().exists()) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid file upload");
            }
        }
    }

    private static Path acquireStagingDir(Agent agent) {
        try {
            return AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        } catch (SecurityException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid upload target");
            return null; // unreachable — ApiResponses.error() throws
        }
    }

    private static AttachmentService.Input stageOne(Path stagingDir, Upload upload) throws IOException {
        var f = upload.asFile();
        var safeName = sanitizeFilename(upload.getFileName() != null ? upload.getFileName() : f.getName());
        if (safeName.isEmpty()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid filename: " + upload.getFileName());
        }
        var sniffedMime = sniffMime(f, upload.getContentType());
        var kind = MessageAttachment.kindForMime(sniffedMime);
        enforceCap(f, kind, upload.getFileName());

        var uuid = UUID.randomUUID().toString();
        var ext = extensionFromFilename(safeName);
        if (ext.isEmpty()) ext = canonicalExtensionForMime(sniffedMime);
        var onDiskName = uuid + (ext.isEmpty() ? "" : "." + ext);

        var target = acquireContainedOr400(stagingDir, onDiskName, upload.getFileName());
        Files.copy(f.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

        return new AttachmentService.Input(uuid, safeName, sniffedMime, Files.size(target), kind);
    }

    /**
     * Tika reads file magic bytes — authoritative over the browser-declared
     * Content-Type. The one exception is WebM/Matroska audio-vs-video, resolved by
     * {@link MatroskaTracks#disambiguate}.
     */
    private static String sniffMime(File f, String browserMime) throws IOException {
        var sniffedMime = TIKA.detect(f);
        return MatroskaTracks.disambiguate(sniffedMime, browserMime, f.toPath());
    }

    private static void enforceCap(File f, String kind, String originalName) {
        var cap = UploadLimits.forKind(kind);
        if (f.length() > cap) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "%s too large: %s (max %d MB for %s)"
                    .formatted(UploadLimits.displayName(kind), originalName, cap / (1024 * 1024),
                            UploadLimits.displayName(kind)));
        }
    }

    private static Path acquireContainedOr400(Path stagingDir, String leaf, String originalName) {
        try {
            return WorkspacePathGuard.acquireContained(stagingDir, leaf);
        } catch (SecurityException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Invalid filename: " + originalName);
            return null; // unreachable — ApiResponses.error() throws
        }
    }

    private static String extensionFromFilename(String safeName) {
        var dot = safeName.lastIndexOf('.');
        if (dot < 0 || dot == safeName.length() - 1) return "";
        return safeName.substring(dot + 1).toLowerCase();
    }

    private static String canonicalExtensionForMime(String mime) {
        return MimeExtensions.forMime(mime, EXTENSION_CANDIDATES);
    }

    private static String sanitizeFilename(String name) {
        if (name == null) return "";
        var base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.strip();
        while (base.startsWith(".")) base = base.substring(1);
        var cleaned = base.replaceAll("[^A-Za-z0-9._\\- ]", "_");
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }
}
