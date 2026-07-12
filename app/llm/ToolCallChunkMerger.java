package llm;

import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolCallChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges streaming {@code tool_calls} delta chunks into per-call builders
 * (JCLAW-120). Extracted from {@link LlmProvider} (JCLAW-725) to separate the
 * chunk-merging concern from wire protocol and stream accumulation. Only the
 * streaming accumulator in {@link LlmProvider} and the unit tests drive this;
 * no {@code app/} caller references it directly.
 */
public final class ToolCallChunkMerger {

    // OpenAI tool-call type — the only value the spec defines today.
    private static final String TYPE_FUNCTION = "function";

    private ToolCallChunkMerger() {}

    /**
     * Mutable, per-slot accumulator for one streaming tool call. Fields are
     * fully encapsulated (no public mutable state): the merge logic in this
     * class and the unit tests both go through the fluent mutators.
     */
    public static final class ToolCallBuilder {
        private String id;
        private String type = TYPE_FUNCTION;
        private String functionName;
        private final StringBuilder arguments = new StringBuilder();

        public ToolCallBuilder id(String id) {
            this.id = id;
            return this;
        }

        public ToolCallBuilder type(String type) {
            this.type = type;
            return this;
        }

        public ToolCallBuilder functionName(String functionName) {
            this.functionName = functionName;
            return this;
        }

        public ToolCallBuilder appendArguments(String fragment) {
            arguments.append(fragment);
            return this;
        }

        public ToolCall build() {
            return new ToolCall(id, type, new FunctionCall(functionName, arguments.toString()));
        }
    }

    /**
     * Route the {@code chunks} from a single streaming delta into slots of
     * {@code accumulator} (JCLAW-120). OpenAI's spec says each parallel
     * tool_call gets its own {@code index}; chunks of the same call share
     * an index. Some providers — observed with gemini-3-flash-preview via
     * ollama-cloud — emit every parallel call at {@code index=0} (or omit
     * {@code index} entirely, which the primitive-int field defaults to 0).
     * Without correction, the accumulator would merge all five parallel
     * calls into slot 0: concatenated arguments, last-seen function name,
     * one final ToolCall instead of five.
     *
     * <p>Detection signals (any triggers a fresh slot allocation):
     * <ul>
     *   <li>The same index appears twice within this one delta's chunk
     *       list (defensive — covers providers that bundle fully-formed
     *       parallel calls into a single delta).</li>
     *   <li>The incoming chunk's non-null {@code id} differs from the
     *       existing slot's id.</li>
     *   <li>The incoming chunk's non-null {@code function.name} differs
     *       from the existing slot's functionName.</li>
     * </ul>
     * A fresh slot is numbered {@code max(existing) + 1}. Well-behaved
     * providers (OpenRouter, Anthropic) whose chunks share id and name
     * across the call's streaming lifetime stay on the original slot.
     *
     * @param chunks      tool-call delta chunks emitted on the current SSE
     *                    frame
     * @param accumulator mutable per-call accumulator, keyed by slot
     *                    number; mutated in place as chunks are folded in
     */
    public static void mergeToolCallChunks(
            List<ToolCallChunk> chunks,
            Map<Integer, ToolCallBuilder> accumulator) {
        if (chunks == null || chunks.isEmpty()) return;
        var seenInDelta = new HashSet<Integer>();
        for (var tc : chunks) {
            int slot = pickSlotForToolCall(tc, accumulator, seenInDelta);
            seenInDelta.add(slot);
            var builder = accumulator.computeIfAbsent(slot, _ -> new ToolCallBuilder());
            if (tc.id() != null) builder.id(tc.id());
            if (tc.type() != null) builder.type(tc.type());
            if (tc.function() != null) {
                if (tc.function().name() != null) builder.functionName(tc.function().name());
                if (tc.function().arguments() != null) builder.appendArguments(tc.function().arguments());
            }
        }
    }

    /**
     * Pick the destination slot for {@code chunk}. See
     * {@link #mergeToolCallChunks} for the detection rules. Package-visible
     * so unit tests can exercise the decision in isolation without driving
     * a full streaming call.
     */
    static int pickSlotForToolCall(
            ToolCallChunk chunk,
            Map<Integer, ToolCallBuilder> accumulator,
            Set<Integer> seenInDelta) {
        int slot = chunk.index();
        if (seenInDelta.contains(slot)) return nextSlot(accumulator);
        var existing = accumulator.get(slot);
        if (existing != null) {
            if (chunk.id() != null && existing.id != null
                    && !chunk.id().equals(existing.id)) {
                return nextSlot(accumulator);
            }
            if (chunk.function() != null && chunk.function().name() != null
                    && existing.functionName != null
                    && !chunk.function().name().equals(existing.functionName)) {
                return nextSlot(accumulator);
            }
        }
        return slot;
    }

    private static int nextSlot(Map<Integer, ToolCallBuilder> accumulator) {
        return accumulator.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }
}
