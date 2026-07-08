import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.ClaudeAdapter;
import tools.GenericAdapter;
import tools.HarnessEvent;
import tools.PiAdapter;
import tools.SubagentSpawnTool;

/**
 * JCLAW-657: pure-parser unit tests for the coding-harness {@code
 * tools.HarnessAdapter} implementations. Each adapter's {@link
 * tools.HarnessAdapter#parse} is a pure function from one line of harness stdout
 * to a normalized {@link HarnessEvent}, so these tests instantiate the adapters
 * directly and need no database, fixtures, or {@code @BeforeEach} setup.
 *
 * <p>Coverage per the seam contract: recognized JSON kinds map onto the common
 * vocabulary ({@code token}/{@code tool_call}), a plain non-JSON line degrades
 * to a tolerant {@link HarnessEvent#STEP} carrying the raw line, and a malformed
 * or partial JSON line also degrades to {@code STEP} rather than throwing. Each
 * adapter's advertised {@link tools.HarnessAdapter.Capabilities} is pinned too.
 */
class HarnessAdapterParseTest extends UnitTest {

    // ---- PiAdapter ---------------------------------------------------------

    // Fixtures below are real pi 0.80.3 (@earendil-works/pi-coding-agent) JSONL
    // events, captured from `pi -p --mode json` — pi nests its payloads, unlike
    // the flat schema the pre-JCLAW-657-finding-C parser wrongly assumed.

    @Test
    void piTextDeltaMapsToToken() {
        var ev = new PiAdapter().parse(
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\"Done\"}}");
        assertEquals(HarnessEvent.TOKEN, ev.kind(), "a Pi message_update text_delta maps to TOKEN");
        assertEquals("Done", ev.text(), "TOKEN text is the assistantMessageEvent delta");
    }

    @Test
    void piToolExecutionStartMapsToToolCall() {
        var ev = new PiAdapter().parse(
                "{\"type\":\"tool_execution_start\",\"toolCallId\":\"call_1\",\"toolName\":\"write\","
                        + "\"args\":{\"path\":\"hello.txt\",\"content\":\"hi\"}}");
        assertEquals(HarnessEvent.TOOL_CALL, ev.kind(), "a Pi tool_execution_start maps to TOOL_CALL");
        assertEquals("write", ev.text(), "TOOL_CALL text is the toolName");
    }

    @Test
    void piToolExecutionEndMapsToConciseStep() {
        var ev = new PiAdapter().parse(
                "{\"type\":\"tool_execution_end\",\"toolName\":\"write\",\"result\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"Successfully wrote 2 bytes to hello.txt\"}]},\"isError\":false}");
        assertEquals(HarnessEvent.STEP, ev.kind(), "a Pi tool_execution_end surfaces its result as a STEP");
        assertEquals("Successfully wrote 2 bytes to hello.txt", ev.text(),
                "the STEP carries the tool result text, not the raw JSON");
    }

    @Test
    void piFailedToolExecutionEndMapsToError() {
        var ev = new PiAdapter().parse(
                "{\"type\":\"tool_execution_end\",\"toolName\":\"write\",\"result\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"permission denied\"}]},\"isError\":true}");
        assertEquals(HarnessEvent.ERROR, ev.kind(), "a failed tool_execution_end maps to ERROR");
    }

    @Test
    void piAgentEndMapsToResult() {
        var ev = new PiAdapter().parse(
                "{\"type\":\"agent_end\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"do it\"}]},"
                        + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"All done.\"}]}]}");
        assertEquals(HarnessEvent.RESULT, ev.kind(), "agent_end's last assistant text maps to RESULT");
        assertEquals("All done.", ev.text(), "RESULT text is the final assistant answer");
    }

    @Test
    void piFramingAndDuplicateLinesAreDropped() {
        var a = new PiAdapter();
        assertNull(a.parse("{\"type\":\"session\",\"version\":3,\"cwd\":\"/tmp\"}"), "session framing is dropped");
        assertNull(a.parse("{\"type\":\"turn_start\"}"), "turn_start framing is dropped");
        assertNull(a.parse("{\"type\":\"message_start\",\"message\":{\"role\":\"user\"}}"), "message_start framing is dropped");
        assertNull(a.parse("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_end\",\"content\":\"Done\"}}"),
                "a non-text_delta message_update (text_end) is dropped as a duplicate");
        assertNull(a.parse("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"toolcall_delta\",\"delta\":\"{\"}}"),
                "a toolcall_delta message_update is dropped as framing");
    }

