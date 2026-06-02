package channels;

import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import play.Play;
import services.EventLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Durable per-bot {@code setMyCommands} change-detection hash store (JCLAW-387 D1).
 *
 * <p>{@code TelegramCommandsRegistrationJob} is {@code @OnApplicationStart}, so it
 * re-issues {@code setMyCommands} for every enabled binding on every JVM boot. The
 * call is idempotent at the Telegram API layer, but unconditional: restarting with
 * N bindings fires N {@code setMyCommands} calls even when the command menu is
 * byte-for-byte identical to what each bot already has — which is the common case,
 * since the command set only changes when the {@code Commands.Command} enum does.
 * That burst contributes to restart-time Bot API 429s. This store lets the job skip
 * the call when the list is unchanged.
 *
 * <p><b>Scoping.</b> Keyed by <i>bot id</i> — the numeric prefix before the
 * {@code ':'} in the bot token (the immutable account id; the secret half after the
 * colon can be rotated without changing which Telegram account the command menu
 * belongs to). One small text file per bot id under
 * {@code data/telegram-command-hashes/<botId>.hash} holding the hex digest of the
 * ordered command list. Mirrors {@link TelegramOffsetStore}'s layout exactly.
 *
 * <p><b>Persistence choice.</b> A tiny file (not a JPA entity) — the whole point is
 * to survive a restart so the boot-time registration pass can skip the unchanged
 * call, so an in-memory cache would defeat the feature. Single-writer per bot, tiny,
 * and naturally lives next to other runtime state under {@code data/} (mirrors
 * {@link TelegramOffsetStore} and {@code LuceneIndexer}'s {@code data/jclaw-lucene}).
 * A row would drag in an entity, migration, and a transaction for no gain.
 *
 * <p><b>Fail-open.</b> An unreadable stored hash, a missing file, or a write failure
 * all degrade to "re-issue {@code setMyCommands}" — the pre-JCLAW-387 behaviour. The
 * worst case is a redundant API call, never a skipped-but-needed one.
 */
public final class TelegramCommandsHashStore {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

    /** Field separator (US, 0x1F) between a command's name and its description. */
    private static final char UNIT_SEP = '';
    /** Record separator (RS, 0x1E) between successive commands in the list. */
    private static final char RECORD_SEP = '';

    /**
     * Test override for the command-hash directory (mirrors
     * {@link TelegramOffsetStore#OFFSET_PATH_PROPERTY}). When set, the store reads
     * and writes here instead of {@code data/telegram-command-hashes}, so autotest
     * runs never touch production state.
     */
    public static final String HASH_PATH_PROPERTY = "jclaw.telegram.commandHashPath";

    private TelegramCommandsHashStore() {}

    /**
     * Bot id for {@code token}: the numeric prefix before the first {@code ':'}.
     * Delegates to {@link TelegramOffsetStore#botId} so both stores key on the
     * identical derivation. {@code null}/blank tokens yield {@code null}.
     */
    public static String botId(String token) {
        return TelegramOffsetStore.botId(token);
    }

    /**
     * Stable SHA-256 hex digest of the ordered command list (each command's name
     * and description, in order). The unit/record separators (non-printable, so
     * they can't occur in a real command name or description) keep two adjacent
     * fields from colliding across a boundary (e.g. {@code ("ab","c")} vs
     * {@code ("a","bc")}). A {@code null} list yields the digest of the empty input.
     */
    public static String hash(List<BotCommand> commands) {
        StringBuilder sb = new StringBuilder();
        if (commands != null) {
            for (BotCommand c : commands) {
                sb.append(c.getCommand() == null ? "" : c.getCommand());
                sb.append(UNIT_SEP);
                sb.append(c.getDescription() == null ? "" : c.getDescription());
                sb.append(RECORD_SEP);
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; unreachable on any conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Directory holding the per-bot command-hash files. */
    private static Path hashDir() {
        String override = System.getProperty(HASH_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Play.applicationPath.toPath().resolve("data/telegram-command-hashes");
    }

    /** File holding the hash for {@code botId} ({@code <botId>.hash}). */
    private static Path hashFile(String botId) {
        return hashDir().resolve(botId + ".hash");
    }

    /**
     * Persisted command hash for {@code token}'s bot, or {@code null} if none is
     * stored (or the token has no derivable bot id, or the file is missing or
     * unreadable). {@code null} feeds back as "no recorded state → don't skip".
     */
    public static String load(String token) {
        String botId = botId(token);
        if (botId == null) return null;
        Path file = hashFile(botId);
        if (!Files.isRegularFile(file)) return null;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : raw;
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Unreadable Telegram command hash for bot %s, will re-register: %s".formatted(
                            botId, e.getMessage()));
            return null;
        }
    }

    /**
     * Persist {@code hash} as the recorded command-list digest for {@code token}'s
     * bot. No-op for a token with no derivable bot id. Persistence failures are
     * logged, not thrown: a missed write costs at most one redundant
     * {@code setMyCommands} on the next restart, which is the pre-JCLAW-387
     * behaviour.
     */
    public static synchronized void record(String token, String hash) {
        String botId = botId(token);
        if (botId == null) return;
        try {
            Path dir = hashDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(botId + ".hash"), hash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to persist Telegram command hash for bot %s: %s".formatted(
                            botId, e.getMessage()));
        }
    }

    /**
     * Pure decision: should {@code setMyCommands} be skipped for {@code token}'s
     * bot given the {@code current} command list? Returns {@code true} iff the
     * digest of {@code current} matches the persisted hash for that bot id. Any
     * missing/unreadable stored hash (or token with no bot id) yields {@code false}
     * — fail open, re-register. Network-free, so callers can unit-test the gate
     * without invoking the real {@code setMyCommands}.
     */
    public static boolean shouldSkip(String token, List<BotCommand> current) {
        if (botId(token) == null) return false;
        String stored = load(token);
        return stored != null && stored.equals(hash(current));
    }
}
