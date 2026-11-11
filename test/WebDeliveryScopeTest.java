import agents.ToolContext;
import com.google.gson.JsonObject;
import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import models.Message;
import models.Notification;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.DeliveryDispatcher;
import services.DeliveryDispatcher.DispatchResult;
import services.ReminderDispatcher;
import services.TaskWriteService;
import services.Tx;
import services.WebDeliveryScope;
import tools.MessageTool;
import tools.TaskTool;
import utils.AppClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An agent delivers on the web channel only into its own conversations and those above a run it is the
 * child of, and {@code main} into any (JF-289), through the dispatcher, the {@code message} tool and
 * {@code task_manager}. {@code POST}/{@code PATCH /api/tasks} is in {@code WebDeliveryTaskApiTest} and a
 * task's fire in {@code TaskExecutorTest}.
 *
 * <p>A task records who saved its target (JF-305): the operator's or {@code main}'s is delivered to at the
 * fire whatever the task's agent reaches, and the agent's is judged as the agent's. The operator's save
 * followed by a real fire is in {@code OperatorWebTargetFireTest}.
 *
 * <p>{@code main} is a detached entity, as in {@code RequestPrincipalPolicyTest}: its row is provisioned
 * by code and a test may not seed one, and the rule reads only its name.
 */
class WebDeliveryScopeTest extends UnitTest {

    private String suffix;
    private Agent owner;
    private Agent alien;
    private Conversation own;
    private Conversation alienChat;

