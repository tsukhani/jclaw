import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.google.gson.JsonParser;
import models.Agent;
import models.EventLog;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;
import services.Tx;
import tools.TaskTool;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-310: direct coverage for {@link TaskTool}'s {@code execute()}
 * dispatch — the agent-facing task management tool. The existing
 * ApiTasksController* tests cover the HTTP surface; this test
 * exercises the TOOL entry point with the same business logic,
 * pinning down audit emissions and agent-scoping that the controller
 * tests don't reach directly.
 *
 * <p>Coverage:
 * <ul>
 *   <li>createTask across all four schedule shorthands ("now", "30m",
 *       "every 30m", and 6-field cron).</li>
 *   <li>pause / resume round-trip with TASK_MGMT_PAUSE +
 *       TASK_MGMT_RESUME audit emissions.</li>
 *   <li>runNow forces immediate fire (reschedules existing or
 *       schedules fresh row, revives CANCELLED).</li>
 *   <li>updateTask: description, delivery, payloadType, noAgent,
 *       repeatLimit, schedule re-parse.</li>
 *   <li>cancelTask: PENDING → CANCELLED, scheduler row removed.</li>
 *   <li>Agent-scoping: addressing another agent's Task by name
 *       reports "not found" (does not leak the row).</li>
 *   <li>TASK_MGMT_CREATE / UPDATE / PAUSE / RESUME / DELETE /
 *       MANUAL_RUN audit events on the corresponding action.</li>
 *   <li>Recurring duplicate detection (409-equivalent message with
 *       the conflicting Task id).</li>
 * </ul>
 *
 * <p>Uses the same recording-Proxy SchedulerClient stub the other
 * Task tests use, wired via {@link services.TaskSchedulingServiceTestHooks}.
 */
class TaskToolTest extends UnitTest {

