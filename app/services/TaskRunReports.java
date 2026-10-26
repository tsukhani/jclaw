package services;

import agents.AgentRunner;
import agents.DangerousActionGate;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import utils.GsonHolder;
import utils.SecretRedactor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Tells the chat that ran a task how the run ended, so the agent that asked never waits on a timer:
 * a message stating the run's outcome and where its output went is posted into that conversation as
 * a USER-role row, and the agent is resumed to answer it, as a background scrape's completion is.
 * The output itself goes only where the task's delivery sends it. The link is recorded on the task
 * when an agent runs it from a chat, moved onto the run the executor opens, and claimed once, when
 * {@code task.delivery_settled}, {@code task.failed} or {@code task.run_cancelled} is published on
 * the {@link NotificationBus}.
 */
public final class TaskRunReports {

    /** {@code Message.messageKind} of the message that reports a run into the chat. */
    public static final String MESSAGE_KIND_RESULT = "task_run_result";
    /** What the agent that ran the task is told, so it ends its turn rather than polling. */
    public static final String AGENT_NOTE = "When the run ends, its result is posted to this chat and you are "
            + "resumed to report it. End your turn now; do not poll, sleep or check on the run.";

    /** What the agent is told instead of {@link #AGENT_NOTE} for a paused task, whose fire is skipped. */
    public static String pausedNote(String taskName) {
        return "Task '%s' is paused, so nothing runs until it is resumed.".formatted(taskName);
    }

    private static final String EVENT_CATEGORY = "task";
    private static final String LABEL = "[task result]";
    // The output went where the task delivers it; a group chat may hold readers it was not meant for.
    private static final String DO_NOT_REPEAT = "Tell the user how the run ended, without fetching or repeating its output here.";
    private static final String KEY_RUN_ID = "runId";
    private static final CompletionTurn PRODUCTION = TaskRunReports::runReportTurn;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Set<String> REPORTED_EVENTS = Set.of(TaskLifecycleEvents.BUS_DELIVERY_SETTLED,
            TaskLifecycleEvents.BUS_FAILED, TaskLifecycleEvents.BUS_RUN_CANCELLED);

    private TaskRunReports() {}

    /** What happens after the result is posted: a turn that reads it and answers. */
    @FunctionalInterface
    public interface CompletionTurn {
        void run(Agent agent, Conversation conversation, boolean ownerInitiated);
    }

    /** The chat waiting on a run, and whether the turn that asked was owner-initiated. */
    public record Link(Long conversationId, boolean ownerInitiated) {}

    /**
     * Ask for a report of this task's run: the one in flight, else the next one the executor opens.
     * False, recording nothing, when the conversation is gone or another chat is already waiting.
     */
    public static boolean request(Long taskId, Long conversationId, boolean ownerInitiated) {
        return Tx.run(() -> {
            if (Conversation.findById(conversationId) == null) return false;
            var em = JPA.em();
            List<Long> running = em.createQuery("SELECT r.id FROM TaskRun r WHERE r.task.id = :task "
                            + "AND r.status = :running ORDER BY r.id DESC", Long.class)
                    .setParameter("task", taskId)
                    .setParameter("running", TaskRun.Status.RUNNING)
                    .setMaxResults(1)
                    .getResultList();
            // Bulk updates, as the executor writes the task: a full-row save could write back an older copy.
            // The first chat to ask keeps the report: taking it would leave that chat waiting for nothing.
            var update = running.isEmpty()
                    ? em.createQuery("UPDATE Task t SET t.reportConversationId = :conversation, t.reportOwnerInitiated = :owner "
                            + "WHERE t.id = :id AND (t.reportConversationId IS NULL OR t.reportConversationId = :conversation)")
                            .setParameter("id", taskId)
                    : em.createQuery("UPDATE TaskRun r SET r.reportConversationId = :conversation, r.reportOwnerInitiated = :owner "
                            + "WHERE r.id = :id AND (r.reportConversationId IS NULL OR r.reportConversationId = :conversation)")
                            .setParameter("id", running.getFirst());
            return update.setParameter("conversation", conversationId)
                    .setParameter("owner", ownerInitiated)
                    .executeUpdate() == 1;
        });
    }

