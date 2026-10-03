import memory.AnchorResolver;
import memory.AnchorResolver.MemoryPred;
import memory.AnchorResolver.MessagePred;
import memory.AnchorResolver.Node;
import memory.AnchorResolver.Predecessor;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JCLAW-1361: the four-step anchor rule, over a stub lookup. */
class AnchorResolverTest extends UnitTest {

    private static final LocalDate M_DAY = LocalDate.of(2026, 3, 1);
    private static final LocalDate P_DAY = LocalDate.of(2026, 2, 1);
    private static final LocalDate Q_DAY = LocalDate.of(2026, 1, 1);
    private static final LocalDate MSG_DAY = LocalDate.of(2025, 12, 24);

    private final Map<Long, Node> nodes = new HashMap<>();
    private final AnchorResolver resolver = new AnchorResolver(id -> Optional.ofNullable(nodes.get(id)));

    private static Instant at(String day) {
        return Instant.parse(day + "T12:00:00Z");
    }

    private void node(long id, String text, LocalDate anchor, String source, boolean derived,
            Predecessor... predecessors) {
        nodes.put(id, new Node(text, anchor, source, derived, List.of(predecessors)));
    }

    @Test
    void stepOneASpanInTheSourceMessageIsTheMemorysOwnAnchor() {
        node(1, "Moved to Porto last year.", M_DAY, "We moved to Porto LAST YEAR.", true,
                new MemoryPred(2, at("2026-01-01")));
        node(2, "Moved last year.", P_DAY, null, false);
        assertEquals(Optional.of(M_DAY), resolver.base(1, "last year"));
    }

    @Test
    void theMatchIsWordBounded() {
        node(1, "Back next week.", M_DAY, "Off until next weekend.", false);
        node(2, "Back next week.", M_DAY, "Off until next weekend.", true);
        assertEquals(Optional.of(M_DAY), resolver.base(1, "next week"), "step 3, not step 1");
        assertEquals(Optional.empty(), resolver.base(2, "next week"));
    }

    @Test
    void stepTwoASupersededPredecessorHandsOverItsBase() {
        node(1, "Moved to Porto last year.", M_DAY, "Porto now.", false, new MemoryPred(2, at("2026-02-01")));
        node(2, "Moved to Lisbon last year.", P_DAY, "We moved last year.", false);
        assertEquals(Optional.of(P_DAY), resolver.base(1, "last year"));
    }

    @Test
    void stepTwoADerivationInputMemoryRecursesToItsOwnPredecessor() {
        node(1, "Started running last year.", M_DAY, null, true, new MemoryPred(2, at("2026-02-01")));
        node(2, "Started running last year.", P_DAY, null, true, new MemoryPred(3, at("2026-01-01")));
        node(3, "Started running last year.", Q_DAY, "I started running last year.", false);
        assertEquals(Optional.of(Q_DAY), resolver.base(1, "last year"));
    }

    @Test
    void stepTwoADerivationInputMessageIsItsOwnBase() {
        node(1, "Started running last year.", M_DAY, null, true,
                new MessagePred(9, at("2025-12-24"), "I started running last year!", MSG_DAY));
        assertEquals(Optional.of(MSG_DAY), resolver.base(1, "last year"));
    }

    @Test
    void stepTwoTakesTheEarliestStatingPredecessorMemoriesFirstOnATie() {
        node(1, "Ran a marathon last year.", M_DAY, null, true,
                new MessagePred(9, at("2026-01-01"), "a marathon last year", MSG_DAY),
                new MemoryPred(3, at("2026-01-01")),
                new MemoryPred(2, at("2026-02-01")),
                new MemoryPred(4, at("2025-06-01")));
        node(2, "Ran a marathon last year.", P_DAY, "ran last year", false);
        node(3, "Ran a marathon last year.", Q_DAY, "ran last year", false);
        node(4, "Ran a marathon.", LocalDate.of(2025, 6, 1), "ran", false);
        assertEquals(Optional.of(Q_DAY), resolver.base(1, "last year"), "node 4 never states the span");
    }

    @Test
    void stepTwoAnUnresolvedPredecessorLeavesTheSpanUnresolved() {
        node(1, "Moved last year.", M_DAY, null, false, new MemoryPred(2, at("2026-02-01")));
        node(2, "Moved last year.", P_DAY, null, true);
        assertEquals(Optional.empty(), resolver.base(1, "last year"));
    }

    @Test
    void stepThreeAnUnderivedMemoryFallsBackToItsOwnAnchor() {
        node(1, "Moved last year.", M_DAY, null, false);
        assertEquals(Optional.of(M_DAY), resolver.base(1, "last year"));
    }

    @Test
    void stepFourADerivedMemoryWithNoStatedSpanIsUnresolved() {
        node(1, "Moved last year.", M_DAY, "Moved.", true,
                new MessagePred(9, at("2025-12-24"), "We moved.", MSG_DAY), new MemoryPred(2, at("2026-02-01")));
        node(2, "Moved to Porto.", P_DAY, null, false);
        assertEquals(Optional.empty(), resolver.base(1, "last year"));
    }

    @Test
    void aMissingMemoryIsUnresolved() {
        assertEquals(Optional.empty(), resolver.base(42, "last year"));
        node(1, "Moved last year.", M_DAY, null, true, new MemoryPred(42, at("2026-02-01")));
        assertEquals(Optional.empty(), resolver.base(1, "last year"));
    }

    @Test
    void aCycleEndsUnresolved() {
        node(1, "Moved last year.", M_DAY, null, false, new MemoryPred(2, at("2026-02-01")));
        node(2, "Moved last year.", P_DAY, null, false, new MemoryPred(1, at("2026-01-01")));
        assertEquals(Optional.empty(), resolver.base(1, "last year"));
    }
}
