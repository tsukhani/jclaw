package agents;

/**
 * An image a tool produced during a turn (JCLAW-228), carried back through
 * {@link ToolRegistry.ToolResult} so the tool-call commit path can inline it on the assistant turn
 * via {@link AgentExecutionSink#appendAssistantMessage(String, String, GeneratedAttachment)} →
 * {@code services.AttachmentService.persistGeneratedImage}. Kept transport-agnostic (raw bytes, not a
 * staged file or a data URL) so the tool stays free of persistence concerns.
 *
 * @param bytes    the produced image bytes
 * @param mimeType the image content type (e.g. {@code image/png})
 * @param metadata small JSON blob (prompt, model, provider) stored on the attachment row
 */
public record GeneratedAttachment(byte[] bytes, String mimeType, String metadata) {}
