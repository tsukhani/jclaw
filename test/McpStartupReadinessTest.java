import mcp.McpConnectionManager;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * JCLAW-496: boot must wait (bounded) for MCP first-connect attempts to resolve
 * so tasks scheduled at startup don't fire before MCP tools are registered.
 * Exercises {@link McpConnectionManager#awaitFirstConnects} — the join used by
 * {@code startAll} — directly with synthetic futures, no live MCP servers.
 */
class McpStartupReadinessTest extends UnitTest {

    @Test
    void returnsTrueWhenEveryFutureResolvesWithinBudget() {
        var fast = CompletableFuture.<Void>completedFuture(null);
        var slightlyDelayed = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            slightlyDelayed.complete(null);
        });

        boolean all = McpConnectionManager.awaitFirstConnects(
                List.of(fast, slightlyDelayed), Duration.ofSeconds(5));
        assertTrue(all, "must report all-connected once every first-connect future resolves");
    }

    @Test
    void isBoundedWhenAServerNeverResolves() {
        var resolved = CompletableFuture.<Void>completedFuture(null);
        var neverResolves = new CompletableFuture<Void>(); // a hung / very slow connect

        long start = System.nanoTime();
        boolean all = McpConnectionManager.awaitFirstConnects(
                List.of(resolved, neverResolves), Duration.ofMillis(300));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(all, "a never-resolving server must not report all-connected");
        assertTrue(elapsedMs < 3000,
                "await must return shortly after the budget, not hang: " + elapsedMs + "ms");
    }

    @Test
    void noOpsWhenNoServersConfigured() {
        assertTrue(McpConnectionManager.awaitFirstConnects(List.of(), Duration.ofSeconds(1)),
                "an empty server set is trivially ready");
    }
}
