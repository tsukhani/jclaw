package services;

import models.Task;
import play.Play;

import java.time.ZoneId;

/**
 * JCLAW-261: resolves the effective IANA timezone for a Task's fire-time
 * computation through a four-step fallback chain:
 *
 * <ol>
 *   <li>{@link Task#timezone} — operator-set per-task override.</li>
 *   <li>{@link ConfigService} key {@code tasks.defaultTimezone} — user-
 *       overridable global default written from Settings → Tasks.</li>
 *   <li>application.conf {@code tasks.defaultTimezone} — the baked-in
 *       installation default. Read via {@link Play#configuration} so
 *       the value picks up {@code %prod.} / {@code %test.} prefix
 *       overrides at boot.</li>
 *   <li>{@link ZoneId#systemDefault()} — last-resort JVM default, in
 *       case both Config and conf are absent (e.g. test classpath
 *       running with a stripped application.conf).</li>
 * </ol>
 *
 * <p>{@link Task.Type#INTERVAL} and {@link Task.Type#IMMEDIATE} are
 * timezone-agnostic by design (duration-based, no wall-clock binding);
 * callers should only invoke this for {@link Task.Type#CRON} /
 * {@link Task.Type#SCHEDULED}. Misuse is harmless — the resolver still
 * returns a valid {@link ZoneId} — but the result has no effect on
 * those types.
 *
 * <p>Invalid IANA strings at any layer fall through to the next step
 * with a one-time warn. Validation belongs at the write boundary
 * (controller / TaskTool); this resolver is read-time and never throws.
 */
public final class TimezoneResolver {

    public static final String CONFIG_KEY = "tasks.defaultTimezone";
    private static final String LOG_CATEGORY = "task";

    private TimezoneResolver() {}

    /**
     * Resolve the effective {@link ZoneId} for the given task. Never
     * returns null — falls through to {@link ZoneId#systemDefault()}
     * if every other source is absent or invalid.
     */
    public static ZoneId resolve(Task task) {
        return resolve(task != null ? task.timezone : null);
    }

    /**
     * Resolve when only a candidate {@code perTask} string is known
     * (e.g. preview-validating a user's input before persisting it).
     */
    public static ZoneId resolve(String perTask) {
        var zone = tryParse(perTask, "task.timezone");
        if (zone != null) return zone;

        var configValue = ConfigService.get(CONFIG_KEY);
        zone = tryParse(configValue, "Config '" + CONFIG_KEY + "'");
        if (zone != null) return zone;

        var confDefault = Play.configuration != null
                ? Play.configuration.getProperty(CONFIG_KEY)
                : null;
        zone = tryParse(confDefault, "application.conf '" + CONFIG_KEY + "'");
        if (zone != null) return zone;

        return ZoneId.systemDefault();
    }

    /**
     * The currently-effective global default — what {@link #resolve(Task)}
     * returns for a task with {@code timezone == null}. Useful for the
     * Settings UI's "default timezone" display and for the tasks page
     * to render the effective zone next to each task's next-run column.
     */
    public static ZoneId currentDefault() {
        return resolve((String) null);
    }

    private static ZoneId tryParse(String raw, String source) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ZoneId.of(raw.trim());
        } catch (Exception _) {
            EventLogger.warn(LOG_CATEGORY,
                    "%s contains invalid IANA timezone '%s'; falling through to next default"
                            .formatted(source, raw));
            return null;
        }
    }
}
