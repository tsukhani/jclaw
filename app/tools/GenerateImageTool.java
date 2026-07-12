package tools;

import agents.GeneratedAttachment;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.MessageAttachment;
import services.AttachmentService;
import services.Tx;
import services.imagegen.ImageGenerationException;
import services.imagegen.ImageGenerationRouter;
import services.imagegen.ImageGenerationService;
import utils.JsonArgs;

import java.util.List;
import java.util.Map;

/**
 * {@code generate_image} (JCLAW-228): generate an image from a text prompt via the configured
 * {@code services.imagegen.ImageGenerationService} (Settings → Image Generation). The produced bytes
 * ride back on the {@link ToolRegistry.ToolResult}; the tool-call commit path
 * ({@code agents.ParallelToolExecutor}) inlines them on the assistant turn that called the tool as a
 * {@code generated=true} {@code MessageAttachment} (JCLAW-227), so the user sees the image in chat the
 * same way an uploaded one renders.
 *
 * <p>Default-OFF per agent ({@code ToolRegistry.computeDisabledTools}) — image generation can cost
 * money / hit rate limits, so an operator opts each agent in via the agent editor.
 */
public class GenerateImageTool implements ToolRegistry.Tool {

    private static final String ARG_PROMPT = "prompt";
    private static final String ARG_WIDTH = "width";
    private static final String ARG_HEIGHT = "height";
    private static final String ARG_ASPECT = "aspect_ratio";
    private static final String ARG_USE_REFERENCE = "use_reference_image";

