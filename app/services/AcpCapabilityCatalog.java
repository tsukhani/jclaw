package services;

import java.util.Map;

/**
 * Curated catalog of which coding harnesses speak Zed's Agent Client Protocol
 * (ACP), and how. Used by {@link AcpHarnessProbe} to badge each detected
 * harness in Settings → Subagents, and (later, Stage 2) by the subagent runtime
 * to pick the ACP launch command over the stdin/stdout wrapper.
 *
 * <p>The classification is a hand-maintained catalog, NOT derived from
 * {@code HarnessAdapter.capabilities().bidirectional()} — that flag describes a
 * harness's own line protocol (e.g. Pi's JSONL), which is unrelated to ACP and
 * would mislabel. Verified against the installed CLIs: {@code gemini --acp} and
 * {@code opencode acp} are native ACP servers; {@code codex app-server} is
 * Codex's near-ACP JSON-RPC (drivable via a shim); Claude Code and Pi need a
 * separate adapter binary ({@code claude-code-acp} / {@code pi-acp}).
 */
public final class AcpCapabilityCatalog {

    /** Wire values for the per-harness ACP badge. */
    public static final String NATIVE = "native";
    public static final String ADAPTER = "adapter";
    public static final String ADAPTER_MISSING = "adapter-missing";
    public static final String NONE = "none";

    /** How a known harness reaches ACP. {@code acpCommand} is the ACP launch
     *  form (Stage 2). {@code adapterBinary} is a separate CLI to probe on PATH
     *  ({@code null} = ACP rides the harness's own binary, e.g. {@code codex
     *  app-server}). {@code installHint} names how to get a missing adapter. */
    private record Cap(String base, String acpCommand, String adapterBinary, String installHint) {}

    private static final Map<String, Cap> CATALOG = Map.of(
            "gemini", new Cap(NATIVE, "gemini --acp", null, null),
            "opencode", new Cap(NATIVE, "opencode acp", null, null),
            "codex", new Cap(ADAPTER, "codex app-server", null, null),
            "claude", new Cap(ADAPTER, "claude-code-acp", "claude-code-acp",
                    "npm i -g @zed-industries/claude-code-acp"),
            "pi", new Cap(ADAPTER, "pi-acp", "pi-acp", "install the pi-acp adapter"));

    /** The effective ACP support for a harness plus a human-readable tooltip. */
    public record Classification(String support, String detail) {}

    private static final Classification NO_ACP =
            new Classification(NONE, "No ACP — runs via the stdin/stdout wrapper");

    private AcpCapabilityCatalog() {}

    /** The ACP launch command for a harness id, or {@code null} if it has none. */
    public static String acpCommand(String id) {
        var cap = CATALOG.get(id);
        return cap == null ? null : cap.acpCommand();
    }

    /**
     * Classify a harness's ACP support for the badge. {@code harnessAvailable}
     * is whether the harness's own binary is on PATH — it gates the "codex
     * app-server" style adapter that reuses the harness binary. Separate adapter
     * binaries are probed here; a missing one yields {@link #ADAPTER_MISSING}
     * with an install hint.
     */
    public static Classification classify(String id, boolean harnessAvailable) {
        var cap = CATALOG.get(id);
        if (cap == null) return NO_ACP;
        if (NATIVE.equals(cap.base())) {
            return new Classification(NATIVE, "Speaks ACP natively — " + cap.acpCommand());
        }
        // adapter
        if (cap.adapterBinary() == null) {
            // ACP rides the harness's own binary (codex app-server).
            return harnessAvailable
                    ? new Classification(ADAPTER, "ACP via " + cap.acpCommand())
                    : NO_ACP;
        }
        var probe = ExecutableProbeSupport.probeOnPath(cap.adapterBinary(), "--version", "AcpCapabilityCatalog", "");
        if (probe.available()) {
            return new Classification(ADAPTER, "ACP via the " + cap.adapterBinary() + " adapter");
        }
        return new Classification(ADAPTER_MISSING,
                "Needs the " + cap.adapterBinary() + " adapter — " + cap.installHint());
    }
}