    // No Fixtures.deleteDatabase(): every row is this class's by its unique name, and the rollback
    // in tearDown leaves none behind.
    @BeforeEach
    void seed() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = agent("jf289-owner-", null);
        alien = agent("jf289-alien-", null);
        own = conversation(owner, null);
        alienChat = conversation(alien, null);
    }

    @AfterEach
    void tearDown() {
        // task_manager defers its scheduler call to the commit, which must never come.
        DeferredSchedulerCalls.discard();
    }

    // --- the dispatcher ------------------------------------------------------------------

    @Test
    void anAgentDeliversIntoItsOwnConversation() {
        assertEquals(DispatchResult.delivered(), send(owner, own.id, "to my own chat"));
        assertEquals(List.of("to my own chat"), delivered(own));
    }

    @Test
    void anotherAgentsConversationIsRefusedWithTheErrorAMissingOneGets() {
        var missing = deletedConversationId();

        assertEquals(DispatchResult.failedDelivery(notFound(alienChat.id)), send(owner, alienChat.id, "not mine"));
        assertEquals(DispatchResult.failedDelivery(notFound(missing)), send(owner, missing, "nobody's"));
        assertEquals(List.of(), delivered(alienChat));
    }

    @Test
    void aSubagentDeliversIntoTheConversationThatStartedItsRun() {
        var family = family();

        assertEquals(DispatchResult.delivered(), send(family.child(), family.started().id, "report"));
        assertEquals(List.of("report"), delivered(family.started()));
    }

    @Test
    void aSubagentsOwnConversationStillReachesTheChatAboveIt() {
        var family = family();

        assertEquals(DispatchResult.delivered(), send(family.child(), family.childChat().id, "45%"));
        assertEquals(List.of("45%"), delivered(family.started()));
        assertEquals(List.of(), delivered(family.childChat()));
    }

    @Test
    void aSubagentDeliversIntoAConversationFurtherUpItsChain() {
        var family = family();
        var grandchild = agent("jf289-grandchild-", family.child());
        var grandchildChat = conversation(grandchild, family.childChat());
        run(family.child(), grandchild, family.childChat(), grandchildChat);

        assertEquals(DispatchResult.delivered(), send(grandchild, family.started().id, "from two runs down"));
        assertEquals(List.of("from two runs down"), delivered(family.started()));
    }

    @Test
    void aSubagentIsRefusedAConversationOfItsParentsThatDidNotStartItsRun() {
        var family = family();
        var parentsOtherChat = conversation(family.parent(), null);

        assertEquals(DispatchResult.failedDelivery(notFound(parentsOtherChat.id)),
                send(family.child(), parentsOtherChat.id, "sideways"));
        assertEquals(List.of(), delivered(parentsOtherChat));
    }

    @Test
    void aSubagentIsRefusedTheConversationOfARunBesideItsOwn() {
        var family = family();
        var sibling = agent("jf289-sibling-", family.parent());
        var siblingChat = conversation(sibling, family.started());
        run(family.parent(), sibling, family.started(), siblingChat);

        assertEquals(DispatchResult.failedDelivery(notFound(siblingChat.id)),
                send(family.child(), siblingChat.id, "across"));
        assertEquals(List.of(), delivered(family.started()));
    }

    @Test
    void anInlineRunsAgentDeliversIntoTheChatItRunsInAndNoOther() {
        var parent = agent("jf289-inline-parent-", null);
        var chat = conversation(parent, null);
        var otherChat = conversation(parent, null);
        var inline = agent("jf289-inline-child-", parent);
        // An inline run's child conversation is the parent's chat; the child agent owns none.
        run(parent, inline, chat, chat);

        assertEquals(DispatchResult.delivered(), send(inline, chat.id, "inline progress"));
        assertEquals(List.of("inline progress"), delivered(chat));
        assertEquals(DispatchResult.failedDelivery(notFound(otherChat.id)), send(inline, otherChat.id, "sideways"));
        assertEquals(List.of(), delivered(otherChat));
    }

    @Test
    void mainDeliversIntoAnyConversation() {
        assertEquals(DispatchResult.delivered(), send(main(), alienChat.id, "from main"));
        assertEquals(List.of("from main"), delivered(alienChat));
    }

    // --- the message tool ----------------------------------------------------------------

    @Test
    void theMessageToolRefusesAnotherAgentsConversationAsItDoesAMissingOne() {
        var missing = deletedConversationId();

        assertEquals("Error: " + notFound(alienChat.id), message(owner, own, alienChat.id, "not mine"));
        assertEquals("Error: " + notFound(missing), message(owner, own, missing, "nobody's"));
        assertEquals(List.of(), delivered(alienChat));

        var ownOther = conversation(owner, null);
        assertEquals("{\"action\":\"sent\",\"channel\":\"web\",\"target\":\"" + ownOther.id + "\"}",
                message(owner, own, ownOther.id, "to my other chat"));
        assertEquals(List.of("to my other chat"), delivered(ownOther));
    }

    @Test
    void theMessageToolLetsASubagentReportToTheConversationThatStartedItsRun() {
        var family = family();

        assertEquals("{\"action\":\"sent\",\"channel\":\"web\",\"target\":\"" + family.started().id + "\"}",
                message(family.child(), family.childChat(), family.started().id, "done"));
        assertEquals(List.of("done"), delivered(family.started()));
    }

    // --- task_manager --------------------------------------------------------------------

    @Test
    void anAgentCannotCreateATaskThatDeliversIntoAnotherAgentsConversation() {
        var missing = deletedConversationId();
        var name = "jf289-create-" + suffix;

        assertEquals("Error: " + notFound(alienChat.id), taskManager(owner, create(name, "web:" + alienChat.id)));
        assertEquals("Error: " + notFound(missing), taskManager(owner, create(name, "web:" + missing)));
        assertEquals(0L, (long) Tx.run(() -> Task.count("name = ?1", name)));

        assertEquals("Interval task '" + name + "' created (every 1800s).",
                taskManager(owner, create(name, "web:" + own.id)));
        assertEquals("web:" + own.id, deliveryOf(name));
    }

    @Test
    void anAgentCannotRepointItsTaskAtAnotherAgentsConversation() {
        var missing = deletedConversationId();
        var name = "jf289-update-" + suffix;
        taskManager(owner, create(name, "web:" + own.id));

        assertEquals("Error: " + notFound(alienChat.id), taskManager(owner, update(name, "web:" + alienChat.id)));
        assertEquals("Error: " + notFound(missing), taskManager(owner, update(name, "web:" + missing)));
        assertEquals("web:" + own.id, deliveryOf(name));

        var ownOther = conversation(owner, null);
        assertEquals("Task '" + name + "' updated.", taskManager(owner, update(name, "web:" + ownOther.id)));
        assertEquals("web:" + ownOther.id, deliveryOf(name));
    }

    @Test
    void aSubagentSavesTheConversationThatStartedItsRunAndNoOtherOfItsParents() {
        var family = family();
        var parentsOtherChat = conversation(family.parent(), null);
        var name = "jf289-sub-" + suffix;

        assertEquals("Interval task '" + name + "' created (every 1800s).",
                taskManager(family.child(), create(name, "web:" + family.started().id)));
        assertEquals("web:" + family.started().id, deliveryOf(name));

        var refused = "jf289-sub-refused-" + suffix;
        assertEquals("Error: " + notFound(parentsOtherChat.id),
                taskManager(family.child(), create(refused, "web:" + parentsOtherChat.id)));
        assertEquals(0L, (long) Tx.run(() -> Task.count("name = ?1", refused)));
    }

    @Test
    void aTaskCreatedInAChatDeliversBackToIt() {
        var name = "jf289-inferred-" + suffix;
        var created = ToolContext.withConversation(own.id, () -> new TaskTool().execute(
                "{\"action\":\"createTask\",\"name\":\"%s\",\"schedule\":\"every 30m\"}".formatted(name), owner));
        assertEquals("Interval task '" + name + "' created (every 1800s).", created);
        assertEquals("web:" + own.id, deliveryOf(name));

        // What TaskExecutor.dispatchDelivery calls when the fire closes.
        var result = Tx.run(() -> DeliveryDispatcher.dispatchSpec(owner, deliveryOf(name), "the fire's output",
                "the result of task '" + name + "'"));
        assertEquals(DispatchResult.delivered(), result);
        assertEquals(List.of("the fire's output"), delivered(own));
    }

    @Test
    void mainSavesAnyConversationAsATasksTarget() {
        var missing = deletedConversationId();

        assertNull(WebDeliveryScope.refusalFor(main(), "web:" + alienChat.id));
        assertNull(WebDeliveryScope.refusalFor(main(), "web:" + missing),
                "main's save is not checked: an id that names nothing fails at the fire, as it always did");
        assertEquals(notFound(alienChat.id), WebDeliveryScope.refusalFor(owner, "web:" + alienChat.id));
    }

    @Test
    void onlyADeliveryThatNamesAWebConversationIsChecked() {
        for (var spec : List.of("telegram:" + alienChat.id, "slack:C0123", "tool:send_gmail_message", "none",
                "web", "web:admin")) {
            assertNull(WebDeliveryScope.refusalFor(owner, spec), spec);
        }
        assertNull(WebDeliveryScope.refusalFor(owner, null));
        assertEquals(notFound(alienChat.id), WebDeliveryScope.refusalFor(owner, " WEB: " + alienChat.id + " "));
    }

    @Test
    void anAgentNobodyIdentifiedReachesNoConversation() {
        assertEquals(notFound(own.id), WebDeliveryScope.refusalFor(null, "web:" + own.id));
    }

    // --- who saved a task's target (JF-305) ----------------------------------------------

    @Test
    void aFireDeliversATargetTheOperatorSavedForAnAgentsTaskIntoAnyConversation() {
        var task = taskOf(owner, "web:" + own.id);
        patchDelivery(task, "web:" + alienChat.id, true);

        assertEquals(DispatchResult.delivered(), fire(task, "for the other agent's chat"));
        assertEquals(List.of("for the other agent's chat"), delivered(alienChat));
        assertEquals(List.of(), delivered(own));
    }

    @Test
    void aFireJudgesATargetTheAgentSavedAsTheAgentsOwnSend() {
        var task = taskOf(owner, "web:" + own.id);
        patchDelivery(task, "web:" + own.id, false);
        assertEquals(DispatchResult.delivered(), fire(task, "to my own chat"));
        assertEquals(List.of("to my own chat"), delivered(own));

        patchDelivery(task, "web:" + alienChat.id, false);
        assertEquals(DispatchResult.failedDelivery(notFound(alienChat.id)), fire(task, "not mine"));
        assertEquals(List.of(), delivered(alienChat));
    }

    @Test
    void aTargetWrittenBeforeTheTaskRecordedWhoSavedItIsJudgedAsTheAgents() {
        // The field alone, as a row from before the column holds it.
        var task = taskOf(owner, "web:" + alienChat.id);

        assertEquals(new Saved("web:" + alienChat.id, false), savedOn(task.name));
        assertEquals(DispatchResult.failedDelivery(notFound(alienChat.id)), fire(task, "not mine"));
        assertEquals(List.of(), delivered(alienChat));
    }

    @Test
    void theColumnDefaultsToTheAgentSoARowFromBeforeItGainsNoReach() {
        var column = Tx.run(() -> (Object[]) JPA.em().createNativeQuery(
                "SELECT COLUMN_DEFAULT, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME) = 'task' AND LOWER(COLUMN_NAME) = 'delivery_set_by_operator'")
                .getSingleResult());

        assertEquals(List.of("FALSE", "NO"), List.of(String.valueOf(column[0]), String.valueOf(column[1])));
        assertFalse(new Task().deliverySetByOperator);
    }

    @Test
    void aTargetTheOperatorSavedThatNamesNoConversationIsNotFound() {
        var missing = deletedConversationId();
        var task = taskOf(owner, "web:" + own.id);
        patchDelivery(task, "web:" + missing, true);

        assertEquals(DispatchResult.failedDelivery(notFound(missing)), fire(task, "nobody's"));
    }

    @Test
    void onlyTheOperatorsAndMainsSaveOfATargetReachesPastTheTasksAgent() {
        // What PATCH /api/tasks/{id} asks of its caller: the operator's session, then three agent-originated requests.
        record Caller(String who, boolean agentOriginated, Agent agent, boolean delivers) {}
        var callers = List.of(
                new Caller("the operator", false, null, true),
                new Caller("main", true, main(), true),
                new Caller("the task's own agent", true, owner, false),
                new Caller("an agent nobody identified", true, null, false));
        var task = taskOf(owner, "web:" + own.id);

        for (var caller : callers) {
            var byOperatorOrMain = RequestPrincipal.isOperatorOrMain(caller.agentOriginated(), caller.agent());
            patchDelivery(task, "web:" + alienChat.id, byOperatorOrMain);
            var output = "saved by " + caller.who();

            assertEquals(new Saved("web:" + alienChat.id, caller.delivers()), savedOn(task.name), caller.who());
            assertEquals(caller.delivers() ? DispatchResult.delivered()
                    : DispatchResult.failedDelivery(notFound(alienChat.id)), fire(task, output), caller.who());
        }
        assertEquals(List.of("saved by the operator", "saved by main"), delivered(alienChat));
    }

    @Test
    void taskManagerRecordsTheAgentAsTheOneWhoSavedTheTarget() {
        var named = "jf305-named-" + suffix;
        var inferred = "jf305-inferred-" + suffix;
        taskManager(owner, create(named, "web:" + own.id));
        ToolContext.withConversation(own.id, () -> new TaskTool().execute(
                "{\"action\":\"createTask\",\"name\":\"%s\",\"schedule\":\"every 30m\"}".formatted(inferred), owner));

        assertEquals(new Saved("web:" + own.id, false), savedOn(named));
        assertEquals(new Saved("web:" + own.id, false), savedOn(inferred));
    }

    @Test
    void anAgentsEditOfWhatAFireProducesOrWhenItFiresMakesTheOperatorsTargetTheAgents() {
        var name = "jf305-update-" + suffix;
        taskManager(owner, create(name, "web:" + own.id));
        var edits = List.of(
                "\"description\":\"rewritten\"",
                "\"schedule\":\"every 45m\"",
                "\"payloadType\":\"reminder\"",
                "\"enabledToolNames\":\"[\\\"web_search\\\"]\"",
                "\"timezone\":\"Asia/Tokyo\"");

        for (var edit : edits) {
            patchDelivery(taskNamed(name), "web:" + alienChat.id, true);
            assertEquals(DispatchResult.delivered(), fire(taskNamed(name), "before " + edit), edit);

            assertEquals("Task '" + name + "' updated.", taskManager(owner,
                    "{\"action\":\"updateTask\",\"name\":\"%s\",%s}".formatted(name, edit)), edit);

            assertEquals(new Saved("web:" + alienChat.id, false), savedOn(name), edit);
            assertEquals(DispatchResult.failedDelivery(notFound(alienChat.id)), fire(taskNamed(name), "after " + edit), edit);
        }
        assertEquals(edits.stream().map(edit -> "before " + edit).toList(), delivered(alienChat));
    }

    @Test
    void anAgentThatSwitchesItsTaskOffAndOnKeepsTheOperatorsTarget() {
        var name = "jf305-switch-" + suffix;
        taskManager(owner, create(name, "web:" + own.id));
        patchDelivery(taskNamed(name), "web:" + alienChat.id, true);

        for (var edit : List.of("\"paused\":true", "\"paused\":false", "\"autoDeleteOnComplete\":true")) {
            assertEquals("Task '" + name + "' updated.", taskManager(owner,
                    "{\"action\":\"updateTask\",\"name\":\"%s\",%s}".formatted(name, edit)), edit);
            assertEquals(new Saved("web:" + alienChat.id, true), savedOn(name), edit);
        }
        assertEquals(DispatchResult.delivered(), fire(taskNamed(name), "after the switches"));
        assertEquals(List.of("after the switches"), delivered(alienChat));
    }

    @Test
    void theOperatorsTargetSentBackByTheAgentIsRefusedAndStaysTheOperators() {
        var name = "jf305-echo-" + suffix;
        var ownOther = conversation(owner, null);
        taskManager(owner, create(name, "web:" + own.id));
        patchDelivery(taskNamed(name), "web:" + alienChat.id, true);

        // Sent back unchanged it is still the agent's save, and out of the agent's reach.
        assertEquals("Error: " + notFound(alienChat.id), taskManager(owner, update(name, "web:" + alienChat.id)));
        assertEquals(new Saved("web:" + alienChat.id, true), savedOn(name));

        assertEquals("Task '" + name + "' updated.", taskManager(owner, update(name, "web:" + ownOther.id)));
        assertEquals(new Saved("web:" + ownOther.id, false), savedOn(name));
        assertEquals("Error: " + notFound(alienChat.id), taskManager(owner, update(name, "web:" + alienChat.id)));
        assertEquals(new Saved("web:" + ownOther.id, false), savedOn(name));
    }

    @Test
    void aTargetAnAgentPutInPlaceOfTheOperatorsIsRefusedOnceItIsOutOfTheAgentsReach() {
        var family = family();
        var name = "jf305-sub-" + suffix;
        taskManager(family.child(), create(name, "web:" + family.childChat().id));
        patchDelivery(taskNamed(name), "web:" + alienChat.id, true);
        assertEquals(DispatchResult.delivered(), fire(taskNamed(name), "the operator's target"));

        assertEquals("Task '" + name + "' updated.",
                taskManager(family.child(), update(name, "web:" + family.started().id)));
        assertEquals(new Saved("web:" + family.started().id, false), savedOn(name));
        assertEquals(DispatchResult.delivered(), fire(taskNamed(name), "while the run is on record"));

        // The run that put the chat in the child's reach is deleted, as retention or the operator deletes one.
        Tx.run(() -> {
            ((SubagentRun) SubagentRun.find("childAgent = ?1", family.child()).first()).delete();
        });
        assertEquals(DispatchResult.failedDelivery(notFound(family.started().id)),
                fire(taskNamed(name), "after the run is gone"));
        assertEquals(List.of("the operator's target"), delivered(alienChat));
        assertEquals(List.of("while the run is on record"), delivered(family.started()));
    }

    @Test
    void anAgentThatClearsTheOperatorsTargetLeavesNoneBehind() {
        var name = "jf305-clear-" + suffix;
        taskManager(owner, create(name, "web:" + own.id));
        patchDelivery(taskNamed(name), "web:" + alienChat.id, true);

        assertEquals("Task '" + name + "' updated.", taskManager(owner,
                "{\"action\":\"updateTask\",\"name\":\"%s\",\"delivery\":null}".formatted(name)));

        assertEquals(new Saved("null", false), savedOn(name));
    }

    @Test
    void noCodeSetsATasksDeliveryWithoutSayingWhoSavesIt() throws IOException {
        var assignment = Pattern.compile("\\.delivery\\s*=(?!=)");
        var offenders = new ArrayList<String>();
        int writers = 0;
        try (var files = Files.walk(Path.of(Play.applicationPath.getAbsolutePath()).resolve("app"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var source = ResourceLeakGateConformanceTest.withoutComments(Files.readString(file));
                if (assignment.matcher(source).find()) offenders.add(file.getFileName().toString());
                for (int at = source.indexOf(".setDelivery("); at >= 0; at = source.indexOf(".setDelivery(", at + 1)) writers++;
            }
        }

        assertEquals(List.of(), offenders, "these files assign Task.delivery directly, which leaves whoever saved "
                + "the last target as the saver of the new one. Call Task.setDelivery(spec, byOperatorOrMain)");
        assertEquals(4, writers, "the scan must still find the writers it guards: the tool's create and update, "
                + "and the API's. Update the count when a writer is added or removed");
    }

    // --- a reminder's fire ---------------------------------------------------------------

    @Test
    void aReminderBecomesANotificationAndNoMessageInTheConversationItsDeliveryNames() {
        var spec = "web:" + alienChat.id;
        var task = Tx.run(() -> {
            var reminder = new Task();
            reminder.agent = owner;
            reminder.name = "jf289-reminder-" + suffix;
            reminder.description = "stand up";
            reminder.type = Task.Type.SCHEDULED;
            reminder.status = Task.initialStatusFor(Task.Type.SCHEDULED);
            reminder.payloadType = "reminder";
            reminder.delivery = spec;
            reminder.save();
            return reminder;
        });
        var run = Tx.run(() -> {
            var fired = new TaskRun();
            fired.task = task;
            fired.startedAt = AppClock.now();
            fired.status = TaskRun.Status.COMPLETED;
            fired.save();
            return fired;
        });

        assertEquals(DispatchResult.delivered(),
                Tx.run(() -> ReminderDispatcher.dispatch(task, run, spec, "stand up")));

        var notified = Tx.run(() -> {
            var rows = new ArrayList<String>();
            for (var row : Notification.find("sourceTaskId = ?1", task.id).fetch()) {
                var notification = (Notification) row;
                rows.add(notification.agent.name + ": " + notification.content);
            }
            return rows;
        });
        assertEquals(List.of(owner.name + ": stand up"), notified);
        assertEquals(0L, (long) Tx.run(() -> Message.count("conversation.id = ?1", alienChat.id)));
    }

    // --- helpers -------------------------------------------------------------------------

    private record Family(Agent parent, Conversation started, Agent child, Conversation childChat) {}

    /** A parent's chat, and a subagent whose session run that chat started. */
    private Family family() {
        var parent = agent("jf289-parent-", null);
        var started = conversation(parent, null);
        var child = agent("jf289-child-", parent);
        var childChat = conversation(child, started);
        run(parent, child, started, childChat);
        return new Family(parent, started, child, childChat);
    }

    private static Agent main() {
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        return main;
    }

    private Agent agent(String prefix, Agent parent) {
        return Tx.run(() -> {
            var agent = new Agent();
            agent.name = prefix + suffix;
            agent.modelProvider = "test-provider";
            agent.modelId = "test-model";
            agent.enabled = true;
            agent.parentAgent = parent;
            agent.save();
            return agent;
        });
    }

    private static Conversation conversation(Agent agent, Conversation parent) {
        return Tx.run(() -> {
            var conversation = new Conversation();
            conversation.agent = agent;
            conversation.channelType = "web";
            conversation.peerId = "jf289";
            conversation.parentConversation = parent;
            conversation.save();
            return conversation;
        });
    }

    private static void run(Agent parent, Agent child, Conversation started, Conversation childChat) {
        Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = started;
            run.childConversation = childChat;
            run.startedAt = AppClock.now();
            run.save();
        });
    }

    /** The id of a conversation that existed a moment ago, so it names nothing now. */
    private Long deletedConversationId() {
        return Tx.run(() -> {
            var gone = conversation(owner, null);
            var id = gone.id;
            gone.delete();
            return id;
        });
    }

    private static String notFound(Long conversationId) {
        return "Web target conversation " + conversationId + " not found.";
    }

    private static DispatchResult send(Agent agent, Long targetId, String text) {
        return Tx.run(() -> DeliveryDispatcher.dispatch(agent, "web", String.valueOf(targetId), text));
    }

    /** The {@code message} tool as {@code agent}, in a turn running in {@code calling}. */
    private static String message(Agent agent, Conversation calling, Long targetId, String text) {
        var args = "{\"action\":\"send\",\"channel\":\"web\",\"target\":\"%d\",\"message\":\"%s\"}"
                .formatted(targetId, text);
        return ToolContext.withConversation(calling.id, () -> new MessageTool().execute(args, agent));
    }

    private static String taskManager(Agent agent, String args) {
        return new TaskTool().execute(args, agent);
    }

    private static String create(String name, String delivery) {
        return "{\"action\":\"createTask\",\"name\":\"%s\",\"schedule\":\"every 30m\",\"delivery\":\"%s\"}"
                .formatted(name, delivery);
    }

    private static String update(String name, String delivery) {
        return "{\"action\":\"updateTask\",\"name\":\"%s\",\"delivery\":\"%s\"}".formatted(name, delivery);
    }

    private static String deliveryOf(String taskName) {
        return Tx.run(() -> ((Task) Task.find("name = ?1", taskName).first()).delivery);
    }

    /** A task's delivery and whether the operator or main saved it. */
    private record Saved(String delivery, boolean byOperatorOrMain) {}

    private static Saved savedOn(String taskName) {
        var task = taskNamed(taskName);
        return new Saved(String.valueOf(task.delivery), task.deliverySetByOperator);
    }

    private static Task taskNamed(String taskName) {
        return Tx.run(() -> (Task) Task.find("name = ?1", taskName).first());
    }

    /** A task of {@code agent} whose {@code delivery} field is written directly, as every row before JF-305 was. */
    private Task taskOf(Agent agent, String delivery) {
        return Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = "jf305-task-" + UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
            task.description = "Report.";
            task.type = Task.Type.SCHEDULED;
            task.status = Task.initialStatusFor(Task.Type.SCHEDULED);
            task.delivery = delivery;
            task.save();
            return task;
        });
    }

    /** What {@code PATCH /api/tasks/{id}} applies, once its checks pass, for a body that carries a delivery alone. */
    private static void patchDelivery(Task task, String delivery, boolean byOperatorOrMain) {
        Tx.run(() -> {
            var body = new JsonObject();
            body.addProperty("delivery", delivery);
            TaskWriteService.applyOptionalFieldUpdates(task, body, byOperatorOrMain);
            task.save();
        });
    }

    /** What {@code TaskExecutor.dispatchDelivery} calls when a fire of {@code task} closes with {@code output}. */
    private static DispatchResult fire(Task task, String output) {
        return Tx.run(() -> DeliveryDispatcher.dispatchTaskDelivery((Task) Task.findById(task.id), output,
                "the result of task '" + task.name + "'"));
    }

    /** What web delivery appended to {@code conversation}, oldest first. */
    private static List<String> delivered(Conversation conversation) {
        return Tx.run(() -> {
            var contents = new ArrayList<String>();
            for (var row : Message.find("conversation.id = ?1 AND messageKind = ?2 ORDER BY id ASC",
                    conversation.id, "subagent_send").fetch()) {
                contents.add(((Message) row).content);
            }
            return contents;
        });
    }
}
