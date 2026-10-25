import agents.DangerousActionGate;
import agents.TaskRunSink;
import models.Agent;
import models.Message;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.NotificationBus;
import services.TaskExecutor;
import services.TaskLifecycleEvents;
import services.TaskRunReports;
import services.Tx;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A task run an agent started from a chat reports its result back to that chat and resumes the agent.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: play1 runs test classes concurrently, and every row here
 * belongs to an agent this class creates. Setup and reads commit on a fresh thread, as the report
 * runs on its own thread and sees only what has been committed.
 */
class TaskRunReportsTest extends UnitTest {

    private static final String DO_NOT_REPEAT = " Tell the user how the run ended, without fetching or repeating its output here.";
    private final List<Agent> agents = new ArrayList<>();
    private final List<String> turns = new CopyOnWriteArrayList<>();
    private final TaskRunReports.CompletionTurn recorder = (agent, conversation, ownerInitiated) ->
            turns.add(agent.id + "/" + conversation.id + "/" + ownerInitiated + "/" + DangerousActionGate.ownerInitiated());

    @AfterEach
    void cleanUp() {
        for (var agent : agents) {
            fresh(() -> {
                Agent row = Agent.findById(agent.id);
                if (row != null) AgentService.delete(row);
                return null;
            });
        }
    }

    // ==================== the report ====================

    @Test
    void aCompletedRunReportsWhereItsOutputWentButNotTheOutput() {
        var agent = agent("report");
        var chat = webChat(agent);
        var task = task(agent, "daily-briefing", "telegram:878224171");

        assertTrue(fresh(() -> TaskRunReports.request(task, chat, true)));
        var link = fresh(() -> TaskRunReports.claimForNewRun(task));
        assertEquals(new TaskRunReports.Link(chat, true), link);
        assertNull(fresh(() -> ((Task) Task.findById(task)).reportConversationId), "the task's request is taken");

        var run = run(task, link, r -> {
            r.status = TaskRun.Status.COMPLETED;
            r.durationMs = 67_540L;
            r.outputSummary = "AQI 28 (Good).";
            r.deliveryStatus = TaskRun.DeliveryStatus.DELIVERED;
            r.deliveryTarget = "telegram:878224171";
        });
        assertTrue(reportOnItsOwnThread(run, recorder));

        var posted = messages(chat);
        assertEquals(1, posted.size(), posted.toString());
        var message = posted.getFirst();
        assertEquals("user", message.role);
        assertEquals(TaskRunReports.MESSAGE_KIND_RESULT, message.messageKind);
        assertEquals("[task result] Task 'daily-briefing' (run %d) completed in 67.5 s. Its output was delivered to telegram. Tell the user how the run ended, without fetching or repeating its output here."
                .formatted(run), message.content);
        assertEquals(("{\"taskId\":%d,\"taskName\":\"daily-briefing\",\"runId\":%d,\"status\":\"COMPLETED\",\"durationMs\":67540,"
                + "\"deliveryStatus\":\"DELIVERED\",\"headline\":\"Task 'daily-briefing' (run %d) completed in 67.5 s.\","
                + "\"outcome\":\"Its output was delivered to telegram.\"}").formatted(task, run, run), message.metadata);
        assertEquals(List.of(agent.id + "/" + chat + "/true/false"), turns,
                "the turn gets the asking turn's trust as a value, and inherits none from the reporting thread");
    }

    @Test
    void aRunWhoseDeliveryFailedSaysWhy() {
        var agent = agent("undelivered");
        var chat = webChat(agent);
        var run = run(task(agent, "digest", "slack:C0123"), new TaskRunReports.Link(chat, true), r -> {
            r.status = TaskRun.Status.COMPLETED;
            r.durationMs = 1_000L;
            r.outputSummary = "digest body";
            r.deliveryStatus = TaskRun.DeliveryStatus.NOT_DELIVERED;
            r.deliveryTarget = "slack:C0123";
            r.deliveryError = "channel_not_found";
        });

        assertTrue(reportOnItsOwnThread(run, recorder));

        assertEquals("[task result] Task 'digest' (run %d) completed in 1.0 s. Its output was not delivered to slack: channel_not_found. Tell the user how the run ended, without fetching or repeating its output here."
                .formatted(run), messages(chat).getFirst().content);
    }