    @Test
    void piNonJsonLineDegradesToStep() {
        var line = "just a plain log line";
        var ev = new PiAdapter().parse(line);
        assertEquals(HarnessEvent.STEP, ev.kind(), "a non-JSON line is a tolerant STEP");
        assertEquals(line, ev.text(), "the STEP carries the raw line verbatim");
        assertNull(ev.raw(), "a non-JSON STEP has no parsed raw object");
    }

    @Test
    void piMalformedJsonDegradesToStepWithoutThrowing() {
        var line = "{\"type\":\"token\",\"text\":\"hel";  // truncated, unterminated
        var ev = new PiAdapter().parse(line);
        assertEquals(HarnessEvent.STEP, ev.kind(), "partial/malformed JSON is a tolerant STEP");
        assertEquals(line, ev.text(), "the STEP carries the raw malformed line");
        assertNull(ev.raw(), "a malformed-JSON STEP has no parsed raw object");
    }

    @Test
    void piAdvertisesStreamingAndBidirectional() {
        var caps = new PiAdapter().capabilities();
        assertTrue(caps.streaming(), "Pi streams incremental events");
        assertTrue(caps.bidirectional(), "Pi holds a long-lived interactive session");
    }

    // ---- ClaudeAdapter -----------------------------------------------------

    @Test
    void claudeToolUseFrameMapsToToolCall() {
        var ev = new ClaudeAdapter().parse(
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\"}]}}");
        assertEquals(HarnessEvent.TOOL_CALL, ev.kind(),
                "a Claude assistant tool_use content block maps to TOOL_CALL");
        assertEquals("Bash", ev.text(), "TOOL_CALL text is the tool name");
    }

