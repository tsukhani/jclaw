package agents;

import services.EventLogger;

import java.util.List;

/**
 * JCLAW-976: strips text that would forge a prompt-section boundary out of content JClaw
 * injects into the system prompt.
 *
 * <p>JClaw frames recalled memories as untrusted reference data, which handles a malicious
 * memory's <em>content</em>. It does not handle a memory whose text imitates the framing
 * itself: those lines land inside the system message, where nothing distinguishes them from
 * JClaw's own.
 *
 * <p>Two classes of fence, and the machine-readable one is the sharper of the two:
 *
 * <ul>
 *   <li>{@link SystemPromptAssembler#CACHE_BOUNDARY_MARKER} and
 *       {@link SystemPromptAssembler#CORE_MEMORY_BOUNDARY_MARKER} are parsed, not just read.
 *       {@code OpenRouterProvider.splitIntoCachedBlocks} locates them with {@code indexOf},
 *       and core memories are rendered ABOVE the real cache boundary — so a marker inside a
 *       core memory's text is found FIRST and the prompt is split at the attacker's chosen
 *       point instead of JClaw's. Everything past it silently stops being cacheable.
 *       {@code LlmProvider.stripCacheBoundaryMarker} cannot help: it runs after
 *       {@code applyCacheDirectives}, so the split has already happened.</li>
 *   <li>The section headings are read by the model rather than parsed, so forging one is a
 *       weaker attack — but it is still a claim of stored-fact authority made from inside
 *       the block that tells the model those lines are authoritative.</li>
 * </ul>
 *
 * <p>Deliberately NOT applied to inbound user messages. All four providers send
 * role-separated JSON and {@code userMessage} never enters the system prompt — it is only
 * the recall query — so a user turn cannot forge a system-message boundary the way injected
 * content can. Stripping markdown from someone's own words to defend against that would do
 * visible damage for a threat role separation already answers. The machine markers ARE
 * scrubbed everywhere, at send time, because a literal marker reaching the model is the
 * thing {@code stripCacheBoundaryMarker} already exists to prevent.
 */
public final class PromptFenceScrubber {

    private PromptFenceScrubber() {}

    /** Parsed markers: forging one changes how the prompt is cut, not just how it reads. */
    static final List<String> MARKERS = List.of(
            SystemPromptAssembler.CACHE_BOUNDARY_MARKER,
            SystemPromptAssembler.CORE_MEMORY_BOUNDARY_MARKER);

    /** Headings the model is told to treat as authoritative section boundaries. */
    private static final List<String> HEADINGS = List.of(
            SystemPromptAssembler.CORE_MEMORY_HEADING,
            SystemPromptAssembler.RECALL_HEADING);

    /**
     * Remove every fence from {@code text} before it is injected into the system prompt.
     * A scrub is logged, never silent — a stripped fence is a signal worth seeing.
     *
     * @param source what is being scrubbed, for the log line (e.g. {@code "core memory 42"})
     */
    public static String scrubForInjection(String text, String source) {
        if (text == null || text.isEmpty()) return text;
        var out = text;
        for (var fence : MARKERS) out = out.replace(fence, "");
        for (var heading : HEADINGS) out = out.replace(heading, "");
        if (!out.equals(text)) {
            EventLogger.warn("memory", null, null,
                    ("Stripped a forged prompt-section fence from %s — stored text imitated JClaw's "
                            + "own memory framing").formatted(source));
        }
        return out;
    }
}
