import jobs.DbSchedulerShutdownLogFilter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;
import services.EventLogger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JF-187: db-scheduler polls until ShutdownJob stops it, so a poll after SIGTERM fails when H2's own exit
 * hook has closed the database (where the fork leaves that hook in place), and db-scheduler logs that
 * failure itself.
 */
class DbSchedulerShutdownLogFilterTest extends UnitTest {

    private static final String RUNNER = "com.github.kagkarlsson.scheduler.RunUntilShutdown";
    private static final String JDBC = "com.github.kagkarlsson.shaded.jdbc.JdbcRunner";

    private final List<String> reported = new ArrayList<>();
    private volatile boolean stopping;
    private final DbSchedulerShutdownLogFilter filter = new DbSchedulerShutdownLogFilter(() -> stopping, reported::add);

    private static SQLException closed() {
        return DbException.getJdbcSQLException(ErrorCode.DATABASE_CALLED_AT_SHUTDOWN);
    }

    /** What db-scheduler logs at ERROR for a failed poll: the closed database as its cause and in a suppressed one. */
    private static RuntimeException failedPoll(SQLException closed) {
        var rollback = new RuntimeException("Failed to rollback.", closed);
        rollback.addSuppressed(new RuntimeException("Error when preparing statement.", closed));
        return rollback;
    }

    private static LogEvent event(String logger, Level level, String message, Throwable thrown) {
        return Log4jLogEvent.newBuilder().setLoggerName(logger).setLevel(level)
                .setMessage(new SimpleMessage(message)).setThrown(thrown).build();
    }

    private static String infoLine(SQLException closed) {
        return "db-scheduler: database call cut short by the shutdown: " + closed + ". Later ones are not logged.";
    }

