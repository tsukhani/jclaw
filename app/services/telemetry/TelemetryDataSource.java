package services.telemetry;

import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * The datasource Hikari draws its pooled connections from (JCLAW-1164): the raw driver
 * datasource while export is off, the OpenTelemetry-wrapped one while it is on. A switch
 * soft-evicts the pool, so connections are re-created in the new state instead of lingering
 * in the old one. Installed from {@code db.factory} at plugin start, before the SDK exists,
 * so it reaches nothing else in this package; {@link OtelRuntime} calls in.
 */
public final class TelemetryDataSource implements DataSource {

    private static final List<TelemetryDataSource> ALL = new CopyOnWriteArrayList<>();

    static {
        // opentelemetry-jdbc 2.31 emits db.statement/db.system unless the stable database
        // conventions are opted in, and the flag is read once when the instrumenter first loads.
        if (System.getProperty("otel.semconv-stability.opt-in") == null
                && System.getenv("OTEL_SEMCONV_STABILITY_OPT_IN") == null) {
            System.setProperty("otel.semconv-stability.opt-in", "database");
        }
    }

    private final DataSource raw;
    private final HikariDataSource pool;
    private volatile DataSource current;
    private volatile @Nullable OpenTelemetry instrumentedWith;

    private TelemetryDataSource(DataSource raw, HikariDataSource pool) {
        this.raw = raw;
        this.pool = pool;
        this.current = raw;
    }

    /** Makes one {@code pool}'s inner datasource; must run before the pool starts. */
    static void install(HikariDataSource pool, DataSource raw) {
        var ds = new TelemetryDataSource(raw, pool);
        pool.setDataSource(ds);
        ALL.add(ds);
    }

    /** A non-null {@code api} switches every pool to instrumented connections; null switches back. */
    static void attach(@Nullable OpenTelemetry api) {
        for (var ds : ALL) {
            ds.switchTo(api);
        }
    }

    /** True while new pooled connections come from the instrumented datasource. */
    public boolean instrumented() {
        return current != raw;
    }

    private synchronized void switchTo(@Nullable OpenTelemetry api) {
        if (api == instrumentedWith) {
            return;
        }
        instrumentedWith = api;
        // No span per checkout: the pool's own connects are noise next to the statements.
        current = api == null
                ? raw
                : JdbcTelemetry.builder(api).setDataSourceInstrumenterEnabled(false).build().wrap(raw);
        var running = pool.getHikariPoolMXBean();
        if (running == null) {
            return;
        }
        // Evicting closes every idle connection at once, and an H2 in-memory database is
        // dropped with its last connection: hold one across the eviction, and return its
        // successor to the pool before letting go.
        try (var hold = pool.getConnection()) {
            running.softEvictConnections();
            try (var successor = pool.getConnection()) {
                successor.isValid(1);
            }
        } catch (SQLException e) {
            play.Logger.warn(e, "JDBC telemetry switch could not rotate the pool; existing connections keep their old state");
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current.getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return raw.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        raw.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        raw.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return raw.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return raw.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : raw.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || raw.isWrapperFor(iface);
    }
}
