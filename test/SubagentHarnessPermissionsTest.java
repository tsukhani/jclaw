import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.HarnessEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Branch coverage for {@code tools.SubagentHarnessPermissions} (JCLAW-707) —
 * a completely untested, logic-heavy class. The class and its
 * {@code HarnessPermission} record are package-private, so the static entry
 * points are reached via reflection; {@link HarnessEvent} and {@link Agent} are
 * public and imported directly.
 *
 * <p>Coverage centres on {@code detectPermission}, the tolerant frame parser:
 * every early-return arm (null event, null raw, no discriminator, non-permission
 * discriminator) and every extraction path (top-level vs nested envelope, id
 * fallback, tool-name fallback, args fallback to the payload). Plus the
 * {@code arbitratePermission} write decision (a null agent auto-approves via the
 * gate, producing an "allow" frame; a non-permission event writes nothing) and
 * {@code closeQuietly}'s null / normal / throwing arms.
 */
class SubagentHarnessPermissionsTest extends UnitTest {

    private static final String CLS = "tools.SubagentHarnessPermissions";

    private static Object detect(HarnessEvent ev) throws Exception {
        var m = Class.forName(CLS).getDeclaredMethod("detectPermission", HarnessEvent.class);
        m.setAccessible(true);
        return m.invoke(null, ev);
    }

    private static String field(Object perm, String accessor) throws Exception {
        var a = perm.getClass().getDeclaredMethod(accessor);
        a.setAccessible(true);
        return (String) a.invoke(perm);
    }

    private static void arbitrate(HarnessEvent ev, OutputStream stdin, Long runId, Agent agent) throws Exception {
        var m = Class.forName(CLS).getDeclaredMethod("arbitratePermission",
                HarnessEvent.class, OutputStream.class, Long.class, Agent.class, Long.class);
        m.setAccessible(true);
        m.invoke(null, ev, stdin, runId, agent, null);
    }

    private static void closeQuietly(OutputStream stream) throws Exception {
        var m = Class.forName(CLS).getDeclaredMethod("closeQuietly", OutputStream.class);
        m.setAccessible(true);
        m.invoke(null, stream);
    }

    private static void evictLock(Long runId) throws Exception {
        var m = Class.forName(CLS).getDeclaredMethod("evictStdinLock", Long.class);
        m.setAccessible(true);
        m.invoke(null, runId);
    }

    private static HarnessEvent event(String json) {
        return new HarnessEvent(HarnessEvent.STEP, "", JsonParser.parseString(json).getAsJsonObject());
    }

    // ─── detectPermission: early returns ─────────────────────────────────────

    @Test
    void detectNullEventReturnsNull() throws Exception {
        assertNull(detect(null));
    }

    @Test
    void detectNullRawReturnsNull() throws Exception {
        // A non-JSON step line carries a null raw object.
        assertNull(detect(new HarnessEvent(HarnessEvent.STEP, "plain log line", null)));
    }

    @Test
    void detectNoDiscriminatorReturnsNull() throws Exception {
        assertNull(detect(event("{\"foo\":\"bar\"}")),
                "no type/kind/event/method/subtype field → not a permission frame");
    }

    @Test
    void detectNonPermissionDiscriminatorReturnsNull() throws Exception {
        assertNull(detect(event("{\"type\":\"token\",\"text\":\"hi\"}")));
    }

    // ─── detectPermission: recognition + extraction ──────────────────────────

    @Test
    void detectTopLevelPermissionExtractsIdToolAndArgs() throws Exception {
        var perm = detect(event(
                "{\"type\":\"permission_request\",\"id\":\"req-1\",\"tool\":\"bash\","
                        + "\"input\":{\"cmd\":\"ls\"}}"));
        assertNotNull(perm);
        assertEquals("req-1", field(perm, "id"));
        assertEquals("bash", field(perm, "toolName"));
        assertTrue(field(perm, "argsJson").contains("\"cmd\""),
                "args come from the top-level input member");
    }

    @Test
    void detectDiscriminatorIsTrimmedAndCaseInsensitive() throws Exception {
        var perm = detect(event("{\"type\":\"  Permission_Request  \",\"tool\":\"edit\"}"));
        assertNotNull(perm, "discriminator matched after strip + lowercase");
        assertEquals("edit", field(perm, "toolName"));
    }

