package memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.Set;

/**
 * Facts just forgotten on operator request, so auto-capture does not immediately
 * re-learn them (JCLAW-919).
 *
 * <p>Without this, {@code forget} does not work at all. The turn that asks to forget
 * something necessarily states it — "forget what I told you about X" contains X — and
 * capture runs on that same turn, extracts X, and stores it again. Observed live: a
 * forget deleted the memory and capture recreated it eleven seconds later, under a new
 * id, with identical text.
 *
 * <p>Deliberately a short window rather than a permanent tombstone. "Forget that" means
 * drop what you know now, not refuse to learn it ever again; if the operator brings the
 * same fact up in a later conversation, re-learning it is correct. The window only has to
 * outlive the turn that requested the forget and any immediate follow-up about it.
 *
 * <p>In-memory and per-process: a restart clears it, which is harmless — the window is
 * minutes and the memory it was protecting is already deleted.
 */
public final class MemoryForgetLog {

    private MemoryForgetLog() {}

    /** Long enough to cover the requesting turn and a follow-up, short enough not to be a policy. */
    private static final Duration TTL = Duration.ofMinutes(10);

    /**
     * One forgotten fact. Keyed per text rather than per agent (JCLAW-971) so each note carries
     * its own TTL: a per-agent list entry is written once and mutated in place, so Caffeine never
     * saw a second write and the whole agent's window expired on the FIRST forget's clock.
     *
     * <p>{@code expireAfterWrite}, not {@code expireAfterAccess}: capture probes this on every
     * turn, so refreshing on read would keep an entry alive for as long as the agent stays busy —
     * turning the deliberately short window into the permanent tombstone this class rejects.
     */
    private record Forgotten(String agentId, String text) {}

    /** Swappable clock so a test can drive the window's expiry instead of sleeping ten
     *  minutes; mirrors the {@code setEmbedderForTest} / {@code setIndexPathForTest} seams. */
    private static volatile Ticker ticker = Ticker.systemTicker();

    private static final Cache<Forgotten, Boolean> FORGOTTEN = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .ticker(() -> ticker.read())
            .maximumSize(1000)
            .build();

    /** Test-only: install (or clear with {@code null}) a controllable clock. */
    public static void setTickerForTest(Ticker t) {
        ticker = t == null ? Ticker.systemTicker() : t;
    }

    /** Note that {@code text} was just forgotten for {@code agentId}. */
    public static void noteForgotten(String agentId, String text) {
        if (agentId == null || text == null || text.isBlank()) return;
        FORGOTTEN.put(new Forgotten(agentId, text), Boolean.TRUE);
    }

    /**
     * Fraction of the forgotten fact's own content tokens that {@code candidate} repeats.
     *
     * <p>Directional on purpose, and this is the whole point of the rule. Dedup asks
     * whether two texts are the <em>same fact</em> and so divides by the smaller token
     * set; the question here is whether a candidate <em>restates</em> what was just
     * deleted, however much extra framing it wraps around it. UAT caught the difference:
     * forgetting "the user's son Arjun plays the cello" produced the memory "the user
     * wants the memory that Arjun plays the cello to be forgotten", which scored 0.75
     * against dedup's 0.82 floor and was stored — putting the forgotten fact back in the
     * store, inside the record of its own deletion, and retrievable at rank 3.
     */
    private static double restatement(Set<String> forgotten, Set<String> candidate) {
        if (forgotten.isEmpty()) return 0.0;
        return (double) forgotten.stream().filter(candidate::contains).count() / forgotten.size();
    }

    /**
     * How much of a forgotten fact a candidate may repeat before it counts as re-learning
     * it. Below dedup's floor because over-suppressing inside a ten-minute window costs a
     * fact that can be re-stated, while under-suppressing defeats an explicit forget.
     */
    private static final double RESTATEMENT_THRESHOLD = 0.6;

    /**
     * Whether {@code text} states something forgotten within the window. Two tests: the
     * duplicate rule capture dedups on, for a near-identical re-extraction, and the
     * directional {@link #restatement} above, for a candidate that buries the forgotten
     * fact inside other words.
     */
    public static boolean recentlyForgotten(String agentId, String text) {
        if (agentId == null || text == null) return false;
        var probe = MemorySimilarity.Tokens.of(text);
        var probeContent = MemorySimilarity.contentTokens(text);
        return FORGOTTEN.asMap().keySet().stream()
                .filter(f -> f.agentId().equals(agentId))
                .anyMatch(f -> MemorySimilarity.isDuplicate(
                            probe, MemorySimilarity.Tokens.of(f.text()), 0.85, 0.82, 0.5)
                        || restatement(MemorySimilarity.contentTokens(f.text()), probeContent)
                                >= RESTATEMENT_THRESHOLD);
    }

    /**
     * Drop any record matching {@code text}, so an explicit re-store takes effect
     * immediately. "Forget X" then "actually, remember X" has to work inside the window,
     * or the second instruction silently does nothing.
     */
    public static void clearMatching(String agentId, String text) {
        if (agentId == null || text == null) return;
        var probe = MemorySimilarity.Tokens.of(text);
        FORGOTTEN.asMap().keySet().removeIf(f -> f.agentId().equals(agentId)
                && MemorySimilarity.isDuplicate(
                        probe, MemorySimilarity.Tokens.of(f.text()), 0.85, 0.82, 0.5));
    }

    /** play1 runs tests concurrently in one JVM, so a test that records must reset. */
    public static void clearForTest() {
        FORGOTTEN.invalidateAll();
    }
}
