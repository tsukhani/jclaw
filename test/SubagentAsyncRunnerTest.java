import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JCLAW-498 leak fix: {@code SubagentAsyncRunner.forgetOutstanding} must prune a
 * scope entry once its outstanding-run set empties, rather than leaving a
 * permanent empty {@code Set} per distinct {@code conv:}/{@code task:} scope.
 *
 * <p>{@code SubagentAsyncRunner} is package-private in {@code tools} and these
 * tests live in the default package, so the private static registry + method are
 * reached via reflection. Ids are negative + scope keys UUID-tagged so nothing
 * collides with a concurrently-running spawn under the parallel TestEngine.
 */
class SubagentAsyncRunnerTest extends UnitTest {

    @Test
    @SuppressWarnings("unchecked")
    void forgetOutstandingPrunesEmptyScopesButKeepsNonEmptyOnes() throws Exception {
        var cls = Class.forName("tools.SubagentAsyncRunner");
        Field mapField = cls.getDeclaredField("OUTSTANDING_BY_SCOPE");
        mapField.setAccessible(true);
        var map = (ConcurrentHashMap<String, Set<Long>>) mapField.get(null);
        Method forget = cls.getDeclaredMethod("forgetOutstanding", Long.class);
        forget.setAccessible(true);

        // Real runIds are positive DB ids; negatives can't clash with a live spawn.
        long target = -System.nanoTime();
        long other = target - 1;
        var emptyingScope = "conv:test-" + UUID.randomUUID();
        var survivingScope = "task:test-" + UUID.randomUUID();

        var soloSet = ConcurrentHashMap.<Long>newKeySet();
        soloSet.add(target);
        var sharedSet = ConcurrentHashMap.<Long>newKeySet();
        sharedSet.add(target);
        sharedSet.add(other);
        map.put(emptyingScope, soloSet);
        map.put(survivingScope, sharedSet);

        try {
            forget.invoke(null, target);

            assertFalse(map.containsKey(emptyingScope),
                    "a scope whose set emptied after the collect must be dropped, not left as a permanent empty entry");
            assertTrue(map.containsKey(survivingScope),
                    "a scope that still has outstanding runs is kept");
            assertTrue(map.get(survivingScope).contains(other),
                    "the still-outstanding run is untouched");
            assertFalse(map.get(survivingScope).contains(target),
                    "the collected run is removed from every scope it appeared in");
        } finally {
            map.remove(emptyingScope);
            map.remove(survivingScope);
        }
    }
}
