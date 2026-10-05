import memory.MemoryAutoCapture;
import memory.MemoryProvenance;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import models.MemoryAuthorType;
import models.MemoryDerivation;
import models.MemoryVerification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import utils.CircuitBreaker;

import java.util.List;

/** JCLAW-1318: what the store records about where a memory came from. */
class MemoryProvenanceTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        agent = newAgent("provenance-agent");
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private static Agent newAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private String aid() {
        return String.valueOf(agent.id);
    }

    private String storeWith(String text, MemoryProvenance provenance) {
        return MemoryStoreFactory.get().storeDeferred(aid(), text, "fact", 0.5, null, provenance);
    }

    private static MemoryProvenance derivedFrom(Long... inputs) {
        return new MemoryProvenance(null, null, MemoryProvenance.process("consolidation"),
                MemoryAuthorType.CONSOLIDATION_DERIVED, List.of(inputs));
    }

    private static Memory row(String id) {
        return Memory.findById(Long.parseLong(id));
    }

    @Test
    void theDerivedFlagAndTheAuthorTypeMustAgree() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryProvenance(null, null,
                MemoryProvenance.OPERATOR_ACTOR, MemoryAuthorType.HUMAN_TURN, List.of(1L)));
        assertThrows(IllegalArgumentException.class, () -> new MemoryProvenance(null, null,
                MemoryProvenance.process("x"), MemoryAuthorType.CONSOLIDATION_DERIVED, List.of()));
        assertEquals("extractor/m1", MemoryProvenance.extractor("m1").actor());
        assertEquals("process:nightly", MemoryProvenance.process("nightly"));
    }

    @Test
    void aCapturedWriteRecordsItsTurnAndActor() {
        var id = storeWith("The user lives in Porto", MemoryProvenance.extractor("m1").withSource(5L, 50L));

        var m = row(id);
        assertEquals(5L, m.sourceConversationId);
        assertEquals(50L, m.sourceMessageId);
        assertEquals("extractor/m1", m.actor);
        assertEquals(MemoryAuthorType.HUMAN_TURN, m.authorType);
        assertFalse(m.derived);
        assertEquals(0, m.corroborationCount);
    }

    @Test
    void aDerivedWriteLinksEachInputWithItsSourceTurn() {
        var a = storeWith("Input A", MemoryProvenance.extractor("m1").withSource(1L, 10L));
        var b = storeWith("Input B", MemoryProvenance.extractor("m1").withSource(2L, 20L));

        var d = storeWith("Derived", derivedFrom(Long.parseLong(a), Long.parseLong(b)));

        var m = row(d);
        assertTrue(m.derived);
        assertEquals(MemoryAuthorType.CONSOLIDATION_DERIVED, m.authorType);
        List<MemoryDerivation> links = MemoryDerivation.find("derivedMemory.id = ?1 ORDER BY id", m.id).fetch();
        assertEquals(2, links.size());
        assertEquals(Long.parseLong(a), links.get(0).inputMemoryId);
        assertEquals(1L, links.get(0).inputConversationId);
        assertEquals(10L, links.get(0).inputMessageId);
        assertEquals(Long.parseLong(b), links.get(1).inputMemoryId);
        assertEquals(2L, links.get(1).inputConversationId);
        assertEquals(20L, links.get(1).inputMessageId);
    }

    @Test
    void anInputFromAnotherAgentWritesNoRow() {
        var other = newAgent("provenance-other");
        var foreign = MemoryStoreFactory.get().storeDeferred(String.valueOf(other.id), "Not yours", "fact",
                0.5, null, MemoryProvenance.extractor("m1"));

        var fromForeign = derivedFrom(Long.parseLong(foreign));
        assertThrows(IllegalArgumentException.class, () -> storeWith("Derived", fromForeign));
        assertTrue(Memory.findByAgent(aid()).isEmpty(), "a refused derived write leaves no row");
    }

    @Test
    void aMissingInputWritesNoRow() {
        var fromMissing = derivedFrom(999_999L);
        assertThrows(IllegalArgumentException.class, () -> storeWith("Derived", fromMissing));
        assertTrue(Memory.findByAgent(aid()).isEmpty());
    }

    @Test
    void captureStampsTheExtractorAndTheSourceTurnOnEveryStoredRow() {
        var conv = ConversationService.create(agent, "web", "u-provenance");
        var user = ConversationService.appendUserMessage(conv, "I moved to Porto last month.");
        ConversationService.appendAssistantMessage(conv, "Noted.", null);
        assertEquals(user.id, MemoryAutoCapture.latestUserMessageId(conv.id));

        MemoryAutoCapture.Extractor extractor = msgs ->
                "{\"memories\":[{\"text\":\"The user lives in Porto.\",\"category\":\"fact\",\"importance\":0.7}]}";
        var result = MemoryAutoCapture.capture(aid(), agent.name,
                "Here is something durable worth remembering about my setup: I live in Porto.", "Noted.", extractor, null,
                new CircuitBreaker(20, 0.5, 5, 30_000L),
                MemoryProvenance.extractor("m1").withSource(conv.id, user.id));

        assertEquals(1, result.captured());
        var m = Memory.findByAgent(aid()).getFirst();
        assertEquals(conv.id, m.sourceConversationId);
        assertEquals(user.id, m.sourceMessageId);
        assertEquals("extractor/m1", m.actor);
        assertEquals(MemoryAuthorType.HUMAN_TURN, m.authorType);
        assertFalse(m.derived);
        assertEquals(0, m.corroborationCount);
    }

    @Test
    void theChatPathNamesTheConversationItsLatestUserMessageAndTheModel() {
        var conv = ConversationService.create(agent, "web", "u-provenance-chat");
        ConversationService.appendUserMessage(conv, "First question");
        ConversationService.appendAssistantMessage(conv, "First answer", null);
        var latest = ConversationService.appendUserMessage(conv, "Second question");

        var p = MemoryAutoCapture.chatTurnSource(conv.id).apply("m1");

        assertEquals(conv.id, p.sourceConversationId());
        assertEquals(latest.id, p.sourceMessageId());
        assertEquals("extractor/m1", p.actor());
        assertEquals(MemoryAuthorType.HUMAN_TURN, p.authorType());
    }

    @Test
    void theChatPathStampsAGuestTurnAsGuestButKeepsTheExtractorActor() {
        var conv = ConversationService.create(agent, "telegram", "u-provenance-guest");
        var latest = ConversationService.appendUserMessage(conv, "I'm a group member");

        var p = agents.DangerousActionGate.withOwnerInitiated(false,
                () -> MemoryAutoCapture.chatTurnSource(conv.id)).apply("m1");

        assertEquals(conv.id, p.sourceConversationId());
        assertEquals(latest.id, p.sourceMessageId());
        assertEquals("extractor/m1", p.actor());
        assertEquals(MemoryAuthorType.GUEST_TURN, p.authorType());
        assertFalse(p.mayCorroborate());
    }

    @Test
    void theChatPathStampsAnOwnersTurnOnAChannelAsHuman() {
        var conv = ConversationService.create(agent, "telegram", "u-provenance-owner");
        ConversationService.appendUserMessage(conv, "I'm the owner");

        var p = agents.DangerousActionGate.withOwnerInitiated(true,
                () -> MemoryAutoCapture.chatTurnSource(conv.id)).apply("m1");

        assertEquals(MemoryAuthorType.HUMAN_TURN, p.authorType());
        assertTrue(p.mayCorroborate());
    }

    private MemoryProvenance chatTurnOn(String channel, Boolean ownerInitiated) {
        var conv = ConversationService.create(agent, channel, "u-provenance-" + channel + "-" + ownerInitiated);
        ConversationService.appendUserMessage(conv, "Something worth remembering");
        java.util.function.Supplier<MemoryProvenance> body =
                () -> MemoryAutoCapture.chatTurnSource(conv.id).apply("m1");
        return ownerInitiated == null ? body.get() : agents.DangerousActionGate.withOwnerInitiated(ownerInitiated, body);
    }

    @Test
    void theChatPathStampsEveryChannelAndSenderAsTheMatrixSays() {
        record Row(String channel, Boolean ownerInitiated, MemoryAuthorType expected) {}
        for (var row : List.of(
                new Row("web", null, MemoryAuthorType.HUMAN_TURN),
                new Row("telegram", true, MemoryAuthorType.HUMAN_TURN),
                new Row("slack", true, MemoryAuthorType.HUMAN_TURN),
                new Row("telegram", false, MemoryAuthorType.GUEST_TURN),
                new Row("slack", false, MemoryAuthorType.GUEST_TURN),
                new Row("whatsapp", null, MemoryAuthorType.GUEST_TURN),
                new Row("telegram", null, MemoryAuthorType.GUEST_TURN))) {
            var p = chatTurnOn(row.channel(), row.ownerInitiated());
            assertEquals(row.expected(), p.authorType(), row::toString);
            assertEquals("extractor/m1", p.actor(), row::toString);
            assertEquals(row.expected() == MemoryAuthorType.HUMAN_TURN, p.mayCorroborate(), row::toString);
        }
    }

    @Test
    void aRepeatedInputIsLinkedOnce() {
        var a = storeWith("Input A", MemoryProvenance.extractor("m1"));
        var d = storeWith("Derived", derivedFrom(Long.parseLong(a), Long.parseLong(a)));

        assertEquals(1, MemoryDerivation.count("derivedMemory.id = ?1", Long.parseLong(d)));
    }

    private static long dependents(List<Long> ids) {
        return MemoryVerification.count("memory.id IN (?1)", ids)
                + MemoryDerivation.count("derivedMemory.id IN (?1)", ids);
    }

    @Test
    void deletingMemoriesTakesTheirVerificationsAndDerivationLinksWithThem() {
        var a = Long.parseLong(storeWith("Input A", MemoryProvenance.extractor("m1")));
        var d1 = Long.parseLong(storeWith("Derived once", derivedFrom(a)));
        var d2 = Long.parseLong(storeWith("Derived twice", derivedFrom(a)));
        MemoryVerification.record(Memory.findById(a), MemoryProvenance.OPERATOR_ACTOR, MemoryVerification.Kind.CONFIRMED);
        MemoryVerification.record(Memory.findById(d2), MemoryProvenance.OPERATOR_ACTOR, MemoryVerification.Kind.EDITED);

        ((Memory) Memory.findById(d1)).deleteWithLineage();
        assertEquals(0, MemoryDerivation.count("derivedMemory.id = ?1", d1));

        MemoryStoreFactory.get().deleteAll(aid());
        assertEquals(0, dependents(List.of(a, d1, d2)), "no verification or derivation row may outlive its memory");
    }

    @Test
    void aConversationWithNoUserMessageHasNoSourceMessage() {
        var conv = ConversationService.create(agent, "web", "u-provenance-empty");
        assertNull(MemoryAutoCapture.latestUserMessageId(conv.id));
    }
}
