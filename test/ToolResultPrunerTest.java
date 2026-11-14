import agents.CompressionPipeline;
import agents.ContextWindowManager;
import agents.CurrentTimeInjector;
import agents.ToolContext;
import agents.ToolResultPruner;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import services.compression.ContentHash;
import tools.CcrRetrieveTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** JCLAW-1202: old large tool results become stubs that resolve through ccr_retrieve. */
class ToolResultPrunerTest extends UnitTest {

    private static final String BIG = "x".repeat(5_000);
    /** A history the load did not cut at its row cap. */
    private static final int NO_CAP = Integer.MAX_VALUE;
    /** The smallest threshold a user message preview has: 2,000 tokens at 4 characters a token. */
    private static final int FLOOR_CHARS = 8_000;
    private static final String PREVIEW_PROVIDER = "prune-preview-provider";
    /** No class registers this one, so an agent on it has no known window. */
    private static final String UNREGISTERED_PROVIDER = "prune-unregistered-provider";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @AfterEach
    void teardown() {
        ConfigService.delete(ToolResultPruner.EVERY_TURNS_KEY);
        ConfigService.delete(ToolResultPruner.USER_PREVIEW_PERCENT_KEY);
        for (var key : List.of("baseUrl", "apiKey", "models")) ConfigService.delete("provider." + PREVIEW_PROVIDER + "." + key);
        ProviderRegistry.refresh();
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
    void aStubOfACompressedResultCarriesTheOriginalsHandle() {
        var original = bigJsonArray();
        var compressed = (String) CompressionPipeline.compressMessages(
                List.of(tool("c1", "web_search", original)), "gpt-4o", 250).get(0).content();
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("first ask"),
                tool("c1", "web_search", compressed),
                ChatMessage.user("second ask"));

        var pruned = ToolResultPruner.prune(messages, 100, 0, 1, NO_CAP, null, null);

        assertEquals("[tool result elided: web_search returned " + compressed.length()
                        + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(original)
                        + "\") if you need the full text again]",
                pruned.get(2).content());
    }

    @Test
    void aStubOfATruncatedCompressedResultCarriesTheOriginalsHandle() {
        var original = bigJsonArray();
        var compressed = (String) CompressionPipeline.compressMessages(
                List.of(tool("c1", "web_search", original)), "gpt-4o", 250).get(0).content();
        var truncated = ContextWindowManager.truncateToolResultContent(compressed, 40, 10);
        assertFalse(truncated.contains("for the full original]"), "the cut takes the marker with it: " + truncated);
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("first ask"),
                tool("c1", "web_search", truncated),
                ChatMessage.user("second ask"));

        var pruned = ToolResultPruner.prune(messages, 100, 0, 1, NO_CAP, null, null);