    @Test
    void aRunThatDeliveredNothingItselfSaysWhereItsOutputIs() {
        var agent = agent("elsewhere");
        var chat = webChat(agent);
        var link = new TaskRunReports.Link(chat, true);
        var noDelivery = run(task(agent, "no-delivery", null), link, r -> notRequested(r, null));
        var byTool = run(task(agent, "by-tool", "tool:send_gmail_message"), link, r -> notRequested(r, null));
        var byMessageTool = run(task(agent, "by-message-tool", "telegram:1"), link, r -> notRequested(r, "telegram:1"));
        var unrecorded = run(task(agent, "unrecorded", "telegram:1"), link, r -> {
            r.status = TaskRun.Status.COMPLETED;
            r.durationMs = 1_000L;
        });

        for (var run : List.of(noDelivery, byTool, byMessageTool, unrecorded)) reportOnItsOwnThread(run, recorder);

        assertEquals(List.of(
                "[task result] Task 'no-delivery' (run %d) completed in 1.0 s. The task has no delivery set, so its output is only on the Tasks page. Tell the user how the run ended, without fetching or repeating its output here.".formatted(noDelivery),
                "[task result] Task 'by-tool' (run %d) completed in 1.0 s. The task delivers its output itself, with send_gmail_message. Tell the user how the run ended, without fetching or repeating its output here.".formatted(byTool),
                "[task result] Task 'by-message-tool' (run %d) completed in 1.0 s. The run sent its output itself with the 'message' tool. Tell the user how the run ended, without fetching or repeating its output here.".formatted(byMessageTool),
                "[task result] Task 'unrecorded' (run %d) completed in 1.0 s. Its delivery was not recorded. Tell the user how the run ended, without fetching or repeating its output here.".formatted(unrecorded)),
                messages(chat).stream().map(m -> m.content).toList());
    }

    @Test
    void aFailedRunReportsItsError() {
        var agent = agent("failed");
        var chat = webChat(agent);
        var run = run(task(agent, "nightly", null), new TaskRunReports.Link(chat, false), r -> {
            r.status = TaskRun.Status.FAILED;
            r.durationMs = 1_200L;
            r.error = "Provider refused: 402";
        });

        assertTrue(reportOnItsOwnThread(run, recorder));

        assertEquals("[task result] Task 'nightly' (run %d) failed. The error was: Provider refused: 402. Tell the user how the run ended, without fetching or repeating its output here.".formatted(run),
                messages(chat).getFirst().content);
        assertEquals(List.of(agent.id + "/" + chat + "/false/false"), turns);
    }

    @Test
    void aCancelledRunSaysWhoOrWhatStoppedIt() {
        var agent = agent("cancelled");
        var chat = webChat(agent);
        var link = new TaskRunReports.Link(chat, true);
        var task = task(agent, "long-scan", null);
        var byOperator = run(task, link, r -> {
            r.status = TaskRun.Status.CANCELLED;
            r.outputSummary = "Cancelled by operator";
        });
        var byTimeLimit = run(task, link, r -> {
            r.status = TaskRun.Status.CANCELLED;
            r.outputSummary = "Stopped after 30 minutes, the most a fire may run";
        });

        reportOnItsOwnThread(byOperator, recorder);
        reportOnItsOwnThread(byTimeLimit, recorder);

        assertEquals(List.of(
                "[task result] Task 'long-scan' (run %d) was cancelled. Cancelled by operator.%s".formatted(byOperator, DO_NOT_REPEAT),
                "[task result] Task 'long-scan' (run %d) was cancelled. Stopped after 30 minutes, the most a fire may run.%s"
                        .formatted(byTimeLimit, DO_NOT_REPEAT)),
                messages(chat).stream().map(m -> m.content).toList());
    }