    /**
     * The report the run being opened carries: the task's request, else one a run of it still holds
     * because it ended without reporting — cut short by a restart or a shutdown's cancel, or failed
     * with a retry to follow. Call inside the transaction that opens the run, before saving it.
     */
    public static @Nullable Link claimForNewRun(Long taskId) {
        var em = JPA.em();
        List<Object[]> asked = em.createQuery("SELECT t.reportConversationId, t.reportOwnerInitiated FROM Task t "
                        + "WHERE t.id = :id AND t.reportConversationId IS NOT NULL", Object[].class)
                .setParameter("id", taskId)
                .getResultList();
        if (!asked.isEmpty()) {
            em.createQuery("UPDATE Task t SET t.reportConversationId = NULL, t.reportOwnerInitiated = NULL WHERE t.id = :id")
                    .setParameter("id", taskId)
                    .executeUpdate();
            return link(asked.getFirst()[0], asked.getFirst()[1]);
        }
        // A run's report claims its link moments after the run ends, so one still held here was never reported.
        List<Object[]> held = em.createQuery("SELECT r.id, r.reportConversationId, r.reportOwnerInitiated FROM TaskRun r "
                        + "WHERE r.task.id = :task AND r.reportConversationId IS NOT NULL ORDER BY r.id DESC", Object[].class)
                .setParameter("task", taskId)
                .setMaxResults(1)
                .getResultList();
        if (held.isEmpty()) return null;
        em.createQuery("UPDATE TaskRun r SET r.reportConversationId = NULL, r.reportOwnerInitiated = NULL WHERE r.id = :id")
                .setParameter("id", held.getFirst()[0])
                .executeUpdate();
        return link(held.getFirst()[1], held.getFirst()[2]);
    }

    /** The bus listener: a run whose delivery settled, that failed or that was canceled is reported on a fresh thread. */
    public static BiConsumer<String, Map<String, Object>> listener(CompletionTurn turn) {
        return (type, data) -> {
            if (!REPORTED_EVENTS.contains(type)) return;
            if (!(data.get(KEY_RUN_ID) instanceof Number runId)) return;
            // The publisher is the task fire's thread; inheriting would carry its origin into a chat turn.
            Thread.ofVirtual().name("task-run-report-" + runId).inheritInheritableThreadLocals(false)
                    .start(() -> report(runId.longValue(), turn));
        };
    }

    /** Subscribes {@link #listener} to the bus, once per application start. */
    public static void install() {
        if (INSTALLED.compareAndSet(false, true)) NotificationBus.subscribeEvents(listener(PRODUCTION));
    }

    /**
     * Post a run's result into the chat waiting on it and hand that chat a turn. The run's link is
     * claimed first, so a run reports once; false when nobody is waiting or the chat is gone.
     */
    public static boolean report(Long runId, CompletionTurn turn) {
        record Posted(Agent agent, Conversation conversation, boolean ownerInitiated) {}
        Posted posted;
        try {
            posted = Tx.run(() -> {
                var em = JPA.em();
                List<Object[]> link = em.createQuery("SELECT r.reportConversationId, r.reportOwnerInitiated FROM TaskRun r "
                                + "WHERE r.id = :id AND r.reportConversationId IS NOT NULL", Object[].class)
                        .setParameter("id", runId)
                        .getResultList();
                if (link.isEmpty()) return null;
                int claimed = em.createQuery("UPDATE TaskRun r SET r.reportConversationId = NULL, "
                                + "r.reportOwnerInitiated = NULL WHERE r.id = :id AND r.reportConversationId IS NOT NULL")
                        .setParameter("id", runId)
                        .executeUpdate();
                TaskRun run = TaskRun.findById(runId);
                Conversation conversation = Conversation.findById(link.getFirst()[0]);
                if (claimed != 1 || run == null || conversation == null) return null;
                boolean ownerInitiated = Boolean.TRUE.equals(link.getFirst()[1]);
                // Error text can carry ids, URLs or a key, so only a chat the owner reads alone is shown it.
                boolean ownerReadsAlone = DangerousActionGate.withOwnerInitiated(ownerInitiated,
                        () -> DangerousActionGate.ownerReadsAlone(conversation.id, conversation.channelType, conversation.peerId));
                var message = ConversationService.appendMessage(conversation, MessageRole.USER,
                        resultText(run, ownerReadsAlone), null, null, null);
                message.messageKind = MESSAGE_KIND_RESULT;
                message.metadata = GsonHolder.GSON.toJson(resultMetadata(run, ownerReadsAlone));
                message.save();
                return new Posted(conversation.agent, conversation, ownerInitiated);
            });
        } catch (RuntimeException e) {
            EventLogger.warn(EVENT_CATEGORY, "Task run %d: could not post its result to the chat that ran it: %s"
                    .formatted(runId, e.getMessage()));
            return false;
        }
        if (posted == null) return false;
        conversationUpdated(posted.conversation().id);
        try {
            turn.run(posted.agent(), posted.conversation(), posted.ownerInitiated());
        } catch (RuntimeException e) {
            EventLogger.warn(EVENT_CATEGORY, "Task run %d: the turn that reports it failed: %s".formatted(runId, e.getMessage()));
        }
        conversationUpdated(posted.conversation().id);
        return true;
    }

    private static String resultText(TaskRun run, boolean ownerReadsAlone) {
        var outcome = outcome(run, ownerReadsAlone);
        return LABEL + " " + headline(run) + (outcome.isEmpty() ? "" : " " + outcome) + " " + DO_NOT_REPEAT;
    }