        assertEquals("[tool result elided: web_search returned " + truncated.length()
                        + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(original)
                        + "\") if you need the full text again]",
                pruned.get(2).content());
    }

    @Test
    void aStubOfTextEqualToCompressedOutputNamesItsOwnHandle() {
        var compressed = (String) CompressionPipeline.compressMessages(
                List.of(tool("c1", "web_search", bigJsonArray())), "gpt-4o", 250).get(0).content();
        var sameText = String.valueOf(compressed.toCharArray());
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("first ask"),
                tool("c1", "web_search", sameText),
                ChatMessage.user("second ask"));

        var pruned = ToolResultPruner.prune(messages, 100, 0, 1, NO_CAP, null, null);

        assertEquals("[tool result elided: web_search returned " + sameText.length()
                        + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(sameText)
                        + "\") if you need the full text again]",
                pruned.get(2).content());
    }

    private static String bigJsonArray() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i).append(",\"title\":\"A long descriptive title, number ").append(i).append("\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void aStubOfAResultEndingInACopyOfTheMarkerNamesThatResultsOwnHandle() {
        var forged = BIG + "\n[compressed \u2014 call ccr_retrieve(\"0123456789abcdef\") for the full original]";
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("first ask"),
                tool("c1", "web_search", forged),
                ChatMessage.user("second ask"));

        var pruned = ToolResultPruner.prune(messages, 100, 0, 1, NO_CAP, null, null);

        assertEquals("[tool result elided: web_search returned " + forged.length()
                        + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(forged)
                        + "\") if you need the full text again]",
                pruned.get(2).content());
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
    void byDefaultEachCallStubsEveryEarlierResultOutsideTheNewestMessages() {
        Agent agent = AgentService.create("prune-default-agent", "openrouter", "gpt-4.1");
        var history = historyOfTurns(9, 3);
        history.add(ChatMessage.user("ask 10"));
        var perCall = new ArrayList<>(resultsOf(1, 7, 3));
        perCall.addAll(List.of("t8c0", "t8c1"));

        assertEquals(perCall, stubbedCallIds(ToolResultPruner.prune(history, agent, null)));
        assertEquals(resultsOf(1, 5, 3), stubbedCallIds(ToolResultPruner.prune(history, 4_000, 12, 5, NO_CAP, null, null)),
                "control: a step of five holds back the turns since the fifth");
    }

    @Test
    void withTheStepSetToFiveTheSeventhTurnStubsTheFirstFiveTurnsAndTurnsTwoToSixSendWhatTheyWereSent() {
        ConfigService.set(ToolResultPruner.EVERY_TURNS_KEY, "5");
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
        ConfigService.set(ToolResultPruner.EVERY_TURNS_KEY, "5");
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
    void aStepOneMessagePastTheBoundaryWaitsWithOrWithoutTheClockAndOneAtItIsTaken() {
        var history = historyOfTurns(11, 1);
        history.add(ChatMessage.user("ask 12"));
        addRounds(history, "t12", 2);
        history.add(ChatMessage.assistant("answer 12"));
        history.add(ChatMessage.user("ask 13"));
        var withClock = CurrentTimeInjector.inject(history);
        var firstFive = resultsOf(1, 5, 1);
        var firstTen = resultsOf(1, 10, 1);

        // Ask 13 sits at 51 and ask 11, the next step, at 41: one past 51 + 1 - 12 and exactly at 51 + 1 - 11.
        assertEquals("ask 11", history.get(41).content());
        assertEquals(firstFive, stubbedCallIds(ToolResultPruner.prune(history, 4_000, 12, 5, NO_CAP, null, null)));
        assertEquals(firstFive, stubbedCallIds(ToolResultPruner.prune(withClock, 4_000, 12, 5, NO_CAP, null, null)),
                "the clock the round prunes carry does not move the boundary the prologue's prune drew");
        assertEquals(firstTen, stubbedCallIds(ToolResultPruner.prune(history, 4_000, 11, 5, NO_CAP, null, null)));
        assertEquals(firstTen, stubbedCallIds(ToolResultPruner.prune(withClock, 4_000, 11, 5, NO_CAP, null, null)));
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

    // ─── JF-417: a large user message from an earlier turn reaches the model as its head and tail ───

    @Test
    void aUserMessageAtTheThresholdStaysWholeAndOneCharacterOverBecomesItsHeadAndTail() {
        var atThreshold = pasted(FLOOR_CHARS);
        var over = pasted(FLOOR_CHARS + 1);
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(atThreshold),
                ChatMessage.assistant("read it"),
                ChatMessage.user(over),
                ChatMessage.assistant("read that too"),
                ChatMessage.user("now compare them"));

        var sent = ToolResultPruner.previewEarlierUserMessages(messages, FLOOR_CHARS, null, null);

        assertSame(atThreshold, sent.get(1).content());
        assertEquals(over.substring(0, 2_000)
                + "\n[message shortened: 8,001 characters, the middle left out; call ccr_retrieve(\""
                + ContentHash.handle(over) + "\") for the full text]\n" + over.substring(7_001), sent.get(3).content());
        assertEquals(messages.subList(4, 6), sent.subList(4, 6));
        assertEquals(over, messages.get(3).content(), "the input list is not mutated");
    }

    @Test
    void thePastedMessagesOwnTurnSendsItWholeInEveryRound() {
        var paste = pasted(40_000);
        var turn = List.of(ChatMessage.system("sys"), ChatMessage.user("hi"), ChatMessage.assistant("hello"),
                ChatMessage.user(paste));
        assertSame(turn, ToolResultPruner.previewEarlierUserMessages(turn, FLOOR_CHARS, null, null));

        var rounds = new ArrayList<>(CurrentTimeInjector.inject(turn));
        for (int round = 0; round < 3; round++) {
            assertSame(rounds, ToolResultPruner.previewEarlierUserMessages(rounds, FLOOR_CHARS, null, null),
                    "round " + round);
            addRounds(rounds, "r" + round, 1);
        }
    }

    @Test
    void aChannelBatchOfConsecutiveUserMessagesIsAllTheTurnsOwn() {
        var batch = CurrentTimeInjector.inject(List.of(ChatMessage.system("sys"), ChatMessage.user("earlier"),
                ChatMessage.assistant("answered"), ChatMessage.user(pasted(40_000)),
                ChatMessage.user("and here is the second part")));

        assertSame(batch, ToolResultPruner.previewEarlierUserMessages(batch, FLOOR_CHARS, null, null));
    }

    @Test
    void fromTheNextTurnOnThePastedMessageIsSentAsItsPreviewInEveryRound() {
        var paste = pasted(40_000);
        var turn = new ArrayList<>(CurrentTimeInjector.inject(List.of(ChatMessage.system("sys"),
                ChatMessage.user(paste), ChatMessage.assistant("read it"), ChatMessage.user("what does part 3 say?"))));
        addRounds(turn, "r", 1);

        var sent = ToolResultPruner.previewEarlierUserMessages(turn, FLOOR_CHARS, null, null);

        assertEquals(previewOf(paste), sent.get(1).content());
        for (int i = 0; i < turn.size(); i++) {
            if (i != 1) assertSame(turn.get(i), sent.get(i), "message " + i);
        }
        assertSame(sent, ToolResultPruner.previewEarlierUserMessages(sent, FLOOR_CHARS, null, null),
                "a later round meets the preview and sends it as it is");
    }

    @Test
    void aPreviewNeverEndsOrStartsInsideASurrogatePair() {
        var emoji = "\uD83D\uDE00";
        // The head's last character and the tail's first are each half of an emoji.
        var paste = "a".repeat(1_999) + emoji + "b".repeat(36_998) + emoji + "c".repeat(999);
        assertEquals(40_000, paste.length());

        var sent = ToolResultPruner.previewEarlierUserMessages(
                List.of(ChatMessage.user(paste), ChatMessage.assistant("ok"), ChatMessage.user("next")),
                FLOOR_CHARS, null, null);

        assertEquals("a".repeat(1_999) + shortenedLine(40_000, ContentHash.handle(paste)) + "c".repeat(999),
                sent.get(0).content());
    }

    @Test
    void aTextPartNoStoredRowAccountsForIsLeftWhole() {
        var parts = List.of(Map.<String, Object>of("type", "text", "text", pasted(40_000)));
        var messages = List.of(ChatMessage.system("sys"), new ChatMessage("user", parts, null, null, null),
                ChatMessage.assistant("ok"), ChatMessage.user("next"));

        assertSame(messages, ToolResultPruner.previewEarlierUserMessages(messages, FLOOR_CHARS, null, null),
                "its handle would hash attachment notes no row holds, so the agent could not fetch it back");
    }

    @Test
    void theThresholdIsAShareOfTheWindowAtFourCharactersATokenAndNeverUnderTwoThousandTokens() {
        assertEquals(80_000, ToolResultPruner.previewThresholdChars(10, 200_000));
        assertEquals(400_000, ToolResultPruner.previewThresholdChars(10, 1_000_000));
        assertEquals(200_000, ToolResultPruner.previewThresholdChars(25, 200_000));
        assertEquals(8_000, ToolResultPruner.previewThresholdChars(10, 20_000), "10% of 20,000 is the floor itself");
        assertEquals(8_004, ToolResultPruner.previewThresholdChars(10, 20_010));
        assertEquals(8_000, ToolResultPruner.previewThresholdChars(10, 8_192));
        assertEquals(800_000, ToolResultPruner.previewThresholdChars(100, 200_000));
        assertEquals(800_000, ToolResultPruner.previewThresholdChars(101, 200_000), "a share is read at most 100");
        assertEquals(Integer.MAX_VALUE, ToolResultPruner.previewThresholdChars(100, Integer.MAX_VALUE));
    }

    @Test
    void anUnknownWindowTakesEightThousandTokensAndAShareOfZeroOrLessIsOff() {
        assertEquals(32_000, ToolResultPruner.previewThresholdChars(10, 0));
        assertEquals(32_000, ToolResultPruner.previewThresholdChars(10, -1));
        assertEquals(32_000, ToolResultPruner.previewThresholdChars(1, 0));
        assertEquals(8_000, ToolResultPruner.previewThresholdChars(1, 200_000));
        assertEquals(0, ToolResultPruner.previewThresholdChars(0, 200_000));
        assertEquals(0, ToolResultPruner.previewThresholdChars(-1, 0));
    }

    @Test
    void theConfigEntryScalesTheThresholdWithTheModelsWindowAndTheShare() {
        registerPreviewProvider();
        Agent narrow = AgentService.create("prune-narrow-agent", PREVIEW_PROVIDER, "narrow-model");
        Agent wide = AgentService.create("prune-wide-agent", PREVIEW_PROVIDER, "wide-model");
        var narrowConv = ConversationService.create(narrow, "web", "u-narrow");
        var wideConv = ConversationService.create(wide, "web", "u-wide");
        var atTenPercent = pasted(80_000);
        var over = pasted(80_001);
        var messages = earlierAndNext(atTenPercent, over);

        var onNarrow = ToolResultPruner.prune(messages, narrow, narrowConv);
        assertSame(atTenPercent, onNarrow.get(1).content(), "10% of 200,000 tokens is 80,000 characters");
        assertEquals(previewOf(over), onNarrow.get(3).content());
        assertSame(messages, ToolResultPruner.prune(messages, wide, wideConv), "10% of 400,000 tokens is 160,000");

        ConfigService.set(ToolResultPruner.USER_PREVIEW_PERCENT_KEY, "5");
        var atFivePercent = ToolResultPruner.prune(messages, wide, wideConv);
        assertSame(atTenPercent, atFivePercent.get(1).content(), "5% of 400,000 tokens is 80,000 characters");
        assertEquals(previewOf(over), atFivePercent.get(3).content());
    }

    @Test
    void withNoKnownWindowTheConfigEntryReadsEightThousandTokensWhole() {
        registerPreviewProvider();
        Agent unlisted = AgentService.create("prune-unlisted-agent", PREVIEW_PROVIDER, "unlisted-model");
        Agent unregistered = AgentService.create("prune-unregistered-agent", UNREGISTERED_PROVIDER, "any-model");
        var atDefault = pasted(32_000);
        var over = pasted(32_001);
        var messages = earlierAndNext(atDefault, over);

        for (var agent : List.of(unlisted, unregistered)) {
            var sent = ToolResultPruner.prune(messages, agent, ConversationService.create(agent, "web", "u-" + agent.name));
            assertSame(atDefault, sent.get(1).content(), agent.name);
            assertEquals(previewOf(over), sent.get(3).content(), agent.name);
        }
        var noConversation = ToolResultPruner.prune(messages, unlisted, null);
        assertSame(atDefault, noConversation.get(1).content());
        assertEquals(previewOf(over), noConversation.get(3).content());
    }

    @Test
    void aShareOfZeroOrLessLeavesEveryUserMessageWhole() {
        Agent agent = AgentService.create("prune-off-agent", UNREGISTERED_PROVIDER, "any-model");
        var paste = pasted(40_000);
        var messages = List.of(ChatMessage.system("sys"), ChatMessage.user(paste), ChatMessage.assistant("ok"),
                ChatMessage.user("next"));

        for (var share : List.of("0", "-1")) {
            ConfigService.set(ToolResultPruner.USER_PREVIEW_PERCENT_KEY, share);
            assertSame(messages, ToolResultPruner.prune(messages, agent, null), "share " + share);
        }
        ConfigService.set(ToolResultPruner.USER_PREVIEW_PERCENT_KEY, "1");
        assertEquals(previewOf(paste), ToolResultPruner.prune(messages, agent, null).get(1).content(),
                "a share of 1 with no known window takes the 8,000-token default");
    }

    @Test
    void withToolResultPruningOffAPastedMessageIsStillShortenedAndToolResultsAreNot() {
        Agent agent = AgentService.create("prune-tools-off-agent", UNREGISTERED_PROVIDER, "any-model");
        var paste = pasted(40_000);
        var messages = new ArrayList<ChatMessage>(List.of(ChatMessage.system("sys"), ChatMessage.user(paste)));
        addRounds(messages, "t1", 1);
        messages.add(ChatMessage.assistant("answered"));
        // Twelve short messages put the tool result outside the newest twelve, which pruning protects.
        for (int turn = 2; turn <= 7; turn++) {
            messages.add(ChatMessage.user("ask " + turn));
            messages.add(ChatMessage.assistant("answer " + turn));
        }
        messages.add(ChatMessage.user("next"));
        assertEquals(List.of("t1c0"), stubbedCallIds(ToolResultPruner.prune(messages, agent, null)), "control");

        ConfigService.set("chat.pruneToolResults", "false");
        try {
            var sent = ToolResultPruner.prune(messages, agent, null);

            assertEquals(previewOf(paste), sent.get(1).content());
            assertSame(messages.get(3).content(), sent.get(3).content(), "the earlier tool result is sent whole");
        } finally {
            ConfigService.delete("chat.pruneToolResults");
        }
    }

    @Test
    void aPastedMessageIsWholeOnItsTurnAndAPreviewTheRetrieveToolResolvesOnTheNextWhileTheRowIsUnchanged() {
        Agent agent = AgentService.create("prune-paste-agent", UNREGISTERED_PROVIDER, "any-model");
        Conversation conv = ConversationService.create(agent, "web", "u-paste");
        var paste = pasted(40_000);
        var row = ConversationService.appendUserMessage(conv, paste);

        var ownTurn = CurrentTimeInjector.inject(hydrated(conv));
        assertSame(ownTurn, ToolResultPruner.prune(ownTurn, agent, conv));

        ConversationService.appendAssistantMessage(conv, "read it", null);
        ConversationService.appendUserMessage(conv, "what does part 3 say?");
        var nextTurn = ToolResultPruner.prune(CurrentTimeInjector.inject(hydrated(conv)), agent, conv);

        var preview = (String) nextTurn.get(1).content();
        assertEquals(previewOf(paste), preview);
        assertEquals(paste, retrieve(conv, agent, handleIn(preview)));
        assertEquals(paste, Message.<Message>findById(row.id).content, "the stored row is unchanged");
    }

    @Test
    void aMessageWithAnAttachmentKeepsItsNoteAndOnlyItsStoredTextIsShortened() {
        Agent agent = AgentService.create("prune-attach-agent", UNREGISTERED_PROVIDER, "any-model");
        Conversation conv = ConversationService.create(agent, "web", "u-attach");
        var paste = pasted(40_000);
        var row = ConversationService.appendUserMessage(conv, paste);
        var att = new MessageAttachment();
        att.message = row;
        att.uuid = UUID.randomUUID().toString();
        att.originalFilename = "notes.txt";
        att.storagePath = "attachments/notes.txt";
        att.mimeType = "text/plain";
        att.sizeBytes = 5;
        att.kind = MessageAttachment.KIND_FILE;
        att.save();
        ConversationService.appendAssistantMessage(conv, "read it", null);
        ConversationService.appendUserMessage(conv, "and the attachment?");
        JPA.em().flush();
        JPA.em().clear();
        Conversation fresh = Conversation.findById(conv.id);
        var note = "\n[Attached file: notes.txt — workspace:attachments/notes.txt]";
        var history = hydrated(fresh);
        assertEquals(List.of(Map.of("type", "text", "text", paste + note)), history.get(1).content());

        var sent = ToolResultPruner.prune(history, agent, fresh);

        assertEquals(List.of(Map.of("type", "text", "text", previewOf(paste) + note)), sent.get(1).content());
        assertEquals(paste, retrieve(fresh, agent, handleIn(previewOf(paste))));
    }

    /** A pasted document of {@code length} characters whose every line is different. */
    private static String pasted(int length) {
        var sb = new StringBuilder(length + 40);
        for (int line = 1; sb.length() < length; line++) sb.append("Line ").append(line).append(" of the pasted report.\n");
        return sb.substring(0, length);
    }

    /** {@code stored}'s first 2,000 and last 1,000 characters around the line naming its handle. */
    private static String previewOf(String stored) {
        return stored.substring(0, 2_000) + shortenedLine(stored.length(), ContentHash.handle(stored))
                + stored.substring(stored.length() - 1_000);
    }

    private static String shortenedLine(int length, String handle) {
        return String.format(Locale.ROOT, "\n[message shortened: %,d characters, the middle left out; "
                + "call ccr_retrieve(\"%s\") for the full text]\n", length, handle);
    }

    private static String handleIn(String preview) {
        int start = preview.indexOf("ccr_retrieve(\"") + 14;
        return preview.substring(start, preview.indexOf("\")", start));
    }

    /** Two earlier asks, each answered, then the current turn's. */
    private static List<ChatMessage> earlierAndNext(String first, String second) {
        return List.of(ChatMessage.system("sys"), ChatMessage.user(first), ChatMessage.assistant("read it"),
                ChatMessage.user(second), ChatMessage.assistant("read that too"), ChatMessage.user("now compare them"));
    }

    private static List<ChatMessage> hydrated(Conversation conv) {
        return Tx.run(() -> agents.MessageHydrator.buildMessages("sys", conv).messages());
    }

    private static String retrieve(Conversation conv, Agent agent, String handle) {
        return ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"" + handle + "\"}", agent));
    }

    /** A provider no other class names, never reachable, with a 200,000- and a 400,000-token model. */
    private static void registerPreviewProvider() {
        ConfigService.set("provider." + PREVIEW_PROVIDER + ".baseUrl", "http://127.0.0.1:1/v1");
        ConfigService.set("provider." + PREVIEW_PROVIDER + ".apiKey", "sk-test");
        ConfigService.set("provider." + PREVIEW_PROVIDER + ".models",
                "[{\"id\":\"narrow-model\",\"name\":\"Narrow\",\"contextWindow\":200000,\"maxTokens\":4096},"
                        + "{\"id\":\"wide-model\",\"name\":\"Wide\",\"contextWindow\":400000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
    }
}
