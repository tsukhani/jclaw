package channels;

import org.telegram.telegrambots.meta.TelegramUrl;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot-token registry of {@link TelegramChannel} instances (JCLAW-151,
 * extracted from {@code TelegramChannel}). Post-JCLAW-89 each operator-managed
 * bot token has its own {@link TelegramChannel} so send paths carry the correct
 * token without a global "current bot" lookup; {@code OkHttpTelegramClient} owns
 * a dispatcher thread pool, so one instance per token is reused across that
 * token's lifetime. Instances are created on demand via {@link #forToken} and
 * evicted via {@link #evictToken} when the polling runner unregisters a binding.
 */
public final class TelegramClientCache {

    /** Per-token instances. */
    private static final ConcurrentHashMap<String, TelegramChannel> INSTANCES = new ConcurrentHashMap<>();

    private TelegramClientCache() {}

    /** Resolve (or lazily create) the singleton for {@code botToken}. */
    public static TelegramChannel forToken(String botToken) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("botToken required");
        }
        return INSTANCES.computeIfAbsent(botToken, TelegramChannel::new);
    }

    /**
     * Test-only: pre-install a TelegramChannel pointing at {@code urlOverride}
     * for the given {@code botToken}, bypassing the default api.telegram.org
     * target. Subsequent {@link #forToken} calls will return this instance
     * until {@link #evictToken} / {@link #clearForTest} is called. See
     * JCLAW-96 for the MockTelegramServer integration pattern.
     */
    public static void installForTest(String botToken, TelegramUrl urlOverride) {
        INSTANCES.put(botToken, new TelegramChannel(botToken, urlOverride));
    }

    /** Test-only: reset the per-token cache so the next {@link #forToken} call
     *  constructs a fresh (production-targeted) instance. */
    public static void clearForTest(String botToken) {
        if (botToken != null) INSTANCES.remove(botToken);
    }

    /** Drop the cached instance for a token. Call when a binding is deleted or its token rotated. */
    public static void evictToken(String botToken) {
        if (botToken != null) INSTANCES.remove(botToken);
    }

    /**
     * Existing instance for {@code botToken} WITHOUT creating one (null if
     * absent). Used by no-create lookups such as the JCLAW-383 reaction gate,
     * where constructing a TelegramChannel (+ OkHttp clients) for an
     * unknown/garbled token would contradict the "false on a never-seen token"
     * contract.
     */
    static TelegramChannel peek(String botToken) {
        return botToken == null ? null : INSTANCES.get(botToken);
    }
}