    @Test
    void anErrorIsShownMaskedWhereTheOwnerReadsAloneAndOnlyPointedToInAGroup() {
        var agent = agent("errors");
        var web = new TaskRunReports.Link(webChat(agent), true);
        var group = new TaskRunReports.Link(
                fresh(() -> ConversationService.create(Agent.findById(agent.id), "telegram", "-1001234567890").id), false);
        var task = task(agent, "keyed-call", "slack:C0123");
        var error = "HTTP 400 from https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSyA1b2C3d4E5f6G7h8I9j0KlMnOpQrStUv";
        Consumer<TaskRun> failed = r -> {
            r.status = TaskRun.Status.FAILED;
            r.error = error;
        };
        Consumer<TaskRun> undelivered = r -> {
            notRequested(r, "slack:C0123");
            r.deliveryStatus = TaskRun.DeliveryStatus.NOT_DELIVERED;
            r.deliveryError = error;
        };
        var failedInWeb = run(task, web, failed);
        var failedInGroup = run(task, group, failed);
        var undeliveredInGroup = run(task, group, undelivered);

        for (var run : List.of(failedInWeb, failedInGroup, undeliveredInGroup)) reportOnItsOwnThread(run, recorder);

        assertEquals(List.of(("[task result] Task 'keyed-call' (run %d) failed. The error was: HTTP 400 from "
                        + "https://generativelanguage.googleapis.com/v1beta/models?key=[redacted].%s").formatted(failedInWeb, DO_NOT_REPEAT)),
                messages(web.conversationId()).stream().map(m -> m.content).toList());
        assertEquals(List.of(
                "[task result] Task 'keyed-call' (run %d) failed. The reason is on the Tasks page.%s".formatted(failedInGroup, DO_NOT_REPEAT),
                ("[task result] Task 'keyed-call' (run %d) completed in 1.0 s. Its output was not delivered to slack; "
                        + "the reason is on the Tasks page.%s").formatted(undeliveredInGroup, DO_NOT_REPEAT)),
                messages(group.conversationId()).stream().map(m -> m.content).toList());
    }

    @Test
    void aRunReportsOnce() {
        var agent = agent("once");
        var chat = webChat(agent);
        var run = run(task(agent, "once", null), new TaskRunReports.Link(chat, true), r -> notRequested(r, null));

        assertTrue(reportOnItsOwnThread(run, recorder));
        assertFalse(reportOnItsOwnThread(run, recorder), "a second report of the same run");

        assertEquals(1, messages(chat).size());
        assertEquals(1, turns.size());
    }

    @Test
    void aRunNobodyAskedAboutAndOneWhoseChatIsGoneReportNothing() {
        var agent = agent("nobody");
        var chat = webChat(agent);
        var task = task(agent, "quiet", null);
        var unasked = run(task, null, r -> notRequested(r, null));
        var orphaned = run(task, new TaskRunReports.Link(999_999_999L, true), r -> notRequested(r, null));

        assertFalse(reportOnItsOwnThread(unasked, recorder));
        assertFalse(reportOnItsOwnThread(orphaned, recorder));

        assertEquals(List.of(), messages(chat));
        assertEquals(List.of(), turns);
    }

    // ==================== asking for a report ====================

    @Test
    void askingWhileARunIsInFlightAttachesTheReportToThatRun() {
        var agent = agent("inflight");
        var chat = webChat(agent);
        var task = task(agent, "busy", null);
        var running = run(task, null, r -> r.status = TaskRun.Status.RUNNING);

        assertTrue(fresh(() -> TaskRunReports.request(task, chat, false)));

        assertEquals(chat, fresh(() -> ((TaskRun) TaskRun.findById(running)).reportConversationId));
        assertNull(fresh(() -> ((Task) Task.findById(task)).reportConversationId));
    }

    @Test
    void theFirstChatToAskKeepsTheReport() {
        var agent = agent("first");
        var first = webChat(agent);
        var second = webChat(agent);
        var queued = task(agent, "queued", null);
        var busy = task(agent, "in-flight", null);
        var running = run(busy, null, r -> r.status = TaskRun.Status.RUNNING);

        assertTrue(fresh(() -> TaskRunReports.request(queued, first, true)));
        assertFalse(fresh(() -> TaskRunReports.request(queued, second, true)));
        assertTrue(fresh(() -> TaskRunReports.request(queued, first, true)), "asking again from the same chat");
        assertTrue(fresh(() -> TaskRunReports.request(busy, first, true)));
        assertFalse(fresh(() -> TaskRunReports.request(busy, second, true)));

        assertEquals(first, fresh(() -> ((Task) Task.findById(queued)).reportConversationId));
        assertEquals(first, fresh(() -> ((TaskRun) TaskRun.findById(running)).reportConversationId));
    }

