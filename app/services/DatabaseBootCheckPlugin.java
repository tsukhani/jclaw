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
import java.util.Properties;

/**
 * Explains an unreachable database at boot with the 3-part startup template (JCLAW-1136).
 *
 * <p>{@code DBPlugin} (slot 300) opens a test connection in {@code onApplicationStart} and
 * throws when it fails, which aborts {@code Play.start} before {@code afterApplicationStart} —
 * so no {@code @OnApplicationStart} job ever runs to explain it, and the operator saw only
 * Play's "Cannot connected to the database" line. Registered at slot 290, this makes the same
 * connection first and stops boot with the template when it is refused.
 *
 * <p>A {@code conf/play.plugins} class is compiled before the rest of {@code app/}, so this
 * reaches only framework types and the two startup template classes.
 */
public class DatabaseBootCheckPlugin extends PlayPlugin {

    @Override
    public void onApplicationStart() {
        // A dev-mode restart that kept its pool is already connected; opening and closing a
        // connection then gains nothing.
        if (DB.datasource != null) return;
        for (var name : Configuration.getDbNames()) {
            var config = new Configuration(name);
            var message = failureMessage(config.getProperty("db", ""), config.getProperty("db.url"),
                    config.getProperty("db.driver"), config.getProperty("db.user"),
                    config.getProperty("db.pass"), Play.classloader);
            if (message != null) {
                Logger.error("%s", message);
                // One line, so the stack trace Play prints for it does not repeat the paragraph.
                throw new DatabaseException("Database [" + name + "] refused the connection at startup; "
                        + "the message above says what to check.");
            }
        }
    }

    /**
     * The rendered startup message when the configured database refuses a connection, or null
     * when it accepts one or when this check cannot judge the configuration — a JNDI datasource,
     * no URL, a driver that cannot be loaded or does not accept the URL. {@code DBPlugin} still
     * reports those in its own words.
     *
     * <p>Public because test sources are the default package.
     */
    public static @Nullable String failureMessage(String datasource, @Nullable String url,
                                                  @Nullable String driverClass, @Nullable String user,
                                                  @Nullable String pass, ClassLoader loader) {
        if (datasource.startsWith("jndi:") || datasource.startsWith("java:")
                || url == null || url.isBlank() || driverClass == null || driverClass.isBlank()) {
            return null;
        }
        Driver driver;
        try {
            driver = (Driver) Class.forName(driverClass, true, loader).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException _) {
            return null;
        }
        // DriverManager.getConnection's convention, which DBPlugin's own check follows.
        var info = new Properties();
        if (user != null) info.put("user", user);
        if (pass != null) info.put("password", pass);
        try (Connection _ = driver.connect(url, info)) {
            return null;
        } catch (SQLException e) {
            return ErrorRendering.PLAIN.render(StartupErrorTemplates.databaseUnavailable(e.getMessage()));
        }
    }
}
