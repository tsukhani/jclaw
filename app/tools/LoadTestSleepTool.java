package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;

import java.util.List;
import java.util.Map;

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

    @Override public String name() { return "loadtest_sleep"; }
    @Override public String category() { return "System"; }
    @Override public String icon() { return "clock"; }
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
                "type", "object",
                "properties", Map.of(
                        "ms", Map.of(
                                "type", "integer",
                                "description", "Sleep duration in milliseconds (clamped to [0, " + MAX_MS + "])")
                ),
                "required", List.of()
        );
    }

    /** Test-only: a no-op sleep. Stateless by definition. */
    @Override public boolean parallelSafe() { return true; }

    @Override public String execute(String argsJson, Agent agent) {
        int ms = DEFAULT_MS;
        try {
            var args = JsonParser.parseString(argsJson).getAsJsonObject();
            if (args.has("ms") && !args.get("ms").isJsonNull()) {
                ms = args.get("ms").getAsInt();
            }
        } catch (Exception _) {
            // Fall back to default on malformed args — harness caller only.
        }
        int clamped = Math.max(0, Math.min(ms, MAX_MS));
        try {
            Thread.sleep(clamped);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return "slept " + clamped + "ms";
    }
}