    @Test
    void claudeTextDeltaFrameMapsToToken() {
        var ev = new ClaudeAdapter().parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}}");
        assertEquals(HarnessEvent.TOKEN, ev.kind(),
                "a Claude content_block_delta text_delta maps to TOKEN");
        assertEquals("Hel", ev.text(), "TOKEN text is the incremental delta text");
    }

    @Test
    void claudeNonJsonLineDegradesToStep() {
        var line = "Loading configuration...";
        var ev = new ClaudeAdapter().parse(line);
        assertEquals(HarnessEvent.STEP, ev.kind(), "a non-JSON line is a tolerant STEP");
        assertEquals(line, ev.text(), "the STEP carries the raw line verbatim");
        assertNull(ev.raw(), "a non-JSON STEP has no parsed raw object");
    }

    @Test
    void claudeAdvertisesStreamingOneShot() {
        var caps = new ClaudeAdapter().capabilities();
        assertTrue(caps.streaming(), "Claude Code streams incremental tokens");
        assertFalse(caps.bidirectional(), "Claude Code is driven one-shot, not bidirectional");
    }

    @Test
    void claudeSystemAndRateLimitLinesAreDropped() {
        var a = new ClaudeAdapter();
        assertNull(a.parse("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/tmp\"}"),
                "a Claude system/init framing line is dropped (finding B)");
        assertNull(a.parse("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\"}}"),
                "a rate_limit_event ping is dropped");
    }

    @Test
    void claudeNonTextStreamEventFrameIsDropped() {
        var ev = new ClaudeAdapter().parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\",\"message\":{}}}");
        assertNull(ev, "a non-text SSE frame (message_start, …) is pure framing and is dropped");
    }

    @Test
    void claudeTextOnlyAssistantTurnIsDropped() {
        var ev = new ClaudeAdapter().parse(
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Working on it\"}]}}");
        assertNull(ev, "a text-only assistant turn is dropped — its prose already streamed as text_delta TOKENs");
    }

    @Test
    void claudeResultFrameMapsToResult() {
        var ev = new ClaudeAdapter().parse(
                "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"Done.\"}");
        assertEquals(HarnessEvent.RESULT, ev.kind(), "the terminal result frame maps to RESULT");
        assertEquals("Done.", ev.text(), "RESULT text is the final answer");
    }

    @Test
    void claudeUserToolResultMapsToConciseStep() {
        var ev = new ClaudeAdapter().parse(
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                        + "\"content\":\"File created successfully at /tmp/fib.py\"}]}}");
        assertEquals(HarnessEvent.STEP, ev.kind(), "a user tool_result surfaces as a concise STEP");
        assertEquals("File created successfully at /tmp/fib.py", ev.text(),
                "the STEP carries the tool result text, not the raw JSON");
    }

    // ---- GenericAdapter ----------------------------------------------------

    @Test
    void genericTreatsEveryLineAsStep() {
        var adapter = new GenericAdapter();
        var jsonLooking = "{\"type\":\"token\",\"text\":\"ignored\"}";
        var evJson = adapter.parse(jsonLooking);
        assertEquals(HarnessEvent.STEP, evJson.kind(),
                "generic never interprets structure — even JSON-looking lines are STEP");
        assertEquals(jsonLooking, evJson.text(), "the STEP carries the raw line verbatim");

        var plain = "building module foo";
        var evPlain = adapter.parse(plain);
        assertEquals(HarnessEvent.STEP, evPlain.kind(), "a plain line is a STEP");
        assertEquals(plain, evPlain.text(), "the STEP text is the line");
    }

    @Test
    void genericAdvertisesNeitherStreamingNorBidirectional() {
        var caps = new GenericAdapter().capabilities();
        assertFalse(caps.streaming(), "generic has no incremental protocol");
        assertFalse(caps.bidirectional(), "generic accepts no follow-up input");
    }

    // ─── JCLAW-670: permission-flag composition ──────────────────────────────

    @org.junit.jupiter.api.Test
    void claudeDefaultPermissionArgsAppendWhenUnconfigured() {
        services.ConfigService.set(SubagentSpawnTool.ACP_PERMISSION_ARGS_KEY, "");
        var argv = SubagentSpawnTool.withPermissionArgs(new ClaudeAdapter(),
                java.util.List.of("claude", "-p"));
        assertTrue(argv.contains("--allowedTools"), "conservative default present: " + argv);
        assertFalse(String.join(" ", argv).contains("Bash"),
                "shell stays outside the default grant: " + argv);
    }

    @org.junit.jupiter.api.Test
    void configuredPermissionArgsOverrideAdapterDefaults() {
        services.ConfigService.set(SubagentSpawnTool.ACP_PERMISSION_ARGS_KEY,
                "--allowedTools Read,Bash");
        var argv = SubagentSpawnTool.withPermissionArgs(new ClaudeAdapter(),
                java.util.List.of("claude", "-p"));
        assertTrue(String.join(" ", argv).endsWith("--allowedTools Read,Bash"),
                "operator override wins verbatim: " + argv);
        services.ConfigService.set(SubagentSpawnTool.ACP_PERMISSION_ARGS_KEY, "");
    }

    @org.junit.jupiter.api.Test
    void noneDisablesPermissionArgsEntirely() {
        services.ConfigService.set(SubagentSpawnTool.ACP_PERMISSION_ARGS_KEY, "none");
        var argv = SubagentSpawnTool.withPermissionArgs(new ClaudeAdapter(),
                java.util.List.of("claude", "-p"));
        assertEquals(java.util.List.of("claude", "-p"), argv);
        services.ConfigService.set(SubagentSpawnTool.ACP_PERMISSION_ARGS_KEY, "");
    }

    @org.junit.jupiter.api.Test
    void adaptersWithoutRestrictionSurfaceAddNothing() {
        services.ConfigService.set(SubagentSpawnTool.ACP_PERMISSION_ARGS_KEY, "");
        assertEquals(java.util.List.of("pi", "-p", "--mode", "json"),
                SubagentSpawnTool.withPermissionArgs(new PiAdapter(),
                        new PiAdapter().launchArgs(java.util.List.of("pi", "-p"), "t")));
    }
}