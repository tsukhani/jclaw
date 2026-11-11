import agents.ToolContext;
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
import play.test.UnitTest;
import services.DeliveryDispatcher;
import services.DeliveryDispatcher.DispatchResult;
import services.ReminderDispatcher;
import services.Tx;
import services.WebDeliveryScope;
import tools.MessageTool;
import tools.TaskTool;
import utils.AppClock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An agent delivers on the web channel only into its own conversations and those above a run it is the
 * child of, and {@code main} into any (JF-289), through the dispatcher, the {@code message} tool and
 * {@code task_manager}. {@code POST}/{@code PATCH /api/tasks} is in {@code WebDeliveryTaskApiTest} and a
 * task's fire in {@code TaskExecutorTest}.
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
