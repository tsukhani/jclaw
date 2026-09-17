package utils;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static utils.ErrorTemplateSupport.e;

/**
 * Templates for startup and boot failures — the seam JCLAW-1136 fills (JCLAW-60).
 *
 * <p>Every caller here renders {@link ErrorRendering#PLAIN}: the destination is the console and
 * the log, where there is no request, no markup parser and no guarantee of terminal colour.
 *
 * <p>Public, unlike its sibling tables, because the codes are referenced from {@code services}
 * and {@code jobs} rather than only from this package.
 *
 * <p>The factories build the same remedy the registered row carries but with the specifics a
 * person needs in order to act — the offending key and the value that was rejected, or the
 * driver's own words — substituted into {@code whatBroke}, so an {@link ErrorTemplates#forCode}
 * lookup and a factory call cannot give conflicting advice.
 */
public final class StartupErrorTemplates {

    private StartupErrorTemplates() {}

    /** A configured numeric value that would not parse; the built-in default is in use instead. */
    public static final String CONFIG_PARSE_FAILED = "config_parse_failed";

    /** The database refused JClaw's connection at boot. */
    public static final String DATABASE_UNAVAILABLE = "database_unavailable";

    /** The scheduler DDL that ships with the application could not be read. */
    public static final String SCHEMA_DDL_UNREADABLE = "schema_ddl_unreadable";

    /** The database took the connection but refused a statement of the scheduler DDL. */
    public static final String SCHEMA_DDL_FAILED = "schema_ddl_failed";

    private static final String CONFIG_CHECK = "The stored value, in Settings or via "
            + "POST /api/config. A unit suffix, a thousands separator or a stray quote is the "
            + "usual cause.";

    private static final String CONFIG_RETRY = "Store a bare number, or delete the key to take "
            + "the default deliberately.";

    private static final String DATABASE_CHECK = "That the database is running and reachable, "
            + "and that db.url, db.user and db.pass name it. A file database also needs its "
            + "directory writable and not already held by another instance.";

    private static final String DATABASE_RETRY = "Fix the database or the connection settings, "
            + "then start JClaw again.";

    private static final String DDL_CHECK = "conf/db/db_scheduler_h2.sql and "
            + "conf/db/db_scheduler_postgres.sql ship with the application, so one that cannot "
            + "be read means an incomplete install or changed file permissions.";

    private static final String DDL_RETRY = "Restore the file from the distribution, then start "
            + "JClaw again.";

    private static final String DDL_FAILED_CHECK = "The connection worked, so the connection "
            + "settings are right. The database user most likely lacks permission to create "
            + "tables, or a scheduled_tasks table already exists with a different shape.";

    private static final String DDL_FAILED_RETRY = "Give the database user permission to create "
            + "tables, or run conf/db/db_scheduler_postgres.sql or conf/db/db_scheduler_h2.sql, "
            + "whichever matches the database, once as a user who has it. Then start JClaw again.";

    private static final Map<String, ErrorTemplate> TEMPLATES = Map.ofEntries(
            e(CONFIG_PARSE_FAILED,
                    "A configured value is not the number its key expects, so the built-in "
                            + "default is in use instead.",
                    CONFIG_CHECK, CONFIG_RETRY),
            e(DATABASE_UNAVAILABLE,
                    "The database refused the connection JClaw needs to start.",
                    DATABASE_CHECK, DATABASE_RETRY),
            e(SCHEMA_DDL_UNREADABLE,
                    "The scheduler schema that ships with the application could not be read.",
                    DDL_CHECK, DDL_RETRY),
            e(SCHEMA_DDL_FAILED,
                    "The database refused the statements that create the scheduler's table.",
                    DDL_FAILED_CHECK, DDL_FAILED_RETRY));

    static Map<String, ErrorTemplate> templates() {
        return TEMPLATES;
    }

    /**
     * A configured value that would not parse, naming the key and the value that was rejected.
     *
     * @param expected how the value should have looked, e.g. {@code "a whole number"}
     * @param fallback the default now in effect, rendered as the operator would type it
     */
    public static ErrorTemplate configParseFailure(String key, String rejected, String expected,
                                                   String fallback) {
        return new ErrorTemplate(CONFIG_PARSE_FAILED,
                "Configuration key '" + key + "' holds '" + rejected + "', which is not "
                        + expected + ", so the built-in default " + fallback + " is in use instead.",
                CONFIG_CHECK, CONFIG_RETRY);
    }

    /** A database that refused the boot-time connection, carrying the driver's own words. */
    public static ErrorTemplate databaseUnavailable(@Nullable String detail) {
        return new ErrorTemplate(DATABASE_UNAVAILABLE,
                withDetail("The database refused the connection JClaw needs to start", detail),
                DATABASE_CHECK, DATABASE_RETRY);
    }

    /** Shipped DDL that could not be read, carrying the file-system failure's own words. */
    public static ErrorTemplate schemaDdlUnreadable(@Nullable String detail) {
        return new ErrorTemplate(SCHEMA_DDL_UNREADABLE,
                withDetail("The scheduler schema that ships with the application could not be "
                        + "read", detail),
                DDL_CHECK, DDL_RETRY);
    }

    /** A DDL statement the database refused on a working connection, carrying the driver's own words. */
    public static ErrorTemplate schemaDdlFailed(@Nullable String detail) {
        return new ErrorTemplate(SCHEMA_DDL_FAILED,
                withDetail("The database refused the statements that create the scheduler's table",
                        detail),
                DDL_FAILED_CHECK, DDL_FAILED_RETRY);
    }

    /** A driver message is nullable and sometimes blank, and a bare trailing colon reads as truncation. */
    private static String withDetail(String sentence, @Nullable String detail) {
        return detail == null || detail.isBlank() ? sentence + "." : sentence + ": " + detail.strip();
    }
}
