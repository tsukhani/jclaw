package channels;

/**
 * Inbound file attachment extracted from a Telegram Update before the actual
 * bytes are downloaded (JCLAW-136). The webhook handler returns the 200 fast;
 * a virtual thread then resolves each {@code telegramFileId} via the Bot API
 * {@code getFile} call, streams the payload into workspace staging, and
 * produces an {@link services.AttachmentService.Input} the runner can feed
 * into the existing JCLAW-25 multimodal assembly path. Extracted from
 * {@code TelegramChannel} in JCLAW-151.
 *
 * @param telegramFileId    opaque Telegram file id used with the Bot API
 *                          {@code getFile} call to resolve a download URL
 * @param suggestedFilename filename suggested by Telegram (may be null /
 *                          empty for voice notes etc.)
 * @param mimeType          MIME type reported by Telegram
 * @param sizeBytes         size in bytes reported by Telegram
 * @param kind              derived at parse time from which Telegram
 *                          field the attachment came from (photo →
 *                          IMAGE, voice/audio → AUDIO, document/video →
 *                          FILE). Authoritative for the inbound
 *                          modality gate; the stored MessageAttachment
 *                          row's kind is re-sniffed from disk by
 *                          {@code finalizeAttachment}.
 */
public record PendingAttachment(String telegramFileId,
                                String suggestedFilename,
                                String mimeType,
                                long sizeBytes,
                                String kind) {}
