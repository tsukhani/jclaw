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
 * <p>Registered only when {@code loadtest.enabled=true}; absent in
 * production. Never call this from real agent workflows — it exists
 * solely for the harness at {@code POST /api/metrics/loadtest}.
 */
public class LoadTestSleepTool implements ToolRegistry.Tool {

    private static final int DEFAULT_MS = 200;
    private static final int MAX_MS = 10_000;

    @Override public String name() { return "loadtest_sleep"; }

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
