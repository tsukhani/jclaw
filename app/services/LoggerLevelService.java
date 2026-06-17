package services;

import models.Config;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runtime per-logger log-level overrides.
 *
 * <p>Operators add {@code (logger, level)} pairs on the Settings page. Each pair
 * is persisted as a {@link Config} row keyed {@code logging.level.<logger>} and
 * applied live through log4j2's {@link Configurator#setLevel}. Because the apply
 * runs AFTER Play's log4j init has loaded {@code log4j2.xml} /
 * {@code application.conf} (see {@link jobs.LoggerLevelInitJob}), an override
 * always wins over the file configuration — the stated requirement that "these
 * settings override any level specified in the xml or application.conf files".
 *
 * <h2>Why {@code setLevel(name, null)} reverts cleanly</h2>
 * log4j-core's {@code LoggerConfig.getLevel()} returns the PARENT's level when
 * its own level field is null. So deleting an override is just
 * {@code setLevel(logger, null)} — the logger re-inherits from its parent/root,
 * exactly as if the override had never existed. The one exception is the root
 * logger (no parent → a null level would resolve to ERROR), so root reverts to a
 * baseline captured before the first root override is applied.
 *
 * <h2>Why we do NOT call {@code reconfigure()}</h2>
 * In test mode the play1 fork adds a {@code test-result} appender AFTER its
 * {@code Configurator.reconfigure(uri)} call. A runtime {@code reconfigure()}
 * would rebuild the Configuration from the file and silently drop that appender.
 * Per-logger {@code setLevel} only mutates a {@code LoggerConfig}'s level — it
 * never rebuilds the Configuration, so appenders are left untouched.
 */
public final class LoggerLevelService {

    private LoggerLevelService() {}

    /** Config-key prefix for a per-logger override: {@code logging.level.<logger>}. */
    public static final String PREFIX = "logging.level.";

    /** The standard log4j2 levels, least-to-most verbose, offered in the UI dropdown. */
    public static final List<String> VALID_LEVELS =
            List.of("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL");

    private static final Set<String> VALID_SET = Set.copyOf(VALID_LEVELS);

    /** Case-insensitive alias the operator may type to target the root logger. */
    private static final String ROOT_ALIAS = "root";

    /**
     * The root logger's level as loaded from the file configuration, captured
     * once before the first root override is applied so {@link #revert} can
     * restore it (root has no parent to inherit from). Null until captured.
     */
    private static volatile Level baselineRootLevel;

    public record LoggerLevel(String logger, String level) {}

    /**
     * @return an error message if {@code (logger, level)} is invalid, else {@code null}.
     *
     * <p>The level is checked against a closed set. The logger name can only be
     * checked structurally — log4j's logger namespace is open (any dotted string
     * is a legal name, created lazily), so we reject only malformed input
     * (whitespace, leading/trailing dots, empty segments) and cannot verify that
     * a well-formed name actually corresponds to live code. A typo like
     * {@code controlers.Foo} is accepted and simply never matches anything; the
     * Settings UI surfaces a soft hint for names not among {@link #knownLoggers}.
     */
    public static String validate(String logger, String level) {
        if (logger == null || logger.isBlank()) {
            return "Logger name is required.";
        }
        String name = logger.trim();
        if (name.chars().anyMatch(Character::isWhitespace)) {
            return "Logger name cannot contain spaces.";
        }
        if (name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            return "Logger name has an empty segment — check for a stray or doubled dot.";
        }
        if (level == null || !VALID_SET.contains(level.trim().toUpperCase())) {
            return "Level must be one of " + VALID_LEVELS + ".";
        }
        return null;
    }

    /**
     * Logger names currently known to the running context: every logger that
     * has been instantiated plus every logger explicitly configured in the
     * file. A snapshot, not exhaustive — a logger only appears once its class
     * has logged at least once, and the root logger surfaces as {@code "root"}.
     * Used purely as an autocomplete / typo-hint corpus in the UI.
     */
    public static List<String> knownLoggers() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        var names = new TreeSet<String>();
        for (var logger : ctx.getLoggers()) {
            addKnownName(names, logger.getName());
        }
        for (String name : ctx.getConfiguration().getLoggers().keySet()) {
            addKnownName(names, name);
        }
        return new ArrayList<>(names);
    }

    private static void addKnownName(Set<String> names, String name) {
        if (name != null) {
            names.add(name.isEmpty() ? ROOT_ALIAS : name);   // root logger name "" → "root"
        }
    }

    /** Every persisted override, sorted by logger name. */
    public static List<LoggerLevel> list() {
        var out = new ArrayList<LoggerLevel>();
        for (Config c : ConfigService.listAll()) {
            if (c.key != null && c.key.startsWith(PREFIX)) {
                out.add(new LoggerLevel(c.key.substring(PREFIX.length()), c.value));
            }
        }
        out.sort(Comparator.comparing(LoggerLevel::logger));
        return out;
    }

    /** Apply a single override live. Tolerant of an invalid level (logs + skips). */
    public static void apply(String logger, String level) {
        Level l = Level.getLevel(level == null ? "" : level.trim().toUpperCase());
        if (l == null) {
            EventLogger.warn("logging",
                    "Ignoring invalid log level '" + level + "' for logger '" + logger + "'");
            return;
        }
        String name = resolve(logger);
        if (name.isEmpty()) {              // root
            captureRootBaseline();
            Configurator.setRootLevel(l);
        } else {
            Configurator.setLevel(name, l);
        }
    }

    /** Drop a single override: re-inherit from parent (root → captured baseline). */
    public static void revert(String logger) {
        String name = resolve(logger);
        if (name.isEmpty()) {              // root has no parent to inherit from
            Configurator.setRootLevel(baselineRootLevel != null ? baselineRootLevel : Level.INFO);
        } else {
            // Cast disambiguates setLevel(String,Level) from setLevel(String,String);
            // a null Level clears the override so the logger re-inherits its parent.
            Configurator.setLevel(name, (Level) null);
        }
    }

    /** Replay every persisted override. Called once at {@code @OnApplicationStart}. */
    public static void applyAllFromConfig() {
        captureRootBaseline();
        for (LoggerLevel ll : list()) {
            apply(ll.logger(), ll.level());
        }
    }

    /** {@code "root"} (any case) targets the root logger; everything else is verbatim. */
    private static String resolve(String logger) {
        String t = logger == null ? "" : logger.trim();
        return t.equalsIgnoreCase(ROOT_ALIAS) ? LogManager.ROOT_LOGGER_NAME : t;
    }

    private static void captureRootBaseline() {
        if (baselineRootLevel == null) {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Level current = ctx.getConfiguration()
                    .getLoggerConfig(LogManager.ROOT_LOGGER_NAME).getLevel();
            baselineRootLevel = current != null ? current : Level.INFO;
        }
    }
}