    private static String headline(TaskRun run) {
        var name = run.task.name;
        return switch (run.status) {
            case COMPLETED -> "Task '%s' (run %d) completed in %s.".formatted(name, run.id, seconds(run.durationMs));
            case FAILED -> "Task '%s' (run %d) failed.".formatted(name, run.id);
            case CANCELLED -> "Task '%s' (run %d) was cancelled.".formatted(name, run.id);
            case RUNNING -> "Task '%s' (run %d) has not finished.".formatted(name, run.id);
        };
    }

    private static String outcome(TaskRun run, boolean ownerReadsAlone) {
        return switch (run.status) {
            case COMPLETED -> whereTheOutputWent(run, ownerReadsAlone);
            case FAILED -> failure(run, ownerReadsAlone);
            // The executor writes who canceled it, or the time limit it hit, as the run's summary.
            case CANCELLED -> run.outputSummary == null || run.outputSummary.isBlank() ? "" : sentence(run.outputSummary.strip());
            case RUNNING -> "";
        };
    }

    private static String failure(TaskRun run, boolean ownerReadsAlone) {
        if (run.error == null || run.error.isBlank()) return "No reason was recorded.";
        return ownerReadsAlone ? sentence("The error was: " + SecretRedactor.redact(run.error.strip()))
                : "The reason is on the Tasks page.";
    }

    private static String sentence(String text) {
        return text.endsWith(".") || text.endsWith("!") || text.endsWith("?") ? text : text + ".";
    }

    // The executor stamps a target with NOT_REQUESTED only when the run sent its output itself.
    private static String whereTheOutputWent(TaskRun run, boolean ownerReadsAlone) {
        var status = run.deliveryStatus == null ? TaskRun.DeliveryStatus.UNKNOWN : run.deliveryStatus;
        return switch (status) {
            case DELIVERED -> "Its output was delivered to %s.".formatted(channelOf(run.deliveryTarget));
            case NOT_DELIVERED -> notDelivered(run, ownerReadsAlone);
            case NOT_REQUESTED -> {
                if (run.deliveryTarget != null) yield "The run sent its output itself with the 'message' tool.";
                var spec = DeliverySpec.parse(run.task.delivery);
                yield spec.kind() == DeliverySpec.Kind.TOOL
                        ? "The task delivers its output itself, with %s.".formatted(spec.resolvedTool())
                        : "The task has no delivery set, so its output is only on the Tasks page.";
            }
            case UNKNOWN -> "Its delivery was not recorded.";
        };
    }

    private static String notDelivered(TaskRun run, boolean ownerReadsAlone) {
        if (run.deliveryError == null || run.deliveryError.isBlank()) {
            return "Its output was not delivered to %s: no reason was recorded.".formatted(channelOf(run.deliveryTarget));
        }
        return ownerReadsAlone ? sentence("Its output was not delivered to %s: %s".formatted(channelOf(run.deliveryTarget),
                SecretRedactor.redact(run.deliveryError.strip())))
                : "Its output was not delivered to %s; the reason is on the Tasks page.".formatted(channelOf(run.deliveryTarget));
    }

    // The channel alone: a target can be a chat id the conversation's other readers should not see.
    private static String channelOf(@Nullable String target) {
        var spec = DeliverySpec.parse(target);
        return spec.kind() == DeliverySpec.Kind.CHANNEL ? spec.resolvedChannel() : "its delivery channel";
    }

    private static Map<String, Object> resultMetadata(TaskRun run, boolean ownerReadsAlone) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("taskId", run.task.id);
        metadata.put("taskName", run.task.name);
        metadata.put(KEY_RUN_ID, run.id);
        metadata.put("status", run.status.name());
        if (run.durationMs != null) metadata.put("durationMs", run.durationMs);
        if (run.deliveryStatus != null) metadata.put("deliveryStatus", run.deliveryStatus.name());
        metadata.put("headline", headline(run));
        var outcome = outcome(run, ownerReadsAlone);
        if (!outcome.isEmpty()) metadata.put("outcome", outcome);
        return metadata;
    }

    private static String seconds(@Nullable Long durationMs) {
        return durationMs == null ? "an unrecorded time" : String.format(Locale.ROOT, "%.1f s", durationMs / 1000.0);
    }

    private static Link link(Object conversationId, @Nullable Object ownerInitiated) {
        return new Link((Long) conversationId, Boolean.TRUE.equals(ownerInitiated));
    }

    private static void conversationUpdated(Long conversationId) {
        NotificationBus.publish(NotificationBus.BUS_CONVERSATION_UPDATED, Map.of("conversationId", conversationId));
    }

    private static void runReportTurn(Agent agent, Conversation conversation, boolean ownerInitiated) {
        DangerousActionGate.withOwnerInitiated(ownerInitiated, () -> {
            AgentRunner.resumeAndDeliver(agent, conversation);
            return null;
        });
    }
}
