import agents.MessageHydrator;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import utils.GsonHolder;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link MessageHydrator} (JCLAW-309).
 *
 * <p>Covers the central history walker {@code buildMessages} for every
 * persisted role (USER / ASSISTANT with and without tool_calls / TOOL /
 * SYSTEM), the JCLAW-119 tool_call_id sanitization pairing, the JCLAW-193
 * tool-name carry-over from the preceding assistant row, and the
 * {@code contentAsString} / {@code parseToolCalls} helpers.
 *
 * <p>Pruning ACs from the JCLAW-309 ticket ("oldest non-pinned messages
 * drop in order until the budget fits", "Pinned system or first-user
 * messages survive pruning") are deferred — those behaviours live in
 * {@link agents.ContextWindowManager#trimToContextWindow}, not in
 * {@link MessageHydrator}. {@code MessageHydrator} unconditionally hydrates
 * every persisted row returned by {@code ConversationService.loadRecentMessages}
 * (which already caps at {@code chat.maxContextMessages}); the window-aware
 * drop-oldest pass runs in a separate component downstream.
 *
 * <p>This test lives in the default package and invokes the package-private
 * static helpers ({@code buildMessages}, {@code contentAsString},
 * {@code parseToolCalls}) on {@link MessageHydrator} directly. The helpers
 * don't justify a public API surface; same-package visibility is enough.
 */
class MessageHydratorTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "hydrator-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "test-model";
        agent.save();
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static ToolCall toolCall(String id, String name, String args) {
        return new ToolCall(id, "function", new FunctionCall(name, args));
    }

    private static String toolCallJson(String id, String name, String args) {
        return GsonHolder.GSON.toJson(toolCall(id, name, args));
    }

    // ─── buildMessages: empty / system-only inputs ──────────────────────

    @Test
    void buildMessagesEmptyConversationYieldsOnlySystemPrompt() {
        var conv = ConversationService.create(agent, "web", "user1");
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SYSTEM PROMPT", fresh).messages();
        assertEquals(1, messages.size(),
                "empty history must produce a one-element list (system prompt only)");
        assertEquals(MessageRole.SYSTEM.value, messages.getFirst().role());
        assertEquals("SYSTEM PROMPT", messages.getFirst().content());
    }

    @Test
    void buildMessagesAlwaysPlacesSystemPromptAtIndexZero() {
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "first user", null, null, null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, "reply", null, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        assertEquals(MessageRole.SYSTEM.value, messages.getFirst().role());
        assertEquals("SP", messages.getFirst().content());
    }

    // ─── buildMessages: per-role hydration ──────────────────────────────

    @Test
    void buildMessagesHydratesUserRowAsUserChatMessage() {
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "hello there", null, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        assertEquals(2, messages.size());
        var user = messages.get(1);
        assertEquals(MessageRole.USER.value, user.role());
        // VisionAudioAssembler.userMessageFor returns a plain String content
        // for messages without attachments.
        assertEquals("hello there", user.content());
        // Plain user message must not carry tool_calls.
        assertNull(user.toolCalls(), "plain user message must have no tool_calls");
    }

    @Test
    void buildMessagesHydratesPlainAssistantRow() {
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "hi", null, null, null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, "hello back", null, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        assertEquals(3, messages.size());
        var asst = messages.get(2);
        assertEquals(MessageRole.ASSISTANT.value, asst.role());
        assertEquals("hello back", asst.content());
        assertNull(asst.toolCalls(),
                "assistant row without persisted toolCalls JSON must hydrate with null tool_calls");
    }

    @Test
    void buildMessagesHydratesAssistantWithToolCallsArray() {
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "what time?", null, null, null);
        var tcJson = toolCallJson("call_abc", "datetime", "{\"action\":\"now\"}");
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, "let me check", tcJson, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        // SYSTEM + USER + ASSISTANT(with tool_calls)
        assertEquals(3, messages.size());
        var asst = messages.get(2);
        assertEquals(MessageRole.ASSISTANT.value, asst.role());
        assertEquals("let me check", asst.content());
        assertNotNull(asst.toolCalls(), "assistant with persisted toolCalls JSON must hydrate the array");
        assertEquals(1, asst.toolCalls().size(), "single tool call expected");
        var tc = asst.toolCalls().getFirst();
        assertEquals("call_abc", tc.id(), "id round-trips when already safe");
        assertEquals("datetime", tc.function().name(), "function name preserved");
        assertEquals("{\"action\":\"now\"}", tc.function().arguments(),
                "arguments JSON preserved verbatim");
    }

    @Test
    void buildMessagesHydratesToolRowWithIdAndName() {
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "what time?", null, null, null);
        var tcJson = toolCallJson("call_abc", "datetime", "{}");
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, "checking", tcJson, null, null);
        ConversationService.appendMessage(conv, MessageRole.TOOL,
                "2026-05-18T12:00:00Z", null, "call_abc", null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        // SYSTEM + USER + ASSISTANT + TOOL
        assertEquals(4, messages.size());
        var tool = messages.get(3);
        assertEquals(MessageRole.TOOL.value, tool.role(),
                "tool row hydrates with role=tool");
        assertEquals("call_abc", tool.toolCallId(),
                "tool_call_id references the prior assistant tool_call");
        assertEquals("datetime", tool.toolName(),
                "JCLAW-193: function name carried over from the preceding assistant row");
        assertEquals("2026-05-18T12:00:00Z", tool.content(),
                "content (tool result text) passed through");
    }

    @Test
    void buildMessagesSanitizesToolCallIdOnAssistantAndToolRowsConsistently() {
        // JCLAW-119: provider-specific IDs (Gemini-style "functions.web_search:7")
        // get normalized identically on both sides of the pair so pairing survives.
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "search please", null, null, null);
        var tcJson = toolCallJson("functions.web_search:7", "web_search", "{\"q\":\"jclaw\"}");
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, null, tcJson, null, null);
        // Persist the TOOL row with the SAME un-sanitized id; hydration must
        // normalize both sides to the same value.
        ConversationService.appendMessage(conv, MessageRole.TOOL,
                "result body", null, "functions.web_search:7", null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        var asst = messages.get(2);
        var tool = messages.get(3);
        var expectedId = "functions_web_search_7";
        assertEquals(expectedId, asst.toolCalls().getFirst().id(),
                "assistant tool_calls[0].id must be sanitized");
        assertEquals(expectedId, tool.toolCallId(),
                "tool row tool_call_id must be sanitized to the same value");
        assertEquals("web_search", tool.toolName(),
                "tool name lookup uses the sanitized id (registered under sanitized key)");
    }

    @Test
    void buildMessagesHandlesAssistantWithBlankToolCallsString() {
        // toolCalls is non-null but blank — the !isBlank guard takes us into
        // the plain-assistant branch instead of parseToolCalls. content is also
        // null on this row; assistant() helper should fall back to "".
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "hi", null, null, null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, null, "   ", null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        var asst = messages.get(2);
        assertEquals(MessageRole.ASSISTANT.value, asst.role());
        assertNull(asst.toolCalls(),
                "blank toolCalls string must take the no-tool-calls branch");
        // When content is null AgentRunner-side fallback uses "" instead of null.
        assertEquals("", asst.content(), "null content normalized to empty string");
    }

    @Test
    void buildMessagesPreservesAssistantContentWithToolCallsTogether() {
        // assistant turns can carry BOTH a text content and tool_calls; both
        // must come through.
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "do thing", null, null, null);
        var tcJson = toolCallJson("call_1", "shell_exec", "{\"cmd\":\"ls\"}");
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT,
                "I'll run that for you.", tcJson, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        var asst = messages.get(2);
        assertEquals("I'll run that for you.", asst.content());
        assertNotNull(asst.toolCalls());
        assertEquals(1, asst.toolCalls().size());
    }

    @Test
    void buildMessagesHydratesPersistedSystemRow() {
        // Rare but the switch handles it: SYSTEM-role rows hydrate as
        // ChatMessage.system(content).
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.SYSTEM, "context note", null, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        // SYSTEM prompt + persisted SYSTEM row.
        assertEquals(2, messages.size());
        var sys = messages.get(1);
        assertEquals(MessageRole.SYSTEM.value, sys.role());
        assertEquals("context note", sys.content());
    }

    @Test
    void buildMessagesPreservesChronologicalOrderUserAssistantToolUser() {
        // Tests the toolNamesById lookup is properly populated as the loop
        // walks chronologically (the assistant row is always visited before
        // its companion tool row).
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "u1", null, null, null);
        var tc1 = toolCallJson("call_a", "tool_a", "{}");
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, "a1", tc1, null, null);
        ConversationService.appendMessage(conv, MessageRole.TOOL, "result_a", null, "call_a", null);
        ConversationService.appendMessage(conv, MessageRole.USER, "u2", null, null, null);
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        assertEquals(5, messages.size());
        assertEquals(MessageRole.SYSTEM.value, messages.get(0).role());
        assertEquals(MessageRole.USER.value, messages.get(1).role());
        assertEquals(MessageRole.ASSISTANT.value, messages.get(2).role());
        assertEquals(MessageRole.TOOL.value, messages.get(3).role());
        assertEquals(MessageRole.USER.value, messages.get(4).role());
        // Tool row's toolName must be the assistant row's function name.
        assertEquals("tool_a", messages.get(3).toolName());
    }

    @Test
    void buildMessagesUsesUserAsDefaultForUnknownRoleString() {
        // MessageRole.fromValue returns null for unrecognised strings; the
        // hydrator's elvis ?: USER fallback must kick in. Direct entity write
        // so the role string is not normalized.
        var conv = ConversationService.create(agent, "web", "user1");
        var bogus = new models.Message();
        bogus.conversation = conv;
        bogus.role = "voice-of-god";
        bogus.content = "ignored";
        bogus.save();
        commitAndReopen();
        var fresh = Conversation.<Conversation>findById(conv.id);

        var messages = MessageHydrator.buildMessages("SP", fresh).messages();
        // SYSTEM prompt + the bogus row, hydrated through the USER branch.
        assertEquals(2, messages.size());
        assertEquals(MessageRole.USER.value, messages.get(1).role(),
                "unknown role string must fall back to the USER hydration branch");
    }

    // ─── parseToolCalls ─────────────────────────────────────────────────

    @Test
    void parseToolCallsReturnsEmptyForMalformedJson() {
        assertTrue(MessageHydrator.parseToolCalls("not json at all").isEmpty(),
                "malformed JSON must return an empty list rather than throwing");
    }

    @Test
    void parseToolCallsHandlesEmptyJsonObjectWithoutThrowing() {
        // Gson constructs a ToolCall with all-null fields from "{}". The
        // method's catch block only runs on real parse failures — successful
        // parse to a record with null fields still surfaces a one-element list.
        var parsed = MessageHydrator.parseToolCalls("{}");
        assertEquals(1, parsed.size(),
                "Gson parses {} into a non-null record with null fields");
        assertNull(parsed.getFirst().id());
        assertNull(parsed.getFirst().function());
    }

    @Test
    void parseToolCallsReturnsEmptyForJsonNullLiteral() {
        // The JSON null literal takes the explicit `if (tc == null)` null-guard
        // branch (parseToolCalls returns empty list).
        assertTrue(MessageHydrator.parseToolCalls("null").isEmpty(),
                "JSON 'null' must take the null-guard branch and yield empty list");
    }

    @Test
    void parseToolCallsSanitizesIdInRoundTrip() {
        var tcJson = toolCallJson("functions.web_search:7", "web_search", "{}");
        var parsed = MessageHydrator.parseToolCalls(tcJson);
        assertEquals(1, parsed.size());
        assertEquals("functions_web_search_7", parsed.getFirst().id(),
                "parseToolCalls must apply JCLAW-119 sanitization");
        assertEquals("web_search", parsed.getFirst().function().name());
    }

    @Test
    void parseToolCallsLeavesAlreadySafeIdUntouched() {
        var tcJson = toolCallJson("call_abc_123", "shell_exec", "{}");
        var parsed = MessageHydrator.parseToolCalls(tcJson);
        assertEquals("call_abc_123", parsed.getFirst().id(),
                "no rewrite when id is already in [a-zA-Z0-9_-]");
    }

    // ─── contentAsString ────────────────────────────────────────────────

    @Test
    void contentAsStringPassesThroughStrings() {
        assertEquals("hello", MessageHydrator.contentAsString("hello"));
        assertEquals("", MessageHydrator.contentAsString(""));
    }

    @Test
    void contentAsStringReturnsEmptyForNull() {
        assertEquals("", MessageHydrator.contentAsString(null));
    }

    @Test
    void contentAsStringFlattensVisionTextParts() {
        List<Map<String, Object>> parts = List.of(
                Map.of("type", "text", "text", "describe "),
                Map.of("type", "image_url",
                        "image_url", Map.of("url", "data:image/png;base64,AAAA")),
                Map.of("type", "text", "text", "this image"));
        assertEquals("describe this image", MessageHydrator.contentAsString(parts),
                "multi-part content concatenates text parts and skips images");
    }

    @Test
    void contentAsStringFallsBackToToStringForOtherTypes() {
        // A bare number reaches the final .toString() path.
        assertEquals("42", MessageHydrator.contentAsString(42));
    }

    @Test
    void contentAsStringReturnsEmptyForEmptyPartsList() {
        assertEquals("", MessageHydrator.contentAsString(List.of()),
                "empty parts list yields empty string, not the list's toString()");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().clear();
        JPA.em().getTransaction().begin();
    }
}
