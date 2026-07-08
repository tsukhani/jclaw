package tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-667: {@link HarnessAdapter} for Anthropic's Claude Code CLI driven
 * headless in its streaming NDJSON mode. It launches the operator-configured
 * base command (e.g. {@code ["claude", "-p"]}) with {@code --output-format
 * stream-json --verbose --include-partial-messages} appended and delivers the
 * task on stdin, matching the batch/Pi invocation convention. Each stdout line
 * is one JSON object; {@link #parse} maps Claude's on-the-wire event types onto
 * the common {@link HarnessEvent} vocabulary:
 *
 * <ul>
 *   <li>{@code stream_event} carrying a {@code text_delta} → {@link
 *       HarnessEvent#TOKEN} (the live, incremental output stream);</li>
 *   <li>{@code assistant} → a {@code tool_use} content block becomes a {@link
 *       HarnessEvent#TOOL_CALL} (its input arrives complete here, unlike the
 *       partial deltas); a text-only turn is <b>dropped</b> ({@code null}) — its
 *       prose already streamed as {@code text_delta} tokens, so re-emitting it
 *       would double it;</li>
 *   <li>a top-level {@code tool_use} → {@link HarnessEvent#TOOL_CALL};</li>
 *   <li>{@code user} → its {@code tool_result} text as a concise {@link
 *       HarnessEvent#STEP} (dropped when empty);</li>
 *   <li>{@code result} → {@link HarnessEvent#RESULT} (the final answer);</li>
 *   <li>{@code error} → {@link HarnessEvent#ERROR};</li>
 *   <li>framing noise is <b>dropped</b> ({@code null}, JCLAW-657 finding B):
 *       {@code system} session/hook/init/status lines, {@code rate_limit_event},
 *       and non-text {@code stream_event} SSE frames ({@code message_start},
 *       {@code content_block_*}, {@code input_json_delta}, {@code ping}, …);</li>
 *   <li>a non-JSON line or an unrecognized type → a tolerant {@link
 *       HarnessEvent#STEP} carrying the raw line.</li>
 * </ul>
 */
public final class ClaudeAdapter implements HarnessAdapter {

    /**
     * Append Claude's streaming-NDJSON flags to the operator-configured base
     * command; the task rides on stdin (left out of the argv). {@code --verbose}
     * is required by the CLI alongside {@code stream-json}, and {@code
     * --include-partial-messages} turns on the incremental {@code text_delta}
     * events we surface as live tokens.
     */
    @Override
    public List<String> launchArgs(List<String> baseCommand, String task) {
        var argv = new ArrayList<String>(baseCommand.size() + 4);
        argv.addAll(baseCommand);
        argv.add("--output-format");
        argv.add("stream-json");
        argv.add("--verbose");
        argv.add("--include-partial-messages");
        return List.copyOf(argv);
    }

    /**
     * Map one Claude NDJSON line onto a {@link HarnessEvent}. Tolerant by the
     * {@link HarnessAdapter#parse} contract: a non-JSON line, or a JSON object
     * with an absent/unrecognized {@code type}, degrades to a {@link
     * HarnessEvent#STEP} carrying the raw line.
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
        return switch (type.toLowerCase()) {
            case "assistant" -> fromAssistant(obj, line);
            case "tool_use" -> new HarnessEvent(HarnessEvent.TOOL_CALL, toolName(obj, line), obj);
            case "user" -> fromUser(obj);
            case "result" -> fromResult(obj);
            case "stream_event" -> fromStreamEvent(obj);
            case "error" -> new HarnessEvent(HarnessEvent.ERROR, errorText(obj, line), obj);
            // JCLAW-657 finding B: session/hook/init/status lines and rate-limit
            // pings carry no run progress — drop them so the monitor stays signal.
            case "system", "rate_limit_event" -> null;
            default -> new HarnessEvent(HarnessEvent.STEP, line, obj);
        };
    }

    /**
     * Claude Code streams incremental tokens and tool calls, so {@code streaming}
     * is true. It is driven one-shot (task in on stdin, output out) rather than
     * as a long-lived session, so {@code bidirectional} is false. Future bidi:
     * adding {@code --input-format stream-json} lets the CLI accept follow-up
     * user turns on stdin mid-run, which would flip this to {@code true}.
     */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false);
    }

    /**
     * The full assembled assistant turn. A {@code tool_use} content block becomes
     * a {@link HarnessEvent#TOOL_CALL} (its input is complete here); a text-only
     * turn becomes a {@link HarnessEvent#STEP} because that prose already streamed
     * as {@code text_delta} tokens. Falls back to a raw-line step when the message
     * carries no structured content.
     */
    private static HarnessEvent fromAssistant(JsonObject obj, String line) {
        var message = obj.has("message") && obj.get("message").isJsonObject()
                ? obj.getAsJsonObject("message") : obj;
        JsonElement content = message.get("content");
        if (content != null && content.isJsonArray()) {
            for (JsonElement el : content.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                var block = el.getAsJsonObject();
                if ("tool_use".equals(firstString(block, "type"))) {
                    return new HarnessEvent(HarnessEvent.TOOL_CALL, toolName(block, line), obj);
                }
            }
        }
        // JCLAW-657 finding B: a text-only (or contentless) assistant turn — its
        // prose already streamed as text_delta TOKENs, so drop the duplicate.
        return null;
    }

    /**
     * A {@code stream_event} wraps one raw Anthropic SSE frame. Only a {@code
     * content_block_delta} whose delta is a {@code text_delta} carries live output
     * — surface it as a {@link HarnessEvent#TOKEN}. Every other frame ({@code
     * message_start}, {@code ping}, {@code content_block_start}/{@code _stop},
     * {@code input_json_delta}, …) is a coarse {@link HarnessEvent#STEP}.
     */
    private static HarnessEvent fromStreamEvent(JsonObject obj) {
        JsonElement event = obj.get("event");
        if (event != null && event.isJsonObject()) {
            JsonElement delta = event.getAsJsonObject().get("delta");
            if (delta != null && delta.isJsonObject()) {
                var deltaObj = delta.getAsJsonObject();
                if ("text_delta".equals(firstString(deltaObj, "type"))) {
                    var t = firstString(deltaObj, "text");
                    return new HarnessEvent(HarnessEvent.TOKEN, t == null ? "" : t, obj);
                }
            }
        }
        // JCLAW-657 finding B: a non-text SSE frame (message_start, content_block_*,
        // input_json_delta, ping, …) is pure protocol framing — drop it.
        return null;
    }

    /**
     * JCLAW-657 finding B: a {@code user} frame carrying a {@code tool_result} —
     * surface the tool's result text as a concise {@link HarnessEvent#STEP}
     * (e.g. "File created successfully at …") rather than the raw JSON, and drop
     * it ({@code null}) when it carries no readable content.
     */
    private static HarnessEvent fromUser(JsonObject obj) {
        var message = obj.has("message") && obj.get("message").isJsonObject()
                ? obj.getAsJsonObject("message") : obj;
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonArray()) return null;
        var text = new StringBuilder();
        for (JsonElement el : content.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            var block = el.getAsJsonObject();
            if (!"tool_result".equals(firstString(block, "type"))) continue;
            JsonElement c = block.get("content");
            if (c != null && c.isJsonPrimitive()) {
                text.append(c.getAsString());
            } else if (c != null && c.isJsonArray()) {
                for (JsonElement part : c.getAsJsonArray()) {
                    if (part.isJsonObject()) {
                        var t = firstString(part.getAsJsonObject(), "text");
                        if (t != null) text.append(t);
                    }
                }
            }
        }
        return text.isEmpty() ? null : new HarnessEvent(HarnessEvent.STEP, text.toString(), obj);
    }

    /** The terminal {@code result} frame — the final answer text. */
    private static HarnessEvent fromResult(JsonObject obj) {
        var text = firstString(obj, "result", "text", "message");
        return new HarnessEvent(HarnessEvent.RESULT, text == null ? "" : text, obj);
    }

    /** Tool name from a {@code tool_use} object, falling back to the raw line. */
    private static String toolName(JsonObject obj, String line) {
        var name = firstString(obj, "name");
        return name == null ? line : name;
    }

    /** Human-readable message from an {@code error} frame, or the raw line. */
    private static String errorText(JsonObject obj, String line) {
        var msg = firstString(obj, "message", "error", "text");
        return msg == null ? line : msg;
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
