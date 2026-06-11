package channels;

/**
 * Inbound Slack file extracted from a message event's {@code files[]} array
 * before the bytes are downloaded (JCLAW-344). The webhook returns 200 fast; a
 * virtual thread then streams each {@code url_private_download} into workspace
 * staging via {@link SlackFileDownloader}, producing an
 * {@link services.AttachmentService.Input} the runner feeds into the existing
 * multimodal assembly path. Mirrors {@link PendingAttachment} (the Telegram
 * equivalent).
 *
 * @param id                 Slack file id — used only for logging / a fallback
 *                           filename when {@code name} is absent
 * @param urlPrivateDownload the authenticated direct-download URL (preferred over
 *                           {@code url_private}, which serves an HTML page)
 * @param name               filename Slack reports (usually carries the extension;
 *                           may be null for some clips)
 * @param mimeType           MIME type Slack declares on the file object
 * @param sizeBytes          size in bytes Slack reports (used for the early cap)
 * @param subtype            Slack file subtype (e.g. {@code slack_audio}) — drives
 *                           the video/* -> audio/* remap so voice clips transcribe
 */
public record SlackPendingFile(String id,
                               String urlPrivateDownload,
                               String name,
                               String mimeType,
                               long sizeBytes,
                               String subtype) {}
