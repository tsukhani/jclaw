package channels;

import org.telegram.telegrambots.longpolling.BotSession;
import play.Play;
import services.EventLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Durable per-bot last-consumed {@code update_id} store (JCLAW-361).
 *
 * <p>The telegrambots SDK tracks the long-poll offset only in an in-memory
 * {@code AtomicInteger} on {@link BotSession}
 * ({@code lastReceivedUpdate}, starts at 0). On JVM restart that resets, so the
 * next {@code getUpdates} re-fetches every update Telegram still buffers (up to
 * its 24 h retention) and {@code dispatch} re-processes them — {@code
 * MessageDeduplicator} keys on chat/message identity, not Telegram update ids,
 * so it does not stop the replay. This store persists the high-water update id
 * so {@link TelegramPollingRunner} can seed the start offset on (re)start.
 *
 * <p><b>Scoping.</b> Keyed by <i>bot id</i> — the numeric prefix before the
 * {@code ':'} in the bot token (the immutable account id; the secret half after
 * the colon can be rotated without changing which Telegram account/update stream
 * the offset belongs to). One small text file per bot id under
 * {@code data/telegram-offsets/<botId>.offset} holding the decimal update id.
 *
 * <p><b>Persistence choice.</b> A tiny file (not a JPA entity) — this is
 * single-writer per bot (the SDK's single-thread update executor), tiny, and
 * naturally lives next to other runtime state under {@code data/} (mirrors
 * {@code LuceneIndexer}'s {@code data/jclaw-lucene}). A row would drag in an
 * entity, migration, and a transaction on the hot consume path for no gain.
 *
 * <p>Writes are monotonic: {@link #record} never lowers a stored value, so an
 * out-of-order or stale update can't rewind the offset.
 */
public final class TelegramOffsetStore {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

    /**
     * Test override for the offset directory (mirrors
     * {@code LuceneIndexer.INDEX_PATH_PROPERTY}). When set, the store reads and
     * writes here instead of {@code data/telegram-offsets}, so autotest runs
     * never touch production state.
     */
    public static final String OFFSET_PATH_PROPERTY = "jclaw.telegram.offsetPath";

    private TelegramOffsetStore() {}

    /**
     * Bot id for {@code token}: the numeric prefix before the first {@code ':'}.
     * Returns the whole trimmed token if there is no colon (defensive — real
     * tokens always carry one). {@code null}/blank tokens yield {@code null}.
     */
    public static String botId(String token) {
        if (token == null) return null;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return null;
        int colon = trimmed.indexOf(':');
        return colon > 0 ? trimmed.substring(0, colon) : trimmed;
    }

    /** Directory holding the per-bot offset files. */
    private static Path offsetDir() {
        String override = System.getProperty(OFFSET_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Play.applicationPath.toPath().resolve("data/telegram-offsets");
    }

    /** File holding the offset for {@code botId} ({@code <botId>.offset}). */
    private static Path offsetFile(String botId) {
        return offsetDir().resolve(botId + ".offset");
    }

    /**
     * Last consumed update id for {@code token}'s bot, or {@code 0} if none is
     * persisted (or the token has no derivable bot id, or the file is missing or
     * unreadable). {@code 0} feeds back as "no seed", matching the SDK's initial
     * {@code lastReceivedUpdate}.
     */
    public static int load(String token) {
        String botId = botId(token);
        if (botId == null) return 0;
        Path file = offsetFile(botId);
        if (!Files.isRegularFile(file)) return 0;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return 0;
            return Integer.parseInt(raw);
        } catch (IOException | NumberFormatException e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Unreadable Telegram offset for bot %s, starting from 0: %s".formatted(
                            botId, e.getMessage()));
            return 0;
        }
    }

    /**
     * Persist {@code updateId} as the last consumed offset for {@code token}'s
     * bot, but only if it exceeds the currently stored value (monotonic — never
     * rewinds). No-op for a token with no derivable bot id. Persistence failures
     * are logged, not thrown: a missed write costs at most a re-process of the
     * affected updates on the next restart, which is the pre-JCLAW-361 behaviour.
     */
    public static synchronized void persist(String token, int updateId) {
        String botId = botId(token);
        if (botId == null) return;
        if (updateId <= load(token)) return;
        try {
            Path dir = offsetDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(botId + ".offset"),
                    Integer.toString(updateId), StandardCharsets.UTF_8);
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to persist Telegram offset %d for bot %s: %s".formatted(
                            updateId, botId, e.getMessage()));
        }
    }
}
