import memory.AnchorResolver;
import memory.AnchorResolver.MemoryPred;
import memory.AnchorResolver.MessagePred;
import models.Agent;
import models.Conversation;
import models.Memory;
import models.MemoryDerivation;
import models.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TimezoneResolver;
import services.Tx;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** JCLAW-1361: the anchor resolver's rows, read from the memory, message and derivation tables. */
class AnchorResolverJpaLookupTest extends UnitTest {

    private static final Instant SENT = Instant.parse("2026-02-15T12:00:00Z");
    private static final Instant WRITTEN = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant SUPERSEDED = Instant.parse("2026-01-10T12:00:00Z");

    private final AnchorResolver.JpaLookup lookup = new AnchorResolver.JpaLookup();

    /** Memory saves reach the JVM-global index; the lock keeps them off it. */
    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private record Seeded(long memory, long message, long superseded, long input) {}

    private static LocalDate day(Instant at) {
        return at.atZone(TimezoneResolver.appZone()).toLocalDate();
    }

    private static <T> T at(Instant instant, Supplier<T> block) {
        return AppClock.callWith(Clock.fixed(instant, ZoneOffset.UTC), block::get);
    }

    private static Seeded seed() {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "anchor-" + UUID.randomUUID();
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var conversation = new Conversation();
            conversation.agent = agent;
            conversation.channelType = "web";
            conversation.save();
            Message message = at(SENT, () -> {
                var m = new Message();
                m.conversation = conversation;
                m.role = "user";
                m.content = "We moved to Porto last year.";
                m.save();
                return m;
            });
            Memory memory = at(WRITTEN, () -> mem(agent, "The user moved to Porto last year.", message.id, true));
            Memory superseded = at(SUPERSEDED, () -> mem(agent, "The user lives in Berlin.", null, false));
            superseded.supersededById = memory.id;
            superseded.save();
            Memory input = at(SUPERSEDED, () -> mem(agent, "The user planned a move last year.", null, false));
            derivation(memory, input.id, null);
            derivation(memory, null, message.id);
            derivation(memory, 999_999_999L, 999_999_998L);
            return new Seeded(memory.id, message.id, superseded.id, input.id);
        });
    }

    private static Memory mem(Agent agent, String text, Long sourceMessageId, boolean derived) {
        var m = new Memory();
        m.agent = agent;
        m.text = text;
        m.category = "fact";
        m.importance = 0.5;
        m.sourceMessageId = sourceMessageId;
        m.derived = derived;
        m.save();
        return m;
    }

    private static void derivation(Memory derived, Long inputMemoryId, Long inputMessageId) {
        var d = new MemoryDerivation();
        d.derivedMemory = derived;
        d.inputMemoryId = inputMemoryId;
        d.inputMessageId = inputMessageId;
        d.save();
    }

    @Test
    void aMemoryReadsItsSourceTextAndAnchor() {
        var seeded = seed();
        var node = lookup.node(seeded.memory()).orElseThrow();
        assertEquals("The user moved to Porto last year.", node.text());
        assertEquals("We moved to Porto last year.", node.sourceText());
        assertEquals(day(WRITTEN), node.anchor());
        assertTrue(node.derived());
        assertTrue(lookup.node(-1).isEmpty());
    }

    @Test
    void aSupersededRowIsAMemoryPredecessor() {
        var seeded = seed();
        var predecessors = lookup.node(seeded.memory()).orElseThrow().predecessors();
        assertTrue(predecessors.contains(new MemoryPred(seeded.superseded(), SUPERSEDED)), predecessors.toString());
    }

    @Test
    void aDerivationFromAMemoryIsThatMemory() {
        var seeded = seed();
        var predecessors = lookup.node(seeded.memory()).orElseThrow().predecessors();
        assertTrue(predecessors.contains(new MemoryPred(seeded.input(), SUPERSEDED)), predecessors.toString());
    }

    @Test
    void aDerivationFromAMessageIsAMessagePredecessorAndAnUnresolvableOneIsSkipped() {
        var seeded = seed();
        var predecessors = lookup.node(seeded.memory()).orElseThrow().predecessors();
        assertTrue(predecessors.contains(new MessagePred(seeded.message(), SENT, "We moved to Porto last year.",
                day(SENT))), predecessors.toString());
        assertEquals(3, predecessors.size(), predecessors.toString());
    }

    @Test
    void theResolverRunsOverTheRows() {
        var seeded = seed();
        var resolver = new AnchorResolver(lookup);
        assertEquals(List.of(day(WRITTEN)), resolver.base(seeded.memory(), "last year").stream().toList());
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
