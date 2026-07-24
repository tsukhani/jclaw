package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Load-test fixture tool. Sleeps for a deterministic duration so the
 * in-process mock provider can drive the parallel tool-execution path
 * and produce measurable {@code tool_exec} histograms without depending
 * on real network I/O.
 *
 * <p>Registered only while a load test is running ({@code provider.loadtest-mock.enabled=true});
 * unregistered after each run so it never appears in agent tool lists.
 */
public class LoadTestSleepTool implements ToolRegistry.Tool {

    private static final int DEFAULT_MS = 200;
    private static final int MAX_MS = 10_000;

    /**
     * Process-wide count of tool executions. The real-provider tools loadtest
     * ({@code __loadtest_tools__} agent) snapshots this before/after a run to
     * report the model's actual tool-call rate — no forced tool_choice exists,
     * so the model is prompted into calling and we measure how often it did.
     */
    private static final AtomicLong INVOCATIONS = new AtomicLong();

    /** Total {@code loadtest_sleep} executions since JVM start. */
    public static long invocations() {
        return INVOCATIONS.get();
    }

    @Override public String name() { return "loadtest_sleep"; }
    @Override public String category() { return "System"; }
    @Override public String icon() { return "clock"; }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("sleep",
                "Block for the requested duration (load-test latency stand-in)"));
    }
    @Override public String shortDescription() {
        return "Internal load-testing sleep tool — not surfaced in the admin UI.";
    }
    // JCLAW-281: isSystem mechanism deleted. This tool is gated by
    // conditional registration in ToolRegistrationJob (only registered
    // when provider.loadtest-mock.enabled=true), so it's not visible to
    // operators in normal runs regardless.

    @Override public String description() {
        return "Load-test fixture: sleeps the current thread for the given "
                + "number of milliseconds, then returns. Do not use outside "
                + "the load-test harness.";
    }

    @Override public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        "ms", Map.of(
                                SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Sleep duration in milliseconds (clamped to [0, " + MAX_MS + "])")
                ),
                SchemaKeys.REQUIRED, List.of()
        );
    }

    /** Test-only: a no-op sleep. Stateless by definition. */
    @Override public boolean parallelSafe() { return true; }

    @Override public String execute(String argsJson, Agent agent) {
        INVOCATIONS.incrementAndGet();
        int ms = DEFAULT_MS;
        try {
            var args = JsonParser.parseString(argsJson).getAsJsonObject();
            if (args.has("ms") && !args.get("ms").isJsonNull()) {
                ms = args.get("ms").getAsInt();
            }
        } catch (Exception _) {
            // Fall back to default on malformed args — harness caller only.
        }
        int clamped = Math.clamp(ms, 0, MAX_MS);
        // LockSupport.parkNanos instead of Thread.sleep: this tool runs on a
        // per-call virtual thread under exactly the concurrency a load test
        // generates, and JDK 25's Thread.sleep on many concurrent VTs triggers
        // FJP work-queue starvation (JDK-8373224). parkNanos unmounts the VT
        // cleanly and is unaffected by that regression. parkNanos can return
        // spuriously or on interrupt, so loop to the deadline and preserve the
        // original interrupt semantics (stop early, re-assert the flag).
        long deadline = System.nanoTime() + Duration.ofMillis(clamped).toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "slept " + clamped + "ms";
    }
}
