package jobs;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.h2.api.ErrorCode;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import utils.JvmShutdown;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Drops db-scheduler's WARN and ERROR events caused by H2's "Database is already closed" (90121) once the JVM
 * is shutting down, and reports the first as one INFO line (JF-187). H2's exit hook closes the database at
 * SIGTERM while db-scheduler keeps polling until ShutdownJob stops it, and the library logs each failed poll
 * itself, out of reach of {@link PeriodicJob}'s guard (JF-23).
 */
public final class DbSchedulerShutdownLogFilter extends AbstractFilter {

    /** The logger config that db-scheduler's loggers, its shaded JDBC runner's included, resolve to. */
    private static final String LOGGER = "com.github.kagkarlsson";

    private final BooleanSupplier stopping;
    private final Consumer<String> report;
    private final AtomicBoolean reported = new AtomicBoolean();

    /**
     * @param stopping whether the JVM is shutting down: {@link JvmShutdown#inProgress} in production
     * @param report   receives the INFO line, once per filter
     */
    public DbSchedulerShutdownLogFilter(BooleanSupplier stopping, Consumer<String> report) {
        this.stopping = stopping;
        this.report = report;
    }

    /** Installs the production filter before the scheduler first polls. */
    public static void install() {
        install(new DbSchedulerShutdownLogFilter(JvmShutdown::inProgress, DbSchedulerShutdownLogFilter::toEventLog));
    }

    /**
     * Adds {@code filter} to db-scheduler's logger config, creating that config if the file configuration has
     * none, and removes one an earlier start installed: matched by class name, since a dev reload loads this
     * class again in a new class loader.
     */
    @SuppressWarnings("java:S1872") // by name on purpose: a dev reload's copy is another Class, which instanceof misses
    public static void install(DbSchedulerShutdownLogFilter filter) {
        var context = (LoggerContext) LogManager.getContext(false);
        var configuration = context.getConfiguration();
        var loggerConfig = configuration.getLoggerConfig(LOGGER);
        if (!LOGGER.equals(loggerConfig.getName())) {
            loggerConfig = new LoggerConfig(LOGGER, null, true);
            configuration.addLogger(LOGGER, loggerConfig);
        }
        for (var installed : filtersOf(loggerConfig)) {
            if (installed.getClass().getName().equals(DbSchedulerShutdownLogFilter.class.getName())) {
                loggerConfig.removeFilter(installed);
            }
        }
        filter.start();
        loggerConfig.addFilter(filter);
        context.updateLoggers();
    }

    /** The production report. Public because test sources are the default package. */
    public static void toEventLog(String line) {
        EventLogger.info("shutdown", line);
    }

    @Override
    public Result filter(LogEvent event) {
        var thrown = event.getThrown();
        if (thrown == null || !event.getLevel().isMoreSpecificThan(Level.WARN)) return Result.NEUTRAL;
        var closed = closedDatabase(thrown);
        if (closed == null || !stopping.getAsBoolean()) return Result.NEUTRAL;
        if (reported.compareAndSet(false, true)) {
            report.accept("db-scheduler: database call cut short by the shutdown: %s. Later ones are not logged."
                    .formatted(closed));
        }
        return Result.DENY;
    }

    /** db-scheduler wraps the failure, and puts it in a suppressed exception when the rollback fails too. */
    private static @Nullable SQLException closedDatabase(Throwable thrown) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        pending.push(thrown);
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (!seen.add(next)) continue;
            if (next instanceof SQLException sql && sql.getErrorCode() == ErrorCode.DATABASE_CALLED_AT_SHUTDOWN) return sql;
            var cause = next.getCause();
            if (cause != null) pending.push(cause);
            for (var suppressed : next.getSuppressed()) pending.push(suppressed);
        }
        return null;
    }

    private static List<Filter> filtersOf(LoggerConfig loggerConfig) {
        var filter = loggerConfig.getFilter();
        if (filter == null) return List.of();
        if (filter instanceof CompositeFilter composite) return List.of(composite.getFiltersArray());
        return List.of(filter);
    }
}
