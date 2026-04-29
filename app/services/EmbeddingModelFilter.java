package services;

import java.util.regex.Pattern;

/**
 * ID-pattern-based filter that decides whether a model id looks like a
 * non-chat model (embedding, audio transcription, image generation) based on
 * naming conventions used by common providers.
 *
 * <p>JCLAW-183 Tier 3 fallback: used by {@link ModelDiscoveryService} for
 * providers whose {@code /v1/models} response exposes no type or capability
 * metadata (OpenAI, Groq, vanilla OpenAI-compat). Tier 1 providers — Ollama
 * via the {@code capabilities} array on {@code /api/show}, LM Studio via the
 * {@code type} field on {@code /api/v0/models} — use structured signals
 * instead, and the discovery code paths consult those before falling through
 * to this heuristic.
 *
 * <p>The patterns are high-precision in practice (embedding-model publishers
 * tend to use explicit naming) but not exhaustive. False positives can be
 * revisited if a real chat model ever gets caught — adding an exception
 * here is cheaper than letting non-chat models slip into the discover panel
 * and break agent bindings at the first chat call.
 */
public final class EmbeddingModelFilter {

    private EmbeddingModelFilter() {}

    /**
     * Match {@code "embed"} (or {@code "embedding"} / {@code "embeddings"})
     * adjacent to a hyphen or slash boundary anywhere in the id. Anchoring to
     * a boundary avoids matching mid-word tokens that happen to contain the
     * substring (e.g. an unrelated model name with {@code "embedded"} as a
     * descriptive segment would still need a boundary on each side).
     */
    private static final Pattern EMBED_AT_BOUNDARY = Pattern.compile(
            "(^|[/\\-])embed(ding)?(s)?[/\\-]");

    /**
     * Return true if the id matches a known non-chat naming pattern and
     * should be hidden from the discover-models flow.
     */
    public static boolean isLikelyNonChat(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        var lower = modelId.toLowerCase();
        var lastSegment = lower.contains("/")
                ? lower.substring(lower.lastIndexOf('/') + 1)
                : lower;

        // Embedding-model families
        if (lastSegment.startsWith("text-embedding-")) return true;
        if (lastSegment.startsWith("nomic-embed-")) return true;
        if (lastSegment.startsWith("bge-")) return true;
        if (lastSegment.startsWith("e5-")) return true;
        if (lastSegment.startsWith("jina-embeddings-")) return true;
        if (EMBED_AT_BOUNDARY.matcher(lower).find()) return true;

        // Audio and image-generation models served alongside chat on /v1/models
        // (OpenAI hosts whisper/dall-e/tts under the same model catalog).
        if (lastSegment.startsWith("whisper-")) return true;
        if (lastSegment.startsWith("dall-e-")) return true;
        if (lastSegment.startsWith("tts-")) return true;

        return false;
    }
}
