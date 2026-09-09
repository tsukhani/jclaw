package tools;

import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds an ACP harness's reasoning into one {@link HarnessEvent#STEP} per block. A
 * harness streams {@code agent_thought_chunk} updates a few characters at a time, and
 * mapping each through {@link AcpEventMapper} alone persisted one transcript row and one
 * live event per fragment — 145 rows of "The", " user", " wants" around two tool calls in
 * a trivial run. Thought chunks are buffered here; the first non-thought update (or
 * {@link #flush()} at the end of the turn) emits the buffered block as a single step ahead
 * of that update's own event. Not thread-safe: callers serialize on the reply accumulator.
 */
public final class AcpThoughtCoalescer {

    private final StringBuilder thought = new StringBuilder();

    /** The events to dispatch for {@code update}: none while a thought block is still
     *  growing, else the finished block (if any) followed by the update's own event (if any). */
    public List<HarnessEvent> accept(SessionUpdate update) {
        if (update instanceof AgentThoughtChunk c) {
            thought.append(AcpEventMapper.text(c.content()));
            return List.of();
        }
        var out = new ArrayList<HarnessEvent>(2);
        out.addAll(flush());
        var ev = AcpEventMapper.toHarnessEvent(update);
        if (ev != null) out.add(ev);
        return List.copyOf(out);
    }

    /** The pending thought block as one step, or nothing when no reasoning is buffered. */
    public List<HarnessEvent> flush() {
        var text = thought.toString().strip();
        thought.setLength(0);
        return text.isEmpty() ? List.of() : List.of(new HarnessEvent(HarnessEvent.STEP, text, null));
    }
}
