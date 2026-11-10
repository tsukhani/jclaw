package services;

import org.jspecify.annotations.Nullable;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.db.Configuration;
import play.db.DB;
import play.exceptions.DatabaseException;
import utils.ErrorRendering;
import utils.StartupErrorTemplates;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Explains an unreachable database at boot with the 3-part startup template (JCLAW-1136), and
 * keeps the database open until the pool is up (JF-266).
 *
 * <p>{@code DBPlugin} (slot 300) opens a test connection in {@code onApplicationStart} and
 * throws when it fails, which aborts {@code Play.start} before {@code afterApplicationStart} —
 * so no {@code @OnApplicationStart} job ever runs to explain it, and the operator saw only
 * Play's "Cannot connected to the database" line. Registered at slot 290, this makes the same
 * connection first and stops boot with the template when it is refused.
 *
 * <p>The connection it opened stays open until {@link #afterApplicationStart()}. Closing it at
 * once closed the database too, so the boot opened the H2 file three times (this check, DBPlugin's
 * own test connection, then the pool), and with {@code AUTO_SERVER=TRUE} each open starts an H2
 * TCP server and confirms it over a loopback socket whose close blocked the boot thread for a
 * second on about half of the starts measured on 2026-11-10 (JF-265). While a connection is held,
 * the later opens join the open database and the server starts once. {@code DB_CLOSE_DELAY} would
 * do the same but H2 persists it inside the database file, where it outlived the setting and left
 * the lock file behind at exit.
 *
 * <p>A {@code conf/play.plugins} class is compiled before the rest of {@code app/}, so this
 * reaches only framework types and the two startup template classes.
 */
public class DatabaseBootCheckPlugin extends PlayPlugin {

    /** What the check found: an open connection to keep, a message to stop boot with, or neither when it cannot judge. */
    public record Probe(@Nullable Connection connection, @Nullable String message) {}

    private static final List<Connection> HELD = new ArrayList<>();

    @Override
    public void onApplicationStart() {
        // A failed start never reaches onApplicationStop (Play.stop checks `started`), so a boot
        // retried after one must not hold a second connection.
        release();
        // A dev-mode restart that kept its pool is already connected; opening and closing a
        // connection then gains nothing.
        if (DB.getDataSource() != null) return;
        for (var name : Configuration.getDbNames()) {
            var config = new Configuration(name);
            var probe = probe(config.getProperty("db", ""), config.getProperty("db.url"),
                    config.getProperty("db.driver"), config.getProperty("db.user"),
                    config.getProperty("db.pass"), Play.classloader);
            if (probe.message() != null) {
                release();
                Logger.error("%s", probe.message());
                // One line, so the stack trace Play prints for it does not repeat the paragraph.
                throw new DatabaseException("Database [" + name + "] refused the connection at startup; "
                        + "the message above says what to check.");
            }
            if (probe.connection() == null) continue;
            if (holds(config.getProperty("db.url"))) {
                hold(probe.connection());
            } else {
                closeQuietly(probe.connection());
            }
        }
    }

    /**
     * Whether the boot keeps its connection to {@code url}. An in-memory H2 database is not kept:
     * DBPlugin opens one as user {@code sa} (its default for {@code jdbc:h2:mem:}), which a database
     * this check created as a different user refuses, and reopening it costs nothing.
     *
     * <p>Public because test sources are the default package.
     */
    public static boolean holds(@Nullable String url) {
        return url != null && !url.startsWith("jdbc:h2:mem:");
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException _) {
            // The connection served its purpose; nothing depends on it now.
        }
    }

    /** The pool holds its own connections from here on, so the database stays open without this one. */
    @Override
    public void afterApplicationStart() {
        release();
    }

    @Override
    public void onApplicationStop() {
        release();
    }

    /** Keeps {@code connection} open until {@link #release()}. */
    public static synchronized void hold(Connection connection) {
        HELD.add(connection);
    }

    /** Closes every held connection; a close that fails is logged and does not keep the others open. */
    public static synchronized void release() {
        for (var connection : HELD) {
            try {
                connection.close();
            } catch (SQLException e) {
                Logger.warn("Boot-check connection did not close cleanly: %s", e.getMessage());
            }
        }
        HELD.clear();
    }

    /**
     * The rendered startup message when the configured database refuses a connection, or null
     * when it accepts one or when this check cannot judge the configuration. The connection the
     * probe opened is closed before returning; {@link #probe} keeps it.
     *
     * <p>Public because test sources are the default package.
     */
    public static @Nullable String failureMessage(String datasource, @Nullable String url,
                                                  @Nullable String driverClass, @Nullable String user,
                                                  @Nullable String pass, ClassLoader loader) {
        var probe = probe(datasource, url, driverClass, user, pass, loader);
        if (probe.connection() != null) closeQuietly(probe.connection());
        return probe.message();
    }

    /**
     * Connects as {@code DriverManager.getConnection} would and returns the open connection, or
     * the rendered startup message when the database refuses it, or neither when this check cannot
     * judge the configuration — a JNDI datasource, no URL, a driver that cannot be loaded or does
     * not accept the URL. {@code DBPlugin} still reports those in its own words.
     *
     * <p>Public because test sources are the default package.
     */
    public static Probe probe(String datasource, @Nullable String url,
                              @Nullable String driverClass, @Nullable String user,
                              @Nullable String pass, ClassLoader loader) {
        if (datasource.startsWith("jndi:") || datasource.startsWith("java:")
                || url == null || url.isBlank() || driverClass == null || driverClass.isBlank()) {
            return new Probe(null, null);
        }
        Driver driver;
        try {
            driver = (Driver) Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException _) {
            return new Probe(null, null);
        }
        // DriverManager.getConnection's convention, which DBPlugin's own check follows.
        var info = new Properties();
        if (user != null) info.put("user", user);
        if (pass != null) info.put("password", pass);
        try {
            var connection = driver.connect(url, info);
            // A driver that does not accept the URL answers null rather than throwing.
            return new Probe(connection, null);
        } catch (SQLException e) {
            return new Probe(null,
                    ErrorRendering.PLAIN.render(StartupErrorTemplates.databaseUnavailable(e.getMessage())));
        }
    }
}
