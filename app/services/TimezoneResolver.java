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
 *   <li>{@link ConfigService} key {@code tasks.defaultTimezone} — explicit
 *       task-scheduling override written from Settings → Tasks. Set this
 *       only to run tasks in a zone <em>different</em> from your own.</li>
 *   <li>application.conf {@code tasks.defaultTimezone} — explicit
 *       installation-level override. Read via {@link Play#configuration}
 *       so the value picks up {@code %prod.} / {@code %test.} prefix
 *       overrides at boot. Shipped commented-out, so absent by default.</li>
 *   <li>{@link #appZone()} — the operator's wall-clock zone (Settings →
 *       General, {@code app.timezone}), defaulting to the server's JVM
 *       zone. This is the default: with no explicit task zone above, CRON /
 *       SCHEDULED tasks follow the same zone the assistant treats as "now",
 *       so a 9 AM task fires at the operator's 9 AM rather than 9 AM UTC.</li>
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

    /**
     * Config key for the operator's wall-clock timezone — the zone the
     * assistant treats as "now" when it reasons about the current date and
     * time in its system prompt, and (since it is the terminal fallback of
     * {@link #resolve(String)}) the default zone for CRON / SCHEDULED tasks
     * that carry no explicit zone of their own. Defaults to the server's JVM
     * zone so a fresh install reflects where the operator actually is. Set
     * from Settings → General.
     */
    public static final String APP_CONFIG_KEY = "app.timezone";

    private static final String LOG_CATEGORY = "task";

    private TimezoneResolver() {}

    /**
     * Resolve the effective {@link ZoneId} for the given task. Never
     * returns null — falls through to {@link #appZone()} (the operator
     * zone, itself defaulting to {@link ZoneId#systemDefault()}) when no
     * explicit task zone is set.
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

        // Default: follow the operator's wall-clock zone (Settings → General),
        // so tasks fire at the operator's local time rather than UTC. appZone()
        // ends in ZoneId.systemDefault(), so the chain still never returns null.
        return appZone();
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

    /**
     * The operator's effective wall-clock timezone — what the assistant
     * treats as "now". Resolution order:
     *
     * <ol>
     *   <li>Config key {@code app.timezone} — set from Settings → General.</li>
     *   <li>application.conf {@code app.timezone} — optional baked-in default
     *       (absent by default, so fresh installs fall through to the JVM zone).</li>
     *   <li>{@link ZoneId#systemDefault()} — the server's JVM zone.</li>
     * </ol>
     *
     * <p>Unlike {@link #resolve(Task)}, this never consults
     * {@code tasks.defaultTimezone} (which ships as UTC): the operator's clock
     * and the task scheduler's default zone are independent settings. Invalid
     * IANA strings fall through with a one-time warn; never throws.
     */
    public static ZoneId appZone() {
        var configValue = ConfigService.get(APP_CONFIG_KEY);
        var zone = tryParse(configValue, "Config '" + APP_CONFIG_KEY + "'");
        if (zone != null) return zone;

        var confDefault = Play.configuration != null
                ? Play.configuration.getProperty(APP_CONFIG_KEY)
                : null;
        zone = tryParse(confDefault, "application.conf '" + APP_CONFIG_KEY + "'");
        if (zone != null) return zone;

        return ZoneId.systemDefault();
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
