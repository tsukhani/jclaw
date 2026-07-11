package services.imagegen;

/**
 * Single contract for any image-generation backend in jclaw (JCLAW-225) — the cloud OpenAI / BFL
 * clients (and the local image sidecar in the JCLAW-226 phase) all implement this so the
 * {@code generate_image} tool can pick one via the {@code imagegen.provider} config key without
 * caring about the transport. This is the producing counterpart to
 * {@code services.caption.ImageCaptionService} (which consumes images).
 *
 * <p>Failure surface: implementations <b>throw</b> {@link ImageGenerationException} on failure
 * (bad config, transport error, provider rejection, generation timeout). The caller — the
 * {@code generate_image} tool — logs and returns a typed error to the agent. Deliberately the
 * opposite of the caption path's swallow-and-return-empty convention, because a failed
 * <em>generation</em> is a tool outcome the agent must know about, not a silently-skipped enrichment.
 */
public interface ImageGenerationService {

    /**
     * Generate an image from {@code prompt}. {@code model} may be null/blank to use the backend's
     * configured/default model; {@code width}/{@code height} may be null to use the provider's
     * default size. Returns the raw bytes plus content type; never returns null.
     *
     * @throws ImageGenerationException on any failure
     */
    GeneratedImage generate(String prompt, String model, Integer width, Integer height);

    /**
     * JCLAW-694: generate with an optional reference image for image-to-image / style transfer /
     * visual consistency. {@code referenceImage == null} is exactly the text-to-image path (the
     * 4-arg form). The default ignores the reference and delegates to text-to-image; each backend
     * overrides this as it gains reference support (BFL, Replicate, OpenAI, local — per the
     * JCLAW-693 phase stories), so an unsupported backend degrades to plain generation rather than
     * failing.
     *
     * @throws ImageGenerationException on any failure
     */
    default GeneratedImage generate(String prompt, String model, Integer width, Integer height,
                                    ReferenceImage referenceImage) {
        return generate(prompt, model, width, height);
    }

    /**
     * A produced image: raw bytes, its MIME type (e.g. {@code image/png}), and a short
     * {@code generatedBy} tag like {@code openai:gpt-image-1} used for the attachment's
     * {@code generationMetadata} (JCLAW-227).
     */
    record GeneratedImage(byte[] bytes, String mimeType, String generatedBy) {}

    /**
     * JCLAW-694: a user-supplied reference image (raw bytes + MIME type) fed to an image-to-image
     * backend for style transfer / consistency. Sourced from a recent chat upload via
     * {@code AttachmentService.readBytes}. Each backend encodes it however its API expects
     * (base64 field, multipart part, or an uploaded URL).
     */
    record ReferenceImage(byte[] bytes, String mimeType) {}
}
