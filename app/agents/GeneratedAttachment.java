package agents;

/**
 * An attachment a tool produced during a turn (JCLAW-228 images; JCLAW-562
 * audio clips), carried back through {@link ToolRegistry.ToolResult} so the
 * tool-call commit path can inline it on the assistant turn via
 * {@link AgentExecutionSink#appendAssistantMessage(String, String, java.util.List)} →
 * {@code services.AttachmentService.persistGeneratedAttachment}. Kept
 * transport-agnostic (raw bytes, not a staged file or a data URL) so the tool
 * stays free of persistence concerns.
 *
 * @param bytes    the produced content bytes
 * @param mimeType the content type (e.g. {@code image/png}, {@code audio/wav})
 * @param metadata small JSON blob (prompt, model, labels) stored on the attachment row
 * @param filename display filename for the chat chip, or {@code null} for the
 *                 default {@code generated-<timestamp>} name — the
 *                 so the user can tell them apart
 */
public record GeneratedAttachment(byte[] bytes, String mimeType, String metadata, String filename) {

    /** Original three-arg shape — default generated filename. */
    public GeneratedAttachment(byte[] bytes, String mimeType, String metadata) {
        this(bytes, mimeType, metadata, null);
    }
}
