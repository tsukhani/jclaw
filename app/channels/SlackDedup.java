package channels;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * JCLAW-357: short-TTL dedup of inbound Slack events. Slack redelivers an event when it
 * doesn't get a 200 ack within ~3 s, so the same message can arrive several times (over
 * either transport). Keyed by the message's identity ({@code channel:ts}, falling back to
 * the envelope's {@code event_id}), this records the first sighting and reports later ones
 * as duplicates, so the agent processes each message exactly once.
 *
 * <p>The cache is bounded (size + a 5-minute TTL that comfortably covers Slack's retry
 * window). Distinct messages get distinct keys, so a legitimate repeat of the same text is
 * never suppressed — only an actual redelivery of the same event is.
 */
public final class SlackDedup {

    private SlackDedup() {}

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
