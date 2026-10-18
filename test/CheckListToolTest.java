import agents.CurrentTimeInjector;
import agents.ToolContext;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import tools.CheckListTool;
import tools.SubagentSpawnTool;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JCLAW-1569: the checklist is stored per conversation and put back in front of the model. */
class CheckListToolTest extends UnitTest {

    private static final String TWO_ITEMS = """
            {"items":[
              {"content":"Run tests","status":"completed","activeForm":"Running tests","extra":"dropped"},
              {"content":"Fix the parser","status":"in_progress","activeForm":"Fixing the parser"}
            ]}""";

    private Conversation conversation;

    @BeforeEach
    void setup() {
        Agent agent = AgentService.create("checklist-" + UUID.randomUUID(), "openrouter", "test-model");
        conversation = ConversationService.create(agent, "web", "user1");
    }

    @Test
    void aSuccessfulCallStoresTheListOnItsConversation() {
        var result = ToolContext.withConversation(conversation.id,
                () -> new CheckListTool().execute(TWO_ITEMS, conversation.agent));

        assertEquals("Checklist updated successfully (2 items).", result);
        var reloaded = reload();
        assertNotNull(reloaded.checklistUpdatedAt, "the call is dated, so an abandoned list can expire");
        var stored = CheckListTool.storedItems(reloaded.checklist);
        assertNotNull(stored);
        assertEquals(2, stored.size());
        assertEquals("Fixing the parser", stored.get(1).getAsJsonObject().get("activeForm").getAsString());
        assertFalse(stored.get(0).getAsJsonObject().has("extra"), "only the three item fields are stored");
    }

    @Test
    void aRefusedCallLeavesTheStoredListAlone() {
        ToolContext.withConversation(conversation.id, () -> new CheckListTool().execute(TWO_ITEMS, conversation.agent));
        var before = reload().checklist;

        var refused = ToolContext.withConversation(conversation.id, () -> new CheckListTool().execute("""
                {"items":[
                  {"content":"A","status":"in_progress","activeForm":"Doing A"},
                  {"content":"B","status":"in_progress","activeForm":"Doing B"}
                ]}""", conversation.agent));

        assertTrue(refused.startsWith("Error: At most one item"), refused);
        assertEquals(before, reload().checklist);
    }

    @Test
    void aCallOutsideAConversationStoresNothingAndStillSucceeds() {
        assertEquals("Checklist updated successfully (2 items).",
                new CheckListTool().execute(TWO_ITEMS, conversation.agent));
        assertNull(reload().checklist);
    }

    @Test
    void reminderListsTheOpenListWhenNoCallIsInContext() {
        storeTwoItems(Instant.now());

        var reminder = CheckListTool.reminder(conversation, List.of(ChatMessage.user("hi")));

        assertNotNull(reminder);
        assertTrue(reminder.contains(CheckListTool.REMINDER_HEADING), reminder);
        assertTrue(reminder.contains("- [completed] Run tests"), reminder);
        assertTrue(reminder.contains("- [in progress] Fixing the parser"), reminder);
    }

    @Test
    void reminderIsNullWithoutAListOrWithEveryItemCompleted() {
        assertNull(CheckListTool.reminder(conversation, List.of(ChatMessage.user("hi"))));

        conversation.checklist = "[{\"content\":\"Run tests\",\"status\":\"completed\",\"activeForm\":\"Running tests\"}]";
        conversation.checklistUpdatedAt = Instant.now();
        assertNull(CheckListTool.reminder(conversation, List.of(ChatMessage.user("hi"))));
    }

    @Test
    void anUnfinishedListIsForgottenOnceMoreThanThreeUserMessagesPassWithoutAnUpdate() {
        storeTwoItems(Instant.now().minusSeconds(60));
        for (int i = 0; i < CheckListTool.ABANDONED_AFTER_USER_MESSAGES; i++) {
            ConversationService.appendUserMessage(conversation, "unrelated " + i);
        }
        assertNotNull(CheckListTool.reminder(conversation, List.of(ChatMessage.user("hi"))),
                "three later user messages leave the list current");

        ConversationService.appendUserMessage(conversation, "one more");

        assertNull(CheckListTool.reminder(conversation, List.of(ChatMessage.user("hi"))));
        assertNull(reload().checklist, "an abandoned list is cleared, so the chat stops showing it too");
    }

    @Test
    void returningSubagentResultsDoNotAgeTheList() {
        storeTwoItems(Instant.now().minusSeconds(60));
        for (int i = 0; i <= CheckListTool.ABANDONED_AFTER_USER_MESSAGES; i++) {
            var announce = ConversationService.appendMessage(conversation, MessageRole.USER, "subagent " + i + " finished",
                    null, null, null);
            announce.messageKind = SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE;
            announce.save();
            var inlineChild = ConversationService.appendMessage(conversation, MessageRole.USER, "child task " + i,
                    null, null, null);
            inlineChild.subagentRunId = 1000L + i;
            inlineChild.save();
        }

        assertNotNull(CheckListTool.reminder(conversation, List.of(ChatMessage.user("hi"))),
                "only messages a person sent count toward abandoning the list");
        assertNotNull(reload().checklist);
    }

    @Test
    void reminderIsNullWhileTheCallThatSetTheListIsStillInContext() {
        storeTwoItems(Instant.now());
        var call = new ToolCall("c1", "function", new FunctionCall("checklist", TWO_ITEMS));
        var messages = List.of(ChatMessage.user("hi"), new ChatMessage("assistant", null, List.of(call), null, null));

        assertNull(CheckListTool.reminder(conversation, messages));
    }

    @Test
    void theReminderRidesInsideTheTrailingClockMessage() {
        storeTwoItems(Instant.now());
        var messages = List.of(ChatMessage.system("sys"), ChatMessage.user("hi"));

        var out = CurrentTimeInjector.inject(messages, CheckListTool.reminder(conversation, messages));

        assertEquals(messages.size() + 1, out.size(), "the reminder adds no message of its own");
        var last = out.getLast().content();
        assertTrue(CurrentTimeInjector.isClockBlock(last), "the provider must still see the clock message");
        assertTrue(((String) last).contains(CheckListTool.REMINDER_HEADING), String.valueOf(last));
    }

    private void storeTwoItems(Instant updatedAt) {
        conversation.checklist = """
                [{"content":"Run tests","status":"completed","activeForm":"Running tests"},
                 {"content":"Fix the parser","status":"in_progress","activeForm":"Fixing the parser"}]""";
        conversation.checklistUpdatedAt = updatedAt;
        conversation.save();
    }

    private Conversation reload() {
        JPA.em().flush();
        JPA.em().clear();
        return Conversation.findById(conversation.id);
    }
}