    @Test
    void aGroupChatIsToldHowTheRunEndedButAChatThatIsGoneIsNot() {
        var agent = agent("group");
        var group = fresh(() -> ConversationService.create(Agent.findById(agent.id), "telegram", "-1001234567890").id);
        var shared = task(agent, "shared-briefing", null);
        var unheard = task(agent, "unheard", null);

        assertTrue(fresh(() -> TaskRunReports.request(shared, group, false)));
        assertFalse(fresh(() -> TaskRunReports.request(unheard, 999_999_999L, true)));

        assertEquals(group, fresh(() -> ((Task) Task.findById(shared)).reportConversationId));
        assertNull(fresh(() -> ((Task) Task.findById(unheard)).reportConversationId));
    }

    @Test
    void aRunThatEndedWithoutReportingHandsItsReportToTheNextRun() {
        var agent = agent("handoff");
        var chat = webChat(agent);
        var interrupted = task(agent, "interrupted", null);
        var retried = task(agent, "retried", null);
        var shutDown = task(agent, "shut-down", null);
        var reported = task(agent, "reported", null);
        var cutShort = run(interrupted, new TaskRunReports.Link(chat, true), r -> r.status = TaskRun.Status.RUNNING);
        run(retried, new TaskRunReports.Link(chat, false), r -> r.status = TaskRun.Status.FAILED);
        run(shutDown, new TaskRunReports.Link(chat, true), r -> r.status = TaskRun.Status.CANCELLED);
        run(reported, null, r -> notRequested(r, null));

        assertEquals(new TaskRunReports.Link(chat, true), fresh(() -> TaskRunReports.claimForNewRun(interrupted)));
        assertNull(fresh(() -> ((TaskRun) TaskRun.findById(cutShort)).reportConversationId), "handed on, not copied");
        assertEquals(new TaskRunReports.Link(chat, false), fresh(() -> TaskRunReports.claimForNewRun(retried)));
        assertEquals(new TaskRunReports.Link(chat, true), fresh(() -> TaskRunReports.claimForNewRun(shutDown)));
        assertNull(fresh(() -> TaskRunReports.claimForNewRun(reported)));
    }

    // ==================== the cancel ====================

