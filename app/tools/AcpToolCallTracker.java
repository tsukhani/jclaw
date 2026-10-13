package tools;

import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallLocation;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports what each of an ACP harness's tool calls turned out to be, once it finishes. A harness
 * announces a call before it has parsed the input — claude-agent-acp sends "Preparing file…" or
 * "Terminal" — and follows with partial updates: one carries the real title and the file
 * locations, a later one the final status alone. The announcement by itself cannot say which file
 * a run wrote or whether a command failed. Not thread-safe: callers serialize on the reply
 * accumulator, as for {@link AcpThoughtCoalescer}.
 */
public final class AcpToolCallTracker {

    private static final int MAX_TITLE = 200;

    private record Known(String title, List<String> paths) {}

    private final Map<String, Known> open = new HashMap<>();

    /** Note a call's announcement, so its later updates are read against it. */
    public void started(ToolCall call) {
        open.put(call.toolCallId(), new Known(call.title(), paths(call.locations())));
    }

    /** Fold one update into its call: the call's finishing line when the update ends it, else {@code null}. */
    public @Nullable HarnessEvent updated(ToolCallUpdateNotification update) {
        var id = update.toolCallId();
        var before = open.getOrDefault(id, new Known("", List.of()));
        var title = update.title();
        var locations = update.locations();
        var now = new Known(title == null || title.isBlank() ? before.title() : title,
                locations == null || locations.isEmpty() ? before.paths() : paths(locations));
        var status = update.status();
        if (status == null || !(ToolCallStatus.COMPLETED.equals(status) || ToolCallStatus.FAILED.equals(status))) {
            open.put(id, now);
            return null;
        }
        open.remove(id);
        return new HarnessEvent(HarnessEvent.TOOL_CALL, label(now, status), null);
    }

    private static String label(Known call, ToolCallStatus status) {
        var title = call.title().isBlank() ? "tool" : call.title();
        var name = title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) + "…" : title;
        // The path is the point: it is how a write outside the run's folder shows up.
        var where = call.paths().stream().filter(path -> !name.contains(path)).toList();
        return name + (where.isEmpty() ? "" : ": " + String.join(", ", where)) + " (" + status + ")";
    }

    private static List<String> paths(@Nullable List<ToolCallLocation> locations) {
        return locations == null ? List.of() : locations.stream().map(ToolCallLocation::path).toList();
    }
}
