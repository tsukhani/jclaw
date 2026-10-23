import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.jdbc.DefaultJdbcCustomization;
import jobs.DbSchedulerBootstrapJob;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.nio.file.Files;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * JF-22: the deferred start and the shutdown stop of db-scheduler. Each test drives a real
 * scheduler over its own in-memory H2 and its own {@link DbSchedulerBootstrapJob.Lifecycle}, so
 * neither the shared test database nor the process-wide scheduler is touched.
 */
class DbSchedulerBootstrapJobLifecycleTest extends UnitTest {

    private final List<Scheduler> built = new ArrayList<>();
    private JdbcDataSource dataSource;
    private Connection keepAlive;

    @BeforeEach
    void privateDatabase() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:scheduler-lifecycle-" + System.nanoTime());
        // An in-memory H2 is dropped with its last connection, and db-scheduler closes each of its own.
        keepAlive = dataSource.getConnection();
        var ddl = Files.readString(Play.getFile("conf/db/db_scheduler_h2.sql").toPath()).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        try (var s = keepAlive.createStatement()) {
            for (String statement : ddl.split(";")) {
                if (!statement.isBlank()) s.execute(statement);
            }
        }
    }

    @AfterEach
    void stopEverySchedulerAndDropTheDatabase() throws Exception {
        for (var scheduler : built) {
            if (!scheduler.getSchedulerState().isShuttingDown()) scheduler.stop();
        }
        keepAlive.close();
    }

    private Scheduler newScheduler() {
        var scheduler = Scheduler.create(dataSource, List.of())
                .jdbcCustomization(new DefaultJdbcCustomization(false))
                .pollingInterval(Duration.ofSeconds(1))
                .build();
        built.add(scheduler);
        return scheduler;
    }

    @Test
    void aSchedulerStoppedWhileTheGateWaitsIsNeverStarted() {
        var lifecycle = new DbSchedulerBootstrapJob.Lifecycle();
        var scheduler = newScheduler();
        lifecycle.publish(scheduler);
        lifecycle.stop(() -> {});

        assertFalse(lifecycle.startIfCurrent(scheduler));
        assertFalse(scheduler.getSchedulerState().isStarted());
        assertNull(lifecycle.current());
    }

    @Test
    void aSchedulerADevReloadReplacedIsNeverStarted() {
        var lifecycle = new DbSchedulerBootstrapJob.Lifecycle();
        var previous = newScheduler();
        lifecycle.publish(previous);
        lifecycle.stop(() -> {});
        var next = newScheduler();
        lifecycle.publish(next);

        assertFalse(lifecycle.startIfCurrent(previous));
        assertFalse(previous.getSchedulerState().isStarted());
        assertSame(next, lifecycle.current());
        assertFalse(next.getSchedulerState().isStarted());
    }

    @Test
    void aGateThatWakesDuringTheStopStartsNothing() throws Exception {
        var lifecycle = new DbSchedulerBootstrapJob.Lifecycle();
        var scheduler = newScheduler();
        lifecycle.publish(scheduler);
        var gate = new AtomicReference<Thread>();
        var started = new AtomicBoolean();

        lifecycle.stop(() -> {
            gate.set(Thread.ofPlatform().start(() -> started.set(lifecycle.startIfCurrent(scheduler))));
            // Hold the stop until the gate is either waiting on it or has already been and gone.
            while (gate.get().getState() != Thread.State.BLOCKED
                    && gate.get().getState() != Thread.State.TERMINATED) {
                Thread.onSpinWait();
            }
        });

        assertTrue(gate.get().join(Duration.ofSeconds(10)), "the gate finished");
        assertFalse(started.get());
        assertFalse(scheduler.getSchedulerState().isStarted());
        assertNull(lifecycle.current());
    }

    @Test
    void aPublishedSchedulerStartsOnceMcpSettlesAndALaterStopStopsIt() {
        var lifecycle = new DbSchedulerBootstrapJob.Lifecycle();
        var scheduler = newScheduler();
        lifecycle.publish(scheduler);

        assertTrue(lifecycle.startIfCurrent(scheduler));
        assertTrue(scheduler.getSchedulerState().isStarted());

        var cancels = new AtomicInteger();
        lifecycle.stop(cancels::incrementAndGet);
        assertEquals(1, cancels.get());
        assertTrue(scheduler.getSchedulerState().isShuttingDown());
        assertNull(lifecycle.current());
    }

    @Test
    void aStopWithNothingPublishedDoesNothing() {
        var lifecycle = new DbSchedulerBootstrapJob.Lifecycle();
        var cancels = new AtomicInteger();

        lifecycle.stop(cancels::incrementAndGet);

        assertEquals(0, cancels.get());
        assertNull(lifecycle.current());
    }
}
