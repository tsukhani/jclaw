package channels;

import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import services.EventLogger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a Telegram bot's own identity — numeric user id and {@code @}-handle
 * — so the inbound parse path ({@link TelegramChannel#parseUpdate(org.telegram.telegrambots.meta.api.objects.Update, String, Long)})
 * can decide whether a {@code mention} / {@code text_mention} / {@code /cmd@botname}
 * addresses <i>this</i> bot (JCLAW-371, activating JCLAW-367's identity-aware parse).
 *
 * <p>The user id is free: Telegram bot tokens are shaped {@code <bot_id>:<hash>},
 * so the numeric prefix before the first {@code ':'} is the bot's user id — no API
 * call needed. The username is only knowable from a {@code getMe} call, which we
 * issue once per token through {@link TelegramChannel#forToken(String)}'s client
 * and cache (resolved identities live in {@link #CACHE} keyed by token). On any
 * {@code getMe} failure (bad/revoked token, network blip) we fall back to
 * {@code (userId from token, username = null)} so mention-by-handle simply stays
 * dormant for that token rather than aborting the inbound turn — a reply-to-bot
 * mention (matched by id) still works.
 */
public final class TelegramBotIdentity {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

    private static final ConcurrentHashMap<String, Identity> CACHE = new ConcurrentHashMap<>();

    /**
     * Resolved bot identity.
     *
     * @param userId   the bot's numeric Telegram user id, derived from the token
     *                 prefix; null only when the token has no numeric prefix
     * @param username the bot's {@code @}-handle (without the leading {@code @}),
     *                 or null when {@code getMe} failed or returned no username
     */
    public record Identity(Long userId, String username) {}

    private TelegramBotIdentity() {}

    /**
     * Resolve (and cache) the {@link Identity} for {@code botToken}. The username
     * is fetched via {@code getMe} on first call for a token and reused thereafter;
     * the user id is parsed from the token with no API call. Never throws — a
     * {@code getMe} failure degrades to {@code (userId, null)}.
     */
    public static Identity resolve(String botToken) {
        if (botToken == null || botToken.isBlank()) return new Identity(null, null);
        Identity cached = CACHE.get(botToken);
        if (cached != null) return cached;
        // Resolve OUTSIDE computeIfAbsent: resolveUncached makes a blocking getMe HTTP
        // call, which must not run while holding a ConcurrentHashMap bin lock.
        Identity fresh = resolveUncached(botToken);
        // Only cache a fully-resolved identity. A null username means getMe failed
        // (bots always have a username), so leave it uncached and retry next time
        // rather than permanently disabling mention-by-handle for this token.
        if (fresh.username() != null) {
            Identity prior = CACHE.putIfAbsent(botToken, fresh);
            return prior != null ? prior : fresh;
        }
        return fresh;
    }

    /** Test-only: drop the cached identity for {@code botToken} so the next resolve re-fetches. */
    static void clearForTest(String botToken) {
        if (botToken != null) CACHE.remove(botToken);
    }

    private static Identity resolveUncached(String botToken) {
        return new Identity(userIdFromToken(botToken), fetchUsername(botToken));
    }

    /** Numeric prefix before the first {@code ':'} in a {@code <bot_id>:<hash>} token, or null. */
    private static Long userIdFromToken(String botToken) {
        int colon = botToken.indexOf(':');
        if (colon <= 0) return null;
        try {
            return Long.parseLong(botToken.substring(0, colon));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** {@code getMe} the bot's username; null on any failure (logged at warn). */
    private static String fetchUsername(String botToken) {
        try {
            User me = TelegramChannel.forToken(botToken).client()
                    .execute(GetMe.builder().build());
            return me != null ? me.getUserName() : null;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "getMe failed resolving bot username: %s".formatted(e.getMessage()));
            return null;
        }
    }
}