    @Override public String name() { return "generate_image"; }
    @Override public String category() { return "Utilities"; }
    @Override public String icon() { return "image"; }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("generate",
                "Produce an image from a text prompt via the configured backend and show it inline"));
    }

    @Override
    public String description() {
        return """
                Generate an image from a text prompt and show it to the user inline. Provide a \
                detailed 'prompt'. Optionally set 'width' and 'height' in pixels, or an 'aspect_ratio' \
                (1:1, 16:9, 9:16) used when width/height are omitted. To restyle or match the look of \
                an image the user uploaded in this conversation, set 'use_reference_image' true \
                (image-to-image style transfer / visual consistency); the most recently uploaded \
                image is used as the reference. The image is produced by the operator-configured \
                backend (OpenAI gpt-image-1, Black Forest Labs Flux, or a self-hosted engine) and \
                shown to the user as part of your reply.""";
    }

    @Override
    public String summary() {
        return "Generate an image from a text prompt and show it to the user inline.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_PROMPT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "A detailed description of the image to generate."),
                        ARG_WIDTH, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Optional width in pixels."),
                        ARG_HEIGHT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Optional height in pixels."),
                        ARG_ASPECT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("1:1", "16:9", "9:16"),
                                SchemaKeys.DESCRIPTION, "Optional aspect ratio, used when width/height are omitted."),
                        ARG_USE_REFERENCE, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                                SchemaKeys.DESCRIPTION, "When true, use the most recent image the user uploaded in this "
                                        + "conversation as a reference for style transfer / visual consistency "
                                        + "(image-to-image). Defaults to false (text-to-image).")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_PROMPT)
        );
    }

    /** One outbound (possibly long-polling) generation per call; keep sequential within a round. */
    @Override public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent agent) {
        // execute() is the text-only fallback; the dispatcher uses executeRich() (which also carries
        // the produced image). Delegating keeps a single code path.
        return executeRich(argsJson, agent).text();
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException _) {
            return ToolRegistry.ToolResult.text("Error: invalid arguments for generate_image.");
        }
        var prompt = JsonArgs.optString(args, ARG_PROMPT);
        if (prompt == null || prompt.isBlank()) {
            return ToolRegistry.ToolResult.text("Error: 'prompt' is required.");
        }

        var serviceOpt = ImageGenerationRouter.configuredService();
        if (serviceOpt.isEmpty()) {
            return ToolRegistry.ToolResult.text(
                    "Image generation is not configured. Ask the operator to enable a provider in "
                            + "Settings → Image Generation.");
        }

        // JCLAW-694: optional image-to-image reference. When the model asks to reuse the user's
        // uploaded image, resolve the most recent non-generated image in this conversation to raw
        // bytes. Backends that don't yet support references degrade to text-to-image (the default
        // ImageGenerationService.generate override ignores it).
        ImageGenerationService.ReferenceImage reference;
        try {
            reference = resolveReferenceImage(args);
        } catch (ReferenceUnavailable e) {
            return ToolRegistry.ToolResult.text(e.getMessage());
        }

        var dims = resolveDimensions(args);
        try {
            // No model override from here: each provider client resolves its OWN
            // model from its provider-scoped config (imagegen.<provider>.model) →
            // built-in default. A single shared key used to leak one provider's
            // model id into another after a Settings provider switch — e.g. a
            // Replicate slug sent to OpenAI, which 400s with "model does not exist".
            var image = serviceOpt.get().generate(prompt, null, dims[0], dims[1], reference);
            var metadata = buildMetadata(prompt, image.generatedBy(), dims[0], dims[1]);
            // The image is delivered out-of-band (raw bytes -> generated attachment, rendered inline by
            // the chat UI); the model never receives its URL. Say so explicitly: a model that
            // "helpfully" re-embeds the image with markdown/HTML has to invent a URL, which resolves to
            // nothing and renders as a broken-image link in the reply. Telling it the image is already
            // shown removes the incentive to embed.
            var text = "Image generated and displayed to the user inline. It is already shown — do not "
                    + "re-embed or link it in your reply (no markdown image syntax, no HTML <img> tag); "
                    + "just acknowledge or describe it in words.";
            return ToolRegistry.ToolResult.withImage(text, null,
                    new GeneratedAttachment(image.bytes(), image.mimeType(), metadata));
        } catch (ImageGenerationException e) {
            return ToolRegistry.ToolResult.text("Image generation failed: " + e.getMessage());
        }
    }

    /** Signals that the model requested a reference image but none is usable — surfaced to the
     *  agent as a tool-visible message so it can ask the user to attach one. */
    private static final class ReferenceUnavailable extends RuntimeException {
        ReferenceUnavailable(String message) { super(message); }
    }

    /**
     * JCLAW-694: resolve the image-to-image reference when {@code use_reference_image} is set.
     * Returns null (text-to-image) when the flag is absent/false. Uses the current conversation
     * (via {@link ToolContext}) to find the most recent uploaded image and reads its bytes.
     */
    private static ImageGenerationService.ReferenceImage resolveReferenceImage(JsonObject args) {
        if (!JsonArgs.optBool(args, ARG_USE_REFERENCE)) return null;
        var conversationId = ToolContext.conversationId();
        // Tools execute on the dispatcher's virtual threads with no ambient EntityManager, so the
        // attachment lookup + byte read must open their own JPA transaction (JCLAW-694). Returns
        // null when there's no usable upload; the caller turns that into a tool-visible message.
        var reference = Tx.run(() -> {
            var attachment = MessageAttachment.findLatestUploadedImage(conversationId);
            if (attachment == null) return null;
            return new ImageGenerationService.ReferenceImage(
                    AttachmentService.readBytes(attachment), attachment.mimeType);
        });
        if (reference == null) {
            throw new ReferenceUnavailable(
                    "No uploaded image found in this conversation to use as a reference. Ask the user "
                            + "to attach an image, or generate without a reference (set use_reference_image false).");
        }
        return reference;
    }

    /** {width, height} from explicit pixels or an aspect ratio; nulls mean "provider default". */
    private static Integer[] resolveDimensions(JsonObject args) {
        Integer width = JsonArgs.optInteger(args, ARG_WIDTH);
        Integer height = JsonArgs.optInteger(args, ARG_HEIGHT);
        if (width != null && height != null) return new Integer[]{width, height};
        var aspect = JsonArgs.optString(args, ARG_ASPECT);
        if (aspect != null) {
            // True ratios at a Flux-safe scale (each side a multiple of 16; long side 1536). Providers
            // that take raw pixels (local Flux, BFL) render these exactly; Replicate maps them back to its
            // own aspect_ratio label; OpenAI's gpt-image-1 snaps landscape/portrait to its fixed
            // 1536x1024 / 1024x1536 (a 3:2 it can't avoid). The chip shows whatever actually came back.
            return switch (aspect) {
                case "16:9" -> new Integer[]{1536, 864}; // 1536/864 = 16/9 exactly
                case "9:16" -> new Integer[]{864, 1536};
                case "1:1" -> new Integer[]{1024, 1024};
                default -> new Integer[]{width, height};
            };
        }
        return new Integer[]{width, height};
    }

    private static String buildMetadata(String prompt, String generatedBy, Integer width, Integer height) {
        var meta = new JsonObject();
        meta.addProperty(ARG_PROMPT, prompt);
        meta.addProperty("generatedBy", generatedBy);
        if (width != null) meta.addProperty(ARG_WIDTH, width);
        if (height != null) meta.addProperty(ARG_HEIGHT, height);
        return meta.toString();
    }
}