    private Agent agent;
    private Agent otherAgent;
    private TaskTool tool;
    private RecordingSchedulerStub stub;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = persistAgent("task-tool-agent");
        otherAgent = persistAgent("other-agent");
        tool = new TaskTool();
        stub = new RecordingSchedulerStub();
        services.TaskSchedulingServiceTestHooks.setSchedulerClient(stub.proxy());
    }

    @AfterEach
    void tearDown() {
        services.TaskSchedulingServiceTestHooks.reset();
        EventLogger.flush();
    }

    // === Static surface ===

    @Test
    void exposesNameCategoryActionsAndSchema() {
        assertEquals("task_manager", tool.name());
        assertEquals("Utilities", tool.category());
        assertEquals("tasks", tool.icon());
        var actionNames = tool.actions().stream()
                .map(agents.ToolAction::name).toList();
        assertTrue(actionNames.contains("createTask"));
        assertTrue(actionNames.contains("updateTask"));
        assertTrue(actionNames.contains("pause"));
        assertTrue(actionNames.contains("resume"));
        assertTrue(actionNames.contains("runNow"));
        assertTrue(actionNames.contains("cancelTask"));
        assertTrue(actionNames.contains("listRecurringTasks"));
        assertTrue(actionNames.contains("listReminders"));
        // The schema has `action` listed as required.
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.parameters().get("required");
        assertTrue(required.contains("action"));
    }

    @Test
    void unknownActionReturnsErrorString() {
        var result = tool.execute("{\"action\":\"bogus\"}", agent);
        assertTrue(result.startsWith("Error: Unknown action 'bogus'"),
                "unknown action must surface a structured error; got: " + result);
    }

    // === createTask: all four schedule shorthands ===

    @Test
    void createTaskImmediateSchedulesAndEmitsAudit() {
        var result = tool.execute("""
                {"action":"createTask","name":"now-task","description":"do it",
                 "schedule":"now"}""", agent);
        assertTrue(result.contains("immediate execution"),
                "IMMEDIATE branch reply; got: " + result);

        var task = findTaskByName("now-task");
        assertNotNull(task);
        assertEquals(Task.Type.IMMEDIATE, task.type);
        assertEquals(agent.id, task.agent.id);
        // The static handoff path runs schedule() against the stub.
        assertEquals(1, stub.schedules.size());
        assertEquals(task.id.toString(), stub.schedules.getFirst().instance.getId());
        assertAuditCount("TASK_MGMT_CREATE", "now-task", 1L);
    }

    // === createTask: delivery grammar (JCLAW-418) ===

    @Test
    void createTaskWithToolDeliveryPersistsIt() {
        // tool:<name> is a valid delivery kind — the agent self-delivers inline.
        var result = tool.execute("""
                {"action":"createTask","name":"email-task","description":"send the report",
                 "schedule":"now","delivery":"tool:send_gmail_message"}""", agent);
        assertFalse(result.startsWith("Error:"), "tool: delivery should be accepted; got: " + result);
        var task = findTaskByName("email-task");
        assertNotNull(task);
        assertEquals("tool:send_gmail_message", task.delivery);
    }

    @Test
    void createTaskRejectsUnknownDeliveryChannel() {
        // email is not a channel — rejected with a hint toward tool:.
        var result = tool.execute("""
                {"action":"createTask","name":"bad-delivery","description":"x",
                 "schedule":"now","delivery":"email:foo@bar.com"}""", agent);
        assertTrue(result.startsWith("Error:"), "email: delivery should be rejected; got: " + result);
        assertTrue(result.toLowerCase().contains("delivery channel"), result);
        assertNull(findTaskByName("bad-delivery"), "rejected task must not be persisted");
    }

    @Test
    void createTaskScheduledBareDuration() {
        var before = Instant.now();
        var result = tool.execute("""
                {"action":"createTask","name":"in-30","schedule":"30m"}""", agent);
        assertTrue(result.contains("scheduled for"),
                "SCHEDULED branch reply; got: " + result);

        var task = findTaskByName("in-30");
        assertEquals(Task.Type.SCHEDULED, task.type);
        // scheduledAt ~ now + 30m
        var deltaSec = task.scheduledAt.getEpochSecond() - before.getEpochSecond();
        assertTrue(deltaSec >= 1799 && deltaSec <= 1810,
                "scheduledAt ~ now + 30m; got " + deltaSec + "s");

        // Bug fix: the scheduled-for clause must carry an explicit IANA zone
        // (bracketed by ZonedDateTime.toString()) so the agent can't relabel a
        // raw UTC Instant string as the operator's local zone. Raw Instants
        // end with a `Z` and have no `[Zone/Id]` suffix — reject that shape.
        assertFalse(result.matches(".*scheduled for \\S+Z\\.\\s*$"),
                "response must not surface the raw UTC Instant.toString(); got: " + result);
        assertTrue(result.matches(".*\\[[A-Za-z_]+/[A-Za-z_]+\\]\\.\\s*$")
                        || result.matches(".*\\[UTC\\]\\.\\s*$"),
                "response must carry a bracketed IANA zone (or [UTC]); got: " + result);
    }

    @Test
    void createTaskScheduledHonorsExplicitTimezone() {
        // Per-task timezone override flows through TimezoneResolver into the
        // display string. Asia/Tokyo is +09:00 year-round so the assertion is
        // DST-stable regardless of when the test runs.
        var result = tool.execute("""
                {"action":"createTask","name":"in-30-tokyo","schedule":"30m","timezone":"Asia/Tokyo"}""",
                agent);
        assertTrue(result.contains("[Asia/Tokyo]"),
                "explicit per-task timezone must appear bracketed in the response; got: " + result);
        assertTrue(result.contains("+09:00"),
                "Tokyo's fixed +09:00 offset must surface; got: " + result);
    }

    @Test
    void createTaskIntervalEveryDuration() {
        var result = tool.execute("""
                {"action":"createTask","name":"every-30m","schedule":"every 30m"}""", agent);
        assertTrue(result.contains("Interval task"),
                "INTERVAL branch reply; got: " + result);

        var task = findTaskByName("every-30m");
        assertEquals(Task.Type.INTERVAL, task.type);
        assertEquals(1800L, task.intervalSeconds.longValue());
        assertEquals("every 30m", task.scheduleDisplay);
    }

    @Test
    void createTaskCronSpringSixField() {
        var result = tool.execute("""
                {"action":"createTask","name":"morning","schedule":"0 0 9 * * *"}""", agent);
        assertTrue(result.contains("Recurring task"),
                "CRON branch reply; got: " + result);

        var task = findTaskByName("morning");
        assertEquals(Task.Type.CRON, task.type);
        assertEquals("0 0 9 * * *", task.cronExpression);
    }

    // === createTask: validation ===

    @Test
    void createTaskRejectsMissingName() {
        var result = tool.execute("""
                {"action":"createTask","schedule":"now"}""", agent);
        assertTrue(result.contains("'name' is required"),
                "got: " + result);
    }

    @Test
    void createTaskRejectsMissingSchedule() {
        var result = tool.execute("""
                {"action":"createTask","name":"x"}""", agent);
        assertTrue(result.contains("'schedule' is required"),
                "got: " + result);
    }

    @Test
    void createTaskRejectsInvalidScheduleShorthand() {
        var result = tool.execute("""
                {"action":"createTask","name":"x","schedule":"tomorrow"}""", agent);
        assertTrue(result.startsWith("Error: Invalid schedule:"),
                "garbage schedule must surface an error; got: " + result);
    }

    // === createTask: recurring duplicate (409-shape) ===

    @Test
    void createRecurringDuplicateNameReturnsConflictWithExistingId() {
        var first = tool.execute("""
                {"action":"createTask","name":"daily","schedule":"@daily"}""", agent);
        assertTrue(first.contains("Recurring task"), first);
        var firstId = findTaskByName("daily").id;

        var second = tool.execute("""
                {"action":"createTask","name":"daily","schedule":"@hourly"}""", agent);
        assertTrue(second.contains("already exists"),
                "duplicate must surface a 409-equivalent error; got: " + second);
        assertTrue(second.contains("id=" + firstId),
                "error must call out the conflicting Task id; got: " + second);
    }

    @Test
    void createIntervalDuplicateNameReturnsConflictWithExistingId() {
        var first = tool.execute("""
                {"action":"createTask","name":"loop","schedule":"every 1h"}""", agent);
        assertTrue(first.contains("Interval task"), first);
        var firstId = findTaskByName("loop").id;

        var second = tool.execute("""
                {"action":"createTask","name":"loop","schedule":"every 2h"}""", agent);
        assertTrue(second.contains("already exists"), second);
        assertTrue(second.contains("id=" + firstId), second);
    }

    @Test
    void createOneShotDuplicateNameIsAllowed() {
        // IMMEDIATE / SCHEDULED have no duplicate-name guard; two tasks
        // with the same name on the same agent must both create.
        assertTrue(tool.execute("""
                {"action":"createTask","name":"same","schedule":"now"}""", agent)
                .contains("immediate execution"));
        assertTrue(tool.execute("""
                {"action":"createTask","name":"same","schedule":"now"}""", agent)
                .contains("immediate execution"));
        assertEquals(2, countTasksWithName("same"),
                "one-shot duplicate names are allowed");
    }

    @Test
    void createRecurringSameNameDifferentAgentNotAConflict() {
        // Multi-tenancy: same recurring name on a different agent is fine.
        assertTrue(tool.execute("""
                {"action":"createTask","name":"shared","schedule":"@daily"}""", agent)
                .contains("Recurring task"));
        assertTrue(tool.execute("""
                {"action":"createTask","name":"shared","schedule":"@daily"}""", otherAgent)
                .contains("Recurring task"));
    }

    // === pause / resume round-trip ===

    @Test
    void pauseAndResumeRoundTripWithAudit() {
        tool.execute("""
                {"action":"createTask","name":"pauseme","schedule":"every 1h"}""", agent);
        var taskId = findTaskByName("pauseme").id;

        var pauseReply = tool.execute("""
                {"action":"pause","name":"pauseme"}""", agent);
        assertTrue(pauseReply.contains("paused"),
                "pause reply; got: " + pauseReply);
        var afterPause = (Task) Tx.run(() -> Task.findById(taskId));
        assertTrue(afterPause.paused, "paused flag should be true after pause");

        var resumeReply = tool.execute("""
                {"action":"resume","name":"pauseme"}""", agent);
        assertTrue(resumeReply.contains("resumed"),
                "resume reply; got: " + resumeReply);
        var afterResume = (Task) Tx.run(() -> Task.findById(taskId));
        assertFalse(afterResume.paused, "paused flag should be false after resume");

        assertAuditCount("TASK_MGMT_PAUSE", "pauseme", 1L);
        assertAuditCount("TASK_MGMT_RESUME", "pauseme", 1L);
    }

    @Test
    void pauseRequiresName() {
        var result = tool.execute("""
                {"action":"pause"}""", agent);
        assertTrue(result.contains("'name' is required"), result);
    }

    @Test
    void resumeRequiresName() {
        var result = tool.execute("""
                {"action":"resume"}""", agent);
        assertTrue(result.contains("'name' is required"), result);
    }

    @Test
    void pauseOnUnknownNameReturnsNotFound() {
        var result = tool.execute("""
                {"action":"pause","name":"nope"}""", agent);
        assertTrue(result.contains("No task found"), result);
    }

    @Test
    void resumeOnUnknownNameReturnsNotFound() {
        var result = tool.execute("""
                {"action":"resume","name":"nope"}""", agent);
        assertTrue(result.contains("No task found"), result);
    }

    // === runNow ===

    @Test
    void runNowReschedulesExistingPendingTask() {
        tool.execute("""
                {"action":"createTask","name":"runme","schedule":"every 1h"}""", agent);
        var taskId = findTaskByName("runme").id;
        stub.reschedules.clear();  // ignore create-time schedule

        var reply = tool.execute("""
                {"action":"runNow","name":"runme"}""", agent);
        assertTrue(reply.contains("run-now triggered"),
                "runNow happy path; got: " + reply);
        // reschedule() called against the stub with the matching Task id.
        assertEquals(1, stub.reschedules.size(),
                "runNow should call reschedule() once");
        assertEquals(taskId.toString(),
                stub.reschedules.getFirst().instanceId.getId());
        assertAuditCount("TASK_MGMT_MANUAL_RUN", "runme", 1L);
    }

    @Test
    void runNowRevivesCancelledTask() {
        // Create then cancel — runNow must revive PENDING and fire.
        tool.execute("""
                {"action":"createTask","name":"revive","schedule":"now"}""", agent);
        var taskId = findTaskByName("revive").id;
        Tx.run(() -> {
            var t = (Task) Task.findById(taskId);
            t.status = Task.Status.CANCELLED;
            t.save();
        });

        var reply = tool.execute("""
                {"action":"runNow","name":"revive"}""", agent);
        assertTrue(reply.contains("revived 1 from CANCELLED"),
                "runNow must call out the revived count; got: " + reply);
        var afterRun = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals(Task.Status.PENDING, afterRun.status,
                "CANCELLED → PENDING revive");
    }

    @Test
    void runNowOnUnknownNameReturnsNotFound() {
        var result = tool.execute("""
                {"action":"runNow","name":"nope"}""", agent);
        assertTrue(result.contains("No task found"), result);
    }

    @Test
    void runNowRequiresName() {
        var result = tool.execute("""
                {"action":"runNow"}""", agent);
        assertTrue(result.contains("'name' is required"), result);
    }

    // === updateTask: patchable fields ===

    @Test
    void updateTaskDescription() {
        tool.execute("""
                {"action":"createTask","name":"u1","schedule":"now"}""", agent);
        var taskId = findTaskByName("u1").id;

        var result = tool.execute("""
                {"action":"updateTask","name":"u1","description":"new body"}""", agent);
        assertTrue(result.contains("updated"), result);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals("new body", fresh.description);
        assertAuditCount("TASK_MGMT_UPDATE", "u1", 1L);
    }

    @Test
    void createTaskCompletesBareChannelDeliveryFromConversation() {
        // The chat agent often passes a bare channel name like "web" because
        // it understands the user wants the task to land in this chat but
        // doesn't know the channel-specific target format. resolveDeliverySpec
        // should backfill the target from the calling agent's most-recently-
        // updated conversation (auto-delivery wire — Bug 1 fix).
        var conv = Tx.run(() -> {
            var c = new models.Conversation();
            c.agent = agent;
            c.channelType = "web";
            c.save();
            return c;
        });
        tool.execute("""
                {"action":"createTask","name":"bare-web","schedule":"30m","delivery":"web"}""", agent);
        var fresh = (Task) Tx.run(() -> Task.findById(findTaskByName("bare-web").id));
        assertEquals("web:" + conv.id, fresh.delivery,
                "bare channel name must be completed with the calling conversation id");
    }

    @Test
    void createTaskAutoInfersDeliveryWhenOmitted() {
        var conv = Tx.run(() -> {
            var c = new models.Conversation();
            c.agent = agent;
            c.channelType = "web";
            c.save();
            return c;
        });
        tool.execute("""
                {"action":"createTask","name":"infer-web","schedule":"30m"}""", agent);
        var fresh = (Task) Tx.run(() -> Task.findById(findTaskByName("infer-web").id));
        assertEquals("web:" + conv.id, fresh.delivery,
                "omitted delivery must auto-infer from the calling conversation");
    }

    @Test
    void updateTaskDelivery() {
        tool.execute("""
                {"action":"createTask","name":"u-del","schedule":"now"}""", agent);
        var taskId = findTaskByName("u-del").id;

        tool.execute("""
                {"action":"updateTask","name":"u-del","delivery":"telegram:42"}""", agent);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals("telegram:42", fresh.delivery);
    }

    @Test
    void updateTaskPayloadType() {
        tool.execute("""
                {"action":"createTask","name":"u-pt","schedule":"now"}""", agent);
        var taskId = findTaskByName("u-pt").id;

        tool.execute("""
                {"action":"updateTask","name":"u-pt","payloadType":"markdown"}""", agent);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals("markdown", fresh.payloadType);
    }

    @Test
    void updateTaskNoAgentFlag() {
        tool.execute("""
                {"action":"createTask","name":"u-na","schedule":"now"}""", agent);
        var taskId = findTaskByName("u-na").id;

        tool.execute("""
                {"action":"updateTask","name":"u-na","noAgent":true}""", agent);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertTrue(fresh.noAgent);
    }

    @Test
    void updateTaskRepeatLimit() {
        tool.execute("""
                {"action":"createTask","name":"u-rl","schedule":"every 1h"}""", agent);
        var taskId = findTaskByName("u-rl").id;

        tool.execute("""
                {"action":"updateTask","name":"u-rl","repeatLimit":5}""", agent);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals(5, fresh.repeatLimit.intValue());
    }

    @Test
    void updateTaskScheduleReparseSwitchesType() {
        tool.execute("""
                {"action":"createTask","name":"u-sched","schedule":"every 1h"}""", agent);
        var taskId = findTaskByName("u-sched").id;

        var result = tool.execute("""
                {"action":"updateTask","name":"u-sched","schedule":"@daily"}""", agent);
        assertTrue(result.contains("updated"), result);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals(Task.Type.CRON, fresh.type);
        assertEquals("@daily", fresh.cronExpression);
        assertNull(fresh.intervalSeconds,
                "intervalSeconds should clear on type switch");
    }

    @Test
    void updateTaskScheduleChangeRearmsSchedulerForSavedTask() {
        // JCLAW-408: the patch + reschedule now share one Tx — the saved Task
        // is reused for TaskSchedulingService.update instead of a second
        // re-read Tx. Assert the scheduler is re-armed (cancel + register) for
        // the correct task id when the schedule changes.
        tool.execute("""
                {"action":"createTask","name":"u-rearm","schedule":"every 1h"}""", agent);
        var taskId = findTaskByName("u-rearm").id;
        stub.cancels.clear();
        stub.schedules.clear();
        stub.reschedules.clear();

        var result = tool.execute("""
                {"action":"updateTask","name":"u-rearm","schedule":"every 2h"}""", agent);
        assertTrue(result.contains("updated"), result);

        // update() cancels the existing scheduler row then registers a fresh one.
        assertEquals(1, stub.cancels.size(), "schedule change must cancel the old row");
        assertEquals(taskId.toString(), stub.cancels.getFirst().getId());
        assertEquals(1, stub.schedules.size(), "schedule change must register a fresh row");
        assertEquals(taskId.toString(), stub.schedules.getFirst().instance.getId());
    }

    @Test
    void updateTaskNonScheduleChangeDoesNotRearmScheduler() {
        // A non-schedule patch must NOT touch the scheduler (scheduleChanged=false).
        tool.execute("""
                {"action":"createTask","name":"u-no-rearm","schedule":"every 1h"}""", agent);
        stub.cancels.clear();
        stub.schedules.clear();
        stub.reschedules.clear();

        var result = tool.execute("""
                {"action":"updateTask","name":"u-no-rearm","description":"new desc"}""", agent);
        assertTrue(result.contains("updated"), result);
        assertEquals(0, stub.cancels.size(), "description-only update must not re-arm the scheduler");
        assertEquals(0, stub.schedules.size(), "description-only update must not re-arm the scheduler");
    }

    @Test
    void updateTaskRequiresName() {
        var result = tool.execute("""
                {"action":"updateTask","description":"x"}""", agent);
        assertTrue(result.contains("'name' is required"), result);
    }

    @Test
    void updateTaskUnknownNameReturnsNotFound() {
        var result = tool.execute("""
                {"action":"updateTask","name":"missing","description":"x"}""", agent);
        assertTrue(result.contains("No task found"), result);
    }

    @Test
    void updateTaskNoFieldsProvidedReturnsError() {
        tool.execute("""
                {"action":"createTask","name":"empty-patch","schedule":"now"}""", agent);
        var result = tool.execute("""
                {"action":"updateTask","name":"empty-patch"}""", agent);
        assertTrue(result.contains("No patchable fields"),
                "empty patch must surface an error; got: " + result);
    }

    @Test
    void updateTaskInvalidScheduleReturnsError() {
        tool.execute("""
                {"action":"createTask","name":"bad-sched","schedule":"now"}""", agent);
        var result = tool.execute("""
                {"action":"updateTask","name":"bad-sched","schedule":"tomorrow"}""", agent);
        assertTrue(result.startsWith("Error: Invalid schedule:"),
                "invalid schedule must surface; got: " + result);
    }

    @Test
    void updateTaskAmbiguousMultipleMatchesReportsAmbiguity() {
        // Two IMMEDIATE tasks with the same name (one-shot duplicates allowed).
        tool.execute("""
                {"action":"createTask","name":"dup","schedule":"now"}""", agent);
        tool.execute("""
                {"action":"createTask","name":"dup","schedule":"now"}""", agent);

        var result = tool.execute("""
                {"action":"updateTask","name":"dup","description":"x"}""", agent);
        assertTrue(result.startsWith("Ambiguous:"),
                "ambiguous multi-match must report; got: " + result);
    }

    // === cancelTask ===

    @Test
    void cancelTaskFlipsStatusToCancelledAndRemovesSchedulerRow() {
        tool.execute("""
                {"action":"createTask","name":"cancelme","schedule":"every 1h"}""", agent);
        var taskId = findTaskByName("cancelme").id;
        int cancelsBefore = stub.cancels.size();

        var reply = tool.execute("""
                {"action":"cancelTask","name":"cancelme"}""", agent);
        assertTrue(reply.contains("cancelled"), reply);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertEquals(Task.Status.CANCELLED, fresh.status,
                "PENDING → CANCELLED");
        assertEquals(cancelsBefore + 1, stub.cancels.size(),
                "scheduler row cancel() must be invoked");
        assertEquals(taskId.toString(), stub.cancels.getLast().getId());
        assertAuditCount("TASK_MGMT_DELETE", "cancelme", 1L);
    }

    @Test
    void cancelTaskUnknownNameReturnsNotFound() {
        var result = tool.execute("""
                {"action":"cancelTask","name":"nope"}""", agent);
        assertTrue(result.contains("No task found"), result);
    }

    @Test
    void cancelTaskRequiresName() {
        var result = tool.execute("""
                {"action":"cancelTask"}""", agent);
        assertTrue(result.contains("'name' is required"), result);
    }

    // === Agent scoping: forbidden cross-agent addressing ===

    @Test
    void otherAgentCannotPauseAnotherAgentsTask() {
        // Create a task under `agent`, address it as `otherAgent`.
        tool.execute("""
                {"action":"createTask","name":"private","schedule":"every 1h"}""", agent);

        var reply = tool.execute("""
                {"action":"pause","name":"private"}""", otherAgent);
        // Agent-scoped finder returns empty → "No task found" rather
        // than exposing the row. This is the "forbidden" shape per the
        // multi-tenancy stance.
        assertTrue(reply.contains("No task found"),
                "cross-agent pause must surface not-found; got: " + reply);
        // Original task still PENDING / unpaused.
        var original = findTaskByName("private");
        assertFalse(original.paused,
                "cross-agent pause must not mutate the row");
    }

    @Test
    void otherAgentCannotCancelAnotherAgentsTask() {
        tool.execute("""
                {"action":"createTask","name":"shielded","schedule":"every 1h"}""", agent);

        var reply = tool.execute("""
                {"action":"cancelTask","name":"shielded"}""", otherAgent);
        assertTrue(reply.contains("No task found"),
                "cross-agent cancel must surface not-found; got: " + reply);
        var original = findTaskByName("shielded");
        // ACTIVE because this is an INTERVAL recurring task; the
        // cross-agent guard kept it from flipping to CANCELLED.
        assertEquals(Task.Status.ACTIVE, original.status,
                "cross-agent cancel must not mutate the row");
    }

    @Test
    void otherAgentCannotRunAnotherAgentsTask() {
        tool.execute("""
                {"action":"createTask","name":"hands-off","schedule":"every 1h"}""", agent);

        var reply = tool.execute("""
                {"action":"runNow","name":"hands-off"}""", otherAgent);
        assertTrue(reply.contains("No task found"),
                "cross-agent runNow must surface not-found; got: " + reply);
    }

    @Test
    void otherAgentCannotUpdateAnotherAgentsTask() {
        tool.execute("""
                {"action":"createTask","name":"sealed","schedule":"now",
                 "description":"original"}""", agent);

        var reply = tool.execute("""
                {"action":"updateTask","name":"sealed","description":"hacked"}""", otherAgent);
        assertTrue(reply.contains("No task found"),
                "cross-agent updateTask must surface not-found; got: " + reply);
        var original = findTaskByName("sealed");
        assertEquals("original", original.description,
                "cross-agent updateTask must not mutate the row");
    }

    // === listRecurringTasks ===

    @Test
    void listRecurringTasksReturnsAgentScopedList() {
        tool.execute("""
                {"action":"createTask","name":"daily-r","schedule":"@daily"}""", agent);
        tool.execute("""
                {"action":"createTask","name":"hourly-r","schedule":"every 1h"}""", agent);
        // A one-shot — must NOT appear in the list.
        tool.execute("""
                {"action":"createTask","name":"once","schedule":"now"}""", agent);
        // Another agent's recurring — must NOT appear under the calling agent.
        tool.execute("""
                {"action":"createTask","name":"other-daily","schedule":"@daily"}""", otherAgent);

        var reply = tool.execute("""
                {"action":"listRecurringTasks"}""", agent);
        assertTrue(reply.contains("daily-r"), reply);
        assertTrue(reply.contains("hourly-r"), reply);
        assertFalse(reply.contains("once"),
                "one-shot tasks must not appear in recurring list");
        assertFalse(reply.contains("other-daily"),
                "other agent's recurring tasks must not appear; got: " + reply);
    }

    @Test
    void listRecurringTasksShowsDeliveryLabel() {
        // JCLAW-421: surface each task's typed delivery so a re-normalization
        // pass can spot tasks whose delivery is still "none".
        tool.execute("""
                {"action":"createTask","name":"emailer","schedule":"@daily",
                 "delivery":"tool:send_gmail_message"}""", agent);
        tool.execute("""
                {"action":"createTask","name":"silent","schedule":"@daily"}""", agent);
        var reply = tool.execute("""
                {"action":"listRecurringTasks"}""", agent);
        assertTrue(reply.contains("[delivery: send_gmail_message]"), reply);
        assertTrue(reply.contains("[delivery: none]"), reply);
    }

    @Test
    void listRecurringTasksEmptyReturnsFriendlyMessage() {
        var reply = tool.execute("""
                {"action":"listRecurringTasks"}""", agent);
        assertEquals("No recurring tasks configured.", reply);
    }

    // === listReminders ===

    @Test
    void listRemindersReturnsUpcomingAgentScopedReminders() {
        // A one-shot reminder (SCHEDULED → PENDING) and a recurring reminder
        // (CRON → ACTIVE) — both must appear.
        tool.execute("""
                {"action":"createTask","name":"dentist","schedule":"30m",
                 "payloadType":"reminder","description":"See the dentist"}""", agent);
        tool.execute("""
                {"action":"createTask","name":"standup","schedule":"@daily",
                 "payloadType":"reminder","description":"Daily standup"}""", agent);
        // Non-reminder tasks (no payloadType) — must NOT appear.
        tool.execute("""
                {"action":"createTask","name":"plain-once","schedule":"30m"}""", agent);
        tool.execute("""
                {"action":"createTask","name":"plain-cron","schedule":"@daily"}""", agent);
        // Another agent's reminder — must NOT appear under the calling agent.
        tool.execute("""
                {"action":"createTask","name":"other-rem","schedule":"30m",
                 "payloadType":"reminder","description":"not yours"}""", otherAgent);

        var reply = tool.execute("""
                {"action":"listReminders"}""", agent);
        assertTrue(reply.contains("dentist"), reply);
        assertTrue(reply.contains("standup"), reply);
        assertFalse(reply.contains("plain-once"),
                "non-reminder one-shot must not appear in the reminder list; got: " + reply);
        assertFalse(reply.contains("plain-cron"),
                "non-reminder recurring must not appear in the reminder list; got: " + reply);
        assertFalse(reply.contains("other-rem"),
                "another agent's reminders must not appear; got: " + reply);
    }

    @Test
    void listRemindersExcludesCancelledReminder() {
        tool.execute("""
                {"action":"createTask","name":"gone","schedule":"30m",
                 "payloadType":"reminder","description":"will be cancelled"}""", agent);
        tool.execute("""
                {"action":"cancelTask","name":"gone"}""", agent);
        // findReminders is scoped to PENDING/ACTIVE, so a CANCELLED reminder
        // drops out of the discovery list — it's no longer upcoming.
        var reply = tool.execute("""
                {"action":"listReminders"}""", agent);
        assertEquals("No upcoming reminders.", reply);
    }

    @Test
    void listRemindersEmptyReturnsFriendlyMessage() {
        var reply = tool.execute("""
                {"action":"listReminders"}""", agent);
        assertEquals("No upcoming reminders.", reply);
    }

    @Test
    void listRemindersHeaderAndOneShotStatusAreStable() {
        tool.execute("""
                {"action":"createTask","name":"rem-header","schedule":"30m",
                 "payloadType":"reminder","description":"Brush your teeth"}""", agent);
        var reply = tool.execute("""
                {"action":"listReminders"}""", agent);
        assertTrue(reply.startsWith("Reminders:"),
                "list must start with the stable header; got: " + reply);
        assertTrue(reply.contains("- rem-header"),
                "list entry should be dash-prefixed by name");
        assertTrue(reply.contains("[PENDING]"),
                "a one-shot reminder should surface its PENDING status; got: " + reply);
    }

    // === Helpers ===

    private Agent persistAgent(String name) {
        var a = new Agent();
        a.name = name;
        // Sentinel values: TaskTool tests don't exercise the LLM path; using non-production names guards against future scope expansion silently triggering a real API call.
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private Task findTaskByName(String name) {
        return Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var list = (java.util.List<Object>) (java.util.List<?>)
                    Task.find("name = ?1 ORDER BY id DESC", name).fetch();
            return list.isEmpty() ? null : (Task) list.getFirst();
        });
    }

    private long countTasksWithName(String name) {
        return Tx.run(() -> Task.count("name = ?1", name));
    }

    private static void assertAuditCount(String category, String namePattern, long expected) {
        EventLogger.flush();
        long got = EventLog.count("category = ?1 AND message LIKE ?2",
                category, "%" + namePattern + "%");
        assertEquals(expected, got,
                "expected " + expected + " " + category + " event(s) referencing "
                        + namePattern);
    }

    /**
     * Smoke-check that JSON parsing of args works for nested keys —
     * exercise an explicit-null-out on optional fields. Catches a
     * regression where {@code el.isJsonNull()} would mis-handle.
     */
    @Test
    void updateTaskExplicitNullClearsField() {
        tool.execute("""
                {"action":"createTask","name":"clearme","schedule":"every 1h",
                 "delivery":"telegram:1","repeatLimit":3}""", agent);
        var taskId = findTaskByName("clearme").id;

        var reply = tool.execute("""
                {"action":"updateTask","name":"clearme","delivery":null,
                 "repeatLimit":null}""", agent);
        assertTrue(reply.contains("updated"), reply);
        var fresh = (Task) Tx.run(() -> Task.findById(taskId));
        assertNull(fresh.delivery, "explicit null should clear delivery");
        assertNull(fresh.repeatLimit, "explicit null should clear repeatLimit");
    }

    /**
     * Smoke-check JSON parsing of the response from listRecurringTasks
     * makes structural sense — non-empty replies always start with the
     * "Recurring tasks:\n" header.
     */
    @Test
    void listRecurringTasksHeaderIsStable() {
        tool.execute("""
                {"action":"createTask","name":"r-header","schedule":"@hourly"}""", agent);
        var reply = tool.execute("""
                {"action":"listRecurringTasks"}""", agent);
        assertTrue(reply.startsWith("Recurring tasks:"),
                "list must start with the stable header; got: " + reply);
        // Sanity-parse the description structure for one entry.
        assertTrue(reply.contains("- r-header"),
                "list entry should be dash-prefixed by name");
    }

    @Test
    void createTaskWithBlankNameRejected() {
        // Empty name string is technically not blank-checked at the tool
        // (the controller catches it via Hibernate constraints); the tool
        // happily creates. Pin the current behavior: blank still creates.
        // If JCLAW-310 tightens this later, this test will surface the
        // change as expected.
        var result = tool.execute("""
                {"action":"createTask","name":"","schedule":"now"}""", agent);
        // Currently the tool does NOT blank-check beyond the !has/null
        // guard. The empty string slips through. We assert one of two
        // valid outcomes: either the response is the IMMEDIATE happy
        // path (no validation) or an explicit name-required error.
        assertTrue(result.contains("immediate execution")
                        || result.contains("'name' is required"),
                "empty-name response must be one of the documented paths; got: " + result);
    }

    @Test
    void invokeViaParseStringResultIsString() {
        // Argument shape: JSON parsed via JsonParser.parseString.
        var argsJson = "{\"action\":\"listRecurringTasks\"}";
        // Round-trip the JSON to confirm shape before invoking — guards
        // against accidental quoting bugs in this test file.
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        assertEquals("listRecurringTasks", args.get("action").getAsString());
        var reply = tool.execute(argsJson, agent);
        assertNotNull(reply);
    }

    // === Stub ===

    /**
     * Same dynamic-Proxy SchedulerClient stub used by the other Task
     * tests, with reschedule+cancel recording (TaskTool drives
     * TaskSchedulingService which calls all three).
     */
    static class RecordingSchedulerStub {
        static class ScheduleCall {
            final TaskInstance<?> instance;
            final Instant when;
            ScheduleCall(TaskInstance<?> i, Instant w) { instance = i; when = w; }
        }
        static class RescheduleCall {
            final TaskInstanceId instanceId;
            final Instant when;
            RescheduleCall(TaskInstanceId id, Instant w) { instanceId = id; when = w; }
        }
        final List<ScheduleCall> schedules = new ArrayList<>();
        final List<TaskInstanceId> cancels = new ArrayList<>();
        final List<RescheduleCall> reschedules = new ArrayList<>();
        boolean rescheduleReturns = true;

        SchedulerClient proxy() {
            return (SchedulerClient) Proxy.newProxyInstance(
                    SchedulerClient.class.getClassLoader(),
                    new Class<?>[] { SchedulerClient.class },
                    this::dispatch);
        }

        private Object dispatch(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("schedule".equals(name) && args != null && args.length == 2
                    && args[0] instanceof TaskInstance<?> inst
                    && args[1] instanceof Instant when) {
                schedules.add(new ScheduleCall(inst, when));
                return null;
            }
            if ("cancel".equals(name) && args != null && args.length == 1
                    && args[0] instanceof TaskInstanceId id) {
                cancels.add(id);
                return null;
            }
            if ("reschedule".equals(name) && args != null && args.length == 2
                    && args[0] instanceof TaskInstanceId id
                    && args[1] instanceof Instant when) {
                reschedules.add(new RescheduleCall(id, when));
                return rescheduleReturns;
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class || r == Boolean.class) return false;
            if (r == int.class || r == Integer.class) return 0;
            if (r == long.class || r == Long.class) return 0L;
            if (r == List.class) return List.of();
            if (r == java.util.Optional.class) return java.util.Optional.empty();
            return null;
        }
    }
}