    @Test
    void detectNestedEnvelopeExtractsToolAndArgs() throws Exception {
        // tool + args live under a nested request envelope (params).
        var perm = detect(event(
                "{\"kind\":\"can_use_tool\",\"params\":{\"tool_name\":\"edit\","
                        + "\"input\":{\"path\":\"/x\"}}}"));
        assertNotNull(perm);
        assertEquals("edit", field(perm, "toolName"));
        assertTrue(field(perm, "argsJson").contains("\"path\""),
                "args come from params.input, not the whole envelope");
    }

    @Test
    void detectIdFallsBackToNestedPayload() throws Exception {
        // No id at the top level → resolved from the nested payload.
        var perm = detect(event(
                "{\"method\":\"approval_request\",\"params\":{\"request_id\":\"p-9\",\"tool\":\"rm\"}}"));
        assertNotNull(perm);
        assertEquals("p-9", field(perm, "id"));
        assertEquals("rm", field(perm, "toolName"));
    }

    @Test
    void detectToolNameFallsBackToPlaceholder() throws Exception {
        var perm = detect(event("{\"type\":\"permission\",\"id\":\"x\"}"));
        assertNotNull(perm);
        assertEquals("(harness action)", field(perm, "toolName"),
                "no tool field anywhere → placeholder");
    }

    @Test
    void detectArgsFallBackToPayloadToStringWhenNoArgsMember() throws Exception {
        // Top-level payload with a tool but no input/arguments/args/params member.
        var perm = detect(event("{\"type\":\"approval\",\"tool\":\"deploy\"}"));
        assertNotNull(perm);
        var args = field(perm, "argsJson");
        assertTrue(args.contains("\"tool\"") && args.contains("deploy"),
                "args fall back to the payload's own JSON: " + args);
    }

    // ─── arbitratePermission: write decision ─────────────────────────────────

    @Test
    void arbitrateNonPermissionEventWritesNothing() throws Exception {
        var stdin = new ByteArrayOutputStream();
        arbitrate(event("{\"type\":\"token\",\"text\":\"chunk\"}"), stdin, 707_0001L, null);
        assertEquals(0, stdin.size(), "a non-permission event must not write to stdin");
    }

    @Test
    void arbitrateNullAgentWritesAllowFrameWithId() throws Exception {
        var runId = 707_0002L;
        var stdin = new ByteArrayOutputStream();
        // A null agent makes the gate return PROCEED without any DB/channel I/O,
        // so the decision written back is "allow".
        arbitrate(event("{\"type\":\"permission_request\",\"id\":\"req-42\",\"tool\":\"bash\"}"),
                stdin, runId, null);
        var line = stdin.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("\"decision\":\"allow\""), "approved → allow: " + line);
        assertTrue(line.contains("\"approved\":true"), line);
        assertTrue(line.contains("\"id\":\"req-42\""), "correlation id echoed back: " + line);
        assertTrue(line.endsWith("\n"), "frame terminated by a newline");
        evictLock(runId);
    }

    @Test
    void arbitrateOmitsIdWhenRequestHadNone() throws Exception {
        var runId = 707_0003L;
        var stdin = new ByteArrayOutputStream();
        arbitrate(event("{\"type\":\"permission_request\",\"tool\":\"bash\"}"), stdin, runId, null);
        var line = stdin.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("\"decision\":\"allow\""), line);
        assertFalse(line.contains("\"id\""), "no request id → no id field in the frame: " + line);
        evictLock(runId);
    }

    // ─── closeQuietly ────────────────────────────────────────────────────────

    @Test
    void closeQuietlyHandlesNullNormalAndThrowingStreams() throws Exception {
        // null → no-op (must not throw).
        closeQuietly(null);

        // normal stream → actually closed.
        var flag = new boolean[]{false};
        closeQuietly(new OutputStream() {
            @Override public void write(int b) { /* unused */ }
            @Override public void close() { flag[0] = true; }
        });
        assertTrue(flag[0], "a live stream must be closed");

        // throwing close → IOException swallowed (reader VT must not be disturbed).
        closeQuietly(new OutputStream() {
            @Override public void write(int b) { /* unused */ }
            @Override public void close() throws IOException {
                throw new IOException("boom");
            }
        });
        // Reaching here without a propagated exception is the assertion.
        assertTrue(true, "close() failure was swallowed");
    }
}
