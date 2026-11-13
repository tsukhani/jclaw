package services;

import models.ChannelConfig;
import models.JsonCredential;
import models.McpServer;
import models.SkillSecret;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;
import org.jspecify.annotations.Nullable;
import services.secrets.SecretGrants;
import utils.AppClock;
import utils.MultiStringMatcher;
import utils.SecretRedactor;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Masks the credentials JClaw holds wherever a tool's result carries them, before the model or the conversation sees
 * it (JF-361). A command never holds a stored secret, only its stand-in, yet the value can still reach a result: a
 * file outside the store that holds the same token, a session key a skill saved, a page that echoes what it was sent.
 *
 * <p>Every exact value JClaw keeps sealed is replaced by a mask that names it: each skill secret, each Config key
 * {@link ConfigService#encryptsAtRest} flags, the Slack, Telegram and WhatsApp binding tokens, and the secret values
 * in MCP server and channel JSON. Values are matched in one pass, however many there are, and a value shorter than
 * {@value #MIN_CHARS} characters is left alone so common words are not masked. The log masker's key shapes are
 * masked too, unless {@value #SHAPES_KEY} is {@code false}.
 *
 * <p>What a skill saved for its next run is masked too, though JClaw does not hold it: each file under an agent's
 * {@code skill-state/} folder, up to {@value #MAX_STATE_BYTES} bytes and never through a link, adds its whole content
 * when it is one word (a session key) and each long random-looking string inside it otherwise (a cookie jar's token
 * values), so a domain or a field name in the same file is not masked everywhere. That folder holds secrets only
 * (skill-creator's {@code references/secrets.md}): an ordinary value a skill keeps goes in {@code skill-data/}.
 *
 * <p>No result waits on the database: the stored values are read again every {@code 30 s} on a background thread
 * while the last ones keep masking, and at once only after a skill secret or a sealed Config key changes. Each result
 * stats the saved-state folders and files already known, so a file a skill writes, renews or adds is read before
 * that result is masked.
 */
public final class CredentialMask {

    public static final int MIN_CHARS = 8;
    /** What {@link #mask} writes in place of a stored value, before the value's name and a closing bracket. */
    public static final String MASK_PREFIX = "[masked: ";
    static final int MAX_STATE_BYTES = 65_536;
    /** A saved-state string at least this long, with no space and both letters and digits, reads as a token. */
    private static final int STATE_TOKEN_CHARS = 16;
    /** Config key: {@code false} leaves key shapes unmasked in tool results; absent or anything else masks them. */
    public static final String SHAPES_KEY = "tools.maskTokenShapes";
    private static final Duration REFRESH = Duration.ofSeconds(30);
    private static final String CATEGORY = "tool";
    /** What separates the strings of a JSON or text file: quotes, spaces, and the punctuation JSON and cookies use. */
    private static final Pattern STATE_WORD = Pattern.compile("[\\s\"',;:{}\\[\\]=]+");

    /**
     * The values a mask is built from: the stored ones as read at {@code storedAt}, and the saved-state ones with the
     * paths whose stamps, taken before the files were read, say when they must be read again.
     */
    @SuppressWarnings("java:S6218") // never compared or printed: the cache's one holder
    private record Snapshot(Instant storedAt, Map<String, String> stored, List<Path> watched, long[] stamps,
                            Map<String, String> state, MultiStringMatcher<String> matcher) {}

    @SuppressWarnings("java:S6218") // never compared or printed: one scan's result
    private record StateScan(List<Path> watched, long[] stamps, Map<String, String> values) {}

    private static final Object LOCK = new Object();
    /** Counts {@link #invalidate} calls, so a build that began before one never replaces what it cleared. */
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    @SuppressWarnings("java:S3077") // an immutable snapshot replaced whole
    private static volatile @Nullable Snapshot snapshot;

    private CredentialMask() {}

    /**
     * Read the stored values again before the next mask: a credential was added, changed or removed. Call it once the
     * change is committed ({@link Tx#afterCommit}), or a read racing the commit keeps the old values.
     */
    public static void invalidate() {
        GENERATION.incrementAndGet();
        snapshot = null;
    }

    /** {@code text} with every credential JClaw holds replaced by a mask naming it, and key shapes masked. */
    public static @Nullable String mask(@Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        var out = current().matcher().replaceAll(text, name -> MASK_PREFIX + name + "]");
        if (!"false".equalsIgnoreCase(ConfigService.get(SHAPES_KEY, "true").strip())) {
            out = SecretRedactor.redactShapes(out);
        }
        return out;
    }

    /** Whether {@code text} can hold a mask at all: the cheap test before a write reads the file it replaces. */
    public static boolean mayHoldMask(String text) {
        return text.contains(MASK_PREFIX) || text.contains(SecretRedactor.MASK);
    }

    /** How many masks {@code text} holds: a stored value's {@link #MASK_PREFIX} and a key shape's mask. */
    public static int masksIn(String text) {
        return occurrences(text, MASK_PREFIX) + occurrences(text, SecretRedactor.MASK);
    }

    private static int occurrences(String text, String marker) {
        int count = 0;
        for (int i = text.indexOf(marker); i >= 0; i = text.indexOf(marker, i + marker.length())) count++;
        return count;
    }

    private static Snapshot current() {
        var s = snapshot;
        if (s == null) return rebuild(null);
        if (!unchanged(s.watched(), s.stamps())) s = rebuild(s);
        if (s.storedAt().plus(REFRESH).isBefore(AppClock.now())) refreshInBackground();
        return s;
    }

    /**
     * A snapshot with the saved state read again, and the stored values too when there are none or {@code seen} was
     * cleared meanwhile. Under the lock, so a burst of results after one change reads the files once.
     */
    private static Snapshot rebuild(@Nullable Snapshot seen) {
        synchronized (LOCK) {
            var latest = snapshot;
            if (latest != null && latest != seen && unchanged(latest.watched(), latest.stamps())) return latest;
            long generation = GENERATION.get();
            var built = latest == null ? build(null) : withState(latest, scanState());
            if (GENERATION.get() == generation) snapshot = built;
            return built;
        }
    }

    private static void refreshInBackground() {
        if (!REFRESHING.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("credential-mask-refresh").start(() -> {
            try {
                long generation = GENERATION.get();
                var built = build(snapshot);
                synchronized (LOCK) {
                    if (GENERATION.get() == generation) snapshot = built;
                }
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    /** Every value read again; a stored read that fails keeps {@code last}'s values rather than mask nothing. */
    private static Snapshot build(@Nullable Snapshot last) {
        var now = AppClock.now();
        Map<String, String> stored;
        try {
            stored = storedValues();
        } catch (RuntimeException e) {
            EventLogger.warn(CATEGORY, "Could not read the credentials to mask in tool results: "
                    + e.getClass().getSimpleName());
            stored = last != null ? last.stored() : Map.of();
        }
        return withState(new Snapshot(now, stored, List.of(), new long[0], Map.of(), new MultiStringMatcher<>(stored)),
                scanState());
    }

    private static Snapshot withState(Snapshot base, StateScan scan) {
        var matcher = base.matcher();
        if (!scan.values().equals(base.state())) {
            var all = new LinkedHashMap<>(base.stored());
            scan.values().forEach(all::putIfAbsent);
            matcher = new MultiStringMatcher<>(all);
        }
        return new Snapshot(base.storedAt(), base.stored(), scan.watched(), scan.stamps(), scan.values(), matcher);
    }

    /** Each credential value JClaw holds, by the name a mask gives it. */
    static Map<String, String> storedValues() {
        var out = new LinkedHashMap<String, String>();
        Tx.run(() -> {
            for (var entry : ConfigService.listAll()) {
                if (ConfigService.encryptsAtRest(entry.key())) add(out, entry.value(), entry.key());
            }
            List<SkillSecret> secrets = SkillSecret.findAll();
            for (var secret : secrets) add(out, secret.value, secret.name);
            List<SlackBinding> slack = SlackBinding.findAll();
            for (var b : slack) {
                add(out, b.botToken, "Slack bot token");
                add(out, b.signingSecret, "Slack signing secret");
                add(out, b.appToken, "Slack app token");
            }
            List<TelegramBinding> telegram = TelegramBinding.findAll();
            for (var b : telegram) {
                add(out, b.botToken, "Telegram bot token");
                add(out, b.webhookSecret, "Telegram webhook secret");
            }
            List<WhatsAppBinding> whatsApp = WhatsAppBinding.findAll();
            for (var b : whatsApp) {
                add(out, b.accessToken, "WhatsApp access token");
                add(out, b.appSecret, "WhatsApp app secret");
                add(out, b.verifyToken, "WhatsApp verify token");
            }
            List<McpServer> servers = McpServer.findAll();
            for (var server : servers) {
                for (var e : JsonCredential.MCP_SERVER.secretValues(server.configJson).entrySet()) {
                    add(out, e.getValue(), "MCP server " + server.name + " " + e.getKey());
                }
            }
            List<ChannelConfig> channels = ChannelConfig.findAll();
            for (var channel : channels) {
                for (var e : JsonCredential.CHANNEL_CONFIG.secretValues(channel.configJson).entrySet()) {
                    add(out, e.getValue(), "channel " + channel.channelType + " " + e.getKey());
                }
            }
        });
        return out;
    }

    /**
     * The saved-state files, and the paths to watch: the workspace root (a new agent), each agent's
     * {@code skill-state/} whether it exists yet, and every folder and file inside it, never through a link.
     */
    private static StateScan scanState() {
        var root = AgentService.workspaceRoot();
        var watched = new ArrayList<Path>();
        var files = new ArrayList<Path>();
        watched.add(root);
        try (var agents = Files.list(root)) {
            for (var agent : agents.sorted().toList()) {
                if (!Files.isDirectory(agent, LinkOption.NOFOLLOW_LINKS)) continue;
                var state = agent.resolve(WorkspacePathGuard.SKILL_STATE_DIR);
                watched.add(state);
                if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var walk = Files.walk(state)) {
                    for (var p : walk.sorted().toList()) {
                        if (p.equals(state)) continue;
                        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                            watched.add(p);
                        } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                            watched.add(p);
                            files.add(p);
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException _) {
            // An unreadable workspace adds nothing to mask; the stored credentials are still masked.
        }
        var stamps = new long[watched.size() * 2];
        for (int i = 0; i < watched.size(); i++) stamp(watched.get(i), stamps, i * 2);
        var values = new LinkedHashMap<String, String>();
        for (var file : files) addStateFile(values, root, file);
        return new StateScan(List.copyOf(watched), stamps, values);
    }

    private static boolean unchanged(List<Path> watched, long[] stamps) {
        var now = new long[2];
        for (int i = 0; i < watched.size(); i++) {
            stamp(watched.get(i), now, 0);
            if (now[0] != stamps[i * 2] || now[1] != stamps[i * 2 + 1]) return false;
        }
        return true;
    }

    /** When {@code path} last changed and its size, or {@link Long#MIN_VALUE} twice when it is gone. */
    private static void stamp(Path path, long[] out, int at) {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            out[at] = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
            out[at + 1] = attributes.size();
        } catch (IOException _) {
            out[at] = Long.MIN_VALUE;
            out[at + 1] = Long.MIN_VALUE;
        }
    }

    /** The token-like contents of one saved-state file, named for the skill and file they came from. */
    private static void addStateFile(Map<String, String> out, Path root, Path file) {
        String text;
        try {
            if (Files.size(file) > MAX_STATE_BYTES) return;
            text = Files.readString(file);
        } catch (IOException | UncheckedIOException _) {
            return;
        }
        var rel = root.relativize(file);
        var name = "saved " + (rel.getNameCount() > 2 ? rel.subpath(2, rel.getNameCount()) : rel);
        var whole = text.strip();
        var words = STATE_WORD.split(whole);
        if (words.length == 1 && !whole.isEmpty()) {
            // One word, such as a session key: the whole of it, whatever it looks like.
            add(out, whole, name);
            return;
        }
        for (var word : words) {
            if (looksLikeToken(word)) add(out, word, name);
        }
    }

    private static boolean looksLikeToken(String word) {
        return word.length() >= STATE_TOKEN_CHARS && word.chars().anyMatch(Character::isLetter)
                && word.chars().anyMatch(Character::isDigit);
    }

    /** {@code value} under {@code name}, and its token alone when it is an authorization header's value. */
    private static void add(Map<String, String> out, @Nullable String value, String name) {
        if (value == null) return;
        var v = value.strip();
        if (v.length() < MIN_CHARS || v.contains(SecretGrants.PLACEHOLDER_PREFIX)) return;
        out.putIfAbsent(v, name.replaceAll("[^A-Za-z0-9_./ -]", "_"));
        for (var scheme : new String[] {"Bearer ", "Basic ", "Token "}) {
            if (v.regionMatches(true, 0, scheme, 0, scheme.length())) add(out, v.substring(scheme.length()), name);
        }
    }
}