    @Test
    void aCancelIsAnnouncedOnceByTheCallThatClosesTheRunAndAShutdownsIsNot() throws Exception {
        var agent = agent("sink");
        var task = task(agent, "sink-task", null);
        var timedOut = run(task, null, r -> r.status = TaskRun.Status.RUNNING);
        var shutDown = run(task, null, r -> r.status = TaskRun.Status.RUNNING);
        var heard = new CopyOnWriteArrayList<String>();
        var unsubscribe = NotificationBus.subscribeEvents((type, data) -> {
            if (List.of(timedOut, shutDown).contains(data.get("runId"))) heard.add(type + " " + data.get("runId"));
        });
        try {
            var timedOutSink = new TaskRunSink(fresh(() -> (TaskRun) TaskRun.findById(timedOut)));
            var shutDownSink = new TaskRunSink(fresh(() -> (TaskRun) TaskRun.findById(shutDown)));
            onItsOwnThread(() -> {
                timedOutSink.onCancelled("Stopped after 30 minutes", true);
                timedOutSink.onCancelled("Stopped after 30 minutes", true);
                shutDownSink.onCancelled("Cancelled by operator", false);
                return null;
            });

            assertEquals(List.of(TaskLifecycleEvents.BUS_RUN_CANCELLED + " " + timedOut), heard);
            assertEquals(TaskRun.Status.CANCELLED, fresh(() -> ((TaskRun) TaskRun.findById(shutDown)).status));
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    void theExecutorMovesTheRequestOntoTheRunItOpens() throws Exception {
        var agent = agent("executor");
        var chat = webChat(agent);
        var task = task(agent, "fired", null);
        fresh(() -> TaskRunReports.request(task, chat, true));

        var open = TaskExecutor.class.getDeclaredMethod("openRunningTaskRun", Task.class);
        open.setAccessible(true);
        var opened = fresh(() -> {
            try {
                return (TaskRun) ((java.util.Optional<?>) open.invoke(null, (Task) Task.findById(task))).orElseThrow();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });

        var run = fresh(() -> (TaskRun) TaskRun.findById(opened.id));
        assertEquals(chat, run.reportConversationId);
        assertEquals(Boolean.TRUE, run.reportOwnerInitiated);
        assertNull(fresh(() -> ((Task) Task.findById(task)).reportConversationId));
    }

    // ==================== the bus ====================

    @Test
    void theListenerReportsASettledFailedOrCancelledRunOnAThreadThatInheritsNoTrust() throws Exception {
        var agent = agent("bus");
        var chat = webChat(agent);
        var task = task(agent, "bus-task", null);
        var link = new TaskRunReports.Link(chat, false);
        var completedOnly = run(task, link, r -> notRequested(r, null));
        var settled = run(task, link, r -> notRequested(r, null));
        var failed = run(task, link, r -> r.status = TaskRun.Status.FAILED);
        var cancelled = run(task, link, r -> r.status = TaskRun.Status.CANCELLED);
        var listener = TaskRunReports.listener(recorder);

        listener.accept(TaskLifecycleEvents.BUS_STARTED, Map.of("taskId", task, "runId", completedOnly));
        listener.accept(TaskLifecycleEvents.BUS_COMPLETED, Map.of("taskId", task, "runId", completedOnly));
        DangerousActionGate.withOwnerInitiated(true, () -> {
            listener.accept(TaskLifecycleEvents.BUS_DELIVERY_SETTLED, Map.of("taskId", task, "runId", settled));
            listener.accept(TaskLifecycleEvents.BUS_FAILED, Map.of("taskId", task, "runId", failed));
            listener.accept(TaskLifecycleEvents.BUS_RUN_CANCELLED, Map.of("taskId", task, "runId", cancelled));
            return null;
        });

        await(() -> turns.size() == 3);
        var turn = agent.id + "/" + chat + "/false/false";
        assertEquals(List.of(turn, turn, turn), turns);
        assertEquals(chat, fresh(() -> ((TaskRun) TaskRun.findById(completedOnly)).reportConversationId),
                "a run is reported once its delivery settles, not when it completes");
    }

    // ==================== fixtures ====================

    private Agent agent(String prefix) {
        var name = prefix + "-" + (System.nanoTime() % 1_000_000_000L);
        var agent = fresh(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        agents.add(agent);
        return agent;
    }

    private static Long webChat(Agent agent) {
        return fresh(() -> ConversationService.create(Agent.findById(agent.id), "web", null).id);
    }

    private static Long task(Agent agent, String name, String delivery) {
        return fresh(() -> {
            var task = new Task();
            task.agent = Agent.findById(agent.id);
            task.name = name;
            task.description = name;
            task.type = Task.Type.CRON;
            task.cronExpression = "0 0 9 * * *";
            task.status = Task.Status.ACTIVE;
            task.delivery = delivery;
            task.createdAt = Instant.now();
            task.updatedAt = Instant.now();
            task.save();
            return task.id;
        });
    }

    private static Long run(Long taskId, TaskRunReports.Link link, Consumer<TaskRun> setup) {
        return fresh(() -> {
            var run = new TaskRun();
            run.task = Task.findById(taskId);
            run.startedAt = Instant.now();
            setup.accept(run);
            if (link != null) {
                run.reportConversationId = link.conversationId();
                run.reportOwnerInitiated = link.ownerInitiated();
            }
            run.save();
            return run.id;
        });
    }

    private static void notRequested(TaskRun run, String target) {
        run.status = TaskRun.Status.COMPLETED;
        run.durationMs = 1_000L;
        run.outputSummary = "the run's output";
        run.deliveryStatus = TaskRun.DeliveryStatus.NOT_REQUESTED;
        run.deliveryTarget = target;
    }

    private static List<Message> messages(Long conversationId) {
        return fresh(() -> {
            List<Message> rows = Message.find("conversation.id = ?1 ORDER BY id", conversationId).fetch();
            return new ArrayList<>(rows);
        });
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) fail("timed out waiting for the report");
            Thread.sleep(50);
        }
    }

    /** As the bus listener runs it: on a thread of its own, outside the test's open transaction. */
    private boolean reportOnItsOwnThread(Long runId, TaskRunReports.CompletionTurn turn) {
        return onItsOwnThread(() -> TaskRunReports.report(runId, turn));
    }

    private static <T> T fresh(Supplier<T> block) {
        return onItsOwnThread(() -> Tx.run(block::get));
    }

    private static <T> T onItsOwnThread(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(block.get());
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
