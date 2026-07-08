package tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-660 / JCLAW-657 finding C: {@link HarnessAdapter} for the Pi coding
 * harness (earendil-works/pi) driven in its streaming JSONL mode. It launches
 * the operator-configured base command with {@code --mode json} appended (task
 * on stdin) and maps Pi's <em>nested</em> event stream onto the common {@link
 * HarnessEvent} vocabulary:
 *
 * <ul>
 *   <li>{@code message_update} whose {@code assistantMessageEvent.type} is
 *       {@code text_delta} → {@link HarnessEvent#TOKEN} (the incremental output);</li>
 *   <li>{@code tool_execution_start} → {@link HarnessEvent#TOOL_CALL} (its
 *       {@code toolName});</li>
 *   <li>{@code tool_execution_end} → a concise {@link HarnessEvent#STEP} of the
 *       result text ({@link HarnessEvent#ERROR} when {@code isError});</li>
 *   <li>{@code agent_end} → {@link HarnessEvent#RESULT} (the last assistant
 *       message's text — the final answer);</li>
 *   <li>envelope/framing lines — {@code session}, {@code agent_start}, {@code
 *       turn_start}/{@code _end}, {@code message_start}/{@code _end}, and every
 *       non-{@code text_delta} {@code message_update} (text_start/end,
 *       toolcall_*) — are <b>dropped</b> ({@code null});</li>
 *   <li>a non-JSON line or an unrecognized top-level {@code type} → a tolerant
 *       {@link HarnessEvent#STEP} carrying the raw line.</li>
 * </ul>
 *
 * <p>Pi streams tokens/tool-calls and holds a long-lived session, so it
 * advertises both {@code streaming} and {@code bidirectional}.
 */
public final class PiAdapter implements HarnessAdapter {

    /** Launch the base command in Pi's line-streamed JSON mode; task on stdin. */
    @Override
    public List<String> launchArgs(List<String> baseCommand, String task) {
        var argv = new ArrayList<String>(baseCommand.size() + 2);
        argv.addAll(baseCommand);
        argv.add("--mode");
        argv.add("json");
        return List.copyOf(argv);
    }

    /**
     * Map one Pi JSONL line onto a {@link HarnessEvent} — or {@code null} to drop
     * a framing/duplicate line — keyed on the top-level {@code type}. See the
     * class doc for the full mapping. Tolerant by contract: a non-JSON line or an
     * unrecognized type degrades to a {@link HarnessEvent#STEP}, never throws.
     */
    @Override
    public HarnessEvent parse(String line) {
        JsonObject obj;
        try {
            var parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                return new HarnessEvent(HarnessEvent.STEP, line, null);
            }
            obj = parsed.getAsJsonObject();
        } catch (RuntimeException _) {
            // Not JSON — tolerant fallback carrying the raw line.
            return new HarnessEvent(HarnessEvent.STEP, line, null);
        }

        var type = firstString(obj, "type");
        if (type == null) {
            return new HarnessEvent(HarnessEvent.STEP, line, obj);
        }
        return switch (type) {
            case "message_update" -> fromMessageUpdate(obj);
            case "tool_execution_start" ->
                    new HarnessEvent(HarnessEvent.TOOL_CALL, orLine(firstString(obj, "toolName"), line), obj);
            case "tool_execution_end" -> fromToolExecutionEnd(obj, line);
            case "agent_end" -> fromAgentEnd(obj);
            case "error", "failure" ->
                    new HarnessEvent(HarnessEvent.ERROR, orLine(firstString(obj, "message", "error", "text"), line), obj);
            // JCLAW-657 finding C: session/agent/turn/message envelope lines carry
            // no run progress — drop them so the monitor and transcript stay signal.
            case "session", "agent_start", "turn_start", "turn_end",
                 "message_start", "message_end" -> null;
            default -> new HarnessEvent(HarnessEvent.STEP, line, obj);
        };
    }

    /**
     * JCLAW-670: pi exposes no per-tool restriction flags on its CLI today, so
     * there is no conservative default to bake — containment for pi rests on
     * the channel gate (JCLAW-669) and the sandbox track (JCLAW-671). The
     * {@code subagent.acp.permissionArgs} hatch still appends whatever the
     * operator configures.
     */

    /** Pi streams tokens/tool-calls and holds an interactive session. */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true);
    }

    /** JCLAW-672: pi reads its own config/state dir under $HOME. */
    @Override
    public List<String> sandboxAllowances() {
        return List.of(".pi", ".config/pi");
    }

    /**
     * A {@code message_update} wraps one streaming sub-event in {@code
     * assistantMessageEvent}. Only a {@code text_delta} carries live output —
     * surface its {@code delta} as a {@link HarnessEvent#TOKEN}. Everything else
     * (text_start/end, toolcall_start/delta/end) is framing or duplicates an
     * already-surfaced event, so drop it.
     */
    private static HarnessEvent fromMessageUpdate(JsonObject obj) {
        JsonElement ame = obj.get("assistantMessageEvent");
        if (ame == null || !ame.isJsonObject()) return null;
        var event = ame.getAsJsonObject();
        if (!"text_delta".equals(firstString(event, "type"))) return null;
        var delta = firstString(event, "delta");
        return delta == null ? null : new HarnessEvent(HarnessEvent.TOKEN, delta, obj);
    }

    /**
     * A {@code tool_execution_end} — surface the tool's result text as a concise
     * {@link HarnessEvent#STEP} (or {@link HarnessEvent#ERROR} when {@code
     * isError}), e.g. "Successfully wrote 2 bytes to hello.txt".
     */
    private static HarnessEvent fromToolExecutionEnd(JsonObject obj, String line) {
        boolean isError = obj.has("isError") && obj.get("isError").isJsonPrimitive()
                && obj.get("isError").getAsBoolean();
        var text = toolResultText(obj.get("result"));
        if (text == null) text = firstString(obj, "toolName");
        return new HarnessEvent(isError ? HarnessEvent.ERROR : HarnessEvent.STEP,
                orLine(text, line), obj);
    }

    /** Concatenate the {@code text} of a Pi tool result's {@code content[]} blocks. */
    private static String toolResultText(JsonElement result) {
        if (result == null || !result.isJsonObject()) return null;
        JsonElement content = result.getAsJsonObject().get("content");
        if (content == null || !content.isJsonArray()) return null;
        var sb = new StringBuilder();
        for (JsonElement el : content.getAsJsonArray()) {
            if (el.isJsonObject()) {
                var t = firstString(el.getAsJsonObject(), "text");
                if (t != null) sb.append(t);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * The terminal {@code agent_end} — the final answer is the text of the last
     * assistant message in {@code messages}. Drops ({@code null}) when the run
     * ended without a final assistant text (the reply then falls back to the
     * streamed tokens).
     */
    private static HarnessEvent fromAgentEnd(JsonObject obj) {
        JsonElement messages = obj.get("messages");
        if (messages == null || !messages.isJsonArray()) return null;
        var arr = messages.getAsJsonArray();
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonElement m = arr.get(i);
            if (!m.isJsonObject()) continue;
            var msg = m.getAsJsonObject();
            if (!"assistant".equals(firstString(msg, "role"))) continue;
            var text = assistantText(msg.get("content"));
            if (text != null) return new HarnessEvent(HarnessEvent.RESULT, text, obj);
        }
        return null;
    }

    /** Concatenate the {@code text} blocks of an assistant message's {@code content[]}. */
    private static String assistantText(JsonElement content) {
        if (content == null || !content.isJsonArray()) return null;
        var sb = new StringBuilder();
        for (JsonElement el : content.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            var block = el.getAsJsonObject();
            if ("text".equals(firstString(block, "type"))) {
                var t = firstString(block, "text");
                if (t != null) sb.append(t);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** The value if non-null, else the raw line (tolerant fallback). */
    private static String orLine(String value, String line) {
        return value == null ? line : value;
    }

    /** First present, non-null primitive field among {@code keys}, as a string. */
    private static String firstString(JsonObject obj, String... keys) {
        for (var key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        return null;
    }
}