    @Test
    void aPollFailureWhileTheJvmRunsIsLoggedAsToday() {
        var closed = closed();

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(JDBC, Level.WARN, "Exception on close of HikariProxyConnection", closed)));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", failedPoll(closed))));
        assertEquals(List.of(), reported);
    }

    @Test
    void pollsTheShutdownCutShortLogNoStackAndOneInfoLine() {
        stopping = true;
        var first = closed();

        assertEquals(Filter.Result.DENY, filter.filter(event(JDBC, Level.WARN, "Exception on close of HikariProxyConnection", first)));
        assertEquals(Filter.Result.DENY, filter.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", failedPoll(first))));
        var second = closed();
        assertEquals(Filter.Result.DENY, filter.filter(event(JDBC, Level.WARN, "Exception on close of HikariProxyConnection", second)));
        assertEquals(Filter.Result.DENY, filter.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", failedPoll(second))));
        assertEquals(List.of(infoLine(first)), reported);
    }

    @Test
    void theClosedDatabaseIsFoundAmongSuppressedExceptionsAlone() {
        stopping = true;
        var closed = closed();
        var unhandled = new RuntimeException("Unhandled");
        unhandled.addSuppressed(new RuntimeException("Error when preparing statement.", closed));

        assertEquals(Filter.Result.DENY, filter.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", unhandled)));
        assertEquals(List.of(infoLine(closed)), reported);
    }

    @Test
    void anotherDatabaseFailureDuringTheShutdownIsLoggedAsToday() {
        stopping = true;
        var lockTimeout = DbException.getJdbcSQLException(ErrorCode.LOCK_TIMEOUT_1, "scheduled_tasks");

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", failedPoll(lockTimeout))));
        assertEquals(List.of(), reported);
    }

    @Test
    void warnIsTheLowestLevelDropped() {
        stopping = true;
        var closed = closed();

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(JDBC, Level.INFO, "Closing connection", closed)));
        assertEquals(List.of(), reported);
        assertEquals(Filter.Result.DENY, filter.filter(event(JDBC, Level.WARN, "Exception on close of HikariProxyConnection", closed)));
        assertEquals(List.of(infoLine(closed)), reported);
    }

    @Test
    void aWarnWithoutAnExceptionIsLoggedAsToday() {
        stopping = true;

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(
                "com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization", Level.WARN, "No database-specific jdbc-overrides applied", null)));
        assertEquals(List.of(), reported);
    }

    @Test
    void aCycleInTheCauseChainEndsTheSearch() {
        stopping = true;
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);
        a.addSuppressed(b);

        var result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> filter.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", a)));
        assertEquals(Filter.Result.NEUTRAL, result);
    }

    @Test
    void theInfoLineGoesToTheEventLogAsAShutdownEvent() {
        var production = new DbSchedulerShutdownLogFilter(() -> true, DbSchedulerShutdownLogFilter::toEventLog);
        var closed = closed();

        var events = EventLogger.captureForTest(
                () -> production.filter(event(RUNNER, Level.ERROR, "Unhandled exception. Will keep running.", failedPoll(closed))));

        assertEquals(List.of(new EventLogger.Captured("INFO", "shutdown", null, null, infoLine(closed), null)), events);
    }

    private static final String PROBE = """
            import com.github.kagkarlsson.scheduler.Scheduler;
            import com.github.kagkarlsson.scheduler.task.helper.Tasks;
            import com.zaxxer.hikari.HikariConfig;
            import com.zaxxer.hikari.HikariDataSource;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.time.Duration;
            import org.apache.logging.log4j.LogManager;
            import org.apache.logging.log4j.core.LoggerContext;
            import org.apache.logging.log4j.core.filter.CompositeFilter;

            public class Probe {
                public static void main(String[] args) throws Exception {
                    if (args[2].equals("filtered")) {
                        for (int i = 0; i < 2; i++) {
                            jobs.DbSchedulerShutdownLogFilter.install(new jobs.DbSchedulerShutdownLogFilter(
                                    utils.JvmShutdown::inProgress, line -> System.out.println("REPORT " + line)));
                        }
                        var installed = ((LoggerContext) LogManager.getContext(false)).getConfiguration()
                                .getLoggerConfig("com.github.kagkarlsson").getFilter();
                        System.out.println("FILTERS " + (installed instanceof CompositeFilter c ? c.size() : installed == null ? 0 : 1));
                    }
                    var config = new HikariConfig();
                    config.setJdbcUrl("jdbc:h2:retry:" + args[0] + ";MODE=MYSQL;AUTO_SERVER=TRUE");
                    config.setAutoCommit(false);
                    var datasource = new HikariDataSource(config);
                    try (var connection = datasource.getConnection(); var statement = connection.createStatement()) {
                        var ddl = String.join(" ", Files.readAllLines(Path.of(args[1])).stream()
                                .filter(line -> !line.strip().startsWith("--")).toList());
                        for (var sql : ddl.split(";")) if (!sql.isBlank()) statement.execute(sql);
                        connection.commit();
                    }
                    var task = Tasks.oneTime("probe-task").execute((instance, context) -> {});
                    Scheduler.create(datasource, task).pollingInterval(Duration.ofMillis(100))
                            .commitWhenAutocommitDisabled(true).build().start();
                    Thread.sleep(500);
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                    System.out.println("STOPPING");
                    System.exit(0);
                }
            }
            """;

    private static final String PROBE_LOG_CONFIG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Configuration status="WARN" shutdownHook="disable">
                <Appenders>
                    <Console name="out" target="SYSTEM_OUT">
                        <PatternLayout pattern="%p %c - %m%n%ex"/>
                    </Console>
                </Appenders>
                <Loggers>
                    <Root level="INFO">
                        <AppenderRef ref="out"/>
                    </Root>
                </Loggers>
            </Configuration>
            """;

    /**
     * Runs db-scheduler on an H2 database opened as production opens it, polling every 100 ms, then exits
     * while a hook holds the JVM up for 1.5 s: H2's own exit hook closes the database under the polls.
     * Hikari hands back a connection used in the last 500 ms without checking it, so polls 100 ms apart get
     * the closed one every time; at production's 2 s it is checked, and a new connection reopens the database.
     */
    private static List<String> runProbe(Path dir, String mode) throws Exception {
        var probe = dir.resolve("Probe.java");
        Files.writeString(probe, PROBE);
        var logConfig = dir.resolve("log4j2-probe.xml");
        Files.writeString(logConfig, PROBE_LOG_CONFIG);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var classpath = Path.of("build/classes/java/main").toAbsolutePath() + File.pathSeparator + System.getProperty("java.class.path");
        var process = new ProcessBuilder(java, "-Dlog4j2.configurationFile=" + logConfig, "-cp", classpath, probe.toString(),
                dir.resolve("db").resolve("probe").toString(), Play.getFile("conf/db/db_scheduler_h2.sql").getAbsolutePath(), mode)
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe JVM exited");
        assertEquals(0, process.exitValue(), output);
        var lines = output.lines().toList();
        assertTrue(lines.contains("STOPPING"), output);
        return lines;
    }

    private static List<String> afterStopping(List<String> lines, String prefix) {
        return lines.subList(lines.indexOf("STOPPING"), lines.size()).stream().filter(l -> l.startsWith(prefix)).toList();
    }

    @Test
    void withoutTheFilterAPollAfterTheShutdownBeganLogsAWarnAndAnErrorStack(@TempDir Path dir) throws Exception {
        var lines = runProbe(dir, "unfiltered");

        var output = String.join("\n", lines);
        assertFalse(afterStopping(lines, "ERROR " + RUNNER + " - Unhandled exception. Will keep running.").isEmpty(), output);
        assertFalse(afterStopping(lines, "WARN " + JDBC + " - Exception on close of HikariProxyConnection").isEmpty(), output);
        assertTrue(output.contains("[" + ErrorCode.DATABASE_CALLED_AT_SHUTDOWN + "-"), output);
    }

    @Test
    void withTheFilterAPollAfterTheShutdownBeganLogsOneInfoLineAndNoStack(@TempDir Path dir) throws Exception {
        var lines = runProbe(dir, "filtered");

        var output = String.join("\n", lines);
        assertTrue(lines.contains("FILTERS 1"), output);
        assertEquals(List.of(), afterStopping(lines, "WARN com.github.kagkarlsson"), output);
        assertEquals(List.of(), afterStopping(lines, "ERROR com.github.kagkarlsson"), output);
        assertEquals(List.of("REPORT " + infoLine(closed())), afterStopping(lines, "REPORT "), output);
    }
}
