import agents.CurrentTimeInjector;
import agents.ToolContext;
import agents.ToolResultPruner;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.compression.ContentHash;
import tools.CcrRetrieveTool;

import java.util.ArrayList;
import java.util.List;

/** JCLAW-1202: old large tool results become stubs that resolve through ccr_retrieve. */
class ToolResultPrunerTest extends UnitTest {

    private static final String BIG = "x".repeat(5_000);
    /** A history the load did not cut at its row cap. */
    private static final int NO_CAP = Integer.MAX_VALUE;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    private static ChatMessage tool(String id, String name, String body) {
        return new ChatMessage("tool", body, null, id, name);
    }

    @Test
    void anEarlierTurnsLargeResultBecomesAStubCarryingItsHandleAndCallId() {
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("first ask"),
                ChatMessage.assistant("", List.of()),
                tool("c1", "web_fetch", BIG),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"),
                ChatMessage.assistant("", List.of()),
                tool("c2", "web_fetch", BIG));

        var pruned = ToolResultPruner.prune(messages, 4_000, 2, 1, NO_CAP, null, null);

        var stub = pruned.get(3);
        assertTrue(((String) stub.content()).startsWith(ToolResultPruner.STUB_MARKER), stub.content().toString());
        assertTrue(((String) stub.content()).contains("ccr_retrieve(\"" + ContentHash.handle(BIG) + "\")"),
                "the stub carries the hash of the original body");
        assertTrue(((String) stub.content()).contains("web_fetch returned 5000 chars"));
        assertEquals("c1", stub.toolCallId(), "the tool_call pairing survives");
        assertSame(BIG, pruned.get(7).content(), "the current turn's result is sent whole");
        assertSame(messages.get(3), messages.get(3), "the input list is not mutated");
        assertEquals(BIG, messages.get(3).content());
    }

    @Test
    void anEarlierTurnsRetrievedCopyIsStubbedAndTheCurrentTurnsIsSentWhole() {
        var messages = List.of(
                ChatMessage.user("first ask"),
                tool("c1", "ccr_retrieve", BIG),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"),
                tool("c2", "ccr_retrieve", BIG));

        var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

        var stub = (String) pruned.get(1).content();
        assertTrue(stub.startsWith(ToolResultPruner.STUB_MARKER), stub);
        assertTrue(stub.contains("ccr_retrieve(\"" + ContentHash.handle(BIG) + "\")"),
                "its handle is the original's, so the copy can be fetched again");
        assertSame(BIG, pruned.get(4).content(), "a copy fetched this turn is what the model asked to read");
    }

    @Test
    void smallResultsAndExistingStubsAreLeftAlone() {
        var messages = List.of(
                ChatMessage.user("first ask"),
                tool("c1", "web_fetch", "short"),
                tool("c3", "web_fetch", ToolResultPruner.STUB_MARKER + ": already stubbed]"),
                ChatMessage.user("second ask"),
                ChatMessage.assistant("done"));

        var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

        assertSame(messages, pruned, "nothing eligible: the same list instance comes back");
    }

    @Test
    void theCurrentTurnAndTheNewestMessagesAreNeverStubbed() {
        var messages = new java.util.ArrayList<ChatMessage>();
        messages.add(ChatMessage.user("one ask, many rounds"));
        for (int i = 0; i < 10; i++) messages.add(tool("c" + i, "web_search", BIG));
        assertSame(messages, ToolResultPruner.prune(messages, 4_000, 2, 1, NO_CAP, null, null),
                "everything after the last user message belongs to the turn in flight");

        var older = List.of(ChatMessage.user("a"), tool("c1", "t", BIG), ChatMessage.user("b"), tool("c2", "t", BIG),
                ChatMessage.user("c"));
        var pruned = ToolResultPruner.prune(older, 4_000, 3, 1, NO_CAP, null, null);
        assertTrue(((String) pruned.get(1).content()).startsWith(ToolResultPruner.STUB_MARKER));
        assertEquals(BIG, pruned.get(3).content(), "inside the protected tail");
    }

    @Test
    void aSkillsInstructionsStayWholeWhileAnotherEarlierResultIsStubbed() throws Exception {
        // A skill is a standing instruction: the agent needs its rules (enrollment, wording) for the whole chat.
        var messages = List.of(
                ChatMessage.user("first ask"),
                call("s1", "skill_read", "{\"name\":\"abundent-api\"}"),
                tool("s1", "skill_read", BIG),
                call("w1", "web_fetch", "{\"url\":\"https://example.com\"}"),
                tool("w1", "web_fetch", BIG),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"));

        ToolRegistrySync.withCanonicalTools(() -> {
            var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

            assertSame(BIG, pruned.get(2).content(), "the skill's text is sent whole");
            assertTrue(((String) pruned.get(4).content()).startsWith(ToolResultPruner.STUB_MARKER),
                    pruned.get(4).content().toString());
        });
    }

    @Test
    void aFilesystemReadOfASkillMdStaysWholeButAnyOtherReadIsStubbed() throws Exception {
        var messages = List.of(
                ChatMessage.user("first ask"),
                call("f1", "filesystem", "{\"action\":\"readFile\",\"path\":\"skills/abundent-api/SKILL.md\"}"),
                tool("f1", "filesystem", BIG),
                call("f2", "filesystem", "{\"action\":\"readFile\",\"path\":\"notes/SKILL.md.bak\"}"),
                tool("f2", "filesystem", BIG),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"));

        ToolRegistrySync.withCanonicalTools(() -> {
            var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

            assertSame(BIG, pruned.get(2).content(), "a skill read through filesystem is sent whole");
            assertTrue(((String) pruned.get(4).content()).startsWith(ToolResultPruner.STUB_MARKER),
                    pruned.get(4).content().toString());
        });
    }

    @Test
    void aSkillOverTheCapKeepsItsFirst20000CharactersAndPointsToTheRest() throws Exception {
        var body = "s".repeat(25_000);
        var messages = List.of(
                ChatMessage.user("first ask"),
                call("s1", "skill_read", "{\"name\":\"big\"}"),
                tool("s1", "skill_read", body),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"));

        ToolRegistrySync.withCanonicalTools(() -> {
            var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

            assertEquals("s".repeat(20_000) + "\n[skill text cut at 20,000 of 25,000 characters; call ccr_retrieve(\""
                    + ContentHash.handle(body) + "\") for the full text]", pruned.get(2).content());
        });
    }

    @Test
    void theNewestSkillsFillTheBudgetAndAnOlderOneIsStubbed() throws Exception {
        var messages = new java.util.ArrayList<ChatMessage>();
        messages.add(ChatMessage.user("first ask"));
        for (int i = 0; i < 6; i++) {
            messages.add(call("s" + i, "skill_read", "{\"name\":\"skill-" + i + "\"}"));
            messages.add(tool("s" + i, "skill_read", Character.toString('a' + i).repeat(19_000)));
        }
        messages.add(ChatMessage.assistant("answered"));
        messages.add(ChatMessage.user("second ask"));

        ToolRegistrySync.withCanonicalTools(() -> {
            var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

            assertTrue(((String) pruned.get(2).content()).startsWith(ToolResultPruner.STUB_MARKER),
                    "six 19,000-char skills pass the 100,000-char budget, so the oldest is stubbed");
            for (int i = 1; i < 6; i++) {
                assertSame(messages.get(2 + 2 * i).content(), pruned.get(2 + 2 * i).content(), "skill " + i + " stays whole");
            }
        });
    }

    @Test
    void anOlderCopyOfASkillReadAgainLaterIsStubbed() throws Exception {
        var messages = List.of(
                ChatMessage.user("first ask"),
                call("s1", "skill_read", "{\"name\":\"abundent-api\"}"),
                tool("s1", "skill_read", BIG),
                call("s2", "skill_read", "{\"name\":\"abundent-api\"}"),
                tool("s2", "skill_read", BIG),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"));

        ToolRegistrySync.withCanonicalTools(() -> {
            var pruned = ToolResultPruner.prune(messages, 4_000, 1, 1, NO_CAP, null, null);

            assertTrue(((String) pruned.get(2).content()).startsWith(ToolResultPruner.STUB_MARKER),
                    "the newer copy carries the same text");
            assertSame(BIG, pruned.get(4).content());
        });
    }

    private static ChatMessage call(String id, String name, String argsJson) {
        return ChatMessage.assistant("", List.of(new ToolCall(id, "function", new FunctionCall(name, argsJson))));
    }

    @Test
    void aHugeNegativeProtectRecentWidensInsteadOfWrappingToNothingPruned() {
        var messages = List.of(ChatMessage.user("a"), tool("c1", "t", BIG), ChatMessage.user("b"));

        var pruned = ToolResultPruner.prune(messages, 4_000, Integer.MIN_VALUE, 1, NO_CAP, null, null);

        assertTrue(((String) pruned.get(1).content()).startsWith(ToolResultPruner.STUB_MARKER),
                "size - MIN_VALUE computed in int wraps negative and clamps the cutoff to 0");
    }

    @Test
    void byDefaultTheSeventhTurnStubsTheFirstFiveTurnsAndTurnsTwoToSixSendWhatTheyWereSent() {
        Agent agent = AgentService.create("prune-step-agent", "openrouter", "gpt-4.1");
        var history = new ArrayList<ChatMessage>(List.of(ChatMessage.system("sys")));
        List<ChatMessage> previous = null;
        for (int turn = 1; turn <= 7; turn++) {
            history.add(ChatMessage.user("ask " + turn));
            var sent = ToolResultPruner.prune(List.copyOf(history), agent, null);
            if (turn == 7) {
                // Six rounds a turn keeps the sixth turn's ask inside the newest 12 messages until turn 7.
                var firstFiveTurns = new ArrayList<String>();
                for (int t = 1; t <= 5; t++) for (int r = 0; r < 6; r++) firstFiveTurns.add("t" + t + "c" + r);
                assertEquals(firstFiveTurns, stubbedCallIds(sent));
            } else if (previous != null) {
                assertEquals(previous, sent.subList(0, previous.size()), "turn " + turn + " changed what turn "
                        + (turn - 1) + " sent");
            }
            previous = sent;
            addRounds(history, "t" + turn, 6);
            history.add(ChatMessage.assistant("answer " + turn));
        }
    }

    @Test
    void withAStepOfFiveTheSixthTurnStubsTheFirstFiveTurnsAtOnceAndTurnsTwoToFiveStubNothing() {
        var sent = sentPerTurn(6, 1, 5);

        for (int turn = 1; turn <= 5; turn++) {
            assertEquals(List.of(), stubbedCallIds(sent.get(turn - 1).first()), "turn " + turn);
        }
        assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0"), stubbedCallIds(sent.get(5).first()));
    }

    @Test
    void betweenStepsEachTurnStartsWithWhatThePreviousTurnLastSentAndTheNextStepStubsTheNextFive() {
        var sent = sentPerTurn(11, 1, 5);

        for (int turn = 7; turn <= 10; turn++) {
            // The provider cached the previous turn's last call, which ends at its last result.
            var cached = sent.get(turn - 2).lastRound();
            var now = sent.get(turn - 1).first();
            assertEquals(cached, now.subList(0, cached.size()), "turn " + turn);
            assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0"), stubbedCallIds(now), "turn " + turn);
        }
        assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0", "t6c0", "t7c0", "t8c0", "t9c0", "t10c0"),
                stubbedCallIds(sent.get(10).first()));
    }

    @Test
    void aStepOfOneStubsThePreviousTurnsResultEveryTurnAsBefore() {
        var sent = sentPerTurn(4, 1, 1);

        assertEquals(List.of(), stubbedCallIds(sent.get(0).first()));
        assertEquals(List.of("t1c0"), stubbedCallIds(sent.get(1).first()));
        assertEquals(List.of("t1c0", "t2c0"), stubbedCallIds(sent.get(2).first()));
        assertEquals(List.of("t1c0", "t2c0", "t3c0"), stubbedCallIds(sent.get(3).first()));
    }

    @Test
    void aHistoryCutAtItsCapStubsWhatAStepOfOneStubsInEveryRound() {
        var conversation = historyOfTurns(9, 3);
        conversation.add(ChatMessage.user("ask 10"));
        // The newest 45 rows open on turn 4's second result, and the head slides on every later turn.
        var window = new ArrayList<ChatMessage>(List.of(conversation.getFirst()));
        window.addAll(conversation.subList(conversation.size() - 45, conversation.size()));
        var expected = new ArrayList<>(List.of("t4c1", "t4c2"));
        expected.addAll(resultsOf(5, 7, 3));
        expected.addAll(List.of("t8c0", "t8c1"));

        for (int round = 0; round <= 3; round++) {
            var perCall = ToolResultPruner.prune(window, 4_000, 12, 1, 45, null, null);
            if (round == 0) assertEquals(expected, stubbedCallIds(perCall));
            assertEquals(perCall, ToolResultPruner.prune(window, 4_000, 12, 5, 45, null, null), "round " + round);
            addRounds(window, "r" + round, 1);
        }
    }

    @Test
    void aHistoryOneRowUnderItsCapStepsAndAHistoryAtItsCapStubsPerCall() {
        var history = historyOfTurns(9, 3);
        history.add(ChatMessage.user("ask 10"));
        var perCall = new ArrayList<>(resultsOf(1, 7, 3));
        perCall.addAll(List.of("t8c0", "t8c1"));

        assertEquals(resultsOf(1, 5, 3), stubbedCallIds(ToolResultPruner.prune(history, 4_000, 12, 5, 74, null, null)),
                "73 rows under a cap of 74 are every row since the floor");
        assertEquals(perCall, stubbedCallIds(ToolResultPruner.prune(history, 4_000, 12, 5, 73, null, null)));
        assertEquals(perCall, stubbedCallIds(ToolResultPruner.prune(history, 4_000, 12, 1, 73, null, null)));
    }

    @Test
    void theConfigEntryTakesTheCapFromTheConversation() {
        Agent agent = AgentService.create("prune-cap-agent", "openrouter", "gpt-4.1");
        var conversation = new Conversation();
        conversation.channelType = "web";
        // 1,201 rows exceed every chat.maxContextMessages a test sets, so this history is a cut one.
        var history = historyOfTurns(300, 1);
        history.add(ChatMessage.user("ask 301"));

        assertEquals(resultsOf(1, 297, 1), stubbedCallIds(ToolResultPruner.prune(history, agent, conversation)));
        assertEquals(resultsOf(1, 295, 1), stubbedCallIds(ToolResultPruner.prune(history, agent, null)),
                "with no conversation there is no cap, and the step holds at the 296th ask");
    }

    @Test
    void theTurnsRoundsAndClockMoveTheBoundaryOnlyWithAStepOfOne() {
        var history = new ArrayList<ChatMessage>(List.of(ChatMessage.system("sys")));
        for (int turn = 1; turn <= 11; turn++) {
            history.add(ChatMessage.user("ask " + turn));
            addRounds(history, "t" + turn, 1);
            history.add(ChatMessage.assistant("answer " + turn));
        }
        history.add(ChatMessage.user("ask 12"));
        var turn12 = new ArrayList<>(CurrentTimeInjector.inject(history));
        var firstFive = List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0");

        for (int round = 0; round <= 8; round++) {
            // By round 3 the per-call boundary has passed the eleventh ask, the next step.
            assertEquals(firstFive, stubbedCallIds(ToolResultPruner.prune(turn12, 4_000, 12, 5, NO_CAP, null, null)),
                    "round " + round);
            if (round == 0) {
                assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0", "t6c0", "t7c0", "t8c0"),
                        stubbedCallIds(ToolResultPruner.prune(turn12, 4_000, 12, 1, NO_CAP, null, null)));
            }
            addRounds(turn12, "r" + round, 1);
        }
        assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0", "t6c0", "t7c0", "t8c0", "t9c0", "t10c0", "t11c0"),
                stubbedCallIds(ToolResultPruner.prune(turn12, 4_000, 12, 1, NO_CAP, null, null)),
                "with a step of one the rounds carry the boundary up to the turn's ask");
    }

    @Test
    void theNewestTwelveMessagesStayWholeWhenAStepIsReached() {
        var seventhTurn = historyOfTurns(6, 1);
        seventhTurn.add(ChatMessage.user("ask 7"));

        assertSame(seventhTurn, ToolResultPruner.prune(seventhTurn, 4_000, 12, 5, NO_CAP, null, null),
                "the sixth ask, the step, is among the newest 12 messages");
        assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0"),
                stubbedCallIds(ToolResultPruner.prune(seventhTurn, 4_000, 1, 5, NO_CAP, null, null)));

        var ninthTurn = historyOfTurns(8, 1);
        ninthTurn.add(ChatMessage.user("ask 9"));
        var sent = ToolResultPruner.prune(ninthTurn, 4_000, 12, 5, NO_CAP, null, null);
        assertEquals(List.of("t1c0", "t2c0", "t3c0", "t4c0", "t5c0"), stubbedCallIds(sent));
        for (int i = ninthTurn.size() - 12; i < ninthTurn.size(); i++) {
            assertSame(ninthTurn.get(i), sent.get(i), "message " + i + " is among the newest 12");
        }
    }

    /** What a turn sends on its first call and on its last round, after its one result came back. */
    private record TurnSends(List<ChatMessage> first, List<ChatMessage> lastRound) {}

    /** What each of turns 1 to {@code turns} sends, each turn one call with a large result. */
    private static List<TurnSends> sentPerTurn(int turns, int protectRecent, int everyTurns) {
        var history = new ArrayList<ChatMessage>(List.of(ChatMessage.system("sys")));
        var sent = new ArrayList<TurnSends>();
        for (int turn = 1; turn <= turns; turn++) {
            history.add(ChatMessage.user("ask " + turn));
            var first = ToolResultPruner.prune(List.copyOf(history), 4_000, protectRecent, everyTurns, NO_CAP, null, null);
            addRounds(history, "t" + turn, 1);
            sent.add(new TurnSends(first,
                    ToolResultPruner.prune(List.copyOf(history), 4_000, protectRecent, everyTurns, NO_CAP, null, null)));
            history.add(ChatMessage.assistant("answer " + turn));
        }
        return sent;
    }

    /** {@code turns} finished turns, each an ask, {@code rounds} calls with large results, and an answer. */
    private static ArrayList<ChatMessage> historyOfTurns(int turns, int rounds) {
        var history = new ArrayList<ChatMessage>(List.of(ChatMessage.system("sys")));
        for (int turn = 1; turn <= turns; turn++) {
            history.add(ChatMessage.user("ask " + turn));
            addRounds(history, "t" + turn, rounds);
            history.add(ChatMessage.assistant("answer " + turn));
        }
        return history;
    }

    /** The call ids {@link #addRounds} gives turns {@code firstTurn} to {@code lastTurn}, in order. */
    private static List<String> resultsOf(int firstTurn, int lastTurn, int rounds) {
        var ids = new ArrayList<String>();
        for (int t = firstTurn; t <= lastTurn; t++) for (int r = 0; r < rounds; r++) ids.add("t" + t + "c" + r);
        return ids;
    }

    private static List<String> stubbedCallIds(List<ChatMessage> sent) {
        return sent.stream()
                .filter(m -> m.content() instanceof String body && body.startsWith(ToolResultPruner.STUB_MARKER))
                .map(ChatMessage::toolCallId)
                .toList();
    }

    /** {@code rounds} tool rounds, each a call and its large result. */
    private static void addRounds(List<ChatMessage> history, String prefix, int rounds) {
        for (int r = 0; r < rounds; r++) {
            history.add(call(prefix + "c" + r, "web_fetch", "{}"));
            history.add(tool(prefix + "c" + r, "web_fetch", BIG + prefix + r));
        }
    }

    @Test
    void aStubResolvesToTheOriginalThroughTheRetrieveTool() {
        new jobs.ToolRegistrationJob().doJob();
        Agent agent = AgentService.create("prune-agent", "openrouter", "gpt-4.1");
        Conversation conv = ConversationService.create(agent, "web", "u-prune");
        var body = "{\"rows\":[" + "\"r\",".repeat(2_000) + "\"end\"]}";
        ConversationService.appendToolResult(conv, "call_1", body);

        var pruned = ToolResultPruner.prune(List.of(
                ChatMessage.user("earlier"), tool("call_1", "documents", body), ChatMessage.user("now")),
                4_000, 1, 1, NO_CAP, agent, conv);
        var stub = (String) pruned.get(1).content();
        var hash = stub.substring(stub.indexOf("ccr_retrieve(\"") + 14, stub.indexOf("\")"));

        var result = ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"" + hash + "\"}", agent));
        assertEquals(body, result, "the handle in the stub returns the stored original verbatim");
    }
}
