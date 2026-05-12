package controllers;

import models.MessageAttachment;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Download endpoint for persisted chat-message attachments (JCLAW-279).
 * Resolves the storage path via the same workspace-bounded resolver used
 * for agent workspace files, sets a content-disposition appropriate for
 * the attachment kind (inline for image/audio so the browser previews,
 * attachment for everything else so the browser downloads), and streams
 * the bytes.
 */
@With(AuthCheck.class)
public class ApiAttachmentsController extends Controller {

    /** GET /api/attachments/{uuid} — Serve the raw bytes for a persisted
     *  attachment. */
    public static void download(String uuid) {
        var att = MessageAttachment.findByUuid(uuid);
        if (att == null) { notFound(); return; }

        // storagePath is workspace-relative and looks like
        // "<agentName>/attachments/<conversationId>/<uuid>.<ext>". Strip the
        // agent-name prefix so acquireWorkspacePath can do its lexical +
        // canonical check inside the workspace root.
        var agentName = att.message.conversation.agent.name;
        var prefix = agentName + "/";
        if (!att.storagePath.startsWith(prefix)) notFound();
        var relPath = att.storagePath.substring(prefix.length());

        java.nio.file.Path path;
        try {
            path = AgentService.acquireWorkspacePath(agentName, relPath);
        } catch (SecurityException e) {
            forbidden();
            return;
        }
        var file = path.toFile();
        if (!file.exists() || !file.isFile()) notFound();

        // Inline for media that browsers can render natively (image, audio);
        // attachment otherwise so the user gets a download prompt. Matches
        // the convention used by ApiAgentsController.serveWorkspaceFile.
        var disposition = (att.isImage() || att.isAudio()) ? "inline" : "attachment";
        response.setHeader("Content-Type", att.mimeType);
        response.setHeader("Content-Disposition",
                disposition + "; filename=\"" + asciiSafeFilename(att.originalFilename) + "\""
                + "; filename*=UTF-8''" + percentEncodeFilename(att.originalFilename));
        response.setHeader("Cache-Control", "private, max-age=300");
        renderBinary(file);
    }

    /**
     * Strip non-ASCII characters from a filename for the legacy {@code filename="..."}
     * directive — RFC 6266 requires this to be a pure ASCII fallback. The
     * companion {@code filename*=UTF-8''...} directive carries the full
     * UTF-8 name; modern browsers prefer the latter.
     */
    private static String asciiSafeFilename(String name) {
        if (name == null) return "file";
        var sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((c >= 0x20 && c < 0x7F && c != '"' && c != '\\') ? c : '_');
        }
        var safe = sb.toString();
        return safe.isEmpty() ? "file" : safe;
    }

    /** Percent-encode for the RFC 6266 {@code filename*=UTF-8''...} directive. */
    private static String percentEncodeFilename(String name) {
        if (name == null) return "file";
        // URLEncoder encodes spaces as '+', which is wrong for RFC 5987 token
        // syntax (must be %20). Post-process to fix.
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
