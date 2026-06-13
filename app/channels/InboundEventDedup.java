package channels;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * Short-TTL dedup of redelivered inbound channel events (JCLAW-357, generalized
 * in JCLAW-446). A platform redelivers an event when it doesn't get a fast ack —
 * Slack retries when it doesn't see a 200 within ~3 s, Meta's WhatsApp Cloud API
 * retries its webhook, and a reconnecting socket can replay recent messages — so
 * the same message can arrive several times. Keyed by a caller-supplied identity
 * (Slack: {@code channel:ts} / {@code event_id}; WhatsApp: the message id), this
 * records the first sighting and reports later ones as duplicates, so the agent
 * processes each message exactly once.
 *
 * <p>The cache is bounded (size + a 5-minute TTL that comfortably covers the
 * platforms' retry windows). Distinct messages get distinct keys, so a legitimate
 * repeat of the same text is never suppressed — only an actual redelivery of the
 * same event is.
 *
 * <p>Channel-agnostic on purpose: each channel computes its own key, the cache is
 * shared. (Not named {@code MessageDedup} — that would collide with the unrelated
 * {@link agents.MessageDeduplicator}, which dedups image/link markdown.)
 */
public final class InboundEventDedup {

    private InboundEventDedup() {}

    private static final Cache<String, Boolean> SEEN = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    /**
     * Record {@code key} and report whether this is its FIRST sighting.
     *
     * @return true when {@code key} hadn't been seen (now recorded) — process the event;
     *         false when it's a duplicate — drop it. A null/blank key can't be deduped and
     *         returns true (process), since dropping an unidentifiable event is worse than
     *         occasionally double-processing one.
     */
    public static boolean firstSeen(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        return SEEN.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }
}
