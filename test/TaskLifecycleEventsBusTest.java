import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import models.Task;
import models.TaskRun;
import services.NotificationBus;
import services.TaskLifecycleEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JCLAW-22 (slice L): TaskLifecycleEvents publishes task.* events on the
 * NotificationBus (the SSE transport) in addition to writing the durable
 * event_log entry, so the Tasks UI updates in real time. Publish is
 * synchronous and the record* methods read only entity fields, so the test
 * works with in-memory entities and asserts delivery immediately.
 */
class TaskLifecycleEventsBusTest extends UnitTest {

    private final List<Runnable> unsubs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        unsubs.forEach(Runnable::run);
        unsubs.clear();
    }

    private void subscribe(Consumer<String> listener) {
        unsubs.add(NotificationBus.subscribe(listener));
    }

    private static Task task(long id, String name) {
        var t = new Task();
        t.id = id;
        t.name = name;
        t.type = Task.Type.CRON;
        return t;
    }

    private static TaskRun run(long id) {
        var r = new TaskRun();
        r.id = id;
        return r;
    }

    @Test
    void recordStartedPublishesTaskStartedWithIds() {
        var received = new ArrayList<String>();
        subscribe(received::add);

        TaskLifecycleEvents.recordStarted(task(100L, "t"), run(200L));

        assertEquals(1, received.size());
        var msg = received.getFirst();
        assertTrue(msg.contains("task.started"), msg);
        assertTrue(msg.contains("100"), msg);
        assertTrue(msg.contains("200"), msg);
    }

    @Test
    void recordCompletedPublishesTaskCompleted() {
        var received = new ArrayList<String>();
        subscribe(received::add);

        TaskLifecycleEvents.recordCompleted(task(101L, "t2"), run(201L), 1234L);

        assertEquals(1, received.size());
        assertTrue(received.getFirst().contains("task.completed"), received.getFirst());
        assertTrue(received.getFirst().contains("101"), received.getFirst());
    }

    @Test
    void recordLostPublishesTaskLostWithoutRunId() {
        var received = new ArrayList<String>();
        subscribe(received::add);

        TaskLifecycleEvents.recordLost(task(102L, "t3"), 90L);

        assertEquals(1, received.size());
        assertTrue(received.getFirst().contains("task.lost"), received.getFirst());
        assertTrue(received.getFirst().contains("102"), received.getFirst());
    }
}
