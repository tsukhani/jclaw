package memory;

import services.ConfigService;

import java.util.Locale;
import java.util.Set;

/**
 * Heuristic attention gate for memory auto-capture (JCLAW-39). A cheap,
 * deterministic filter that decides whether a completed turn is even worth
 * sending to the (LLM-backed) extractor, keeping trivial turns — greetings,
 * bare acknowledgements, empty input — off the extraction path so the system
 * doesn't pay an LLM call per turn.
 *
 * <p>Deliberately PERMISSIVE. The LLM extractor is the real signal/noise filter;
 * the gate only rejects the obvious. An over-aggressive gate silently drops
 * rare-but-important facts, which is the costlier failure mode — so anything
 * ambiguous proceeds.
 */
public final class MemoryAttentionGate {

    private MemoryAttentionGate() {}

    public record Decision(boolean proceed, String reason) {
        static Decision ok() { return new Decision(true, null); }
        static Decision skip(String reason) { return new Decision(false, reason); }
    }

    // Purely social one-liners. Kept small on purpose: only used to skip turns
    // whose user message is BOTH short and nothing but one of these.
    private static final Set<String> TRIVIAL = Set.of(
            "hi", "hello", "hey", "yo", "thanks", "thank you", "thx", "ty",
            "ok", "okay", "k", "kk", "cool", "nice", "great", "got it", "sure",
            "yes", "yeah", "no", "yep", "nope", "bye", "goodbye",
            "good morning", "good night", "lol", "haha", "np");

    public static Decision evaluate(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Decision.skip("empty_user_message");
        }
        var user = userMessage.strip();
        int minChars = ConfigService.getInt("memory.autocapture.gate.minChars", 20);

        // A short user turn that is nothing but a greeting/acknowledgement carries
        // no durable content worth an extraction call.
        if (user.length() < minChars && isTrivial(user)) {
            return Decision.skip("trivial");
        }
        return Decision.ok();
    }

    private static boolean isTrivial(String user) {
        var normalized = user.toLowerCase(Locale.ROOT)
                .replaceAll("\\p{Punct}", "")
                .strip();
        return normalized.isEmpty() || TRIVIAL.contains(normalized);
    }
}
